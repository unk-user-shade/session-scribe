package com.sessionscribe;

import com.sessionscribe.SessionScribePanel.LootRow;
import com.sessionscribe.SessionScribePanel.SkillRow;
import com.sessionscribe.SessionScribePanel.Snapshot;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * Renders a compact, screenshot-friendly end-of-session recap to a {@link BufferedImage}.
 * Pure drawing with no client or Swing dependencies, so it is unit-testable headlessly.
 * Top drops are drawn as text (name / quantity / value) rather than item icons, because item
 * images load asynchronously and would not be guaranteed present at render time.
 */
final class SessionReportCard
{
	private static final int WIDTH = 340;
	private static final int PAD = 16;
	private static final int TITLE_H = 30;
	private static final int LINE = 22;
	private static final int SMALL = 19;
	private static final int HEADER_H = 24;
	private static final int GAP = 12;
	private static final int MAX_SKILLS = 5;
	private static final int MAX_DROPS = 6;

	private static final Color BG = new Color(33, 33, 33);
	private static final Color BORDER = new Color(235, 140, 20);
	private static final Color TITLE = new Color(235, 140, 20);
	private static final Color TEXT = new Color(220, 220, 220);
	private static final Color MUTED = new Color(150, 150, 150);
	private static final Color VALUE = new Color(255, 255, 255);

	private static final Font TITLE_FONT = new Font(Font.SANS_SERIF, Font.BOLD, 20);
	private static final Font DATE_FONT = new Font(Font.SANS_SERIF, Font.PLAIN, 12);
	private static final Font HEADER_FONT = new Font(Font.SANS_SERIF, Font.BOLD, 13);
	private static final Font BODY_FONT = new Font(Font.SANS_SERIF, Font.PLAIN, 13);

	private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
	private static final DateTimeFormatter FILE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

	private SessionReportCard()
	{
	}

	static BufferedImage render(Snapshot snapshot, boolean rounded, String subtitle)
	{
		final List<SkillRow> skills = snapshot.skillRows();
		final List<LootRow> drops = snapshot.lootRows();
		final int skillRows = Math.max(1, Math.min(MAX_SKILLS, skills.size()));
		final int dropRows = Math.max(1, Math.min(MAX_DROPS, drops.size()));

		final int height = PAD
			+ TITLE_H
			+ SMALL              // date
			+ SMALL              // subtitle
			+ SMALL              // duration
			+ GAP                // divider
			+ 3 * LINE           // xp / loot / kills
			+ GAP
			+ HEADER_H + skillRows * SMALL
			+ GAP
			+ HEADER_H + dropRows * SMALL
			+ PAD;

		final BufferedImage image = new BufferedImage(WIDTH, height, BufferedImage.TYPE_INT_ARGB);
		final Graphics2D g = image.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

		g.setColor(BG);
		g.fillRoundRect(0, 0, WIDTH - 1, height - 1, 14, 14);
		g.setColor(BORDER);
		g.drawRoundRect(0, 0, WIDTH - 1, height - 1, 14, 14);

		final int right = WIDTH - PAD;
		int y = PAD;

		y = left(g, "Session Scribe", PAD, y, TITLE_FONT, TITLE, TITLE_H);
		y = left(g, DATE_FMT.format(LocalDateTime.now()), PAD, y, DATE_FONT, MUTED, SMALL);
		y = left(g, subtitle, PAD, y, DATE_FONT, MUTED, SMALL);
		y = leftRight(g, "Session time", formatDuration(snapshot.elapsedMillis()), PAD, right, y, DATE_FONT, MUTED, TEXT, SMALL);

		g.setColor(MUTED);
		g.drawLine(PAD, y + GAP / 2, right, y + GAP / 2);
		y += GAP;

		y = leftRight(g, "Total XP gained", Values.format(snapshot.totalXp(), rounded), PAD, right, y, BODY_FONT, TEXT, VALUE, LINE);
		y = leftRight(g, "Total loot value", Values.format(snapshot.totalLootValue(), rounded) + " gp", PAD, right, y, BODY_FONT, TEXT, VALUE, LINE);
		y = leftRight(g, "Kills", Integer.toString(snapshot.kills()), PAD, right, y, BODY_FONT, TEXT, VALUE, LINE);
		y += GAP;

		y = left(g, "Top skills", PAD, y, HEADER_FONT, TITLE, HEADER_H);
		if (skills.isEmpty())
		{
			y = left(g, "No XP gained", PAD, y, BODY_FONT, MUTED, SMALL);
		}
		else
		{
			for (SkillRow row : skills.subList(0, Math.min(MAX_SKILLS, skills.size())))
			{
				y = leftRight(g, formatSkillName(row.skill()), "+" + Values.format(row.gainedXp(), rounded),
					PAD, right, y, BODY_FONT, TEXT, VALUE, SMALL);
			}
		}
		y += GAP;

		y = left(g, "Top drops", PAD, y, HEADER_FONT, TITLE, HEADER_H);
		if (drops.isEmpty())
		{
			left(g, "No loot received", PAD, y, BODY_FONT, MUTED, SMALL);
		}
		else
		{
			for (LootRow row : drops.subList(0, Math.min(MAX_DROPS, drops.size())))
			{
				final String name = row.quantity() > 1 ? row.name() + " x " + Values.count(row.quantity()) : row.name();
				y = leftRight(g, name, Values.format(row.value(), rounded) + " gp",
					PAD, right, y, BODY_FONT, TEXT, VALUE, SMALL);
			}
		}

		g.dispose();
		return image;
	}

	/** Writes the report as a PNG into {@code dir} (created if needed) and returns the file. */
	static File saveTo(BufferedImage image, File dir) throws IOException
	{
		if (!dir.exists() && !dir.mkdirs())
		{
			throw new IOException("Unable to create directory: " + dir);
		}
		final File out = new File(dir, "session-" + FILE_FMT.format(LocalDateTime.now()) + ".png");
		ImageIO.write(image, "png", out);
		return out;
	}

	private static int left(Graphics2D g, String text, int x, int y, Font font, Color color, int lineHeight)
	{
		g.setFont(font);
		g.setColor(color);
		final FontMetrics fm = g.getFontMetrics();
		g.drawString(text, x, y + fm.getAscent());
		return y + lineHeight;
	}

	private static int leftRight(Graphics2D g, String left, String right, int x, int rightEdge, int y,
		Font font, Color leftColor, Color rightColor, int lineHeight)
	{
		g.setFont(font);
		final FontMetrics fm = g.getFontMetrics();
		final int baseline = y + fm.getAscent();
		g.setColor(leftColor);
		g.drawString(left, x, baseline);
		g.setColor(rightColor);
		g.drawString(right, rightEdge - fm.stringWidth(right), baseline);
		return y + lineHeight;
	}

	private static String formatSkillName(net.runelite.api.Skill skill)
	{
		final String name = skill.name();
		return name.charAt(0) + name.substring(1).toLowerCase();
	}

	private static String formatDuration(long millis)
	{
		final long totalSeconds = millis / 1000;
		final long hours = totalSeconds / 3600;
		final long minutes = (totalSeconds % 3600) / 60;
		final long seconds = totalSeconds % 60;
		return hours > 0
			? String.format("%d:%02d:%02d", hours, minutes, seconds)
			: String.format("%d:%02d", minutes, seconds);
	}
}
