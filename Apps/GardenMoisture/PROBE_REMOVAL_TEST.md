# Probe Removal Signature Test — Procedure

Measure what a probe removal actually *looks like* in the data, so the out-of-ground trap
can be built on measured numbers instead of the guessed 6% threshold.

Three unknowns:

1. The decay curve from wet-pull to air — shape and time constant.
2. Whether a **muddy** probe ever gets low enough for a level-based rule to see it at all.
3. Reinsertion settling time — how long before a returned probe reads true.

---

## Already measured — do not re-measure

| Fact | Value | Source |
|---|---|---|
| Dry probe, open air, at pairing | **soilAD 47**, humidity clamps to 0 | 2026-09-01 ~12:19 first report |
| In-soil steady (current) | AD 272–275, 57–58% | 2026-09-04 |
| AD → % map | `pct ≈ (AD − 67) × 0.279` | verified at AD 228/272/339/377 |
| 6% threshold in AD terms | ≈ **AD 88** — only ~41 counts above the air floor | derived |
| Higher-resolution channel | `soilAD` changes more often than `humidity` | per-attribute hub timestamps |

The percent axis is clamped flat below AD 67, which is exactly where probe-out lives.
**The trap should test `soilAD`, not `moisturePct`.**

---

## Preconditions

- No rain for **at least 2 h**, and the canopy not still dripping.
- Daylight — she needs to see the hole to put it back in the same place.
- Does not span midnight (`dayRollover` runs at 00:05).
- Budget **~2 h 20 min**, nearly all of it unattended.

---

## Setup — before touching the probe

1. **Disable app 5259 "Ky's Garden"** on `<hub-a>` (Apps → Ky's Garden → disable).

   This is the whole protection. `trackLowestSurvived()` is a one-way ratchet
   (`if (cur == null || pct < cur)`) with no probe-out guard, unlike its two siblings.
   One pull drops `lowestSurvived` from 45 to near zero permanently.

   - **Do NOT** toggle the season switch instead. The season-on handler wipes `fcObs`
     and `fcDaily`, because the 30-day keep-anchors guard tests `started != null` and
     `seasonStartedMs` is currently `null`.
   - **Do NOT** disable the device or the Hub Mesh app. The logger needs the device live.

2. **Leave every other app alone.** Already checked — nothing else will misbehave:

   | App | Hub | Effect during test |
   |---|---|---|
   | Ky Dashboard (5260) | .40 | Display only. Will show odd numbers. Harmless. |
   | Ecowitt Watchdog (2608) | .38 | Fires on the `orphaned` attribute with a 240-min confirm. Will not fire. |
   | Hub mesh (2505) | .38 | Must stay enabled. |

3. **Start the logger** (I run this). Polls
   `http://<hub-b>/device/fullJson/3243` every 10 s and records `soilAD`,
   `humidity`, `battery` with **the hub's own change timestamps**, appending to
   `data\probe_test_YYYY-MM-DD.csv`. Confirm it is writing rows before pulling anything.

   Note: device 3243 on **.38** is the real sensor — a component child of gateway 3117.
   Device 4940 on .40 is only the Hub Mesh mirror. Poll the source; it drops the mesh hop
   as a variable.

---

## Phases

| # | Phase | Duration | Action |
|---|---|---|---|
| 0 | Baseline, in ground | 15 min | Do nothing. Also measures the WH51's real report interval. |
| 1 | Pulled, **muddy**, outdoors | 30 min | Pull straight up. **Do not wipe.** Set it in shade near the bed. |
| 2 | **Wiped**, outdoors | 20 min | Wipe the probe clean and dry. Same spot. |
| 3 | **Indoors** | 30 min | Set on a counter, away from heat and AC vents. |
| 4 | Reinserted | 45 min | Back outside, wait 5 min for temperature, then into the **same hole at the same depth**. Firm the soil around it. |

**Phase 3 watch item:** the WH51 talks to the gateway over 915 MHz. If the log stops
updating after it comes inside, it has lost RF contact — move it toward the gateway.
That phase is the most interesting one; losing it would be a shame.

**Short version** if time is tight: phases 0, 1, 4 only (~1 h 30). Phases 2 and 3 are the
ones that answer "does a clean, dry probe return to the pairing value of AD 47" — valuable,
but not load-bearing for the detector.

---

## What she records

Four wall-clock times, to the minute: **pull, wipe, indoors, reinsert.**
Phone notes is fine. The logger stamps everything else.

---

## Teardown

1. Confirm the reading is back near **57–58% / AD ~272** before re-enabling.
2. Re-enable app 5259.
3. Expect a brief **"SENSOR LOOKS STALE"** banner — `lastEventMs` will be hours old.
   It self-clears on the next event.
4. Today's garden CSV will have a hole. The daily archive task flags gaps > 45 min,
   so **expect one false watchdog hit tomorrow** and ignore it.

---

## Why no code on the hub

Offered, and not needed. `/device/fullJson/3243` already returns each attribute's
last-change timestamp, stamped hub-side — so the polling rate does not limit timestamp
precision. On-hub code would mean deploying, debugging, and the app-naming trap, and
Hubitat's scheduler floors at 1 minute anyway, which is *worse* than 10 s polling.

Revisit only if Phase 0 shows the sensor reporting faster than ~20 s.

---

## What the data buys

- **Decay shape** → whether a rate-of-change rule can fire before a level rule does.
- **Muddy plateau** → whether a removed probe ever crosses AD 88 at all. If it doesn't,
  a level-only trap is dead and the detector has to be rate-based.
- **Reinsertion settling time** → how long to suppress learning after a return.
- All three feed a `soilAD`-based trap that tags the rows in the CSV `note` column,
  which is currently empty on every row ever written.

---

---

# RESULTS — run 2026-09-04

Raw data: `data\probe_test_2026-09-04.csv` (10 s polls, ~75 KB, zero errors).
Ran in light rain, so the outdoor drying phases were dropped; Ky flung the mud off,
brought it in and rinsed it, so this is **clean-wet probe in air**, not muddy.

## Removal is a cliff, not a curve

```
11:39:48   AD 277   59%    in ground, steady
11:45:00   ——— pulled ———
11:46:45   AD  59    0%    first post-pull report
11:49:45   AD  58    0%
```

**AD 277 → 59 in a single report cycle.** There is no decay to trace. The probe reads the
bulk dielectric around its body, so a water film is negligible next to being buried.

| Finding | Value |
|---|---|
| Detection latency, pull → first report | **~105 s** — set by the sensor, not by the code |
| Out-of-ground band (measured) | **AD 47–59** (47 dry at pairing, 58–59 rinsed/damp) |
| Chosen threshold | **AD 90** (≈6.4%, materially the same as the existing 6% setting) |
| Margin below threshold | 31 counts | 
| Driest soil all season | 45% = **AD 228** — 138 counts clear |

A single reading is enough. No rate rule, no multi-sample confirmation needed.

**Correction to an earlier claim:** for a *binary* threshold, soilAD and percent are
equivalent — the clamp happens below the trip line anyway. soilAD only earns its keep for
telling "out of ground" apart from "very dry soil," since everything below AD 67 reads 0%.

## Reinsertion — confounded, no clean number

```
11:55:45   AD 240   48%    reinserted
12:28:46   AD 243   49%    peak of the settle
13:46:45   AD 230   46%
13:52:45   AD 377   87%    J.R. watered the area
```

Came back **37 counts below** the pre-pull 277 across a 24-minute blind window. Two
mechanisms, not separable:

- Rain rate hit zero at **11:49:44**, while the probe was out — so real drainage occurred.
- But 37 counts in 24 min is 1.5 counts/min. Measured drainage was 0.17 counts/min
  post-reinsertion, and 0.4 counts/min on 09-02 right after a 1.3 in/hr downpour from
  saturated soil. At the fastest observed rate, 24 min buys ~10 counts.

So drainage plausibly explains a quarter to a third; the rest looks like insertion deficit.
**Held loosely** — there is no soil measurement at all for the 11 minutes it was out, which
is the structural hole in this test. To get a clean number, repeat in stable dry weather.

## Re-enable verification (14:04:08)

Predicted from the code and confirmed live: `sampleTick` calls `pushRecent` before
`checkRise` (lines 835-836), and `pushRecent` prunes older than 55 min.

| | before | after first sample |
|---|---|---|
| `recent` | 14 stale entries | **1** |
| `openEvent` | no | **no** |
| `events` | 6 | **6** |
| `lowestSurvived` | 45 | **45** |

No spurious wetting event off the stale 59% baseline. No stale-sensor warning.
`fcObs` and `fcDaily` intact at 1 each.

## Still unverified

- Whether a **muddy, untouched** probe in air stays below AD 90. This run was rinsed.
  It is the only case that could defeat a level-only rule.
- Whether the Ecowitt gateway coalesces sensor reports.

---

# DEPLOYMENT — v0.3.2, 2026-09-04

Pushed to production on `.40`, code id **2143**, version **16 -> 17**, 106,277 chars.
Backup of the prior source: `C:\CLAUDE\Hubitat\backup_before_gardenmoisture_032\`.

Verified on Ky's Garden (5259) after the swap: sampling normally, `lastPct`/`lastAD` tracking
live, `lowestSurvived` 45 intact, `suspectOutOfGround` false, `sensorStale` false, events still 6,
`openEvent` no, `fcObs` 1. No spurious event, nothing lost.

## VALIDATED IN PRODUCTION — 2026-09-04 14:50-14:55

The sim was never needed. Instead of wiring a fake sensor, the threshold was temporarily raised
**above** the live reading, so the real app on the real probe believed it had been pulled. Settings
`outOfGroundAD 90 -> 300`, `outGraceMin 10 -> 1`, `returnHoldMin 30 -> 1`, then all three restored.
No device rewiring, no test child, no sim.

Complete state machine from `state.rows`, one clean pass:

| Sample | AD | note | suspect | lowReadingSince | learnHoldUntil |
|---|---|---|---|---|---|
| 14:48 | 283 | `None` | False | None | None |
| 14:50:26 | 282 | `probe-out-unconfirmed` | False | 14:50:26 | None |
| 14:52:40 | 282 | `probe-out` | **True** | 14:50:26 | None |
| 14:53:27 | 282 | `probe-returned-settling` | False | None | **14:54:27** |
| 14:54:50 | 282 | `None` | False | None | expired |

All five v0.3.2 changes confirmed working:

1. **AD threshold as a setting** — tripped at 300, cleared at 90, on the AD axis.
2. **Configurable grace** — latched on the first sample after the 1 min window, not before.
3. **`trackLowestSurvived` guard** — `lowestSurvived` held at 45 through the entire cycle.
4. **Note tagging** — all three states observed and correctly distinguished.
5. **Return hold** — `learnHoldUntilMs` set to exactly return + `returnHoldMin`, gated `canLearn()`,
   then expired.

Throughout: `events` stayed at 6, `openEvent` stayed `no`. No spurious wetting event on either the
departure or the return, and no anchor was banked from a false reading.

Minor untidiness found, not a bug: `learnHoldUntilMs` is never cleared once it expires. It lingers
in state as a past timestamp. Harmless, since the check is `now() < hold`.

## Sim validation — ABANDONED (superseded by the above)

Attempted and abandoned. Recorded so the next attempt does not repeat the dead ends.

**What worked.** Garden Sim Sensor **4936** now has `adDry 30 -> 67`, `adWet 500 -> 425`, matching
the measured real mapping. This was a genuine fidelity bug: at the old settings a legitimate soil
reading of 12% computed to AD 86, which would falsely trip the new AD-90 threshold. Left changed,
because the new values are correct. The device preference fields are PrimeVue click-to-edit under a
separate **Preferences** tab; they need triple_click + type + **Tab to blur**, or the value renders
but never reaches Vue's model and the save silently keeps the old number. That cost one round trip.

**What blocked it.** A test child **`_SimTest` (5272)** was created off parent 5254, and its config
page consistently **freezes the renderer on Done** — three attempts, CDP `Runtime.evaluate` and
`Input.dispatchMouseEvent` both timing out at 30-45 s. Only `thisName` ever persisted; the device
pickers and `simSpeedup` did not, and `initialize()` never ran, so it has no scheduled jobs. It is
inert: no device bound, no subscriptions, no timers. Untested guess at the cause: `installed()`
calls `restoreAnchors()`, which reads a hub file that does not exist for a new instance.

**Left on the hub:** `_SimTest` (5272), inert, underscore-prefixed. Delete it or finish wiring it.

**Predictions computed but not run,** with the corrected mapping `ad = 67 + pct x 3.58`:

| Scenario | Min soil | AD | Expected |
|---|---|---|---|
| `probePulled` | 2% | 74 | MUST trip the guard |
| `freeze` | 9% | 99 | must NOT trip (only 9 counts of margin) |
| `toThreshold` | 21% | 142 | must NOT trip |
| `dryDown` | 32% | 181 | must NOT trip |

Note `simSpeedup` **must** be set before running: `outGraceMs()` only floors to 30 s when
`simActive()`, otherwise it is a real 10 minutes while `probePulled` runs about 51 seconds — the
guard would never latch and the run would read as a false failure.

Also: the marker switches on `.40` (4937/4938/4939) are **Ky's production switches**. Do not wire
the sim to them. Either build underscore-prefixed virtual switches or restrict the run to the
marker-free scenarios, which still cover everything v0.3.2 changed.

## Diagnostic gotcha found

A **disabled** app's `scheduledJobs.prevRunTime` keeps advancing even though the handler
does not execute (`lastSampleMs` and `flushCount` stayed frozen for 2.5 h). So an advancing
`prevRunTime` is *not* evidence an app is running — test app liveness against its own state
timestamps instead.
