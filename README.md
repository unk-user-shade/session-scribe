# Session Scribe

A single end-of-session recap for Old School RuneScape, in one RuneLite side panel.
Session Scribe aggregates data the client already exposes — XP, loot, kills, and time —
into one view, plus a screenshot-friendly "report card" you can copy or save.

![Session Scribe panel and report card](docs/screenshot.png)
<!-- TODO: replace docs/screenshot.png with a real screenshot of the panel / report card -->

## Features

- **Live side panel**, refreshed each game tick:
  - Elapsed session time (`mm:ss`, or `hh:mm` past an hour)
  - Total XP gained, with a collapsible per-skill breakdown
  - Total loot value (GE prices) and a "Top loot" list with item icons + per-item value
  - NPC kill count
- **Report card export** — a clean, fixed-layout image (title, date, duration, total XP +
  top skills, loot value + top drops, kills):
  - **Copy image** — places the recap on your system clipboard
  - **Save image** — writes a PNG to `.runelite/session-scribe/`
- **New Session** button to zero all counters and start fresh.
- Re-baselines automatically on an account switch; world hops keep the same session.

## Configuration

| Option | Default | Description |
| --- | --- | --- |
| Show skill breakdown | On | Show/hide the collapsible per-skill XP list |
| Auto-reset on logout | Off | Start a fresh session when you log out to the login screen |
| Round large values | Off | Abbreviate XP/GP (e.g. `1.2M`) instead of full numbers |

## Usage

1. Enable **Session Scribe** in the RuneLite plugin list.
2. Click the scroll icon in the sidebar to open the panel.
3. Play — XP, loot, kills, and time accrue automatically.
4. Use **Copy image** / **Save image** for an end-of-session recap, or **New Session** to reset.

## Known limitation

**Kills are counted only when a kill produces a loot drop** (an `NpcLootReceived` event).
Drop-less kills are not counted — this is a deliberate consequence of inferring kills from
loot rather than from combat internals.

## Privacy

All tracking is in-memory. The only thing written to disk is the recap PNG you explicitly
save (under `.runelite/session-scribe/`). No network access; no account data leaves your client.

## License

BSD 2-Clause — see [LICENSE](LICENSE).
