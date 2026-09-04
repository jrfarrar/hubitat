# Season boundary: wipe or keep the field-capacity history?

**Question (J.R., 2026-09-04):** why does the app throw away what it learned about field capacity
every spring? Why not keep a rolling window of the last X soaking cycles, make the season switch a
pure pause, and let new observations displace old ones as they arrive?

**Answer: keep them.** Supported at every re-seat magnitude tested. Details and caveats below.

---

## The harness could not answer this until it was fixed

`season_restart()` had two modes, `clear` and `demote`, and **both wiped `self.daily`**. Under
`fc_mode='percentile'` — the method actually shipped — `_fc_pool()` reads *only* `self.daily`. So the
two modes were the same estimator and the existing comparison was vacuous for the shipped config.

Added a third mode, `keep`, which preserves everything at the boundary. Also added a **cold-start
measurement**: how many days into a season before any threshold is available. Season-end accuracy
cannot see the thing this question is about.

The shipped app has the same contradiction: `anchorWindowDays` defaults to **730 days**, explicitly
so observations survive across seasons — and then the season handler wipes them anyway.

---

## Result 1 — the cold start disappears

Seasons 2-4, 12 seeds, percentile FC:

| Config | blind days | converged | MAE | bias |
|---|---|---|---|---|
| WIPE / diligent | **19.0** | 48/48 | 1.4 | −0.8 |
| KEEP / diligent | **0.0** | 48/48 | 2.2 | −0.2 |
| WIPE / lets it get dry | **19.0** | 48/48 | 4.2 | −4.0 |
| KEEP / lets it get dry | **0.0** | 48/48 | 4.2 | −3.5 |
| WIPE / rarely marks | **19.0** | 45/48 | 2.8 | −2.6 |
| KEEP / rarely marks | **0.0** | 45/48 | 3.1 | −2.1 |

19 blind days every spring, gone. That is the 20-day minimum on the percentile pool.

## Result 2 — the obvious objection was wrong

The worry: a threshold available on day 0 is worthless, or worse, if it is still on last year's scale
after the probe moved. Measured at day 10 and day 30 of seasons 2-4:

| Config | day 10 | day 30 too-late |
|---|---|---|
| WIPE / lets it get dry | blind, 0/36 | **3** |
| KEEP / lets it get dry | usable 36/36 | **1** |
| WIPE / rarely marks | blind, 0/36 | **2** |
| KEEP / rarely marks | usable 33/36 | **0** |

**Keeping is safer, not riskier.** WIPE carries a strong negative bias (−3.6, −2.1): it
systematically under-estimates and therefore fires *late*. Keeping priors pulls it toward truth.

## Result 3 — sensitivity to how far the probe actually moves

The harness assumes spring re-seat offsets of ±3.5 points. The real reinsertion on 2026-09-04 moved
AD 277 → 240, roughly 10 points on the percentage scale — though that is **confounded with drainage
and is an upper bound**, not a measured re-seat offset. Re-run at 1x, 2x and 3x
(`season_offset_sensitivity.py`), day-30 figures:

| Offsets | Scenario | WIPE too-late | KEEP too-late | WIPE MAE | KEEP MAE | KEEP worst-high |
|---|---|---|---|---|---|---|
| 1x (±3.5) | lets it get dry | 3 | **1** | 3.9 | 4.0 | +4.9 |
| 1x | rarely marks | 2 | **0** | 2.5 | 3.2 | +4.6 |
| 2x (±7) | lets it get dry | 6 | **2** | 4.9 | 6.1 | +10.6 |
| 2x | rarely marks | 4 | **0** | 3.8 | 5.8 | +10.4 |
| 3x (±10.5) | lets it get dry | 8 | **4** | 6.1 | 8.3 | +15.7 |
| 3x | rarely marks | 6 | **3** | 5.4 | 8.2 | +16.4 |

**KEEP wins on `too-late` at every scale.** WIPE is never safer, only more accurate in MAE.

The cost of keeping grows with offset: MAE climbs 2-3 points and the high-side tail reaches +16.
But note *which* direction each fails in. KEEP's bias moves **positive** — it flags thirst early,
producing false "water me" alerts. WIPE's bias stays **negative** — it misses. For a garden, a missed
alert costs plants; a false one costs credibility.

---

## Recommendation

Drop the season wipe. Let `anchorWindowDays`, the rolling caps and the top-decile median do the work
they were already written to do. That also retires `seasonStartedMs`, the 30-day guard, and the
entire `seasonDoubleTap` failure class — the mis-tap that currently costs a season of anchors.

**Caveat worth carrying:** at large re-seat offsets, expect more early/false alerts in the first
weeks of a season. Given the whole project exists to avoid notification fatigue, that is not free.
If it bites, the middle path neither mode tests is to keep the pool but shorten
`anchorWindowDays` from 730 to ~400 — carry one winter, not two — which trims stale-scale
contamination while preserving the cold-start benefit.

**What this does not cover:** Groovy, the Hubitat API, event plumbing. Estimator math only.
