package com.sessionscribe;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.IntUnaryOperator;
import javax.inject.Singleton;
import net.runelite.api.Skill;

/** In-memory per-account session history with aggregation, retention, and JSON persistence. */
@Singleton
class SessionStore
{
	static final int MAX_LOOT_ENTRIES = 1000;
	static final long RETENTION_MS = TimeUnit.DAYS.toMillis(90);

	private Map<String, AccountHistory> accounts = new LinkedHashMap<>();

	Set<String> accounts()
	{
		return new LinkedHashMap<>(accounts).keySet();
	}

	void clearAll()
	{
		accounts.clear();
	}

	void clearAccount(String account)
	{
		accounts.remove(account);
	}

	/** Append a completed session, roll it into all-time totals, and prune old detailed rows. */
	void finalizeSession(String account, SessionRecord record, Map<Integer, Integer> lootTally, IntUnaryOperator priceFn)
	{
		AccountHistory history = accounts.computeIfAbsent(account, k -> new AccountHistory());
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
	Aggregate aggregate(String account, Window window, long now, SessionRecord current,
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
