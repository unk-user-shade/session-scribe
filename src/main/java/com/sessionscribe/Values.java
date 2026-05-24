package com.sessionscribe;

/** Number formatting shared by the live panel and the report card. */
final class Values
{
	private Values()
	{
	}

	/** Plain grouped count, e.g. 18,500. Used for item quantities (never abbreviated). */
	static String count(long value)
	{
		return String.format("%,d", value);
	}

	/** Formats an XP/GP value: grouped digits, or abbreviated (1.2M / 345K) when {@code rounded}. */
	static String format(long value, boolean rounded)
	{
		if (!rounded)
		{
			return String.format("%,d", value);
		}
		final long abs = Math.abs(value);
		if (abs >= 1_000_000_000L)
		{
			return trim(value / 1_000_000_000.0) + "B";
		}
		if (abs >= 1_000_000L)
		{
			return trim(value / 1_000_000.0) + "M";
		}
		if (abs >= 1_000L)
		{
			return trim(value / 1_000.0) + "K";
		}
		return Long.toString(value);
	}

	private static String trim(double value)
	{
		final String formatted = String.format("%.1f", value);
		return formatted.endsWith(".0") ? formatted.substring(0, formatted.length() - 2) : formatted;
	}
}
