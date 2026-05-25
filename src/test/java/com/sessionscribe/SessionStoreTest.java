package com.sessionscribe;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.IntUnaryOperator;
import net.runelite.api.Skill;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class SessionStoreTest
{
	private static Map<Skill, Integer> xp(int attack, int slayer)
	{
		Map<Skill, Integer> m = new EnumMap<>(Skill.class);
		m.put(Skill.ATTACK, attack);
		m.put(Skill.SLAYER, slayer);
		return m;
	}

	@Test
	public void finalizeRollsIntoAllTimeAndEvictsLowestValueLoot()
	{
		SessionStore store = new SessionStore();
		IntUnaryOperator price = id -> id; // value == id, so id 1 is the cheapest

		Map<Integer, Integer> loot1 = new HashMap<>();
		loot1.put(100, 2);  // value 200
		store.finalizeSession("Zezima",
			new SessionRecord(0, 1000, 1000, xp(500, 0), 200, 3), loot1, price);

		Map<Integer, Integer> loot2 = new HashMap<>();
		loot2.put(100, 1);  // aggregates -> qty 3
		loot2.put(200, 1);  // value 200
		store.finalizeSession("Zezima",
			new SessionRecord(2000, 3000, 1000, xp(0, 250), 200, 1), loot2, price);

		Aggregate all = store.aggregate("Zezima", Window.ALL_TIME, 5000, null, null, price);
		assertEquals(2000L, all.durationMs);
		assertEquals(Integer.valueOf(500), all.xpBySkill.get(Skill.ATTACK));
		assertEquals(Integer.valueOf(250), all.xpBySkill.get(Skill.SLAYER));
		assertEquals(400L, all.lootValue);
		assertEquals(4, all.kills);
		assertEquals(Integer.valueOf(3), all.lootTally.get(100));
	}

	@Test
	public void allTimeLootTallyEvictsCheapestWhenOverCap()
	{
		SessionStore store = new SessionStore();
		IntUnaryOperator price = id -> id;
		Map<Integer, Integer> loot = new HashMap<>();
		for (int id = 1; id <= SessionStore.MAX_LOOT_ENTRIES; id++)
		{
			loot.put(id, 1);
		}
		store.finalizeSession("Z", new SessionRecord(0, 1, 1, null, 0, 0), loot, price);

		Map<Integer, Integer> more = new HashMap<>();
		more.put(9_999_999, 1);
		store.finalizeSession("Z", new SessionRecord(1, 2, 1, null, 0, 0), more, price);

		Aggregate all = store.aggregate("Z", Window.ALL_TIME, 10, null, null, price);
		assertEquals(SessionStore.MAX_LOOT_ENTRIES, all.lootTally.size());
		assertTrue(all.lootTally.containsKey(9_999_999));
		assertFalse(all.lootTally.containsKey(1));
	}

	@Test
	public void currentWindowHandlesEmptyXpSession()
	{
		SessionStore store = new SessionStore();
		IntUnaryOperator price = id -> id;
		SessionRecord current = new SessionRecord(0, 1000, 1000, null, 0, 0);
		Aggregate agg = store.aggregate("Z", Window.CURRENT, 2000, current, new HashMap<>(), price);
		assertEquals(1000L, agg.durationMs);
		assertTrue(agg.xpBySkill.isEmpty());
	}

	@Test
	public void rollingWindowsCountOnlySessionsEndingInsideThem()
	{
		SessionStore store = new SessionStore();
		long now = TimeUnit.DAYS.toMillis(10);
		IntUnaryOperator price = id -> 1;

		long eightDaysAgo = now - TimeUnit.DAYS.toMillis(8);
		store.finalizeSession("Z", new SessionRecord(eightDaysAgo, eightDaysAgo, 100, xp(10, 0), 5, 1), new HashMap<>(), price);

		long threeDaysAgo = now - TimeUnit.DAYS.toMillis(3);
		store.finalizeSession("Z", new SessionRecord(threeDaysAgo, threeDaysAgo, 200, xp(20, 0), 7, 2), new HashMap<>(), price);

		long twoHoursAgo = now - TimeUnit.HOURS.toMillis(2);
		store.finalizeSession("Z", new SessionRecord(twoHoursAgo, twoHoursAgo, 300, xp(30, 0), 9, 3), new HashMap<>(), price);

		Aggregate day = store.aggregate("Z", Window.DAY, now, null, null, price);
		assertEquals(300L, day.durationMs);
		assertEquals(Integer.valueOf(30), day.xpBySkill.get(Skill.ATTACK));
		assertEquals(9L, day.lootValue);
		assertEquals(3, day.kills);
		assertNull(day.lootTally);

		Aggregate week = store.aggregate("Z", Window.WEEK, now, null, null, price);
		assertEquals(500L, week.durationMs);
		assertEquals(Integer.valueOf(50), week.xpBySkill.get(Skill.ATTACK));
		assertEquals(16L, week.lootValue);
		assertEquals(5, week.kills);
		assertNull(week.lootTally);
	}

	@Test
	public void rollingWindowsFoldInCurrentSession()
	{
		SessionStore store = new SessionStore();
		long now = TimeUnit.DAYS.toMillis(10);
		IntUnaryOperator price = id -> 1;

		long twoHoursAgo = now - TimeUnit.HOURS.toMillis(2);
		store.finalizeSession("Z", new SessionRecord(twoHoursAgo, twoHoursAgo, 300, xp(30, 0), 9, 3), new HashMap<>(), price);

		SessionRecord current = new SessionRecord(now - 100, now, 100, xp(5, 4), 11, 1);
		Aggregate day = store.aggregate("Z", Window.DAY, now, current, new HashMap<>(), price);

		assertEquals(400L, day.durationMs);
		assertEquals(Integer.valueOf(35), day.xpBySkill.get(Skill.ATTACK));
		assertEquals(Integer.valueOf(4), day.xpBySkill.get(Skill.SLAYER));
		assertEquals(20L, day.lootValue);
		assertEquals(4, day.kills);
		assertNull(day.lootTally);
	}

	@Test
	public void retentionPrunesDetailedSessionsButKeepsAllTimeTotals()
	{
		SessionStore store = new SessionStore();
		IntUnaryOperator price = id -> 1;
		long now = TimeUnit.DAYS.toMillis(100);
		long old = now - SessionStore.RETENTION_MS - 1;

		store.finalizeSession("Z", new SessionRecord(old, old, 100, xp(10, 0), 5, 1), new HashMap<>(), price);
		store.finalizeSession("Z", new SessionRecord(now, now, 200, xp(20, 0), 7, 2), new HashMap<>(), price);

		Aggregate week = store.aggregate("Z", Window.WEEK, now, null, null, price);
		assertEquals(200L, week.durationMs);
		assertEquals(Integer.valueOf(20), week.xpBySkill.get(Skill.ATTACK));
		assertEquals(7L, week.lootValue);
		assertEquals(2, week.kills);

		Aggregate all = store.aggregate("Z", Window.ALL_TIME, now, null, null, price);
		assertEquals(300L, all.durationMs);
		assertEquals(Integer.valueOf(30), all.xpBySkill.get(Skill.ATTACK));
		assertEquals(12L, all.lootValue);
		assertEquals(3, all.kills);
	}
}
