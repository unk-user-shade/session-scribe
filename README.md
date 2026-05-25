# Session Scribe

A single end-of-session recap for Old School RuneScape, in one RuneLite side panel.
Session Scribe aggregates data the client already exposes — XP, loot, kills, and time —
into one view, plus a screenshot-friendly "report card" you can copy or save.

![Session Scribe panel and report card](docs/screenshot.png)
<!-- TODO: replace docs/screenshot.png with a real screenshot of the panel / report card -->

## Features

- **Live side panel**, refreshed each game tick:
  - Account and time-window selectors for **Current session**, **Last 24 hours**,
    **Last 7 days**, and **All time**
  - Session context text showing when the current session started, whether it
    continued after a relog, or whether a history window includes the live session
  - Elapsed session time (`mm:ss`, or `hh:mm` past an hour)
  - Total XP gained, with a collapsible per-skill breakdown
  - Total loot value (GE prices) and a "Top loot" list with item icons + per-item value
  - NPC kill count
- **Report card export** — a clean, fixed-layout image (title, date, duration, total XP +
  top skills, loot value + top drops, kills):
  - **Copy image** — places the recap on your system clipboard
  - **Save image** — writes a PNG to `.runelite/session-scribe/`
- **New Session** button to archive the current session and start fresh.
- **Clear history** button with confirmation before removing stored history for the
  selected account.
- Quick relogs to the same account continue the current session; account switches archive
  the previous session and start fresh.

## Session History & Windows

Session history is stored locally at `.runelite/session-scribe/history.json`, keyed by
RSN. It is never uploaded. If the file cannot be read, Session Scribe starts with an
empty history and keeps a `.corrupt` backup next to the history file.

The window selector controls which aggregate is shown:

- **Current session** shows only the live tracker and itemized top loot.
- **Last 24 hours** and **Last 7 days** include completed sessions whose end time falls
  inside the rolling window, plus the live session for the selected account.
- **All time** uses the permanent per-account totals and includes itemized top loot.

Detailed completed-session rows are retained for 90 days for rolling windows. All-time
totals are kept until you clear history.

`Current session` starts on plugin startup or when you click **New Session**. Logging out
does not reset it immediately; logging back into the same account within the relog
continuation window keeps it going. Switching accounts, exceeding the relog gap, or
clicking **New Session** archives the previous session and starts a fresh one.

## Configuration

| Option | Default | Description |
| --- | --- | --- |
| Show skill breakdown | On | Show/hide the collapsible per-skill XP list |
| Round large values | Off | Abbreviate XP/GP (e.g. `1.2M`) instead of full numbers |
| Relog continuation (min) | 5 | Continue the current session when you log back into the same account within this many minutes |

## Usage

1. Enable **Session Scribe** in the RuneLite plugin list.
2. Click the scroll icon in the sidebar to open the panel.
3. Play — XP, loot, kills, and time accrue automatically.
4. Pick an account/window when reviewing history.
5. Use **Copy image** / **Save image** for a recap, **New Session** to archive the
   live session, or **Clear history** to remove stored history for the selected account.

## Known Limitations

- Kills are counted only when a kill produces a loot drop (`NpcLootReceived`).
  Drop-less kills are not counted.
- The first XP update per skill after a reset establishes the baseline and does not
  count as gained XP.
- Last 24 hours and Last 7 days are session-granular windows and do not show itemized loot.

## Privacy

History is stored only on your machine in `.runelite/session-scribe/history.json`.
Exported recap PNGs are saved only when you click **Save image**. No network access;
no account data leaves your client.

## License

BSD 2-Clause — see [LICENSE](LICENSE).
