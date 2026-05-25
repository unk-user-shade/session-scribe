package com.sessionscribe;

import com.google.inject.Provides;
import java.awt.Toolkit;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.StatChanged;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.NpcLootReceived;
import net.runelite.client.events.PlayerLootReceived;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStack;
import net.runelite.client.RuneLite;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.AsyncBufferedImage;
import net.runelite.client.util.ImageUtil;

@Slf4j
@PluginDescriptor(
	name = "Session Scribe",
	description = "Unified end-of-session recap: XP, loot value, kills, and playtime.",
	tags = {"session", "summary", "xp", "loot", "stats"}
)
public class SessionScribePlugin extends Plugin
{
	private static final long REFRESH_THROTTLE_MS = 600;
	private static final int MAX_LOOT_ROWS = 10;

	@Inject
	private SessionTracker tracker;

	@Inject
	private SessionStore store;

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private ScheduledExecutorService executor;

	@Inject
	private ItemManager itemManager;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private SessionScribeConfig config;

	private SessionScribePanel panel;
	private NavigationButton navButton;
	private long lastRefresh;
	private String currentAccount;
	private long pendingLogoutMs;
	private String selectedAccount;
	private Window selectedWindow = Window.CURRENT;

	@Override
	protected void startUp()
	{
		panel = new SessionScribePanel(config, this::requestNewSession, this::requestCopyImage, this::requestSaveImage);

		final BufferedImage icon = ImageUtil.loadImageResource(SessionScribePlugin.class, "icon.png");
		navButton = NavigationButton.builder()
			.tooltip("Session Scribe")
			.icon(icon)
			.priority(7)
			.panel(panel)
			.build();
		clientToolbar.addNavigation(navButton);

		executor.execute(() ->
		{
			store.load();
			store.finalizeLeftoverPending(itemManager::getItemPrice);
			clientThread.invoke(() ->
			{
				tracker.reset();
				pushUpdate();
			});
		});
	}

	@Override
	protected void shutDown()
	{
		finalizeCurrent();
		clientToolbar.removeNavigation(navButton);
		panel = null;
		navButton = null;
	}

	@Subscribe
	public void onStatChanged(StatChanged event)
	{
		tracker.recordXp(event.getSkill(), event.getXp());
	}

	@Subscribe
	public void onNpcLootReceived(NpcLootReceived event)
	{
		// A kill is inferred from its loot drop; drop-less kills go uncounted (see SessionTracker).
		tracker.recordKill();
		for (ItemStack item : event.getItems())
		{
			tracker.addLoot(item.getId(), item.getQuantity());
		}
	}

	@Subscribe
	public void onPlayerLootReceived(PlayerLootReceived event)
	{
		for (ItemStack item : event.getItems())
		{
			tracker.addLoot(item.getId(), item.getQuantity());
		}
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		switch (event.getGameState())
		{
			case LOGGED_IN:
				handleLogin();
				break;
			case LOGIN_SCREEN:
				handleLogout();
				break;
			default:
				break;
		}
	}

	@Subscribe
	public void onGameTick(GameTick tick)
	{
		final long now = System.currentTimeMillis();
		if (now - lastRefresh < REFRESH_THROTTLE_MS)
		{
			return;
		}
		lastRefresh = now;
		pushUpdate();
	}

	private void requestNewSession()
	{
		clientThread.invoke(() ->
		{
			finalizeCurrent();
			tracker.reset();
			pushUpdate();
		});
	}

	private void handleLogin()
	{
		final Player local = client.getLocalPlayer();
		if (local == null || local.getName() == null)
		{
			return;
		}
		final String account = local.getName();

		if (currentAccount == null)
		{
			currentAccount = account;
			if (selectedAccount == null)
			{
				selectedAccount = account;
			}
			pendingLogoutMs = 0;
			return;
		}

		if (account.equals(currentAccount) && SessionStore.withinGap(pendingLogoutMs, System.currentTimeMillis(),
			(long) config.relogGapMinutes() * 60_000))
		{
			pendingLogoutMs = 0;
			return;
		}

		finalizeCurrent();
		currentAccount = account;
		selectedAccount = account;
		pendingLogoutMs = 0;
		tracker.reset();
		pushUpdate();
	}

	private void handleLogout()
	{
		if (currentAccount == null)
		{
			return;
		}
		pendingLogoutMs = System.currentTimeMillis();
		final SessionRecord record = snapshotRecord();
		final Map<Integer, Integer> tally = tracker.getLootTally();
		final String account = currentAccount;
		final long logoutMs = pendingLogoutMs;
		executor.execute(() -> store.savePending(account, record, tally, logoutMs));
	}

	private SessionRecord snapshotRecord()
	{
		final long now = System.currentTimeMillis();
		final long duration = tracker.getElapsedMillis();
		return new SessionRecord(now - duration, now, duration,
			tracker.getXpBySkill(), tracker.getTotalLootValue(), tracker.getKills());
	}

	private void finalizeCurrent()
	{
		if (currentAccount == null)
		{
			return;
		}
		final boolean empty = tracker.getTotalXp() == 0 && tracker.getKills() == 0
			&& tracker.getLootTally().isEmpty();
		if (empty)
		{
			store.clearPending(currentAccount);
			return;
		}
		final SessionRecord record = snapshotRecord();
		final Map<Integer, Integer> tally = tracker.getLootTally();
		final String account = currentAccount;
		executor.execute(() ->
		{
			store.finalizeSession(account, record, tally, itemManager::getItemPrice);
			store.clearPending(account);
			store.save();
		});
	}

	private void requestCopyImage()
	{
		// Build the snapshot on the client thread, then place the rendered image on the clipboard (EDT).
		clientThread.invoke(() ->
		{
			final BufferedImage image = SessionReportCard.render(buildSnapshot(), config.roundValues());
			SwingUtilities.invokeLater(() ->
			{
				Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new ImageTransferable(image), null);
				panel.showExportStatus("Copied recap to clipboard");
			});
		});
	}

	private void requestSaveImage()
	{
		clientThread.invoke(() ->
		{
			final BufferedImage image = SessionReportCard.render(buildSnapshot(), config.roundValues());
			String message;
			try
			{
				final File saved = SessionReportCard.saveTo(image, new File(RuneLite.RUNELITE_DIR, "session-scribe"));
				message = "Saved " + saved.getName();
			}
			catch (IOException e)
			{
				log.warn("Failed to save session recap", e);
				message = "Save failed (see logs)";
			}
			final String result = message;
			SwingUtilities.invokeLater(() -> panel.showExportStatus(result));
		});
	}

	/** Builds an immutable snapshot on the client thread, then hands it to the EDT to render. */
	private void pushUpdate()
	{
		if (panel == null)
		{
			return;
		}
		final SessionScribePanel.Snapshot snapshot = buildSnapshot();
		SwingUtilities.invokeLater(() -> panel.render(snapshot));
	}

	private SessionScribePanel.Snapshot buildSnapshot()
	{
		final List<SessionScribePanel.SkillRow> skillRows = new ArrayList<>();
		for (Map.Entry<Skill, Integer> entry : tracker.getXpBySkill().entrySet())
		{
			if (entry.getValue() > 0)
			{
				skillRows.add(new SessionScribePanel.SkillRow(entry.getKey(), entry.getValue()));
			}
		}
		skillRows.sort((a, b) -> Integer.compare(b.gainedXp(), a.gainedXp()));

		final List<SessionScribePanel.LootRow> lootRows = new ArrayList<>();
		for (Map.Entry<Integer, Integer> entry : tracker.getLootTally().entrySet())
		{
			final int itemId = entry.getKey();
			final int quantity = entry.getValue();
			final long value = (long) itemManager.getItemPrice(itemId) * quantity;
			final String name = itemManager.getItemComposition(itemId).getName();
			final AsyncBufferedImage image = itemManager.getImage(itemId, quantity, quantity > 1);
			lootRows.add(new SessionScribePanel.LootRow(name, quantity, value, image));
		}
		lootRows.sort((a, b) -> Long.compare(b.value(), a.value()));

		final List<SessionScribePanel.LootRow> topLoot = lootRows.size() > MAX_LOOT_ROWS
			? new ArrayList<>(lootRows.subList(0, MAX_LOOT_ROWS))
			: lootRows;

		return new SessionScribePanel.Snapshot(
			tracker.getElapsedMillis(),
			tracker.getTotalXp(),
			tracker.getTotalLootValue(),
			tracker.getKills(),
			skillRows,
			topLoot);
	}

	@Provides
	SessionScribeConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(SessionScribeConfig.class);
	}
}
