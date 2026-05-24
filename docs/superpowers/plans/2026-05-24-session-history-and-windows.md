# Session History & Time Windows — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add per-account local session history with a Current / Last 24h / This week / All time window selector to Session Scribe.

**Architecture:** A new `SessionStore` singleton owns an in-memory per-account history (loaded from / saved to `history.json` via Gson, off the client thread) and answers `aggregate(account, window, now, current)`. The existing `SessionTracker` keeps tracking the live session; the plugin finalizes sessions into the store on logout/account-switch/New Session and folds the live session into windowed aggregates. The panel gains account + window dropdowns and renders the selected aggregate.

**Tech Stack:** Java 11, RuneLite client API, Lombok, Gson (RuneLite-provided), JUnit 4, Gradle.

---

## Design source

Spec: `docs/superpowers/specs/2026-05-24-session-history-and-windows-design.md`. Read it before starting.

## File structure

**New:**
- `src/main/java/com/sessionscribe/Window.java` — enum of the four windows + rolling span.
- `src/main/java/com/sessionscribe/SessionRecord.java` — immutable completed-session record.
- `src/main/java/com/sessionscribe/AllTimeStats.java` — mutable per-account running aggregate.
- `src/main/java/com/sessionscribe/PendingSession.java` — in-progress session mirrored to disk for crash recovery.
- `src/main/java/com/sessionscribe/AccountHistory.java` — `allTime` + `sessions` + nullable `pending`.
- `src/main/java/com/sessionscribe/Aggregate.java` — immutable view returned to the panel.
- `src/main/java/com/sessionscribe/SessionStore.java` — history model, aggregation, persistence, retention.
- Tests: `WindowTest`, `SessionStoreTest`, `SessionStoreJsonTest`.

**Modified:**
- `SessionTracker.java` — drop the `Client`/`lastUsername`/`onLogin()` (account logic moves to the plugin); add `snapshotLootTally()` already exists as `getLootTally()`.
- `SessionScribeConfig.java` — remove `autoResetOnLogout`; add `relogGapMinutes`.
- `SessionScribePlugin.java` — lifecycle glue (finalize/continue), selection state, debounced save.
- `SessionScribePanel.java` — account + window dropdowns; render from the plugin per selection.
- `SessionReportCard.java` — render takes the window/account label.
- `README.md` — document windows, history, privacy, Clear history, limitations.

## Shared interfaces (used across tasks — keep names/signatures identical)

```java
// Window
enum Window { CURRENT, DAY, WEEK, ALL_TIME; String label(); boolean isRolling(); long spanMillis(); }

// SessionRecord (immutable)
SessionRecord(long startMs, long endMs, long durationMs, Map<Skill,Integer> xpBySkill, long lootValue, int kills)

// SessionStore (singleton)
void load();                                  // from default file
void save();                                  // to default file (debounced by caller)
java.util.Set<String> accounts();
void clearAll();
void clearAccount(String account);
void finalizeSession(String account, SessionRecord record, Map<Integer,Integer> lootTally, java.util.function.IntUnaryOperator priceFn);
Aggregate aggregate(String account, Window window, long now, SessionRecord current, Map<Integer,Integer> currentLootTally, java.util.function.IntUnaryOperator priceFn);
static boolean withinGap(long logoutMs, long now, long gapMs);
static String toJson(com.google.gson.Gson gson, java.util.Map<String,AccountHistory> accounts);
static java.util.Map<String,AccountHistory> fromJson(com.google.gson.Gson gson, String json);
```

---

### Task 1: `Window` enum

**Files:**
- Create: `src/main/java/com/sessionscribe/Window.java`
- Test: `src/test/java/com/sessionscribe/WindowTest.java`

- [ ] **Step 1: Write the failing test**

```java
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
		assertEquals("Current session", Window.CURRENT.label());
	}
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `gradlew.bat test --tests com.sessionscribe.WindowTest`
Expected: FAIL — `Window` does not exist.

- [ ] **Step 3: Write the implementation**

```java
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
```

- [ ] **Step 4: Run test to verify it passes**

Run: `gradlew.bat test --tests com.sessionscribe.WindowTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/sessionscribe/Window.java src/test/java/com/sessionscribe/WindowTest.java
git commit -m "Add Window enum for session history time windows"
```

---

### Task 2: Data containers (`SessionRecord`, `AllTimeStats`, `PendingSession`, `AccountHistory`, `Aggregate`)

These are plain data holders with no behavior; they are exercised by later tests, so they are committed together without their own test.

**Files:**
- Create: `src/main/java/com/sessionscribe/SessionRecord.java`
- Create: `src/main/java/com/sessionscribe/AllTimeStats.java`
- Create: `src/main/java/com/sessionscribe/PendingSession.java`
- Create: `src/main/java/com/sessionscribe/AccountHistory.java`
- Create: `src/main/java/com/sessionscribe/Aggregate.java`

- [ ] **Step 1: Write `SessionRecord`**

```java
package com.sessionscribe;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import net.runelite.api.Skill;

/** An immutable record of one completed session. Per-item loot is not stored here. */
final class SessionRecord
{
	long startMs;
	long endMs;
	long durationMs;
	Map<Skill, Integer> xpBySkill;
	long lootValue;
	int kills;

	// Gson uses field injection on deserialize; this constructor is for our code.
	SessionRecord(long startMs, long endMs, long durationMs, Map<Skill, Integer> xpBySkill, long lootValue, int kills)
	{
		this.startMs = startMs;
		this.endMs = endMs;
		this.durationMs = durationMs;
		this.xpBySkill = xpBySkill == null ? Collections.emptyMap() : new EnumMap<>(xpBySkill);
		this.lootValue = lootValue;
		this.kills = kills;
	}
}
```

- [ ] **Step 2: Write `AllTimeStats`**

```java
package com.sessionscribe;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import net.runelite.api.Skill;

/** Per-account running totals kept forever (never pruned). */
final class AllTimeStats
{
	long sinceMs;
	long durationMs;
	Map<Skill, Integer> xpBySkill = new EnumMap<>(Skill.class);
	long lootValue;
	int kills;
	Map<Integer, Integer> lootTally = new HashMap<>();
}
```

- [ ] **Step 3: Write `PendingSession`**

```java
package com.sessionscribe;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import net.runelite.api.Skill;

/** A session mirrored to disk while in progress, so a crash does not lose it. */
final class PendingSession
{
	long startMs;
	long durationMs;
	Map<Skill, Integer> xpBySkill = new EnumMap<>(Skill.class);
	long lootValue;
	int kills;
	Map<Integer, Integer> lootTally = new HashMap<>();
	long logoutMs;
}
```

- [ ] **Step 4: Write `AccountHistory`**

```java
package com.sessionscribe;

import java.util.ArrayList;
import java.util.List;

final class AccountHistory
{
	AllTimeStats allTime = new AllTimeStats();
	List<SessionRecord> sessions = new ArrayList<>();
	PendingSession pending; // nullable
}
```

- [ ] **Step 5: Write `Aggregate`**

```java
package com.sessionscribe;

import java.util.Map;
import net.runelite.api.Skill;

/** Immutable view for one (account, window) selection. lootTally is null when not itemized. */
final class Aggregate
{
	final long durationMs;
	final Map<Skill, Integer> xpBySkill;
	final long lootValue;
	final int kills;
	final Map<Integer, Integer> lootTally;

	Aggregate(long durationMs, Map<Skill, Integer> xpBySkill, long lootValue, int kills, Map<Integer, Integer> lootTally)
	{
		this.durationMs = durationMs;
		this.xpBySkill = xpBySkill;
		this.lootValue = lootValue;
		this.kills = kills;
		this.lootTally = lootTally;
	}
}
```

- [ ] **Step 6: Verify it compiles**

Run: `gradlew.bat compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/sessionscribe/SessionRecord.java src/main/java/com/sessionscribe/AllTimeStats.java src/main/java/com/sessionscribe/PendingSession.java src/main/java/com/sessionscribe/AccountHistory.java src/main/java/com/sessionscribe/Aggregate.java
git commit -m "Add session-history data containers"
```

---

### Task 3: `SessionStore.finalizeSession` + all-time roll-up + loot eviction

**Files:**
- Create: `src/main/java/com/sessionscribe/SessionStore.java`
- Test: `src/test/java/com/sessionscribe/SessionStoreTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.sessionscribe;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.function.IntUnaryOperator;
import net.runelite.api.Skill;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SessionStoreTest
{
	private static Map<Skill, Integer> xp(int attack, int slayer)
	{
		Map<Skill, Integer> m = new EnumMap<>(Skill.class);
		m.put(Skill.ATTACK, attack);
		m.put(Skill.SLAYER, slayer);
		return m;
	}

	@Test
	public void finalizeRollsIntoAllTimeAndEvictsLowestValueLoot()
	{
		SessionStore store = new SessionStore();
		IntUnaryOperator price = id -> id; // value == id, so id 1 is the cheapest

		Map<Integer, Integer> loot1 = new HashMap<>();
		loot1.put(100, 2);  // value 200
		store.finalizeSession("Zezima",
			new SessionRecord(0, 1000, 1000, xp(500, 0), 200, 3), loot1, price);

		Map<Integer, Integer> loot2 = new HashMap<>();
		loot2.put(100, 1);  // aggregates -> qty 3
		loot2.put(200, 1);  // value 200
		store.finalizeSession("Zezima",
			new SessionRecord(2000, 3000, 1000, xp(0, 250), 200, 1), loot2, price);

		Aggregate all = store.aggregate("Zezima", Window.ALL_TIME, 5000, null, null, price);
		assertEquals(2000L, all.durationMs);
		assertEquals(Integer.valueOf(500), all.xpBySkill.get(Skill.ATTACK));
		assertEquals(Integer.valueOf(250), all.xpBySkill.get(Skill.SLAYER));
		assertEquals(400L, all.lootValue);
		assertEquals(4, all.kills);
		assertEquals(Integer.valueOf(3), all.lootTally.get(100));
	}

	@Test
	public void allTimeLootTallyEvictsCheapestWhenOverCap()
	{
		SessionStore store = new SessionStore();
		IntUnaryOperator price = id -> id;
		// Fill exactly to the cap with ids 1..MAX (value == id).
		Map<Integer, Integer> loot = new HashMap<>();
		for (int id = 1; id <= SessionStore.MAX_LOOT_ENTRIES; id++)
		{
			loot.put(id, 1);
		}
		store.finalizeSession("Z", new SessionRecord(0, 1, 1, null, 0, 0), loot, price);

		// Add one more high-value item; the cheapest (id 1) must be evicted.
		Map<Integer, Integer> more = new HashMap<>();
		more.put(9_999_999, 1);
		store.finalizeSession("Z", new SessionRecord(1, 2, 1, null, 0, 0), more, price);

		Aggregate all = store.aggregate("Z", Window.ALL_TIME, 10, null, null, price);
		assertEquals(SessionStore.MAX_LOOT_ENTRIES, all.lootTally.size());
		assertTrue(all.lootTally.containsKey(9_999_999));
		assertFalse(all.lootTally.containsKey(1));
	}
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `gradlew.bat test --tests com.sessionscribe.SessionStoreTest`
Expected: FAIL — `SessionStore` does not exist.

- [ ] **Step 3: Write the implementation (this task's portion)**

Create `SessionStore.java`. (Later tasks add `aggregate` rolling windows, persistence, retention. Include the full file now so it compiles; the methods below cover Task 3 + a minimal `aggregate` for ALL_TIME/CURRENT used by this test. Tasks 4–5 extend `aggregate` and add persistence.)

```java
package com.sessionscribe;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.IntUnaryOperator;
import javax.inject.Singleton;
import net.runelite.api.Skill;

/** In-memory per-account session history with aggregation, retention, and JSON persistence. */
@Singleton
class SessionStore
{
	static final int MAX_LOOT_ENTRIES = 1000;
	static final long RETENTION_MS = TimeUnit.DAYS.toMillis(90);

	private Map<String, AccountHistory> accounts = new LinkedHashMap<>();

	Set<String> accounts()
	{
		return new LinkedHashMap<>(accounts).keySet();
	}

	void clearAll()
	{
		accounts.clear();
	}

	void clearAccount(String account)
	{
		accounts.remove(account);
	}

	/** Append a completed session, roll it into all-time totals, and prune old detailed rows. */
	void finalizeSession(String account, SessionRecord record, Map<Integer, Integer> lootTally, IntUnaryOperator priceFn)
	{
		AccountHistory history = accounts.computeIfAbsent(account, k -> new AccountHistory());
		history.sessions.add(record);

		AllTimeStats all = history.allTime;
		if (all.sinceMs == 0)
		{
			all.sinceMs = record.startMs;
		}
		all.durationMs += record.durationMs;
		all.lootValue += record.lootValue;
		all.kills += record.kills;
		addXp(all.xpBySkill, record.xpBySkill);
		mergeLoot(all.lootTally, lootTally, priceFn);

		pruneOldSessions(history, record.endMs);
	}

	Aggregate aggregate(String account, Window window, long now, SessionRecord current,
		Map<Integer, Integer> currentLootTally, IntUnaryOperator priceFn)
	{
		if (window == Window.CURRENT)
		{
			if (current == null)
			{
				return emptyAggregate(new HashMap<>());
			}
			return new Aggregate(current.durationMs, new EnumMap<>(current.xpBySkill),
				current.lootValue, current.kills,
				currentLootTally == null ? new HashMap<>() : new HashMap<>(currentLootTally));
		}

		AccountHistory history = accounts.get(account);
		long durationMs = 0;
		long lootValue = 0;
		int kills = 0;
		Map<Skill, Integer> xp = new EnumMap<>(Skill.class);
		Map<Integer, Integer> tally = null;

		if (window == Window.ALL_TIME)
		{
			if (history != null)
			{
				durationMs = history.allTime.durationMs;
				lootValue = history.allTime.lootValue;
				kills = history.allTime.kills;
				addXp(xp, history.allTime.xpBySkill);
				tally = new HashMap<>(history.allTime.lootTally);
			}
			else
			{
				tally = new HashMap<>();
			}
			if (current != null)
			{
				durationMs += current.durationMs;
				lootValue += current.lootValue;
				kills += current.kills;
				addXp(xp, current.xpBySkill);
				mergeLoot(tally, currentLootTally, priceFn);
			}
			return new Aggregate(durationMs, xp, lootValue, kills, tally);
		}

		// DAY / WEEK: sum sessions whose end is within the rolling window, plus the live session.
		long cutoff = now - window.spanMillis();
		if (history != null)
		{
			for (SessionRecord record : history.sessions)
			{
				if (record.endMs >= cutoff)
				{
					durationMs += record.durationMs;
					lootValue += record.lootValue;
					kills += record.kills;
					addXp(xp, record.xpBySkill);
				}
			}
		}
		if (current != null)
		{
			durationMs += current.durationMs;
			lootValue += current.lootValue;
			kills += current.kills;
			addXp(xp, current.xpBySkill);
		}
		return new Aggregate(durationMs, xp, lootValue, kills, null);
	}

	private static Aggregate emptyAggregate(Map<Integer, Integer> tally)
	{
		return new Aggregate(0, new EnumMap<>(Skill.class), 0, 0, tally);
	}

	private static void addXp(Map<Skill, Integer> into, Map<Skill, Integer> from)
	{
		if (from == null)
		{
			return;
		}
		for (Map.Entry<Skill, Integer> e : from.entrySet())
		{
			into.merge(e.getKey(), e.getValue(), Integer::sum);
		}
	}

	private static void mergeLoot(Map<Integer, Integer> into, Map<Integer, Integer> from, IntUnaryOperator priceFn)
	{
		if (from == null)
		{
			return;
		}
		for (Map.Entry<Integer, Integer> e : from.entrySet())
		{
			into.merge(e.getKey(), e.getValue(), Integer::sum);
		}
		while (into.size() > MAX_LOOT_ENTRIES)
		{
			Integer cheapest = null;
			long cheapestValue = Long.MAX_VALUE;
			for (Map.Entry<Integer, Integer> e : into.entrySet())
			{
				long value = (long) priceFn.applyAsInt(e.getKey()) * e.getValue();
				if (value < cheapestValue)
				{
					cheapestValue = value;
					cheapest = e.getKey();
				}
			}
			into.remove(cheapest);
		}
	}

	private static void pruneOldSessions(AccountHistory history, long now)
	{
		long cutoff = now - RETENTION_MS;
		List<SessionRecord> kept = new ArrayList<>();
		for (SessionRecord record : history.sessions)
		{
			if (record.endMs >= cutoff)
			{
				kept.add(record);
			}
		}
		history.sessions = kept;
	}
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `gradlew.bat test --tests com.sessionscribe.SessionStoreTest`
Expected: PASS (both methods).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/sessionscribe/SessionStore.java src/test/java/com/sessionscribe/SessionStoreTest.java
git commit -m "Add SessionStore finalize + all-time roll-up with loot eviction"
```

---

### Task 4: `SessionStore` rolling-window aggregation (DAY/WEEK) + retention

The aggregation and pruning code already exists from Task 3; this task **adds the tests that lock the behavior in**.

**Files:**
- Modify: `src/test/java/com/sessionscribe/SessionStoreTest.java`

- [ ] **Step 1: Add the failing tests**

```java
	@Test
	public void rollingWindowsCountOnlySessionsEndingInsideThem()
	{
		SessionStore store = new SessionStore();
		long now = TimeUnit.DAYS.toMillis(10);
		IntUnaryOperator price = id -> 1;

		// 8 days ago (inside the week, outside 24h)
		long eightDaysAgo = now - TimeUnit.DAYS.toMillis(8);
		store.finalizeSession("Z", new SessionRecord(eightDaysAgo, eightDaysAgo, 100, xp(10, 0), 5, 1), new HashMap<>(), price);
		// 3 days ago (inside the week, outside 24h)
		long threeDaysAgo = now - TimeUnit.DAYS.toMillis(3);
		store.finalizeSession("Z", new SessionRecord(threeDaysAgo, threeDaysAgo, 200, xp(20, 0), 7, 2), new HashMap<>(), price);
		// 2 hours ago (inside both)
		long twoHoursAgo = now - TimeUnit.HOURS.toMillis(2);
		store.finalizeSession("Z", new SessionRecord(twoHoursAgo, twoHoursAgo, 300, xp(30, 0), 9, 3), new HashMap<>(), price);

		Aggregate day = store.aggregate("Z", Window.DAY, now, null, null, price);
		assertEquals(300L, day.durationMs);
		assertEquals(Integer.valueOf(30), day.xpBySkill.get(Skill.ATTACK));
		assertEquals(3, day.kills);
		assertEquals(null, day.lootTally); // no itemized loot for rolling windows

		Aggregate week = store.aggregate("Z", Window.WEEK, now, null, null, price);
		assertEquals(500L, week.durationMs); // 3-days-ago + 2-hours-ago (8-days-ago is excluded)
		assertEquals(5, week.kills);
	}

	@Test
	public void rollingWindowFoldsInTheLiveSession()
	{
		SessionStore store = new SessionStore();
		long now = TimeUnit.DAYS.toMillis(10);
		IntUnaryOperator price = id -> 1;
		SessionRecord live = new SessionRecord(now - 60_000, now, 60_000, xp(40, 0), 11, 4);

		Aggregate day = store.aggregate("Z", Window.DAY, now, live, new HashMap<>(), price);
		assertEquals(60_000L, day.durationMs);
		assertEquals(Integer.valueOf(40), day.xpBySkill.get(Skill.ATTACK));
		assertEquals(4, day.kills);
	}

	@Test
	public void sessionsOlderThanRetentionArePrunedButAllTimeSurvives()
	{
		SessionStore store = new SessionStore();
		IntUnaryOperator price = id -> 1;
		long old = 0;
		long recent = SessionStore.RETENTION_MS + TimeUnit.DAYS.toMillis(1);

		store.finalizeSession("Z", new SessionRecord(old, old, 100, xp(10, 0), 5, 1), new HashMap<>(), price);
		// Finalizing a recent session sets "now" to its endMs and prunes the old one.
		store.finalizeSession("Z", new SessionRecord(recent, recent, 200, xp(20, 0), 7, 2), new HashMap<>(), price);

		// The week window now sees only the recent session...
		Aggregate week = store.aggregate("Z", Window.WEEK, recent, null, null, price);
		assertEquals(200L, week.durationMs);
		// ...but all-time still has both.
		Aggregate all = store.aggregate("Z", Window.ALL_TIME, recent, null, null, price);
		assertEquals(300L, all.durationMs);
		assertEquals(3, all.kills);
	}
```

- [ ] **Step 2: Run tests to verify they pass**

Run: `gradlew.bat test --tests com.sessionscribe.SessionStoreTest`
Expected: PASS (all five methods). If any fail, the aggregation/pruning logic from Task 3 has a bug — fix it there.

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/sessionscribe/SessionStoreTest.java
git commit -m "Lock in rolling-window aggregation and retention behavior"
```

---

### Task 5: `SessionStore` JSON persistence + relog-gap helper

**Files:**
- Modify: `src/main/java/com/sessionscribe/SessionStore.java`
- Test: `src/test/java/com/sessionscribe/SessionStoreJsonTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.sessionscribe;

import com.google.gson.Gson;
import java.util.HashMap;
import java.util.Map;
import java.util.function.IntUnaryOperator;
import net.runelite.api.Skill;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class SessionStoreJsonTest
{
	@Test
	public void roundTripsAccountsThroughJson()
	{
		SessionStore store = new SessionStore();
		IntUnaryOperator price = id -> id;
		Map<Skill, Integer> xp = new HashMap<>();
		xp.put(Skill.SLAYER, 12_345);
		Map<Integer, Integer> loot = new HashMap<>();
		loot.put(11_802, 1); // Armadyl godsword
		store.finalizeSession("Zezima", new SessionRecord(1, 2, 1000, xp, 50_000_000, 9), loot, price);

		Gson gson = new Gson();
		String json = SessionStore.toJson(gson, store.exportForTest());
		assertTrue(json.contains("Zezima"));
		assertTrue(json.contains("SLAYER"));

		Map<String, AccountHistory> reloaded = SessionStore.fromJson(gson, json);
		AccountHistory h = reloaded.get("Zezima");
		assertEquals(1000L, h.allTime.durationMs);
		assertEquals(50_000_000L, h.allTime.lootValue);
		assertEquals(9, h.allTime.kills);
		assertEquals(Integer.valueOf(12_345), h.allTime.xpBySkill.get(Skill.SLAYER));
		assertEquals(Integer.valueOf(1), h.allTime.lootTally.get(11_802));
	}

	@Test
	public void corruptJsonYieldsEmptyHistory()
	{
		Map<String, AccountHistory> result = SessionStore.fromJson(new Gson(), "{not valid json");
		assertTrue(result.isEmpty());
	}

	@Test
	public void withinGapHonorsTheWindow()
	{
		assertTrue(SessionStore.withinGap(1000, 1000 + 60_000, 5 * 60_000));   // 1 min later, 5 min gap
		assertEquals(false, SessionStore.withinGap(1000, 1000 + 6 * 60_000, 5 * 60_000)); // 6 min later
		assertEquals(false, SessionStore.withinGap(0, 100_000, 5 * 60_000));   // no logout recorded
	}
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `gradlew.bat test --tests com.sessionscribe.SessionStoreJsonTest`
Expected: FAIL — `toJson` / `fromJson` / `withinGap` / `exportForTest` not defined.

- [ ] **Step 3: Add persistence + helpers to `SessionStore`**

Add these imports to `SessionStore.java`:

```java
import com.google.gson.Gson;
import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.RuneLite;
```

Add `@Slf4j` to the class. Add the injected `Gson`, a serializable wrapper, the file path, and the methods:

```java
	@Inject
	private Gson gson;

	private static final int VERSION = 1;

	/** Serializable top-level shape of history.json. */
	private static final class HistoryFile
	{
		int version = VERSION;
		Map<String, AccountHistory> accounts = new LinkedHashMap<>();
	}

	private static File historyFile()
	{
		return new File(RuneLite.RUNELITE_DIR, "session-scribe" + File.separator + "history.json");
	}

	synchronized void load()
	{
		File file = historyFile();
		if (!file.exists())
		{
			accounts = new LinkedHashMap<>();
			return;
		}
		try (Reader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8))
		{
			HistoryFile parsed = gson.fromJson(reader, HistoryFile.class);
			accounts = parsed != null && parsed.accounts != null ? parsed.accounts : new LinkedHashMap<>();
		}
		catch (Exception e)
		{
			log.warn("Could not read session history; backing up and starting fresh", e);
			backup(file);
			accounts = new LinkedHashMap<>();
		}
	}

	synchronized void save()
	{
		File file = historyFile();
		File dir = file.getParentFile();
		if (dir != null && !dir.exists() && !dir.mkdirs())
		{
			log.warn("Could not create history directory: {}", dir);
			return;
		}
		File tmp = new File(file.getParentFile(), file.getName() + ".tmp");
		try (Writer writer = Files.newBufferedWriter(tmp.toPath(), StandardCharsets.UTF_8))
		{
			HistoryFile out = new HistoryFile();
			out.accounts = accounts;
			gson.toJson(out, writer);
		}
		catch (IOException e)
		{
			log.warn("Could not write session history", e);
			return;
		}
		try
		{
			Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
		}
		catch (IOException e)
		{
			log.warn("Could not replace session history file", e);
		}
	}

	private static void backup(File file)
	{
		try
		{
			Files.move(file.toPath(), new File(file.getParentFile(), file.getName() + ".corrupt").toPath(),
				StandardCopyOption.REPLACE_EXISTING);
		}
		catch (IOException ignored)
		{
			// Best effort; if the backup fails we still overwrite with a fresh file on next save.
		}
	}

	static boolean withinGap(long logoutMs, long now, long gapMs)
	{
		return logoutMs > 0 && (now - logoutMs) <= gapMs;
	}

	static String toJson(Gson gson, Map<String, AccountHistory> accounts)
	{
		HistoryFile out = new HistoryFile();
		out.accounts = accounts;
		return gson.toJson(out);
	}

	static Map<String, AccountHistory> fromJson(Gson gson, String json)
	{
		try
		{
			HistoryFile parsed = gson.fromJson(json, HistoryFile.class);
			return parsed != null && parsed.accounts != null ? parsed.accounts : new LinkedHashMap<>();
		}
		catch (Exception e)
		{
			return new LinkedHashMap<>();
		}
	}

	// Test seam: expose the in-memory map for JSON round-trip tests.
	Map<String, AccountHistory> exportForTest()
	{
		return accounts;
	}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `gradlew.bat test --tests com.sessionscribe.SessionStoreJsonTest`
Expected: PASS. If `roundTripsAccountsThroughJson` fails on the `xpBySkill`/`lootTally` keys, Gson is mis-handling the enum/int map keys — register the field types or confirm the maps are declared `Map<Skill,Integer>` / `Map<Integer,Integer>` (they are). Fix and re-run.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/sessionscribe/SessionStore.java src/test/java/com/sessionscribe/SessionStoreJsonTest.java
git commit -m "Add SessionStore JSON persistence and relog-gap helper"
```

---

### Task 6: Simplify `SessionTracker` (account logic moves to the plugin)

**Files:**
- Modify: `src/main/java/com/sessionscribe/SessionTracker.java`

- [ ] **Step 1: Remove `onLogin`, `lastUsername`, and the `Client` dependency**

Delete the `@Inject private Client client;` field, the `lastUsername` field, and the `onLogin()` method. Change `reset()` so it no longer references the client:

```java
	/** Clear all counters and start a fresh session. */
	public void reset()
	{
		sessionStart = System.currentTimeMillis();
		baseline.clear();
		gained.clear();
		loot.clear();
		kills = 0;
		// Baselines are (re)established lazily by the first StatChanged per skill after a reset.
	}
```

Remove the now-unused import `net.runelite.api.Client`. Keep `net.runelite.api.Skill`, `ItemManager`, etc.

- [ ] **Step 2: Verify existing tracker tests still pass**

Run: `gradlew.bat test --tests com.sessionscribe.SessionTrackerTest`
Expected: PASS (these tests never used `onLogin`/`Client`).

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/sessionscribe/SessionTracker.java
git commit -m "Move account-change handling out of SessionTracker"
```

---

### Task 7: Config — drop `autoResetOnLogout`, add `relogGapMinutes`

**Files:**
- Modify: `src/main/java/com/sessionscribe/SessionScribeConfig.java`

- [ ] **Step 1: Replace the `autoResetOnLogout` item with `relogGapMinutes`**

Remove the `autoResetOnLogout()` `@ConfigItem` entirely. Add:

```java
	@ConfigItem(
		keyName = "relogGapMinutes",
		name = "Relog continuation (min)",
		description = "If you log back into the same account within this many minutes, the session continues instead of starting a new one.",
		position = 4
	)
	default int relogGapMinutes()
	{
		return 5;
	}
```

Keep `showSkillBreakdown` (position 1) and `roundValues` (position 3). Leave positions 1 and 3 as-is.

- [ ] **Step 2: Verify it compiles**

Run: `gradlew.bat compileJava`
Expected: BUILD SUCCESSFUL. (The plugin still references `autoResetOnLogout` at this point — it will fail to compile here. That is expected; Task 8 updates the plugin. If you are running tasks strictly one at a time, do Step 2 after Task 8 instead, or temporarily comment the plugin reference.)

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/sessionscribe/SessionScribeConfig.java
git commit -m "Replace auto-reset config with relog continuation gap"
```

---

### Task 8: Plugin glue — lifecycle, selection state, persistence

This is integration code that drives the client; it is verified manually (Task 11), not by unit tests. The one piece of pure logic (`withinGap`) is already unit-tested in Task 5.

**Files:**
- Modify: `src/main/java/com/sessionscribe/SessionScribePlugin.java`

- [ ] **Step 1: Add fields, store, executor, and selection state**

Add imports:

```java
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import net.runelite.api.Player;
```

Add injected dependencies and state next to the existing fields:

```java
	@Inject
	private SessionStore store;

	@Inject
	private ScheduledExecutorService executor;

	private String currentAccount;       // RSN of the session currently being tracked
	private long pendingLogoutMs;         // when we last hit the login screen (0 = none)
	private String selectedAccount;       // panel selection
	private Window selectedWindow = Window.CURRENT;
```

- [ ] **Step 2: Load history on startup; finalize any leftover pending sessions**

In `startUp()`, after building the panel/nav button, replace the existing `clientThread.invoke(...)` reset block with:

```java
		executor.execute(() ->
		{
			store.load();
			store.finalizeLeftoverPending(itemManager::getItemPrice); // recovers crashed sessions
			clientThread.invoke(() ->
			{
				tracker.reset();
				pushUpdate();
			});
		});
```

Add to `SessionStore.java` (and unit-cover via the existing JSON test pattern if desired) a method to finalize any `pending` slots left by a previous run:

```java
	synchronized void finalizeLeftoverPending(IntUnaryOperator priceFn)
	{
		for (Map.Entry<String, AccountHistory> entry : accounts.entrySet())
		{
			AccountHistory history = entry.getValue();
			PendingSession pending = history.pending;
			if (pending == null)
			{
				continue;
			}
			long end = pending.startMs + pending.durationMs;
			SessionRecord record = new SessionRecord(pending.startMs, end, pending.durationMs,
				pending.xpBySkill, pending.lootValue, pending.kills);
			finalizeSessionInternal(history, entry.getKey(), record, pending.lootTally, priceFn);
			history.pending = null;
		}
		save();
	}
```

Refactor `finalizeSession` to delegate to a shared `finalizeSessionInternal(AccountHistory, String, SessionRecord, tally, priceFn)` so both paths share the roll-up code (move the body of `finalizeSession` into it).

- [ ] **Step 3: Replace `onGameStateChanged` with the lifecycle logic**

```java
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

	private void handleLogin()
	{
		final Player local = client.getLocalPlayer();
		if (local == null || local.getName() == null)
		{
			return; // resolved on a later tick when the name is available
		}
		final String account = local.getName();

		if (currentAccount == null)
		{
			// First login this run: adopt the account, keep the (empty) live session.
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
			// Same account, quick relog: continue the in-memory session.
			pendingLogoutMs = 0;
			return;
		}

		// Different account, or gap exceeded: archive the previous session and start fresh.
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
		// Mirror the in-progress session to disk so a crash before relog still archives it.
		final SessionRecord record = snapshotRecord();
		final Map<Integer, Integer> tally = tracker.getLootTally();
		final String account = currentAccount;
		executor.execute(() ->
		{
			store.savePending(account, record, tally, pendingLogoutMs);
		});
	}
```

Add `savePending` to `SessionStore` (stores a `PendingSession` for the account and saves):

```java
	synchronized void savePending(String account, SessionRecord record, Map<Integer, Integer> lootTally, long logoutMs)
	{
		AccountHistory history = accounts.computeIfAbsent(account, k -> new AccountHistory());
		PendingSession pending = new PendingSession();
		pending.startMs = record.startMs;
		pending.durationMs = record.durationMs;
		pending.xpBySkill = record.xpBySkill;
		pending.lootValue = record.lootValue;
		pending.kills = record.kills;
		pending.lootTally = lootTally == null ? new HashMap<>() : new HashMap<>(lootTally);
		pending.logoutMs = logoutMs;
		history.pending = pending;
		save();
	}
```

- [ ] **Step 4: Add `finalizeCurrent`, `snapshotRecord`, and update New Session**

```java
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
		final long duration = tracker.getElapsedMillis();
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
```

Add `clearPending` to `SessionStore`:

```java
	synchronized void clearPending(String account)
	{
		AccountHistory history = accounts.get(account);
		if (history != null)
		{
			history.pending = null;
		}
	}
```

Change `requestNewSession()` to finalize before resetting:

```java
	private void requestNewSession()
	{
		clientThread.invoke(() ->
		{
			finalizeCurrent();
			tracker.reset();
			pushUpdate();
		});
	}
```

In `shutDown()`, finalize the current session first:

```java
		finalizeCurrent();
		clientToolbar.removeNavigation(navButton);
		panel = null;
		navButton = null;
```

- [ ] **Step 5: Build (no client needed yet)**

Run: `gradlew.bat build`
Expected: BUILD SUCCESSFUL (all existing + new unit tests pass). Fix any compile errors (e.g. add missing `IntUnaryOperator`/`HashMap` imports to `SessionStore`).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/sessionscribe/SessionScribePlugin.java src/main/java/com/sessionscribe/SessionStore.java
git commit -m "Wire session history lifecycle into the plugin"
```

---

### Task 9: Panel — account + window selectors, render from selection

**Files:**
- Modify: `src/main/java/com/sessionscribe/SessionScribePanel.java`
- Modify: `src/main/java/com/sessionscribe/SessionScribePlugin.java`

- [ ] **Step 1: Add the dropdowns to the panel**

Add imports to the panel: `java.util.List`, `javax.swing.JComboBox`, `javax.swing.DefaultComboBoxModel`.

Add fields and a selection callback to the constructor signature:

```java
	private final JComboBox<String> accountSelector = new JComboBox<>();
	private final JComboBox<Window> windowSelector = new JComboBox<>(Window.values());
	private final java.util.function.BiConsumer<String, Window> onSelectionChanged;
```

Change the constructor to
`public SessionScribePanel(SessionScribeConfig config, Runnable onNewSession, Runnable onCopyImage, Runnable onSaveImage, java.util.function.BiConsumer<String, Window> onSelectionChanged)`,
store `onSelectionChanged`, and add the two selectors at the top of `content` (above the stats grid):

```java
		final JPanel selectors = new JPanel(new GridLayout(0, 1, 0, 4));
		accountSelector.addActionListener(e -> fireSelection());
		windowSelector.addActionListener(e -> fireSelection());
		selectors.add(accountSelector);
		selectors.add(windowSelector);
		content.add(selectors);
```

Add the helper + an account-list updater (called when rendering):

```java
	private void fireSelection()
	{
		final Object account = accountSelector.getSelectedItem();
		final Object window = windowSelector.getSelectedItem();
		if (window != null)
		{
			onSelectionChanged.accept(account == null ? null : account.toString(), (Window) window);
		}
	}

	/** Refresh the account dropdown contents without firing the listener. */
	public void setAccounts(List<String> accounts, String selected)
	{
		final DefaultComboBoxModel<String> model = new DefaultComboBoxModel<>();
		for (String account : accounts)
		{
			model.addElement(account);
		}
		accountSelector.setModel(model);
		if (selected != null)
		{
			accountSelector.setSelectedItem(selected);
		}
	}
```

Note: `render(Snapshot)` is unchanged — it still draws totals + skillRows + lootRows. For DAY/WEEK the plugin passes a `Snapshot` with an empty `lootRows` list.

- [ ] **Step 2: Update the plugin to build a Snapshot per selection**

In the plugin, change `buildSnapshot()` to build from a chosen `Aggregate` instead of the live tracker directly, and add selection handling. Replace `buildSnapshot()` and `pushUpdate()`:

```java
	private void pushUpdate()
	{
		if (panel == null)
		{
			return;
		}
		final String account = selectedAccount != null ? selectedAccount : currentAccount;
		final Window window = selectedWindow;
		final SessionRecord live = (account != null && account.equals(currentAccount)) ? snapshotRecord() : null;
		final Map<Integer, Integer> liveTally = live != null ? tracker.getLootTally() : null;
		final Aggregate aggregate = store.aggregate(account, window, System.currentTimeMillis(),
			live, liveTally, itemManager::getItemPrice);
		final boolean itemized = window == Window.CURRENT || window == Window.ALL_TIME;
		final SessionScribePanel.Snapshot snapshot = toSnapshot(aggregate, itemized);
		final List<String> accountList = new ArrayList<>(store.accounts());
		if (currentAccount != null && !accountList.contains(currentAccount))
		{
			accountList.add(currentAccount);
		}
		final String selected = account;
		SwingUtilities.invokeLater(() ->
		{
			panel.setAccounts(accountList, selected);
			panel.render(snapshot);
		});
	}

	private SessionScribePanel.Snapshot toSnapshot(Aggregate aggregate, boolean itemized)
	{
		final List<SessionScribePanel.SkillRow> skillRows = new ArrayList<>();
		for (Map.Entry<Skill, Integer> entry : aggregate.xpBySkill.entrySet())
		{
			if (entry.getValue() > 0)
			{
				skillRows.add(new SessionScribePanel.SkillRow(entry.getKey(), entry.getValue()));
			}
		}
		skillRows.sort((a, b) -> Integer.compare(b.gainedXp(), a.gainedXp()));

		final List<SessionScribePanel.LootRow> lootRows = new ArrayList<>();
		if (itemized && aggregate.lootTally != null)
		{
			for (Map.Entry<Integer, Integer> entry : aggregate.lootTally.entrySet())
			{
				final int itemId = entry.getKey();
				final int quantity = entry.getValue();
				final long value = (long) itemManager.getItemPrice(itemId) * quantity;
				final String name = itemManager.getItemComposition(itemId).getName();
				final AsyncBufferedImage image = itemManager.getImage(itemId, quantity, quantity > 1);
				lootRows.add(new SessionScribePanel.LootRow(name, quantity, value, image));
			}
			lootRows.sort((a, b) -> Long.compare(b.value(), a.value()));
		}
		final List<SessionScribePanel.LootRow> topLoot = lootRows.size() > MAX_LOOT_ROWS
			? new ArrayList<>(lootRows.subList(0, MAX_LOOT_ROWS))
			: lootRows;

		return new SessionScribePanel.Snapshot(aggregate.durationMs, aggregate.totalXp(),
			aggregate.lootValue, aggregate.kills, skillRows, topLoot);
	}

	void onSelectionChanged(String account, Window window)
	{
		selectedAccount = account;
		selectedWindow = window;
		clientThread.invoke(this::pushUpdate);
	}
```

`Aggregate.totalXp()` — add this helper to `Aggregate`:

```java
	long totalXp()
	{
		long total = 0;
		for (int gain : xpBySkill.values())
		{
			total += gain;
		}
		return total;
	}
```

Update the panel construction call in `startUp()`:

```java
		panel = new SessionScribePanel(config, this::requestNewSession, this::requestCopyImage,
			this::requestSaveImage, this::onSelectionChanged);
```

- [ ] **Step 3: Build**

Run: `gradlew.bat build`
Expected: BUILD SUCCESSFUL. Fix imports (`java.util.function.BiConsumer`) as needed.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/sessionscribe/SessionScribePanel.java src/main/java/com/sessionscribe/SessionScribePlugin.java src/main/java/com/sessionscribe/Aggregate.java
git commit -m "Add account/window selectors and render selected aggregate"
```

---

### Task 10: Report card reflects the selected account + window

**Files:**
- Modify: `src/main/java/com/sessionscribe/SessionReportCard.java`
- Modify: `src/main/java/com/sessionscribe/SessionScribePlugin.java`
- Modify: `src/test/java/com/sessionscribe/SessionReportCardTest.java`

- [ ] **Step 1: Add a subtitle line to the report card**

Change `render` to accept a subtitle (account + window label):

```java
	static BufferedImage render(Snapshot snapshot, boolean rounded, String subtitle)
```

After drawing the date line, draw the subtitle in `MUTED` before the divider:

```java
		y = left(g, subtitle, PAD, y, DATE_FONT, MUTED, SMALL);
```

Add `SMALL` to the height calc once more (one extra line):

```java
		final int height = PAD + TITLE_H + SMALL /*date*/ + SMALL /*subtitle*/ + SMALL /*duration*/
			+ GAP + 3 * LINE + GAP + HEADER_H + skillRows * SMALL + GAP + HEADER_H + dropRows * SMALL + PAD;
```

- [ ] **Step 2: Update the export calls in the plugin**

Both `requestCopyImage` and `requestSaveImage` should render the **currently selected** aggregate, not just the live one. Replace their snapshot construction with:

```java
			final String account = selectedAccount != null ? selectedAccount : currentAccount;
			final Window window = selectedWindow;
			final SessionRecord live = (account != null && account.equals(currentAccount)) ? snapshotRecord() : null;
			final Map<Integer, Integer> liveTally = live != null ? tracker.getLootTally() : null;
			final Aggregate aggregate = store.aggregate(account, window, System.currentTimeMillis(),
				live, liveTally, itemManager::getItemPrice);
			final SessionScribePanel.Snapshot snapshot = toSnapshot(aggregate,
				window == Window.CURRENT || window == Window.ALL_TIME);
			final String subtitle = (account == null ? "Unknown" : account) + " — " + window.label();
			final BufferedImage image = SessionReportCard.render(snapshot, config.roundValues(), subtitle);
```

- [ ] **Step 3: Update the report-card test**

```java
		final BufferedImage image = SessionReportCard.render(snapshot, false, "Zezima — All time");
```

- [ ] **Step 4: Build + test**

Run: `gradlew.bat build`
Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/sessionscribe/SessionReportCard.java src/main/java/com/sessionscribe/SessionScribePlugin.java src/test/java/com/sessionscribe/SessionReportCardTest.java
git commit -m "Report card reflects selected account and window"
```

---

### Task 11: Manual verification in the client

**Files:** none (manual).

- [ ] **Step 1: Launch the client**

Run: `gradlew.bat run`
Expected: log line `Plugin SessionScribePlugin is now running`, no `sessionscribe` exceptions.

- [ ] **Step 2: Verify the live path**

Log in; gain XP, a kill, and loot. With window = **Current**, totals update each tick and Top loot shows items.

- [ ] **Step 3: Verify archive + windows**

Click **New Session** (archives the session). Switch window to **All time** and **This week** — they should show the archived totals; **All time** shows a Top-loot list, **This week** shows totals only. Switch the **account** dropdown if you have more than one.

- [ ] **Step 4: Verify relog continuation**

Note the current-session totals, log out and back into the same account within 5 minutes — the Current totals should **continue** (not reset). Wait past the gap (or change the config to 0) and relog — it should start fresh and the prior session should appear under This week / All time.

- [ ] **Step 5: Verify persistence**

Confirm `…/.runelite/session-scribe/history.json` exists and contains your RSN. Close and relaunch the client; All time / This week should still show prior totals.

- [ ] **Step 6: Verify export**

With window = All time, click **Save image**; confirm a PNG with the "RSN — All time" subtitle in `…/.runelite/session-scribe/`.

- [ ] **Step 7: Commit (if any fixes were needed)**

```bash
git add -A
git commit -m "Fixes from manual session-history verification"
```

---

### Task 12: Docs + forbidden-API re-audit

**Files:**
- Modify: `README.md`

- [ ] **Step 1: Update the README**

Add a "Session history & windows" section documenting: the account + window selector (Current / 24h / week / all-time); that history is stored locally in `…/.runelite/session-scribe/history.json`, keyed by RSN, **never uploaded**; the **Clear history** control; the relog-continuation gap config; and the limitations (24h/week are session-granular and show no itemized loot; first XP per skill after a reset uncounted; kills need a drop). Update the config table to drop "Auto-reset on logout" and add "Relog continuation (min)".

- [ ] **Step 2: Re-run the forbidden-API audit**

Run (PowerShell): use the Grep tool / ripgrep for `Runtime|ProcessBuilder|reflect|okhttp|HttpUrl|URL\(|Socket|getDeclared` over `src`.
Expected: **No matches** (Gson and `java.nio.file.Files` are not on the list and are local-only I/O).

- [ ] **Step 3: Confirm I/O surface**

Confirm the only writes are `history.json` (+ `.tmp`/`.corrupt`) and the export PNG, all under `…/.runelite/session-scribe/`. No network.

- [ ] **Step 4: Full clean build**

Run: `gradlew.bat clean build`
Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 5: Commit**

```bash
git add README.md
git commit -m "Document session history and re-audit forbidden APIs"
```

---

## Self-review (performed while writing this plan)

**Spec coverage:**
- Session boundary / relog continuation → Tasks 5 (`withinGap`) + 8 (lifecycle). ✓
- Per-account RSN history, local-only → Tasks 2, 3, 5, 8; README in 12. ✓
- All-time aggregate + capped recent sessions → Task 3. ✓
- Windows (Current/24h/week/all-time) folding current → Tasks 3–4. ✓
- Top loot for Current + All time only → Task 9 (`itemized` flag). ✓
- Remove auto-reset config, add relog gap → Task 7. ✓
- 90-day retention; all-time forever → Tasks 3–4. ✓
- Off-thread persistence (executor), Gson, versioned file, corrupt-file handling → Tasks 5, 8. ✓
- UI selectors; export reflects selection → Tasks 9–10. ✓
- Crash recovery via pending slot → Tasks 8 (`savePending`/`finalizeLeftoverPending`). ✓
- Account identity = RSN resolved when non-null → Task 8 (`handleLogin`). ✓

**Placeholder scan:** No TBD/TODO; every code step shows complete code. Manual-only steps (Task 11) are explicitly manual.

**Type consistency:** `finalizeSession(account, SessionRecord, Map<Integer,Integer>, IntUnaryOperator)`, `aggregate(account, Window, long now, SessionRecord, Map<Integer,Integer>, IntUnaryOperator)`, `withinGap(long,long,long)`, `Aggregate.totalXp()`, and panel `Snapshot`/`SkillRow`/`LootRow` (reused from v1) are consistent across Tasks 3–10. `finalizeSessionInternal` is introduced in Task 8 as the shared roll-up (Task 3's `finalizeSession` body moves into it).
```
