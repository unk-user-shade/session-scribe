package com.sessionscribe;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import net.runelite.api.Skill;

/** A record of one completed session (stable after construction). Per-item loot is not stored here. */
final class SessionRecord
{
	long startMs;
	long endMs;
	long durationMs;
	Map<Skill, Integer> xpBySkill = new EnumMap<>(Skill.class);
	long lootValue;
	int kills;

	// Gson uses field injection on deserialize; this constructor is for our code.
	SessionRecord(long startMs, long endMs, long durationMs, Map<Skill, Integer> xpBySkill, long lootValue, int kills)
	{
		this.startMs = startMs;
		this.endMs = endMs;
		this.durationMs = durationMs;
		this.xpBySkill = xpBySkill == null ? Collections.emptyMap() : new EnumMap<>(xpBySkill);
		this.lootValue = lootValue;
		this.kills = kills;
	}
}
