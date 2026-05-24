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
		keyName = "autoResetOnLogout",
		name = "Auto-reset on logout",
		description = "Start a fresh session automatically when you log out to the login screen.",
		position = 2
	)
	default boolean autoResetOnLogout()
	{
		return false;
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
