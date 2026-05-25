package com.sessionscribe;

import com.google.gson.Gson;
import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.IntUnaryOperator;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Skill;
import net.runelite.client.RuneLite;

/** In-memory per-account session history with aggregation, retention, and JSON persistence. */
@Slf4j
@Singleton
class SessionStore
{
	static final int MAX_LOOT_ENTRIES = 1000;
	static final long RETENTION_MS = TimeUnit.DAYS.toMillis(90);

	private static final int VERSION = 1;

	@Inject
	private Gson gson;

	/** Serializable top-level shape of history.json. */
	private static final class HistoryFile
	{
		int version = VERSION;
		Map<String, AccountHistory> accounts = new LinkedHashMap<>();
	}

	private Map<String, AccountHistory> accounts = new LinkedHashMap<>();

	synchronized Set<String> accounts()
	{
		return new LinkedHashMap<>(accounts).keySet();
	}

	synchronized void clearAll()
	{
		accounts.clear();
	}

	synchronized void clearAccount(String account)
	{
		accounts.remove(account);
	}

	/** Append a completed session, roll it into all-time totals, and prune old detailed rows. */
	synchronized void finalizeSession(String account, SessionRecord record, Map<Integer, Integer> lootTally,
		IntUnaryOperator priceFn)
	{
		AccountHistory history = accounts.computeIfAbsent(account, k -> new AccountHistory());
		finalizeSessionInternal(history, record, lootTally, priceFn);
	}

	synchronized void finalizeLeftoverPending(IntUnaryOperator priceFn)
	{
		for (Map.Entry<String, AccountHistory> entry : accounts.entrySet())
		{
			AccountHistory history = entry.getValue();
			PendingSession pending = history.pending;
			if (pending == null)
			{
				continue;
			}
			long end = pending.startMs + pending.durationMs;
			SessionRecord record = new SessionRecord(pending.startMs, end, pending.durationMs,
				pending.xpBySkill, pending.lootValue, pending.kills);
			finalizeSessionInternal(history, record, pending.lootTally, priceFn);
			history.pending = null;
		}
		save();
	}

	synchronized void savePending(String account, SessionRecord record, Map<Integer, Integer> lootTally, long logoutMs)
	{
		AccountHistory history = accounts.computeIfAbsent(account, k -> new AccountHistory());
		PendingSession pending = new PendingSession();
		pending.startMs = record.startMs;
		pending.durationMs = record.durationMs;
		pending.xpBySkill = record.xpBySkill;
		pending.lootValue = record.lootValue;
		pending.kills = record.kills;
		pending.lootTally = lootTally == null ? new HashMap<>() : new HashMap<>(lootTally);
		pending.logoutMs = logoutMs;
		history.pending = pending;
		save();
	}

	synchronized void clearPending(String account)
	{
		AccountHistory history = accounts.get(account);
		if (history != null)
		{
			history.pending = null;
		}
	}

	private void finalizeSessionInternal(AccountHistory history, SessionRecord record, Map<Integer, Integer> lootTally,
		IntUnaryOperator priceFn)
	{
		history.sessions.add(record);

		AllTimeStats all = history.allTime;
		if (all.sinceMs == 0)
		{
			all.sinceMs = record.startMs;
		}
		all.durationMs += record.durationMs;
		all.lootValue += record.lootValue;
		all.kills += record.kills;
		addXp(all.xpBySkill, record.xpBySkill);
		mergeLoot(all.lootTally, lootTally, priceFn);

		pruneOldSessions(history, record.endMs);
	}

	/**
	 * Aggregates stats for one (account, window). The returned {@link Aggregate} carries an itemized
	 * {@code lootTally} for CURRENT and ALL_TIME, and {@code null} for the rolling DAY / WEEK windows.
	 */
	synchronized Aggregate aggregate(String account, Window window, long now, SessionRecord current,
		Map<Integer, Integer> currentLootTally, IntUnaryOperator priceFn)
	{
		if (window == Window.CURRENT)
		{
			if (current == null)
			{
				return emptyAggregate(new HashMap<>());
			}
			return new Aggregate(current.durationMs, new EnumMap<>(current.xpBySkill),
				current.lootValue, current.kills,
				currentLootTally == null ? new HashMap<>() : new HashMap<>(currentLootTally));
		}

		AccountHistory history = accounts.get(account);
		long durationMs = 0;
		long lootValue = 0;
		int kills = 0;
		Map<Skill, Integer> xp = new EnumMap<>(Skill.class);
		Map<Integer, Integer> tally = null;

		if (window == Window.ALL_TIME)
		{
			if (history != null)
			{
				durationMs = history.allTime.durationMs;
				lootValue = history.allTime.lootValue;
				kills = history.allTime.kills;
				addXp(xp, history.allTime.xpBySkill);
				tally = new HashMap<>(history.allTime.lootTally);
			}
			else
			{
				tally = new HashMap<>();
			}
			if (current != null)
			{
				durationMs += current.durationMs;
				lootValue += current.lootValue;
				kills += current.kills;
				addXp(xp, current.xpBySkill);
				mergeLoot(tally, currentLootTally, priceFn);
			}
			return new Aggregate(durationMs, xp, lootValue, kills, tally);
		}

		// DAY / WEEK: sum sessions whose end is within the rolling window, plus the live session.
		long cutoff = now - window.spanMillis();
		if (history != null)
		{
			for (SessionRecord record : history.sessions)
			{
				if (record.endMs >= cutoff)
				{
					durationMs += record.durationMs;
					lootValue += record.lootValue;
					kills += record.kills;
					addXp(xp, record.xpBySkill);
				}
			}
		}
		if (current != null)
		{
			durationMs += current.durationMs;
			lootValue += current.lootValue;
			kills += current.kills;
			addXp(xp, current.xpBySkill);
		}
		return new Aggregate(durationMs, xp, lootValue, kills, null);
	}

	synchronized void load()
	{
		File file = historyFile();
		if (!file.exists())
		{
			accounts = new LinkedHashMap<>();
			return;
		}
		try (Reader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8))
		{
			HistoryFile parsed = gson.fromJson(reader, HistoryFile.class);
			accounts = parsed != null && parsed.accounts != null ? parsed.accounts : new LinkedHashMap<>();
		}
		catch (Exception e)
		{
			log.warn("Could not read session history; backing up and starting fresh", e);
			backup(file);
			accounts = new LinkedHashMap<>();
		}
	}

	synchronized void save()
	{
		File file = historyFile();
		File dir = file.getParentFile();
		if (dir != null && !dir.exists() && !dir.mkdirs())
		{
			log.warn("Could not create history directory: {}", dir);
			return;
		}

		File tmp = new File(file.getParentFile(), file.getName() + ".tmp");
		try (Writer writer = Files.newBufferedWriter(tmp.toPath(), StandardCharsets.UTF_8))
		{
			HistoryFile out = new HistoryFile();
			out.accounts = accounts;
			gson.toJson(out, writer);
		}
		catch (IOException e)
		{
			log.warn("Could not write session history", e);
			return;
		}

		try
		{
			Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
		}
		catch (IOException e)
		{
			log.warn("Could not replace session history file", e);
		}
	}

	static boolean withinGap(long logoutMs, long now, long gapMs)
	{
		return logoutMs > 0 && (now - logoutMs) <= gapMs;
	}

	static String toJson(Gson gson, Map<String, AccountHistory> accounts)
	{
		HistoryFile out = new HistoryFile();
		out.accounts = accounts;
		return gson.toJson(out);
	}

	static Map<String, AccountHistory> fromJson(Gson gson, String json)
	{
		try
		{
			HistoryFile parsed = gson.fromJson(json, HistoryFile.class);
			return parsed != null && parsed.accounts != null ? parsed.accounts : new LinkedHashMap<>();
		}
		catch (Exception e)
		{
			return new LinkedHashMap<>();
		}
	}

	synchronized Map<String, AccountHistory> exportForTest()
	{
		return accounts;
	}

	private static File historyFile()
	{
		return new File(RuneLite.RUNELITE_DIR, "session-scribe" + File.separator + "history.json");
	}

	private static void backup(File file)
	{
		try
		{
			Files.move(file.toPath(), new File(file.getParentFile(), file.getName() + ".corrupt").toPath(),
				StandardCopyOption.REPLACE_EXISTING);
		}
		catch (IOException ignored)
		{
			// Best effort; the next save can still replace the unreadable history file.
		}
	}

	private static Aggregate emptyAggregate(Map<Integer, Integer> tally)
	{
		return new Aggregate(0, new EnumMap<>(Skill.class), 0, 0, tally);
	}

	private static void addXp(Map<Skill, Integer> into, Map<Skill, Integer> from)
	{
		if (from == null)
		{
			return;
		}
		for (Map.Entry<Skill, Integer> e : from.entrySet())
		{
			into.merge(e.getKey(), e.getValue(), Integer::sum);
		}
	}

	private static void mergeLoot(Map<Integer, Integer> into, Map<Integer, Integer> from, IntUnaryOperator priceFn)
	{
		if (from == null)
		{
			return;
		}
		for (Map.Entry<Integer, Integer> e : from.entrySet())
		{
			into.merge(e.getKey(), e.getValue(), Integer::sum);
		}
		while (into.size() > MAX_LOOT_ENTRIES)
		{
			Integer cheapest = null;
			long cheapestValue = Long.MAX_VALUE;
			for (Map.Entry<Integer, Integer> e : into.entrySet())
			{
				long value = (long) priceFn.applyAsInt(e.getKey()) * e.getValue();
				if (value < cheapestValue)
				{
					cheapestValue = value;
					cheapest = e.getKey();
				}
			}
			into.remove(cheapest);
		}
	}

	private static void pruneOldSessions(AccountHistory history, long now)
	{
		long cutoff = now - RETENTION_MS;
		List<SessionRecord> kept = new ArrayList<>();
		for (SessionRecord record : history.sessions)
		{
			if (record.endMs >= cutoff)
			{
				kept.add(record);
			}
		}
		history.sessions = kept;
	}
}
