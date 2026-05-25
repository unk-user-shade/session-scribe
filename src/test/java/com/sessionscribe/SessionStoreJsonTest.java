package com.sessionscribe;

import com.google.gson.Gson;
import java.util.HashMap;
import java.util.Map;
import java.util.function.IntUnaryOperator;
import net.runelite.api.Skill;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SessionStoreJsonTest
{
	@Test
	public void roundTripsAccountsThroughJson()
	{
		SessionStore store = new SessionStore();
		IntUnaryOperator price = id -> id;
		Map<Skill, Integer> xp = new HashMap<>();
		xp.put(Skill.SLAYER, 12_345);
		Map<Integer, Integer> loot = new HashMap<>();
		loot.put(11_802, 1); // Armadyl godsword
		store.finalizeSession("Zezima", new SessionRecord(1, 2, 1000, xp, 50_000_000, 9), loot, price);

		Gson gson = new Gson();
		String json = SessionStore.toJson(gson, store.exportForTest());
		assertTrue(json.contains("Zezima"));
		assertTrue(json.contains("SLAYER"));

		Map<String, AccountHistory> reloaded = SessionStore.fromJson(gson, json);
		AccountHistory h = reloaded.get("Zezima");
		assertEquals(1000L, h.allTime.durationMs);
		assertEquals(50_000_000L, h.allTime.lootValue);
		assertEquals(9, h.allTime.kills);
		assertEquals(Integer.valueOf(12_345), h.allTime.xpBySkill.get(Skill.SLAYER));
		assertEquals(Integer.valueOf(1), h.allTime.lootTally.get(11_802));
	}

	@Test
	public void corruptJsonYieldsEmptyHistory()
	{
		Map<String, AccountHistory> result = SessionStore.fromJson(new Gson(), "{not valid json");
		assertTrue(result.isEmpty());
	}

	@Test
	public void withinGapHonorsTheWindow()
	{
		assertTrue(SessionStore.withinGap(1000, 1000 + 60_000, 5 * 60_000));
		assertFalse(SessionStore.withinGap(1000, 1000 + 6 * 60_000, 5 * 60_000));
		assertFalse(SessionStore.withinGap(0, 100_000, 5 * 60_000));
	}
}
