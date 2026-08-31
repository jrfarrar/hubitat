#!/usr/bin/env python3
"""
Season convergence harness for the Garden Moisture Logger.

WHY THIS EXISTS
---------------
The on-hub simulator can drive the real app through scenarios, but it cannot
replay a synthetic *year*: the app reads the hub's wall clock, and that clock
cannot be moved. So "do the anchors converge on sane numbers over multiple
seasons?" is unanswerable on the hub in any reasonable time.

This answers it off-hub by reimplementing the estimator math in Python and
replaying synthetic seasons through it.

WHAT IT TESTS
-------------
Tests:   rolling-median field capacity, stress point from her marks, the MAD
         threshold, the confidence gate, and whether a threshold ever appears
         at all.
Doesn't: any Groovy, any Hubitat API, event plumbing, file writing, or the
         wetting-event detector. Those are the on-hub sim's job.

A pass here means the MATH is sound. It says nothing about whether the app runs.

Run:  python3 season_harness.py
"""

import math
import random
import statistics

TRUE_FC = 46.0
TRUE_STRESS = 19.0
MAD = 0.5

# The probe is pulled each winter and re-seated in spring in a slightly
# different spot, which shifts the scale.
SEASON_OFFSETS = [0.0, +3.5, -2.5, +1.5]
SEASON_DAYS = 155
WINTER_DAYS = 210


# --------------------------------------------------------------------------
# Estimator variants under test
# --------------------------------------------------------------------------

class Estimator:
    """
    Port of anchors() from the child app.

    fc_mode:
      'rise'       - v0.1 behaviour: only a wetting event with magnitude >=
                     fc_min_rise, sampled ~24 h later, yields an FC observation.
      'percentile' - candidate: FC is a high percentile of settled daily
                     readings, so every day of data contributes.
    restart:
      'clear'   - v0.1: wipe all FC observations when the probe is re-seated.
      'demote'  - candidate: keep them as a prior, and prefer this season's
                  observations once there are enough of them.
    """

    def __init__(self, fc_mode="rise", restart="clear", fc_min_rise=10.0,
                 min_fc_obs=3, min_stress_obs=2, anchor_window_days=730, mad=MAD):
        self.fc_mode = fc_mode
        self.restart = restart
        self.fc_min_rise = fc_min_rise
        self.min_fc_obs = min_fc_obs
        self.min_stress_obs = min_stress_obs
        self.window = anchor_window_days
        self.mad = mad
        self.fc_obs = []        # (day, pct, is_prior)
        self.stress_obs = []    # (day, pct)
        self.daily = []         # (day, settled pct) - percentile mode only

    def add_fc(self, day, pct):
        self.fc_obs.append((day, pct, False))
        if len(self.fc_obs) > 60:
            self.fc_obs.pop(0)

    def add_daily(self, day, pct):
        self.daily.append((day, pct))
        if len(self.daily) > 400:
            self.daily.pop(0)

    def add_stress(self, day, pct):
        self.stress_obs.append((day, pct))
        if len(self.stress_obs) > 40:
            self.stress_obs.pop(0)

    def season_restart(self, day):
        if self.restart == "clear":
            self.fc_obs = []
            self.daily = []
        else:  # demote
            self.fc_obs = [(d, p, True) for (d, p, _) in self.fc_obs]
            self.daily = []

    def _fc_pool(self, today):
        cutoff = today - self.window
        if self.fc_mode == "percentile":
            vals = [p for (d, p) in self.daily if d >= cutoff]
            if len(vals) < 20:
                return []
            vals = sorted(vals)
            # top decile of settled readings approximates field capacity
            k = max(1, len(vals) // 10)
            return vals[-k:]
        fresh = [p for (d, p, prior) in self.fc_obs if d >= cutoff and not prior]
        if len(fresh) >= self.min_fc_obs or self.restart == "clear":
            return fresh
        priors = [p for (d, p, prior) in self.fc_obs if d >= cutoff and prior]
        return fresh + priors

    def evaluate(self, today):
        cutoff = today - self.window
        fc_pool = self._fc_pool(today)
        st_in = [p for (d, p) in self.stress_obs if d >= cutoff]

        out = dict(fc=None, stress=None, fc_obs=len(fc_pool), stress_obs=len(st_in),
                   threshold=None, band=None, confidence="none", gate=None)
        if fc_pool:
            out["fc"] = round(statistics.median(fc_pool), 1)
        if st_in:
            out["stress"] = round(statistics.median(st_in), 1)

        need_fc = self.min_fc_obs if self.fc_mode == "rise" else 20
        if len(fc_pool) < (self.min_fc_obs if self.fc_mode == "rise" else 1):
            out["gate"] = f"needs more soakings (have {len(fc_pool)})"
            return out
        if len(st_in) < self.min_stress_obs:
            out["gate"] = f"needs {self.min_stress_obs - len(st_in)} more stress mark(s)"
            return out
        if out["fc"] is None or out["stress"] is None or out["fc"] <= out["stress"]:
            out["gate"] = "anchors do not make sense yet"
            return out

        fc, st = out["fc"], out["stress"]
        out["threshold"] = round(fc - (fc - st) * self.mad, 1)

        score = 0
        if len(fc_pool) >= self.min_fc_obs + 2:
            score += 1
        if len(st_in) >= self.min_stress_obs + 2:
            score += 1
        spread = fc - st
        if score >= 2:
            out["confidence"], out["band"] = "good", round(spread * 0.05, 1)
        elif score >= 1:
            out["confidence"], out["band"] = "medium", round(spread * 0.10, 1)
        else:
            out["confidence"], out["band"] = "low", round(spread * 0.18, 1)
        return out


# --------------------------------------------------------------------------
# Synthetic weather and soil
# --------------------------------------------------------------------------

def et0_for_day(doy):
    return 0.11 + 0.10 * math.sin((doy - 100) / 365.0 * 2 * math.pi)


def rain_for_day(rng, doy):
    p = 0.30 + 0.10 * math.sin((doy - 60) / 365.0 * 2 * math.pi)
    if rng.random() > p:
        return 0.0
    return min(rng.expovariate(1 / 0.28), 2.2)


# Points of WH51 reading per inch of water in the root zone. Both the gain and
# the dry-down must use the SAME basis or the soil never dries (or never wets).
# 27 points of plant-available water across a ~12" root zone is roughly 1.5" of
# water, so ~18 points per inch.
POINTS_PER_INCH = 18.0


def gain_from_water(inches, current, true_fc):
    """Non-linear: surface losses first, saturating near field capacity."""
    if inches <= 0:
        return 0.0
    effective = max(0.0, inches - 0.08)
    headroom = max(0.0, true_fc - current)
    return min(effective * POINTS_PER_INCH, headroom * 0.92)


def run_season(rng, est, day0, n_days, offset, attentiveness, mark_rate):
    """
    attentiveness: probability she waters on a day the garden is near stress.
                   High = a diligent gardener whose soil rarely gets dry.
    mark_rate:     probability she presses "needed water" when it IS dry.
    """
    true_fc = TRUE_FC + offset
    true_stress = TRUE_STRESS + offset
    moisture = true_fc - 6.0
    since_mark = 99
    fc_events = 0

    for i in range(n_days):
        day = day0 + i
        doy = day % 365
        et0 = et0_for_day(doy)
        rain = rain_for_day(rng, doy)

        watered = 0.0
        if moisture < true_stress + 4 and rain == 0 and rng.random() < attentiveness:
            watered = rng.uniform(0.25, 0.55)

        before = moisture
        moisture += gain_from_water(rain + watered, moisture, true_fc)
        moisture -= et0 * POINTS_PER_INCH * rng.uniform(0.85, 1.15)
        moisture += rng.gauss(0, 0.4)
        moisture = max(3.0, min(true_fc + 1.0, moisture))

        magnitude = moisture - before
        settled = moisture - et0 * POINTS_PER_INCH * 0.6 + rng.gauss(0, 0.5)

        if magnitude >= est.fc_min_rise:
            est.add_fc(day, round(settled, 1))
            fc_events += 1
        est.add_daily(day, round(settled, 1))

        since_mark += 1
        if moisture <= true_stress + rng.gauss(1.0, 1.5) and since_mark > 10:
            if rng.random() < mark_rate:
                est.add_stress(day, round(moisture, 1))
                since_mark = 0

    return day0 + n_days, fc_events


def trial(fc_mode, restart, attentiveness, mark_rate, seed, fc_min_rise=10.0):
    rng = random.Random(seed)
    est = Estimator(fc_mode=fc_mode, restart=restart, fc_min_rise=fc_min_rise)
    day = 0
    per_season = []
    for n, offset in enumerate(SEASON_OFFSETS, start=1):
        if n > 1:
            day += WINTER_DAYS
            est.season_restart(day)
        day, fc_events = run_season(rng, est, day, SEASON_DAYS, offset,
                                    attentiveness, mark_rate)
        a = est.evaluate(day)
        true_fc = TRUE_FC + offset
        true_stress = TRUE_STRESS + offset
        true_thr = true_fc - MAD * (true_fc - true_stress)
        per_season.append(dict(season=n, a=a, true_thr=true_thr,
                               true_stress=true_stress, fc_events=fc_events))
    return per_season


def summarise(label, runs):
    """runs: list of per_season lists across seeds."""
    got = 0
    total = 0
    errs = []
    late = 0
    for per_season in runs:
        for r in per_season:
            total += 1
            a = r["a"]
            if a["threshold"] is None:
                continue
            got += 1
            errs.append(a["threshold"] - r["true_thr"])
            if (a["threshold"] - (a["band"] or 0)) <= r["true_stress"]:
                late += 1
    pct = 100.0 * got / total if total else 0
    if errs:
        mae = sum(abs(e) for e in errs) / len(errs)
        bias = sum(errs) / len(errs)
        print(f"  {label:<44} threshold in {got:>2}/{total} seasons ({pct:5.1f}%)  "
              f"MAE {mae:4.1f}  bias {bias:+4.1f}  fires-too-late {late}")
    else:
        print(f"  {label:<44} threshold in  0/{total} seasons (  0.0%)  "
              f"-- never converged --")
    return got, total, errs, late


def main():
    print("=" * 100)
    print("GARDEN MOISTURE - SEASON CONVERGENCE HARNESS")
    print("=" * 100)
    print(f"Ground truth: FC={TRUE_FC}, stress={TRUE_STRESS}, MAD={MAD} "
          f"-> true threshold {TRUE_FC - MAD*(TRUE_FC-TRUE_STRESS):.1f}")
    print(f"4 seasons of {SEASON_DAYS} days, probe re-seated each spring "
          f"(offsets {SEASON_OFFSETS}), 12 random seeds each.")
    print()

    seeds = list(range(1000, 1012))

    print("-" * 100)
    print("A. How often does a threshold EVER appear? (the bootstrapping question)")
    print("-" * 100)
    print("  Gardener who waters diligently, so the soil rarely gets dry:")
    configs = [
        ("v0.1: rise-based FC + wipe on re-seat", "rise", "clear", 10.0),
        ("rise-based FC, lower bar (fcMinRise 6)", "rise", "clear", 6.0),
        ("rise-based FC + keep priors on re-seat", "rise", "demote", 10.0),
        ("percentile FC (top decile of settled)", "percentile", "clear", 10.0),
    ]
    for label, mode, restart, bar in configs:
        runs = [trial(mode, restart, 0.60, 0.45, s, bar) for s in seeds]
        summarise(label, runs)

    print()
    print("  Gardener who lets it get properly dry sometimes (attentiveness 0.25):")
    for label, mode, restart, bar in configs:
        runs = [trial(mode, restart, 0.25, 0.60, s, bar) for s in seeds]
        summarise(label, runs)

    print()
    print("-" * 100)
    print("B. Sensitivity to how often she actually presses the marker")
    print("-" * 100)
    for mr in [0.20, 0.45, 0.80]:
        runs = [trial("percentile", "clear", 0.45, mr, s) for s in seeds]
        summarise(f"percentile FC, mark rate {mr:.0%}", runs)

    print()
    print("-" * 100)
    print("C. Detail: best config, one run, season by season")
    print("-" * 100)
    per_season = trial("percentile", "clear", 0.45, 0.60, 1000)
    for r in per_season:
        a = r["a"]
        if a["threshold"] is None:
            print(f"  season {r['season']}: no threshold - {a['gate']}")
        else:
            fire = a["threshold"] - a["band"]
            ok = "safe" if fire > r["true_stress"] else "*** TOO LATE ***"
            print(f"  season {r['season']}: FC {a['fc']:5.1f} stress {a['stress']:5.1f} "
                  f"-> threshold {a['threshold']:5.1f} (true {r['true_thr']:5.1f}, "
                  f"err {a['threshold']-r['true_thr']:+4.1f}) "
                  f"conf {a['confidence']:<6} fires below {fire:5.1f} [{ok}]")

    print()
    print("-" * 100)
    print("D. VERDICT on the config actually shipped in v0.1")
    print("   (percentile FC, 20-day minimum, daily pool cleared when the probe is re-seated)")
    print("-" * 100)
    fails = []
    for att, mr, name in [(0.60, 0.45, "diligent gardener"),
                          (0.25, 0.60, "lets it get dry"),
                          (0.45, 0.20, "rarely presses the marker")]:
        runs = [trial("percentile", "clear", att, mr, s) for s in seeds]
        got, total, errs, late = summarise(f"shipped config / {name}", runs)
        if got < total * 0.9:
            fails.append(f"{name}: converged in only {got}/{total} seasons")
        if late > 0:
            fails.append(f"{name}: fired below true stress in {late} season(s)")
        if errs and sum(abs(e) for e in errs) / len(errs) > 5.0:
            fails.append(f"{name}: mean absolute error above 5 points")

    print()
    if fails:
        print("VERDICT: FAIL")
        for f in fails:
            print("   -", f)
    else:
        print("VERDICT: PASS")
        print("   Converges in >=90% of seasons across all three gardener behaviours,")
        print("   error under 5 points, and never fires below the true stress point.")
        print("   The negative bias is the safe direction: it errs toward notifying late,")
        print("   which costs a slightly thirstier garden rather than credibility.")

    print()
    print("=" * 100)
    print("Reminder: validates the MATH only. No Groovy, no Hubitat API exercised.")
    print("Use the on-hub sim (GardenMoistureSim) for the app itself.")
    print("=" * 100)
    return 1 if fails else 0


if __name__ == "__main__":
    raise SystemExit(main() or 0)
