package com.sessionscribe;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class ValuesTest
{
	@Test
	public void exactWhenNotRounded()
	{
		assertEquals("1,284,500", Values.format(1_284_500L, false));
		assertEquals("18,500", Values.count(18_500L));
	}

	@Test
	public void abbreviatesWhenRounded()
	{
		assertEquals("999", Values.format(999L, true));
		assertEquals("5K", Values.format(5_000L, true));
		assertEquals("1.3M", Values.format(1_284_500L, true));
		assertEquals("2.5B", Values.format(2_500_000_000L, true));
	}
}
