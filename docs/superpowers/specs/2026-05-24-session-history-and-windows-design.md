# Session Scribe v2 — Session History & Time Windows

- **Date:** 2026-05-24
- **Status:** Approved design (pre-implementation)
- **Builds on:** the shipped v1 in-memory per-session recap (`SessionTracker`, `SessionScribePanel`, `SessionReportCard`).

## Goal

Grow Session Scribe from an in-memory per-session recap into a small **local stats tracker**.
The panel gains an **account selector** and a **time-window selector** (Current / Last 24h /
This week / All time), backed by a per-account history persisted to a local JSON file. No
network of any kind; the only disk writes remain under `.runelite/session-scribe/`.

## Non-goals

- No network, upload, sync, or crowdsourced data (unchanged hard constraint).
- No minute-exact rolling windows (we use a session-record model, not an event log).
- No per-item ("top drops") breakdown for the 24h / week windows.
- No XP time-series; XP windows are sums of per-session XP deltas.

## Locked decisions

1. **Session boundary:** logout (`GameState.LOGIN_SCREEN`) archives the current session.
   Logging back into the **same account within a configurable gap (default 5 min)** continues
   the same session instead of starting a new one. **New Session** always forces a split. World
   hops do not pass through `LOGIN_SCREEN`, so they never fragment a session.
2. **Per-account history**, keyed by **display name (RSN)**, stored locally in the JSON, never
   uploaded. A **Clear history** control is provided.
3. **All-time totals** are stored as a running aggregate (kept forever, exact). The detailed
   **session list** is a capped recent log used only for the 24h/week windows.
4. **Top loot (itemized):** shown for the **Current session** (live tally) and **All time**
   (one running per-account tally that evicts the lowest-value item when full, cap ~1000).
   24h/week show headline totals only.
5. **Retention:** detailed session rows are pruned after **90 days** (their numbers already live
   in the all-time aggregate). All-time aggregate is never pruned.
6. **Config:** remove the now-redundant "Auto-reset on logout" toggle; keep "Show skill
   breakdown" and "Round large values"; add a "Relog continuation gap (minutes)" option.

## Data model — `…/.runelite/session-scribe/history.json`

```jsonc
{
  "version": 1,
  "accounts": {
    "Zezima": {
      "allTime": {
        "sinceMs": 1716500000000,
        "durationMs": 123456789,
        "xpBySkill": { "ATTACK": 1234567, "SLAYER": 890123 },
        "lootValue": 987654321,
        "kills": 4210,
        "lootTally": { "4151": 3, "11802": 1 }   // capped, lowest-value evicted when full
      },
      "sessions": [
        { "startMs": …, "endMs": …, "durationMs": …,
          "xpBySkill": { … }, "lootValue": …, "kills": … }
      ],
      "pending": {                                 // at most one open/suspended session
        "startMs": …, "durationMs": …, "xpBySkill": { … },
        "lootValue": …, "kills": …, "lootTally": { … }, "logoutMs": …
      }
    }
  }
}
```

Sessions in the list carry **no per-item tally** (keeps the file bounded). Per-item history
exists only as the single `allTime.lootTally`.

## Components

- **`SessionTracker`** (existing) — unchanged responsibility: the live current session in
  memory. Already exposes everything finalize needs (`getElapsedMillis`, `getTotalXp`,
  `getXpBySkill`, `getLootTally`, `getTotalLootValue`, `getKills`, `reset`). Add the ability to
  **restore** state (gained-XP map, loot tally, kills, start time) so a resumed/pending session
  can continue after a client restart.
- **`SessionRecord`** (new, immutable) — `startMs`, `endMs`, `durationMs`,
  `Map<Skill,Integer> xpBySkill`, `lootValue`, `kills`.
- **`AccountHistory`** (new) — `allTime` aggregate + `List<SessionRecord> sessions` + optional
  `pending` session.
- **`SessionStore`** (new, `@Singleton`) — owns the in-memory `Map<String,AccountHistory>`;
  loads/saves the JSON via injected `Gson` **off the client thread**; finalizes the current
  session into an account, rolls it into `allTime`, prunes, and answers
  `aggregate(account, Window)`. Also `clearHistory()` / `clearAccount(name)`.
- **`Window`** (new enum) — `CURRENT`, `DAY`, `WEEK`, `ALL_TIME` (windowed ones carry a millis span).
- **`Aggregate`** (new, immutable) — `durationMs`, `xpBySkill`, `lootValue`, `kills`, and an
  optional itemized loot list (populated only for `CURRENT` and `ALL_TIME`).
- **`SessionScribePanel`** (existing) — gains the account + window dropdowns and renders an
  `Aggregate`. `CURRENT` renders the live snapshot (updates each tick); historical windows render
  read-only from `SessionStore`.
- **`SessionScribePlugin`** (existing) — glue (below).

## Session lifecycle

- **Active account** = `client.getLocalPlayer().getName()` (RSN) once logged in. Because
  `getLocalPlayer()` can be `null` at the exact `LOGGED_IN` moment, the account is resolved on the
  first tick where it is non-null. This RSN is the single account identity used throughout v2 and
  **replaces v1's `getUsername()`-based account-change detection** (`SessionTracker.onLogin`),
  keeping detection and history keying consistent.
- **On `LOGIN_SCREEN`:** snapshot the current session into the active account's `pending` slot
  with `logoutMs = now`; save (off-thread). Do **not** roll into all-time yet (avoids double
  counting if the player relogs).
- **On `LOGGED_IN`:** resolve the account.
  - Same account **and** `now - pending.logoutMs <= gap` → **resume**: restore the pending
    session into `SessionTracker` and clear `pending`.
  - Otherwise → **finalize** any existing `pending` (append a `SessionRecord`, roll into
    `allTime`, update `allTime.lootTally`, clear `pending`), then **start fresh**
    (`tracker.reset()`).
- **New Session button:** finalize the current session (if non-empty) into history, then
  `tracker.reset()`.
- **Plugin shutdown:** finalize the current session (best-effort) so it isn't lost.
- **Periodic safety save:** the current session is also written to `pending` on a debounced
  timer (off-thread), so a crash loses at most a few minutes rather than the whole session.

## Window aggregation

- `CURRENT` = the live `SessionTracker` (not from history).
- `DAY` / `WEEK` = sum of stored `SessionRecord`s whose **`endMs` is within the window**, **plus
  the in-progress current session** (so "this week" includes today). XP windows sum per-session
  per-skill deltas.
- `ALL_TIME` = the running `allTime` aggregate **plus** the in-progress current session.
- Itemized loot is attached only for `CURRENT` (live tally) and `ALL_TIME` (`allTime.lootTally`).

## UI

Two dropdowns at the top of the panel: **account** (defaults to the logged-in RSN) and **window**
(defaults to Current). Everything below — time, XP (+ breakdown), loot value, kills, and the
Top-loot list where applicable — reflects the selection. Current updates live each tick;
historical windows are read-only.

## Export

The report card renders the **selected account + window**, so a weekly or all-time recap can be
copied/saved, not just the current session. (Report card already takes a data snapshot; it will
take an `Aggregate` instead of the live-only snapshot.)

## Persistence & threading

- Load the JSON once on `startUp` **off the client thread** (injected `ScheduledExecutorService`),
  then serve all reads from the in-memory model.
- All saves happen **off the client thread**, debounced. Game-event mutations stay on the client
  thread (unchanged); the panel renders on the EDT from immutable aggregates/snapshots.
- `Gson` is injected (RuneLite provides it). The file carries a `version` field for future
  migration; unreadable/corrupt files are backed up and replaced with an empty history (logged).

## Privacy & retention

- RSN is stored **locally only**, never transmitted. Documented in the README.
- **Clear history** wipes the file (or a single account).
- Detailed session rows pruned after **90 days**; `allTime` kept forever; `allTime.lootTally`
  bounded to ~1000 items by lowest-value eviction. No collection grows without bound on disk.

## Honest limitations

- 24h/week windows are **session-granular** (a session counts if it *ended* within the window),
  not minute-exact.
- The **first XP increment per skill after a reset isn't counted** (carried over from v1's lazy
  baselining).
- **Kills require a loot drop** to be counted (carried over from v1).
- 24h/week windows show **no itemized loot** (headline totals only).

## Testing

Unit-testable without the client (the v1 pattern):
- `SessionStore.aggregate` for DAY/WEEK/ALL_TIME including the folded-in current session.
- Relog logic: resume within gap vs finalize past gap / different account (no double counting).
- All-time `lootTally` lowest-value eviction at the cap.
- Retention pruning at 90 days; all-time totals unaffected by pruning.
- JSON round-trip via Gson (write → read → equal), and graceful handling of a corrupt file.
- `Window` aggregation math (XP delta sums, loot value, kills, duration).
```
