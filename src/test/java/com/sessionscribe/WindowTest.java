package com.sessionscribe;

import java.util.concurrent.TimeUnit;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class WindowTest
{
	@Test
	public void rollingWindowsExposeTheirSpan()
	{
		assertEquals(TimeUnit.DAYS.toMillis(1), Window.DAY.spanMillis());
		assertEquals(TimeUnit.DAYS.toMillis(7), Window.WEEK.spanMillis());
		assertTrue(Window.DAY.isRolling());
		assertTrue(Window.WEEK.isRolling());
		assertFalse(Window.CURRENT.isRolling());
		assertFalse(Window.ALL_TIME.isRolling());
	}

	@Test
	public void labelsMatchDisplayStrings()
	{
		assertEquals("Current session", Window.CURRENT.label());
		assertEquals("Last 24 hours", Window.DAY.label());
		assertEquals("Last 7 days", Window.WEEK.label());
		assertEquals("All time", Window.ALL_TIME.label());
	}
}
