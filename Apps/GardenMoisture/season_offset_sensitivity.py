"""Sensitivity check: does WIPE-vs-KEEP hold up when the probe is re-seated
much further out than the harness assumes?

The harness models spring re-seat offsets of [0, +3.5, -2.5, +1.5]. The real
reinsertion on 2026-09-04 moved AD 277 -> 240, roughly 10 points on the
percentage scale (confounded with drainage, so an upper bound). If real offsets
are 3x the modelled ones, keeping last year's observations gets correspondingly
more wrong. This re-runs the comparison at several offset scales.
"""
import season_harness as H

seeds = list(range(1000, 1012))
BASE = [0.0, 3.5, -2.5, 1.5]

for scale in (1.0, 2.0, 3.0):
    H.SEASON_OFFSETS = [round(o * scale, 1) for o in BASE]
    print("=" * 96)
    print("re-seat offsets %s   (%.0fx the harness default)" % (H.SEASON_OFFSETS, scale))
    print("=" * 96)
    for att, mr, name in [(0.60, 0.45, "diligent"), (0.25, 0.60, "lets it get dry"),
                          (0.45, 0.20, "rarely marks")]:
        for restart, tag in [("clear", "WIPE "), ("keep", "KEEP ")]:
            runs = [H.trial_cold_start("percentile", restart, att, mr, s) for s in seeds]
            H.summarise_cold_start("%s/ %s" % (tag, name), runs)
            H.summarise_early("%s/ %s" % (tag, name), runs, 30)
        print()
