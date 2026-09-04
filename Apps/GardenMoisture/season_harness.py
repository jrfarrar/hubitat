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
        self.implicit_obs = []  # (day, moisture when she chose to water)
        self.stress_mode = "explicit"
        self.implicit_pct = 25  # percentile of watering-start readings
        self.min_implicit = 4

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

    def add_implicit(self, day, pct):
        """Moisture at the moment she CHOSE to water - an implicit stress signal.

        Filtered when self.implicit_filter is on: only counts if the soil was
        actually in the drier half of its own observed range. Preventative
        watering on a wet garden is not evidence about dryness, and including it
        drags the stress estimate up - which raises the threshold and makes the
        app notify too early.
        """
        if not hasattr(self, "implicit_obs"):
            self.implicit_obs = []
        if getattr(self, "implicit_filter", False):
            vals = [v for (_d, v) in self.daily]
            if len(vals) >= 15:
                mid = sorted(vals)[len(vals) // 2]
                if pct >= mid:
                    return
        self.implicit_obs.append((day, pct))
        if len(self.implicit_obs) > 60:
            self.implicit_obs.pop(0)

    def season_restart(self, day):
        if self.restart == "clear":
            self.fc_obs = []
            self.daily = []
        elif self.restart == "keep":
            # J.R.'s proposal, 2026-09-04: the season switch is a PAUSE, not a
            # reset. Nothing is discarded at the boundary.
            #
            # This mode exists because neither of the other two could test the
            # idea. Under fc_mode='percentile', _fc_pool() reads ONLY self.daily
            # - and 'clear' and 'demote' both wipe self.daily, so they are the
            # same estimator and the comparison was vacuous. The shipped app has
            # the same shape: a 730-day anchorWindowDays that is meant to carry
            # observations across seasons, and a season handler that wipes them
            # anyway.
            #
            # The bet is that the existing bounds are already enough: the 730-day
            # window ages out genuinely stale data, the rolling caps displace old
            # entries as new ones arrive, and FC is a MEDIAN of the top decile,
            # so a re-seat offset drags the estimate gradually instead of
            # lurching it.
            pass
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
        if getattr(self, "stress_mode", "explicit") == "implicit":
            imp = [p for (d, p) in getattr(self, "implicit_obs", []) if d >= cutoff]
            # Low percentile, not the median: some watering is preventative, so
            # the LOWER tail is where she actually let it get dry. Erring low
            # keeps the threshold low, which notifies late rather than early.
            if len(imp) >= self.min_implicit:
                srt = sorted(imp)
                k = max(0, min(len(srt) - 1,
                               int(round((self.implicit_pct / 100.0) * (len(srt) - 1)))))
                st_in = [srt[k]]
            else:
                st_in = []
        else:
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
        need_st = 1 if getattr(self, "stress_mode", "explicit") == "implicit" else self.min_stress_obs
        if len(st_in) < need_st:
            out["gate"] = "needs more watering observations" if need_st == 1 else \
                          f"needs {self.min_stress_obs - len(st_in)} more stress mark(s)"
            return out
        if out["fc"] is None or out["stress"] is None or out["fc"] <= out["stress"]:
            out["gate"] = "anchors do not make sense yet"
            return out

        fc, st = out["fc"], out["stress"]
        thr = fc - (fc - st) * self.mad

        # SAFETY CLAMP. The stress anchor is inferred from behaviour, so it can
        # drift upward if watering becomes routine rather than reactive - and a
        # too-high stress means a too-high threshold means notifying when the
        # soil is not actually dry. Independently of the anchor, never let the
        # threshold sit in the upper part of the soil's own observed range.
        if getattr(self, "clamp", False):
            vals = sorted(v for (_d, v) in self.daily if _d >= cutoff)
            if len(vals) >= 20:
                floor = vals[max(0, int(0.05 * (len(vals) - 1)))]
                cap = fc - self.clamp_frac * (fc - floor)
                if thr > cap:
                    out["clamped"] = True
                    thr = cap
        out["threshold"] = round(thr, 1)

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


def run_season(rng, est, day0, n_days, offset, attentiveness, mark_rate,
               preventative=0.05, track=None):
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
            # This is what the app can see: the reading at the moment she
            # decided to water. No button press involved.
            est.add_implicit(day, round(moisture, 1))
        # Preventative watering: she waters even though it is NOT dry. This is
        # the noise the implicit signal has to survive.
        elif rain == 0 and rng.random() < preventative:
            watered = rng.uniform(0.2, 0.4)
            est.add_implicit(day, round(moisture, 1))

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

        # Cold start: how many days into THIS season before the app can say
        # anything at all? evaluate() is read-only, so this does not perturb the
        # run. Measured per season because the whole point of the wipe-vs-keep
        # question is what happens at the START of a spring, and evaluating only
        # at season end cannot see it.
        if track is not None:
            if track.get("first") is None and est.evaluate(day)["threshold"] is not None:
                track["first"] = i
            # Early-season accuracy. This is the real objection to keeping priors:
            # a threshold available on day 0 is worthless - or worse than
            # worthless - if it is still on LAST year's scale after the probe was
            # re-seated. Season-end MAE cannot see this, because by then new
            # readings have displaced the old ones.
            if i in (10, 30):
                track.setdefault("snap", {})[i] = est.evaluate(day)

    return day0 + n_days, fc_events


def trial(fc_mode, restart, attentiveness, mark_rate, seed, fc_min_rise=10.0,
          stress_mode="explicit", implicit_pct=25, preventative=0.05):
    rng = random.Random(seed)
    est = Estimator(fc_mode=fc_mode, restart=restart, fc_min_rise=fc_min_rise)
    est.stress_mode = stress_mode
    est.implicit_pct = implicit_pct
    day = 0
    per_season = []
    for n, offset in enumerate(SEASON_OFFSETS, start=1):
        if n > 1:
            day += WINTER_DAYS
            est.season_restart(day)
        day, fc_events = run_season(rng, est, day, SEASON_DAYS, offset,
                                    attentiveness, mark_rate, preventative)
        a = est.evaluate(day)
        true_fc = TRUE_FC + offset
        true_stress = TRUE_STRESS + offset
        true_thr = true_fc - MAD * (true_fc - true_stress)
        per_season.append(dict(season=n, a=a, true_thr=true_thr,
                               true_stress=true_stress, fc_events=fc_events))
    return per_season


def trial_cold_start(fc_mode, restart, attentiveness, mark_rate, seed,
                     fc_min_rise=10.0, preventative=0.05):
    """Like trial(), but also records how many days into each season a threshold
    first became available. None means it never did that season."""
    rng = random.Random(seed)
    est = Estimator(fc_mode=fc_mode, restart=restart, fc_min_rise=fc_min_rise)
    day = 0
    per_season = []
    for n, offset in enumerate(SEASON_OFFSETS, start=1):
        if n > 1:
            day += WINTER_DAYS
            est.season_restart(day)
        track = {"first": None}
        day, _ = run_season(rng, est, day, SEASON_DAYS, offset,
                            attentiveness, mark_rate, preventative, track=track)
        a = est.evaluate(day)
        true_fc = TRUE_FC + offset
        true_stress = TRUE_STRESS + offset
        per_season.append(dict(season=n, a=a,
                               true_thr=true_fc - MAD * (true_fc - true_stress),
                               true_stress=true_stress, first=track["first"],
                               snap=track.get("snap", {})))
    return per_season


def summarise_early(label, runs, dayk):
    """Accuracy at day `dayk` of seasons 2-4 only - the re-seat exposure window."""
    errs, late, have, total = [], 0, 0, 0
    for per_season in runs:
        for r in per_season:
            if r["season"] == 1:
                continue
            total += 1
            a = r["snap"].get(dayk)
            if not a or a["threshold"] is None:
                continue
            have += 1
            errs.append(a["threshold"] - r["true_thr"])
            if (a["threshold"] - (a["band"] or 0)) <= r["true_stress"]:
                late += 1
    if errs:
        mae = sum(abs(e) for e in errs) / len(errs)
        bias = sum(errs) / len(errs)
        worst = max(errs)
        print(f"  {label:<40} day {dayk:>2}: usable {have:>2}/{total}  "
              f"MAE {mae:4.1f}  bias {bias:+4.1f}  worst-high {worst:+4.1f}  too-late {late}")
    else:
        print(f"  {label:<40} day {dayk:>2}: usable  0/{total}  -- blind --")


def summarise_cold_start(label, runs):
    """Blind days at the start of each season, and accuracy once converged."""
    firsts, errs, late, got, total = [], [], 0, 0, 0
    s1, later = [], []
    for per_season in runs:
        for r in per_season:
            total += 1
            f = r["first"]
            if f is not None:
                firsts.append(f)
                (s1 if r["season"] == 1 else later).append(f)
            a = r["a"]
            if a["threshold"] is not None:
                got += 1
                errs.append(a["threshold"] - r["true_thr"])
                if (a["threshold"] - (a["band"] or 0)) <= r["true_stress"]:
                    late += 1
    med = statistics.median(firsts) if firsts else float("nan")
    medl = statistics.median(later) if later else float("nan")
    mae = sum(abs(e) for e in errs) / len(errs) if errs else float("nan")
    bias = sum(errs) / len(errs) if errs else float("nan")
    print(f"  {label:<40} blind days: all {med:5.1f}  seasons2-4 {medl:5.1f}   "
          f"converged {got:>2}/{total}  MAE {mae:4.1f}  bias {bias:+4.1f}  too-late {late}")
    return medl


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
    print("C2. WIPE vs KEEP at the season boundary  (J.R.'s proposal, 2026-09-04)")
    print("    'blind days' = days into a season before ANY threshold is available.")
    print("    Seasons 2-4 is the number that matters: season 1 starts empty either way.")
    print("-" * 100)
    for att, mr, name in [(0.60, 0.45, "diligent gardener"),
                          (0.25, 0.60, "lets it get dry"),
                          (0.45, 0.20, "rarely marks")]:
        for restart, tag in [("clear", "WIPE (shipped)"), ("keep", "KEEP (proposed)")]:
            runs = [trial_cold_start("percentile", restart, att, mr, s) for s in seeds]
            summarise_cold_start(f"{tag} / {name}", runs)
        print()

    print("-" * 100)
    print("C3. THE OBJECTION: is an early threshold from last year's scale SAFE?")
    print("    Seasons 2-4 only, sampled at day 10 and day 30, after a re-seat offset.")
    print("    'too-late' is the one that matters: fire line at or below true stress.")
    print("-" * 100)
    for att, mr, name in [(0.60, 0.45, "diligent gardener"),
                          (0.25, 0.60, "lets it get dry"),
                          (0.45, 0.20, "rarely marks")]:
        for restart, tag in [("clear", "WIPE (shipped)"), ("keep", "KEEP (proposed)")]:
            runs = [trial_cold_start("percentile", restart, att, mr, s) for s in seeds]
            for dk in (10, 30):
                summarise_early(f"{tag} / {name}", runs, dk)
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
