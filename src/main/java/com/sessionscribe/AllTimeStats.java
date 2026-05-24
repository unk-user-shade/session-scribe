package com.sessionscribe;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import net.runelite.api.Skill;

/** Per-account running totals kept forever (never pruned). */
final class AllTimeStats
{
	long sinceMs;
	long durationMs;
	Map<Skill, Integer> xpBySkill = new EnumMap<>(Skill.class);
	long lootValue;
	int kills;
	Map<Integer, Integer> lootTally = new HashMap<>();
}
