package com.sessionscribe;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.function.IntUnaryOperator;
import net.runelite.api.Skill;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
}
