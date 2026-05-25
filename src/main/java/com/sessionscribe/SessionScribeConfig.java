package com.sessionscribe;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("sessionscribe")
public interface SessionScribeConfig extends Config
{
	@ConfigItem(
		keyName = "showSkillBreakdown",
		name = "Show skill breakdown",
		description = "Show the collapsible per-skill XP breakdown in the panel.",
		position = 1
	)
	default boolean showSkillBreakdown()
	{
		return true;
	}

	@ConfigItem(
		keyName = "relogGapMinutes",
		name = "Relog continuation (min)",
		description = "If you log back into the same account within this many minutes, the session continues instead of starting a new one.",
		position = 4
	)
	default int relogGapMinutes()
	{
		return 5;
	}

	@ConfigItem(
		keyName = "roundValues",
		name = "Round large values",
		description = "Abbreviate large XP and GP values (e.g. 1.2M) instead of showing full numbers.",
		position = 3
	)
	default boolean roundValues()
	{
		return false;
	}
}
