package com.sessionscribe;

import com.sessionscribe.SessionScribePanel.LootRow;
import com.sessionscribe.SessionScribePanel.SkillRow;
import com.sessionscribe.SessionScribePanel.Snapshot;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Arrays;
import javax.imageio.ImageIO;
import net.runelite.api.Skill;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class SessionReportCardTest
{
	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	@Test
	public void rendersCardAndWritesValidPng() throws Exception
	{
		final Snapshot snapshot = new Snapshot(
			3_661_000L, // 1h 01m 01s
			17_345L,
			25_000L,
			7,
			Arrays.asList(new SkillRow(Skill.SLAYER, 12_345), new SkillRow(Skill.ATTACK, 5_000)),
			Arrays.asList(new LootRow("Dragon bones", 12, 24_000, null), new LootRow("Coins", 1_000, 1_000, null)));

		final BufferedImage image = SessionReportCard.render(snapshot, false, "Zezima - All time");
		assertNotNull(image);
		assertTrue(image.getWidth() > 0);
		assertTrue(image.getHeight() > 0);

		final File dir = new File(folder.getRoot(), "session-scribe");
		final File saved = SessionReportCard.saveTo(image, dir);

		assertTrue("PNG should exist on disk", saved.exists());
		assertTrue("PNG should be non-empty", saved.length() > 0);

		final BufferedImage reloaded = ImageIO.read(saved);
		assertNotNull("saved file should be a readable image", reloaded);
		assertTrue(reloaded.getWidth() == image.getWidth() && reloaded.getHeight() == image.getHeight());
	}
}
