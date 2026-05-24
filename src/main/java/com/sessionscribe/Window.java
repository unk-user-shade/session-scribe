package com.sessionscribe;

import java.util.concurrent.TimeUnit;

enum Window
{
	CURRENT("Current session"),
	DAY("Last 24 hours", TimeUnit.DAYS.toMillis(1)),
	WEEK("This week", TimeUnit.DAYS.toMillis(7)),
	ALL_TIME("All time");

	private final String label;
	private final long spanMillis;

	Window(String label)
	{
		this(label, -1L);
	}

	Window(String label, long spanMillis)
	{
		this.label = label;
		this.spanMillis = spanMillis;
	}

	String label()
	{
		return label;
	}

	boolean isRolling()
	{
		return spanMillis > 0;
	}

	long spanMillis()
	{
		return spanMillis;
	}

	@Override
	public String toString()
	{
		return label;
	}
}
