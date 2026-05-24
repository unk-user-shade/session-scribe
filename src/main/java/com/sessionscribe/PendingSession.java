package com.sessionscribe;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import net.runelite.api.Skill;

/** A session mirrored to disk while in progress, so a crash does not lose it. */
final class PendingSession
{
	long startMs;
	long durationMs;
	Map<Skill, Integer> xpBySkill = new EnumMap<>(Skill.class);
	long lootValue;
	int kills;
	Map<Integer, Integer> lootTally = new HashMap<>();
	long logoutMs; // wall-clock time of the last logout event (for the relog-gap check, not session end)
}
