package com.sessionscribe;

import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.IntUnaryOperator;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.Skill;
import net.runelite.client.game.ItemManager;

/**
 * In-memory tally of everything gained during the current play session: XP per skill, loot
 * quantities, kills, and elapsed time. Nothing is persisted while tracking.
 *
 * Threading: every mutator must run on the client thread. Game events (StatChanged, loot,
 * GameStateChanged) are already dispatched there; the single start-up {@link #reset()} is
 * wrapped in {@code ClientThread#invoke} by the plugin.
 *
 * Honest limitation: a kill is only counted when it produces an NpcLootReceived event.
 * Drop-less kills (nothing dropped) are NOT counted.
 */
@Singleton
public class SessionTracker
{
	/** Defensive cap on the number of distinct loot item ids held in memory. */
	static final int MAX_LOOT_ENTRIES = 1000;

	@Inject
	private Client client;

	@Inject
	private ItemManager itemManager;

	private long sessionStart = System.currentTimeMillis();
	private String lastUsername;

	private final Map<Skill, Integer> baseline = new EnumMap<>(Skill.class);
	private final Map<Skill, Integer> gained = new EnumMap<>(Skill.class);
	private final Map<Integer, Integer> loot = new HashMap<>();
	private int kills;

	/** Clear all counters and start a fresh session. */
	public void reset()
	{
		sessionStart = System.currentTimeMillis();
		baseline.clear();
		gained.clear();
		loot.clear();
		kills = 0;
		// Baselines are (re)established lazily by the first StatChanged per skill after a reset
		// (see recordXp). We deliberately do NOT snapshot client.getSkillExperience() here: at the
		// LOGGED_IN moment the server has not yet sent the player's XP, so a snapshot would read 0
		// and the post-login StatChanged events would report lifetime XP as a single session's gain.
		lastUsername = client.getUsername();
	}

	/** Re-baseline only when the logged-in account changes; world hops keep the same session. */
	public void onLogin()
	{
		if (!Objects.equals(client.getUsername(), lastUsername))
		{
			reset();
		}
	}

	public void recordXp(Skill skill, int currentXp)
	{
		Integer base = baseline.get(skill);
		if (base == null || currentXp < base)
		{
			// First reading after a reset, or XP appears lower than the baseline (stats reloaded on
			// login/world-hop): (re)baseline here so a session never shows a negative or inflated gain.
			setBaseline(skill, currentXp);
			return;
		}
		gained.put(skill, currentXp - base);
	}

	public void recordKill()
	{
		kills++;
	}

	public void addLoot(int itemId, int quantity)
	{
		Integer existing = loot.get(itemId);
		if (existing == null && loot.size() >= MAX_LOOT_ENTRIES)
		{
			return;
		}
		loot.put(itemId, (existing == null ? 0 : existing) + quantity);
	}

	void setBaseline(Skill skill, int xp)
	{
		baseline.put(skill, xp);
		gained.remove(skill);
	}

	public long getElapsedMillis()
	{
		return System.currentTimeMillis() - sessionStart;
	}

	public long getTotalXp()
	{
		long total = 0;
		for (int gain : gained.values())
		{
			total += gain;
		}
		return total;
	}

	public Map<Skill, Integer> getXpBySkill()
	{
		return Collections.unmodifiableMap(new EnumMap<>(gained));
	}

	public Map<Integer, Integer> getLootTally()
	{
		return Collections.unmodifiableMap(new HashMap<>(loot));
	}

	public long getTotalLootValue()
	{
		return totalLootValue(itemManager::getItemPrice);
	}

	/** Value math seam: kept separate from {@link ItemManager} so it is unit-testable. */
	long totalLootValue(IntUnaryOperator priceFn)
	{
		long total = 0;
		for (Map.Entry<Integer, Integer> entry : loot.entrySet())
		{
			total += (long) priceFn.applyAsInt(entry.getKey()) * entry.getValue();
		}
		return total;
	}

	public int getKills()
	{
		return kills;
	}
}
