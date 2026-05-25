package com.sessionscribe;

import java.util.Map;
import net.runelite.api.Skill;

/** Immutable view for one (account, window) selection. lootTally is null when not itemized. */
final class Aggregate
{
	final long durationMs;
	final Map<Skill, Integer> xpBySkill;
	final long lootValue;
	final int kills;
	final Map<Integer, Integer> lootTally;

	Aggregate(long durationMs, Map<Skill, Integer> xpBySkill, long lootValue, int kills, Map<Integer, Integer> lootTally)
	{
		this.durationMs = durationMs;
		this.xpBySkill = xpBySkill;
		this.lootValue = lootValue;
		this.kills = kills;
		this.lootTally = lootTally;
	}

	long totalXp()
	{
		long total = 0;
		for (int gain : xpBySkill.values())
		{
			total += gain;
		}
		return total;
	}
}
