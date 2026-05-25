package com.sessionscribe;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.util.List;
import java.util.function.BiConsumer;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import net.runelite.api.Skill;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.util.AsyncBufferedImage;

/**
 * Live recap panel. Rendered entirely on the EDT from immutable {@link Snapshot}s that the
 * plugin builds on the client thread, so no client-thread work happens inside Swing callbacks.
 */
public class SessionScribePanel extends PluginPanel
{
	private final JLabel elapsedValue = statValue();
	private final JLabel xpValue = statValue();
	private final JLabel gpValue = statValue();
	private final JLabel killsValue = statValue();

	private final JComboBox<String> accountSelector = new JComboBox<>();
	private final JComboBox<Window> windowSelector = new JComboBox<>(Window.values());
	private final JLabel sessionContext = new JLabel(" ");
	private final JButton skillToggle = new JButton();
	private final JPanel skillBody = new JPanel();
	private final JPanel lootBody = new JPanel();
	private final JLabel status = new JLabel(" ");

	private final SessionScribeConfig config;
	private final BiConsumer<String, Window> onSelectionChanged;
	private boolean skillsExpanded;
	private boolean updatingAccounts;

	public SessionScribePanel(SessionScribeConfig config, Runnable onNewSession, Runnable onCopyImage,
		Runnable onSaveImage, Runnable onClearHistory, BiConsumer<String, Window> onSelectionChanged)
	{
		this.config = config;
		this.onSelectionChanged = onSelectionChanged;
		setLayout(new BorderLayout());

		final JPanel content = new JPanel();
		content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));

		content.add(sectionLabel("Session Scribe"));

		final JPanel selectors = new JPanel(new GridLayout(0, 1, 0, 4));
		accountSelector.addActionListener(e -> fireSelection());
		windowSelector.addActionListener(e -> fireSelection());
		selectors.add(accountSelector);
		selectors.add(windowSelector);
		content.add(selectors);

		sessionContext.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		sessionContext.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));
		content.add(sessionContext);

		final JPanel stats = new JPanel(new GridLayout(0, 2, 6, 4));
		stats.setBorder(BorderFactory.createEmptyBorder(6, 0, 6, 0));
		stats.add(new JLabel("Time"));
		stats.add(elapsedValue);
		stats.add(new JLabel("XP gained"));
		stats.add(xpValue);
		stats.add(new JLabel("Loot value"));
		stats.add(gpValue);
		stats.add(new JLabel("Kills"));
		stats.add(killsValue);
		content.add(stats);

		skillToggle.setFocusPainted(false);
		skillToggle.addActionListener(e -> toggleSkills());
		content.add(skillToggle);

		skillBody.setLayout(new BoxLayout(skillBody, BoxLayout.Y_AXIS));
		skillBody.setBorder(BorderFactory.createEmptyBorder(2, 4, 6, 0));
		skillBody.setVisible(false);
		content.add(skillBody);

		content.add(sectionLabel("Top loot"));
		lootBody.setLayout(new BoxLayout(lootBody, BoxLayout.Y_AXIS));
		lootBody.setBorder(BorderFactory.createEmptyBorder(2, 0, 6, 0));
		content.add(lootBody);

		final JPanel actions = new JPanel(new GridLayout(0, 1, 0, 4));
		actions.setBorder(BorderFactory.createEmptyBorder(8, 0, 4, 0));
		actions.add(actionButton("New Session", onNewSession));
		actions.add(actionButton("Copy image", onCopyImage));
		actions.add(actionButton("Save image", onSaveImage));
		actions.add(actionButton("Clear history", onClearHistory));
		content.add(actions);

		status.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		status.setAlignmentX(Component.LEFT_ALIGNMENT);
		content.add(status);

		updateSkillToggleText(0);
		add(content, BorderLayout.NORTH);
	}

	/** Render a snapshot. Must be called on the EDT. */
	public void render(Snapshot snapshot)
	{
		final boolean rounded = config.roundValues();
		elapsedValue.setText(formatElapsed(snapshot.elapsedMillis));
		xpValue.setText(Values.format(snapshot.totalXp, rounded));
		gpValue.setText(Values.format(snapshot.totalLootValue, rounded) + " gp");
		killsValue.setText(Integer.toString(snapshot.kills));
		sessionContext.setText(snapshot.contextText);

		final boolean showSkills = config.showSkillBreakdown();
		skillToggle.setVisible(showSkills);
		if (showSkills)
		{
			updateSkillToggleText(snapshot.skillRows.size());
			skillBody.removeAll();
			if (snapshot.skillRows.isEmpty())
			{
				skillBody.add(mutedLabel("No XP yet"));
			}
			else
			{
				for (SkillRow row : snapshot.skillRows)
				{
					skillBody.add(textRow(formatSkillName(row.skill), "+" + Values.format(row.gained, rounded)));
				}
			}
			skillBody.setVisible(skillsExpanded);
		}
		else
		{
			skillBody.setVisible(false);
		}

		lootBody.removeAll();
		if (!snapshot.itemizedLoot)
		{
			lootBody.add(mutedLabel("Top loot unavailable for this window"));
		}
		else if (snapshot.lootRows.isEmpty())
		{
			lootBody.add(mutedLabel("No loot yet"));
		}
		else
		{
			for (LootRow row : snapshot.lootRows)
			{
				lootBody.add(lootRow(row));
			}
		}

		revalidate();
		repaint();
	}

	/** Refresh the account dropdown contents without firing the listener. */
	public void setAccounts(List<String> accounts, String selected)
	{
		updatingAccounts = true;
		try
		{
			final DefaultComboBoxModel<String> model = new DefaultComboBoxModel<>();
			if (accounts.isEmpty())
			{
				model.addElement("Log in to track account");
				accountSelector.setEnabled(false);
			}
			else
			{
				accountSelector.setEnabled(true);
				for (String account : accounts)
				{
					model.addElement(account);
				}
			}
			accountSelector.setModel(model);
			if (selected != null)
			{
				accountSelector.setSelectedItem(selected);
			}
		}
		finally
		{
			updatingAccounts = false;
		}
	}

	/** Show a one-line result after an export. Must be called on the EDT. */
	public void showExportStatus(String message)
	{
		status.setText(message);
	}

	private void fireSelection()
	{
		if (updatingAccounts || !accountSelector.isEnabled())
		{
			return;
		}
		final Object account = accountSelector.getSelectedItem();
		final Object window = windowSelector.getSelectedItem();
		if (window != null)
		{
			onSelectionChanged.accept(account == null ? null : account.toString(), (Window) window);
		}
	}

	private void toggleSkills()
	{
		skillsExpanded = !skillsExpanded;
		skillBody.setVisible(skillsExpanded);
		updateSkillToggleText(skillBody.getComponentCount());
		revalidate();
		repaint();
	}

	private void updateSkillToggleText(int count)
	{
		skillToggle.setText((skillsExpanded ? "▾" : "▸") + " Skill XP (" + count + ")");
	}

	private JPanel lootRow(LootRow row)
	{
		final JPanel panel = new JPanel(new BorderLayout(6, 0));
		panel.setBorder(BorderFactory.createEmptyBorder(2, 0, 2, 0));

		final JLabel icon = new JLabel();
		icon.setPreferredSize(new Dimension(32, 32));
		row.image.addTo(icon);
		panel.add(icon, BorderLayout.WEST);

		final String name = row.quantity > 1 ? row.name + " x " + Values.count(row.quantity) : row.name;
		panel.add(new JLabel(name), BorderLayout.CENTER);

		final JLabel value = new JLabel(Values.format(row.value, config.roundValues()) + " gp");
		value.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		panel.add(value, BorderLayout.EAST);
		return panel;
	}

	private static JButton actionButton(String text, Runnable action)
	{
		final JButton button = new JButton(text);
		button.setFocusPainted(false);
		button.addActionListener(e -> action.run());
		return button;
	}

	private static JPanel textRow(String left, String right)
	{
		final JPanel panel = new JPanel(new BorderLayout());
		panel.add(new JLabel(left), BorderLayout.WEST);
		final JLabel rightLabel = new JLabel(right);
		rightLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		panel.add(rightLabel, BorderLayout.EAST);
		return panel;
	}

	private static JLabel statValue()
	{
		return new JLabel("-", SwingConstants.RIGHT);
	}

	private static JLabel sectionLabel(String text)
	{
		final JLabel label = new JLabel(text);
		label.setFont(label.getFont().deriveFont(label.getFont().getStyle() | java.awt.Font.BOLD));
		label.setBorder(BorderFactory.createEmptyBorder(4, 0, 2, 0));
		return label;
	}

	private static JLabel mutedLabel(String text)
	{
		final JLabel label = new JLabel(text);
		label.setForeground(ColorScheme.MEDIUM_GRAY_COLOR);
		return label;
	}

	private static String formatSkillName(Skill skill)
	{
		final String name = skill.name();
		return name.charAt(0) + name.substring(1).toLowerCase();
	}

	private static String formatElapsed(long millis)
	{
		final long totalSeconds = millis / 1000;
		final long hours = totalSeconds / 3600;
		final long minutes = (totalSeconds % 3600) / 60;
		final long seconds = totalSeconds % 60;
		return hours > 0
			? String.format("%d:%02d", hours, minutes)
			: String.format("%02d:%02d", minutes, seconds);
	}

	/** Immutable view built on the client thread and handed to {@link #render(Snapshot)}. */
	public static final class Snapshot
	{
		private final long elapsedMillis;
		private final long totalXp;
		private final long totalLootValue;
		private final int kills;
		private final List<SkillRow> skillRows;
		private final List<LootRow> lootRows;
		private final String contextText;
		private final boolean itemizedLoot;

		public Snapshot(long elapsedMillis, long totalXp, long totalLootValue, int kills,
			List<SkillRow> skillRows, List<LootRow> lootRows)
		{
			this(elapsedMillis, totalXp, totalLootValue, kills, skillRows, lootRows, " ", true);
		}

		public Snapshot(long elapsedMillis, long totalXp, long totalLootValue, int kills,
			List<SkillRow> skillRows, List<LootRow> lootRows, String contextText, boolean itemizedLoot)
		{
			this.elapsedMillis = elapsedMillis;
			this.totalXp = totalXp;
			this.totalLootValue = totalLootValue;
			this.kills = kills;
			this.skillRows = skillRows;
			this.lootRows = lootRows;
			this.contextText = contextText;
			this.itemizedLoot = itemizedLoot;
		}

		public long elapsedMillis()
		{
			return elapsedMillis;
		}

		public long totalXp()
		{
			return totalXp;
		}

		public long totalLootValue()
		{
			return totalLootValue;
		}

		public int kills()
		{
			return kills;
		}

		public List<SkillRow> skillRows()
		{
			return skillRows;
		}

		public List<LootRow> lootRows()
		{
			return lootRows;
		}
	}

	public static final class SkillRow
	{
		private final Skill skill;
		private final int gained;

		public SkillRow(Skill skill, int gained)
		{
			this.skill = skill;
			this.gained = gained;
		}

		public Skill skill()
		{
			return skill;
		}

		public int gainedXp()
		{
			return gained;
		}
	}

	public static final class LootRow
	{
		private final String name;
		private final int quantity;
		private final long value;
		private final AsyncBufferedImage image;

		public LootRow(String name, int quantity, long value, AsyncBufferedImage image)
		{
			this.name = name;
			this.quantity = quantity;
			this.value = value;
			this.image = image;
		}

		public String name()
		{
			return name;
		}

		public int quantity()
		{
			return quantity;
		}

		public long value()
		{
			return value;
		}
	}
}
