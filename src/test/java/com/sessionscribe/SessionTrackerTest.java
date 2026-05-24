package com.sessionscribe;

import java.util.Map;
import java.util.function.IntUnaryOperator;
import net.runelite.api.Skill;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class SessionTrackerTest
{
	@Test
	public void xpGainedIsCurrentMinusBaseline()
	{
		SessionTracker tracker = new SessionTracker();
		tracker.setBaseline(Skill.ATTACK, 1000);
		tracker.setBaseline(Skill.MAGIC, 5000);

		tracker.recordXp(Skill.ATTACK, 1500);
		tracker.recordXp(Skill.MAGIC, 5200);

		Map<Skill, Integer> bySkill = tracker.getXpBySkill();
		assertEquals(Integer.valueOf(500), bySkill.get(Skill.ATTACK));
		assertEquals(Integer.valueOf(200), bySkill.get(Skill.MAGIC));
		assertEquals(700L, tracker.getTotalXp());
	}

	@Test
	public void lootValueIsSumOfPriceTimesQuantity()
	{
		SessionTracker tracker = new SessionTracker();
		tracker.addLoot(995, 1000);   // coins
		tracker.addLoot(526, 10);     // bones
		tracker.addLoot(526, 5);      // more bones -> aggregates to 15

		assertEquals(Integer.valueOf(15), tracker.getLootTally().get(526));

		IntUnaryOperator price = id -> id == 995 ? 1 : (id == 526 ? 100 : 0);
		// 1000 * 1  +  15 * 100  = 2500
		assertEquals(2500L, tracker.totalLootValue(price));
	}

	@Test
	public void firstReadingAfterResetEstablishesBaseline()
	{
		// Mirrors login: the first StatChanged per skill sets the baseline (0 gain), then gains count.
		SessionTracker tracker = new SessionTracker();
		tracker.recordXp(Skill.SLAYER, 100_000);
		assertEquals(0L, tracker.getTotalXp());
		tracker.recordXp(Skill.SLAYER, 100_050);
		assertEquals(50L, tracker.getTotalXp());
	}

	@Test
	public void xpAppearingLowerRebaselinesInsteadOfGoingNegative()
	{
		// Mirrors a relog/world-hop where stats reload: never report a negative or inflated gain.
		SessionTracker tracker = new SessionTracker();
		tracker.setBaseline(Skill.ATTACK, 1000);
		tracker.recordXp(Skill.ATTACK, 1500);
		assertEquals(500L, tracker.getTotalXp());

		tracker.recordXp(Skill.ATTACK, 800); // appears lower -> re-baseline, not -200
		assertEquals(0L, tracker.getTotalXp());

		tracker.recordXp(Skill.ATTACK, 950); // counts from the new baseline of 800
		assertEquals(150L, tracker.getTotalXp());
	}

	@Test
	public void lootMapIsCappedButStillAggregatesExistingIds()
	{
		SessionTracker tracker = new SessionTracker();
		for (int id = 1; id <= SessionTracker.MAX_LOOT_ENTRIES; id++)
		{
			tracker.addLoot(id, 1);
		}
		assertEquals(SessionTracker.MAX_LOOT_ENTRIES, tracker.getLootTally().size());

		tracker.addLoot(7_777_777, 1); // new id beyond the cap -> ignored
		assertEquals(SessionTracker.MAX_LOOT_ENTRIES, tracker.getLootTally().size());

		tracker.addLoot(1, 4); // existing id -> still aggregates
		assertEquals(Integer.valueOf(5), tracker.getLootTally().get(1));
	}
}
