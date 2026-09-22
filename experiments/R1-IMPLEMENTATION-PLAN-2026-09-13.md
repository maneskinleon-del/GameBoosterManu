# R1 Implementation Plan — Boost lifecycle authority

**Date:** 2026-09-13 · **Base:** main @ `f5ba334` · **Type:** plan only — NO code changes yet
**Inputs:** `OVERLAY-DISAPPEAR-FORENSIC-2026-09-13.md`, `INVENTORY-REORG-2026-09-13.md`, static audit of HEAD + device evidence.
**Scope rule:** R1 kills the two demonstrated races with minimal, proportionate complexity. R2–R5 stay out.

---

## 1. Current state — who does what (verified at HEAD `f5ba334`)

### 1.1 Decision map

| # | Question | Current answer (evidence) |
|--|--|--|
| 1 | Who decides game **entered**? | **Two independent sources:** (a) `UnifiedAccessibilityService.handleWindowStateChanged()` → `repository.onForegroundAppChanged(pkg)` → `isGamePackage → sessionManager.setForegroundApp(pkg)`; (b) `GameDetector.pollForegroundApp()` (UsageStats polling 3 s fg / 30 s bg) → same funnel. |
| 2 | Who decides game **exited**? | **The same two sources, with different rigor:** a11y fires `onForegroundAppLost()` on **any non-game `WINDOW_STATE_CHANGED` not in its 8-entry ignore list**; polling fires `onGameExited` on any non-game detection (its ignore list is longer, 15+). The a11y path is the one that caused the false exit. |
| 3 | Who changes `isBoostActive`? | `_isBoostActive` is a `MutableStateFlow` **only writable inside `GameSessionManager`**: line 178 (`toggleBoost`), line 191 (baseline-capture failure rollback), line 606 (`triggerExitWithHysteresis`). |
| 4 | Who calls `toggleBoost`? | (a) UI: `MainActivity:402` → `viewModel.toggleBoost()` → `repository.toggleBoost()`; (b) auto-entry: `GameSessionManager` ×3 (lines 476, 484, 500 — remembered-manual path, mapper/external path, tactile-FF path); (c) thermal: `GameBoostRepository:234` (`thermalController.onCriticalHeat`). |
| 5 | Who calls restore? | **4 orchestration sites:** (a) `triggerExitWithHysteresis` (GSM:585-600: `boostSession.restoreVerified()` + `networkOptimizer.restore()` + `systemTweaks.restore()` + `toggleMobilador()` + `touchOptimizer.restore()`); (b) `GSM.restoreSettings()` (toggleBoost OFF path — same sequence, different order); (c) `GameBoostService.restoreSavedSettings()` (DPI/pointer only); (d) boot recovery in `GameBoostRepository.init` (via `boostSessionManager.recoverIfNeeded()`). |
| 6 | Who calls `markActive`? | **One site:** `GSM.toggleBoost()` line ~199: `scope.launch { delay(8000); boostSession.markActive() }` — **fire-and-forget, Job never retained, never cancelled.** |
| 7 | Who owns the overlay? | **4 writers of `show()/hide()`:** service observer (`GameBoostService:270-272`, show/hide by `isBoostActive`), `GameBoostService.handleStart:137` (show), `MainActivity.onResume:159` (re-show), `MainActivity:412` (hide on manual OFF), `Repository:205` (re-show on game detect). Plus internal: `btnCollapse → hide()`, `toggleVisibility()`. |
| 8 | `GameSessionManager` responsibilities | FSM (READY/GAME_ACTIVE) + foreground app state + profile selection (manual/remembered/auto/mapper) + `toggleBoost` lifecycle + privileged-commands funnel + Mobilador + restore orchestration + hysteresis + logging. 949 LOC, 5+ responsibilities. |
| 9 | `GameBoostService` responsibilities | FGS shell (notification, watchdog schedule) + `handleStart` (re-applies profile, restores DPI/pointer — business logic) + **3 collectors that drive business state** (boost→show/hide, profile→update/apply, game→show). |
| 10 | `GameDetector` / `UnifiedAccessibilityService` | Detector: polling, own ignore list (15+), fallback shell readback; **lifecycle-bound to ProcessLifecycleOwner** (poll slows to 30 s in background). A11y: instant entry events + own ignore list (8) + ADS-button feature + own `lastPackage` dedup. **Both feed the same funnel with different filters.** |

### 1.2 Current transition paths

```
ENTRY:  a11y WINDOW_STATE_CHANGED(game) ──┐
        GameDetector poll(game) ──────────┴─> setForegroundApp → profile → toggleBoost(true)
                                                → beginApply → APPLYING → (8 s) → markActive → ACTIVE

EXIT:   a11y WINDOW_STATE_CHANGED(non-game) ─┐
        GameDetector poll(non-game) ─────────┴─> onForegroundAppLost → hysteresis 5 s
                                                → triggerExitWithHysteresis → restoreVerified
                                                → isBoostActive=false → observer → hide()

ZOMBIE: toggleBoost(true) ──> delay(8000){ markActive() }  [orphan Job]
        exit at t<8 s ────────────> RESTORED
        markActive at t=8 s ──────> ACTIVE   ← no guard, unconditional save
```

### 1.3 Problems found (each with evidence)

| # | Problem | Evidence |
|--|--|--|
| P1 | a11y treats any non-game window state as "foreground app changed" → `onForegroundAppLost()`; OEM transients (`com.zjx.ztezscreenshot`, `com.android.vending`, `cn.nubia.gameassist`) pass the 8-entry filter | Forensic log 13:58:03.704–04.562 → false exit 13:58:08.719 → hide 13:58:12.486 |
| P2 | Exit authority is event-driven from a11y with **no confirmation**; polling is not consulted before exiting | Same forensic trace; polling never saw the exit it "confirmed" |
| P3 | `markActive()` has **no state guard**: `store.load() ?: return; store.save(copy(ACTIVE))` — applies even from RESTORED | `BoostSessionManager.kt:257-261` read this session |
| P4 | The 8 s `markActive` Job is anonymous in `GSM.scope`; nothing retains/cancels it | `toggleBoost()` body; `RESTORED→ACTIVE` flip at 13:58:14 with no toggleBoost in log |
| P5 | Exit-vs-apply race: restore path does not cancel the apply path's deferred work (and vice versa: `beginApply` creates a **new sessionId** while restore is in flight — the sessionId guards inside `restoreVerified` catch part of this, but the store-level flip still happens) | Forensic §8b; two sessionIds 2 s apart |
| P6 | Overlay has 4 writers; its visibility is a projection of state but can be manipulated independently | §1.1 #7 |

---

## 2. R1 architecture proposal (minimal)

**Core idea: the exit decision moves from "any a11y event" to "a11y proposes, polling disposes." Nothing new is invented — `GameDetector` already polls and already has the longer ignore list; R1 only reroutes authority.**

```
a11y WINDOW_STATE_CHANGED ──► onForegroundAppChanged(pkg)
        │                        ├─ isGamePackage(pkg) → setForegroundApp(pkg)   [entry: fast path, unchanged]
        │                        └─ !isGamePackage    → PROPOSE exit (not execute) [R1 change]
        │
GameDetector poll ──────────────► CONFIRMED exit only when poll re-reads non-game
        │                         (N=1 consecutive poll outside game, ~3 s worst case)
        ▼
GameSessionManager (sole owner of isBoostActive + hysteresis + restore ordering)
        ▼
BoostSession (SSOT): markActive gains state guard; apply session keeps a cancelable Job
        ▼
GameBoostService observer → overlay (already a projection; becomes THE only writer)
```

### R1 changes (6 concrete edits)

**C1 — `UnifiedAccessibilityService` (a11y proposes, never executes exit):**
in `handleWindowStateChanged`, the `!isGamePackage` branch calls a new
`repository.onForegroundGameExitHint()` instead of `onForegroundAppLost()`.
The a11y **keeps** full authority for **entry** (fast path untouched).
Transient-window protection is then **architectural** (poll must confirm) —
the 3 known OEM packages are additionally added to the a11y ignore list as
defense-in-depth (cheap, and they can never be a real "user went somewhere").

**C2 — `GameBoostRepository`:** rename semantics of the funnel:
`onForegroundAppChanged(pkg)` keeps entry; new `onForegroundGameExitHint()`
is a **no-op for lifecycle** — it only logs (FSM_DIAG) and pokes the detector
to poll immediately (so confirmation arrives fast instead of waiting up to
3 s). Exit execution stays exclusively behind the detector callback
`onGameExited → onForegroundAppLost`.

**C3 — `GameSessionManager`: retained, cancelable apply-armed Job.**
`toggleBoost(true)` stores its delayed-`markActive` job in a field
(`applySettleJob: Job?`). Cancellation points (all inside GSM, no new classes):
- `triggerExitWithHysteresis()` (first line, before restore)
- `toggleBoost(false)` (before `restoreSettings()`)
- `simulateGameLaunchInternal` baseline-failure rollback (line 191 site)
The Job body also re-checks before saving: `boostSession.markActiveIfApplying()`
(see C4) so even an uncanceled survivor is inert.

**C4 — `BoostSessionManager.markActive()` gains a state guard:**
`if (cur.state != APPLYING) return` (log if it happens). New wrapper
`markActiveIfApplying()` used by the deferred Job; direct `markActive()`
keeps existing semantics for callers that *just* began applying
(none other exist today — single call site). This kills the zombie
**at the SSOT layer**, independent of any Job bookkeeping.

**C5 — Overlay single-writer (tiny, no new layer):** `MainActivity:412` and
`MainActivity.onResume` stops calling `hide()/show()` directly; they call
`repository.setOverlayRequested(Boolean)` which the service observer
already effectively implements (its collector re-emits on any state change).
Implementation: repository holds `overlayRequest: MutableStateFlow<Boolean?>`
(null = follow isBoostActive; false = user hid it via ✕) — service collector
combines both flows and is the only caller of `FPM.show()/hide()`.
~25 LOC total. (This is the *minimum* to satisfy acceptance #7 without R3.)

**C6 — Restore ordering convergence (cheap version):** `triggerExitWithHysteresis`
and `restoreSettings()` converge into one private `performRestore(reason)` in GSM
with a single ordered sequence (restoreVerified → optimizers → mobilador).
The two call sites keep their names; `handleStart`'s DPI/pointer restore and
boot recovery stay untouched (R2 scope).

**Explicitly NOT in C1–C6:** no new `ForegroundMonitor` class, no interface
abstractions, no move of business logic out of the service beyond the observer
becoming sole overlay writer, no Watchdog changes, no split of GSM files.

### Q-decisions honored

- **Q1:** Mobilador/ADS untouched (a11y keeps ADS feature; no new responsibilities).
- **Q2:** Service moves *toward* notification/lifecycle shell: it loses overlay-logic co-owners and keeps its observer as executor — business decisions (entry/exit/restore) already live in GSM/repo, and R1 doesn't move more.
- **Q3:** Polling becomes the **arbiter and fallback**: with a11y disabled, `GameDetector` alone drives entry+exit exactly as today (unchanged code path); with a11y enabled, a11y only accelerates entry.

---

## 3. Files to modify (7) / NOT modify

| Modify | Change | Size |
|--|--|--|
| `service/UnifiedAccessibilityService.kt` | C1: non-game branch → exit hint + 3 pkgs in ignore list | ~10 LOC |
| `data/repository/GameBoostRepository.kt` | C2: `onForegroundGameExitHint()` (log + poke poll); wire detector as sole exit executor (already is via `onGameExited`) | ~15 LOC |
| `manager/GameSessionManager.kt` | C3: `applySettleJob` field + 3 cancellation points; C6: `performRestore(reason)` | ~30 LOC |
| `manager/boostsession/BoostSessionManager.kt` | C4: `markActive` guard + `markActiveIfApplying()` | ~8 LOC |
| `MainActivity.kt` | C5: 2 call sites → `repository.setOverlayRequested()` | ~6 LOC |
| `service/GameBoostService.kt` | C5: collector combines `isBoostActive` + `overlayRequest` | ~15 LOC |
| `manager/GameDetector.kt` | C2 support: `pokePoll()` public (immediate single poll) | ~5 LOC |

**Do NOT touch:** `ShizukuExecutor`/`RishExecutor`, `BoostSessionStore` (atomic write path proven on device), `SystemTweaks`/`NetworkOptimizer`/`TouchOptimizer`/`PowerOptimizer`/`RamManager`/`ProfileManager`, `SystemMonitor`, `DependencyState`, `FloatingPanelManager` (its API is already correct), `PreferenceManager`, `WatchdogManager`/`ServiceWatchdogReceiver`, `BootReceiver`, theme, manifest.

**Total: ~90 LOC across 7 files, 0 new files/classes.**

---

## 4. Tests (new, in existing JVM harness `BoostSessionManagerTest` style)

New file `GameLifecycleR1Test.kt` (JVM, fake store + fake detector):
1. **T1 entry works:** a11y game event → `setForegroundApp` → profile+toggle path runs (FakeDevice) → store `APPLYING`.
2. **T2 transient does not exit:** entry → a11y non-game event (`com.zjx.ztezscreenshot`) → assert: no `onForegroundAppLost`, no restore, `isBoostActive` still true, then poll(game) → still ACTIVE.
3. **T3 polling-only lifecycle:** disable a11y (don't fire its events) → poll(game) → boost ON; poll(non-game) → hysteresis → restore → OFF. (Q3 acceptance)
4. **T4 real exit restores:** poll(non-game) after entry → `RESTORED`, settings read back from baseline.
5. **T5 no orphan markActive:** begin apply → exit at t<8 s (simulated by running deferred job *after* restore) → store stays `RESTORED` (guard C4). Also: job-cancellation path → same result.
6. **T6 single authority:** two contradicting sources in same instant (a11y non-game + poll game) → lifecycle follows poll; no restore issued.
7. **T7 overlay projection:** with service collector logic extracted/parametrized — `isBoostActive=false` → hide; user ✕ request → hide even while active; re-entry → show. (Overlay state asserted from combined-flow function, no Robolectric needed.)

Device verification after merge (manual, mirrors forensic repro): FF entry with OEM transients → overlay stays ≥10 s; boost OFF from switch → clean restore.

---

## 5. Acceptance criteria (mapped)

| # | Criterion | Satisfied by |
|--|--|--|
| 1 | FF can enter correctly | C1 keeps a11y entry fast path; T1 |
| 2 | Transient OEM window ≠ game exit | C1+C2 (poll confirms); T2 |
| 3 | Polling maintains lifecycle without a11y | unchanged detector path; T3 |
| 4 | Real exit → restore | detector remains exit executor; T4 |
| 5 | `restore()` cannot be followed by orphan `markActive` | C3+C4 double protection; T5 |
| 6 | No two independent lifecycle authorities | exit authority = poll only; T6 |
| 7 | Overlay reflects state, never decides it | C5 single writer; T7 |

---

## 6. Risks

| Risk | Mitigation |
|--|--|
| Exit latency grows (a11y hint + poll ~3 s worst case vs instant) | `pokePoll()` makes confirmation immediate in practice; hysteresis 5 s already dominated latency |
| a11y exit-hint path loses ADS-context reset timing | ADS monitor reset stays in a11y (unrelated to lifecycle) |
| `markActiveIfApplying` mask a *legitimate* late-ACTIVE? | No: only APPLYING may become ACTIVE by design; late save from RESTORED was exactly the bug |
| Overlay request flow reintroduces two-writers if MainActivity forgets | C5 removes both direct call sites; compiler-level: FPM calls only in service file (grep-checkable) |
| Regression in false-exit *entry* semantics (mapper hot-plug) | `onExternalDeviceDetectedWhileGaming` untouched; T2/T6 cover interleaving |

---

## 7. Explicitly OUT of R1 (deferred)

- R2: boot-recovery/handleStart restore unification (full `restoreAll(reason)` API)
- R3: full overlay request-lifecycle polish (expanded states)
- R4: split of `GameSessionManager`/`MainActivity` files
- R5: ThermalController/ResourceGovernor device-flags
- Mobilador/ADS feature changes (Q1), FGS full demotion (Q2 beyond C5)
- Any new ignore-list entries beyond the 3 evidenced OEM packages

## 8. Size estimate

**~90 LOC production + ~200 LOC tests, 7 files touched, 1 new test file, 0 new classes.** One PR, one review pass, device-verifiable with the existing forensic repro.
