"""
Harness for Water Softener Salt Monitor.

Ports the app's estimator exactly (window minimum, refill detection, violation
counting, day-number arithmetic, least-squares prediction) and replays it against
the REAL captured sensor readings plus synthetic scenarios, before the app goes
anywhere near the hub.

Precedent: running a harness on the garden-moisture app found six production bugs
that a careful read-through had missed.
"""
import datetime as dt

HOUR = 3600_000
DAY = 86400_000

# ---- ported helpers ---------------------------------------------------------

def day_num(s):
    """Howard Hinnant days-from-civil, same arithmetic as the Groovy dayNum()."""
    try:
        y, m, d = [int(x) for x in s.split('-')]
    except Exception:
        return None
    y -= 1 if m <= 2 else 0
    era = (y if y >= 0 else y - 399) // 400
    yoe = y - era * 400
    doy = (153 * (m + (-3 if m > 2 else 9)) + 2) // 5 + d - 1
    doe = yoe * 365 + yoe // 4 - yoe // 100 + doy
    return era * 146097 + doe - 719468


class SaltMonitor:
    def __init__(self, floor_mm=40, ceil_mm=1500, min_window_h=24,
                 refill_drop_mm=50, refill_window_h=6, violation_eps=5,
                 empty_mm=None, full_mm=None, refill_confirm=2, min_reads_per_day=3):
        self.floor_mm, self.ceil_mm = floor_mm, ceil_mm
        self.min_window_h, self.refill_window_h = min_window_h, refill_window_h
        self.refill_drop_mm, self.violation_eps = refill_drop_mm, violation_eps
        self.empty_mm, self.full_mm = empty_mm, full_mm
        self.refill_confirm, self.min_reads_per_day = refill_confirm, min_reads_per_day
        self.skipped = 0
        self.readings = []
        self.daily = []
        self.today = None
        self.today_min = self.today_max = None
        self.today_count = 0
        self.refill_flag_today = False
        self.last_refill_date = None
        self.violations = 0
        self.refill_count = 0
        self.rejected = 0
        self.log = []

    # -- intake
    def reading(self, mm, t, daykey):
        if mm < self.floor_mm or mm > self.ceil_mm:
            self.rejected += 1
            return
        if self.today and self.today != daykey:
            self.rollup()
        if self.today is None:
            self.today = daykey
        self.readings.append((t, mm))
        widest = max(self.min_window_h, self.refill_window_h)
        cutoff = t - (widest + 6) * HOUR
        self.readings = [r for r in self.readings if r[0] >= cutoff][-400:]
        self.today_count += 1
        self.today_min = mm if self.today_min is None else min(self.today_min, mm)
        self.today_max = mm if self.today_max is None else max(self.today_max, mm)
        self.now = t
        self.check_refill(t, daykey)

    def min_over(self, hours, t):
        cutoff = t - hours * HOUR
        w = [mm for (ts, mm) in self.readings if ts >= cutoff]
        return (min(w), len(w)) if w else None

    def since_refill(self):
        if not self.last_refill_date:
            return list(self.daily)
        a = day_num(self.last_refill_date)
        return [r for r in self.daily if day_num(r['d']) is not None and day_num(r['d']) >= a]

    def reference_level(self):
        s = self.since_refill()
        if s:
            return s[-1]['mm']
        return self.today_min

    def check_refill(self, t, daykey):
        ref = self.reference_level()
        if ref is None:
            return
        threshold = ref - self.refill_drop_mm
        cutoff = t - self.refill_window_h * HOUR
        confirming = [mm for (ts, mm) in self.readings if ts >= cutoff and mm <= threshold]
        if len(confirming) < self.refill_confirm:
            return
        new_mm = min(confirming)
        self.last_refill_date = daykey
        self.refill_flag_today = True
        self.refill_count += 1
        self.log.append(f"REFILL on {daykey}: {ref} -> {new_mm} mm, "
                        f"confirmed by {len(confirming)} readings <= {threshold}")

    def rollup(self):
        closing = self.today
        if self.today_min is not None and self.today_count < self.min_reads_per_day:
            self.skipped += 1
            self.log.append(f"SKIP {closing}: only {self.today_count} reading(s)")
            self.today = None
            self.today_min = self.today_max = None
            self.today_count = 0
            self.refill_flag_today = False
            return
        if self.today_min is not None:
            e = dict(d=closing, mm=self.today_min, max=self.today_max,
                     n=self.today_count, refill=self.refill_flag_today, violation=False)
            prev = self.daily[-1] if self.daily else None
            if prev is not None and not e['refill']:
                delta = e['mm'] - prev['mm']
                if delta < -self.violation_eps and abs(delta) < self.refill_drop_mm:
                    e['violation'] = True
                    self.violations += 1
                    self.log.append(f"VIOLATION {closing}: min fell {abs(delta)} mm "
                                    f"({prev['mm']} -> {e['mm']}) with no refill")
            self.daily.append(e)
        self.today = None
        self.today_min = self.today_max = None
        self.today_count = 0
        self.refill_flag_today = False

    def current(self, t):
        w = self.min_over(self.min_window_h, t)
        if w:
            return w[0]
        if self.today_min is not None:
            return self.today_min
        return self.daily[-1]['mm'] if self.daily else None

    def predict(self, t):
        if self.empty_mm is None:
            return {'reason': 'no empty point'}
        s = self.since_refill()
        if len(s) < 7:
            return {'reason': f'need 7 days, have {len(s)}'}
        pts = [(day_num(r['d']), float(r['mm'])) for r in s if day_num(r['d']) is not None]
        if len(pts) < 7:
            return {'reason': 'not enough dated points'}
        n = float(len(pts))
        sx = sum(p[0] for p in pts); sy = sum(p[1] for p in pts)
        sxx = sum(p[0] * p[0] for p in pts); sxy = sum(p[0] * p[1] for p in pts)
        den = n * sxx - sx * sx
        if den == 0:
            return {'reason': 'degenerate'}
        slope = (n * sxy - sx * sy) / den
        if slope <= 0.05:
            return {'reason': f'no depletion yet ({slope:.2f} mm/day)'}
        cur = self.current(t)
        rem = self.empty_mm - cur
        if rem <= 0:
            return {'days': 0, 'slope': round(slope, 1)}
        return {'days': int(round(rem / slope)), 'slope': round(slope, 1), 'points': len(pts)}


# ---- scenarios --------------------------------------------------------------

def base_ms(daystr):
    return day_num(daystr) * DAY


def run(name, events, **kw):
    """events: list of (daystr, hour, mm)"""
    m = SaltMonitor(**kw)
    t = 0
    for (d, h, mm) in events:
        t = base_ms(d) + h * HOUR
        m.reading(mm, t, d)
    m.rollup()
    print(f"\n===== {name} =====")
    print("  daily minima:", [(r['d'][5:], r['mm'], 'R' if r['refill'] else ('V' if r['violation'] else '')) for r in m.daily])
    print(f"  refills={m.refill_count}  violations={m.violations}  "
          f"rejected={m.rejected}  skipped_days={m.skipped}")
    print("  current:", m.current(t), " predict:", m.predict(t))
    for l in m.log:
        print("   *", l)
    return m


FAIL = []
def check(label, cond, detail=""):
    print(("  PASS  " if cond else "  FAIL  ") + label + (f"   [{detail}]" if detail else ""))
    if not cond:
        FAIL.append(label)


# 1. REAL captured data: the bimodal 241/351 flip, exactly as measured.
real = [
    ("2026-08-30", 17.7, 241), ("2026-08-30", 20.7, 355),
    ("2026-08-31", 4.3, 241), ("2026-08-31", 5.5, 351),
    ("2026-08-31", 11.8, 241), ("2026-08-31", 16.2, 351), ("2026-08-31", 23.9, 245),
    ("2026-09-01", 0.9, 351),
    ("2026-09-02", 21.7, 246),
]
m1 = run("1. REAL captured readings, subscription only (bimodal 241/351)", real)
check("the poisoned far-echo-only day is skipped, not recorded",
      all(r['mm'] < 300 for r in m1.daily), str([r['mm'] for r in m1.daily]))
check("no false refill from the 110 mm downward flips", m1.refill_count == 0)
check("no violations on real data", m1.violations == 0)
check("sparse days were skipped rather than guessed", m1.skipped > 0, f"skipped={m1.skipped}")

# 1b. Same real cluster behaviour, but polled every 15 min as the app now does.
#     True level 241, drifting +1 mm/day; ~35% of polls return the far echo.
import random
random.seed(7)
ev = []
for i in range(12):
    d = (dt.date(2026, 8, 20) + dt.timedelta(days=i)).isoformat()
    true = 241 + i
    for q in range(96):
        mm = true + 110 if random.random() < 0.35 else true
        ev.append((d, q * 0.25, mm))
m1b = run("1b. Real cluster behaviour, polled every 15 min", ev, empty_mm=400)
check("every daily minimum is the true near value",
      all(r['mm'] == 241 + i for i, r in enumerate(m1b.daily)),
      str([r['mm'] for r in m1b.daily]))
check("polling yields no skipped days", m1b.skipped == 0)
check("no false refills across 12 polled days", m1b.refill_count == 0)
check("no violations across 12 polled days", m1b.violations == 0)

# 2. Genuine refill: settled at 400, topped up to 150.
ev = []
for i in range(10):
    d = f"2026-07-{i+1:02d}"
    ev += [(d, 6, 400), (d, 10, 505), (d, 14, 402), (d, 20, 401)]
d = "2026-07-11"
ev += [(d, 6, 402), (d, 9, 150), (d, 10, 152), (d, 15, 260), (d, 20, 151)]
for i in range(11, 16):
    d = f"2026-07-{i+1:02d}"
    ev += [(d, 6, 151 + i - 10), (d, 12, 152 + i - 10), (d, 18, 265)]
m2 = run("2. Genuine refill 400 -> 150", ev)
check("refill detected exactly once", m2.refill_count == 1, f"count={m2.refill_count}")
check("refill did not count as a violation", m2.violations == 0)

# 3. Single fluke low reading must NOT be read as a refill.
ev = []
for i in range(10):
    d = f"2026-06-{i+1:02d}"
    ev += [(d, 6, 300), (d, 12, 302), (d, 18, 410)]
ev += [("2026-06-11", 6, 300), ("2026-06-11", 7, 120), ("2026-06-11", 12, 305), ("2026-06-11", 20, 415)]
m3 = run("3. Lone fluke low reading", ev)
check("one low reading does not trigger a refill", m3.refill_count == 0, f"count={m3.refill_count}")

# 3b. But TWO corroborating low readings should.
ev = []
for i in range(10):
    d = f"2026-06-{i+1:02d}"
    ev += [(d, 6, 300), (d, 12, 302), (d, 18, 410)]
ev += [("2026-06-11", 6, 300), ("2026-06-11", 7, 120), ("2026-06-11", 9, 122),
       ("2026-06-11", 12, 235), ("2026-06-11", 20, 121)]
m3b = run("3b. Two corroborating low readings", ev)
check("a corroborated drop IS a refill", m3b.refill_count == 1, f"count={m3b.refill_count}")

# 4. Steady depletion with far-echo noise -> slope recovered, prediction sane.
ev = []
true_slope = 3.0
for i in range(30):
    d = (dt.date(2026, 4, 1) + dt.timedelta(days=i)).isoformat()
    true = 200 + true_slope * i
    ev += [(d, 3, int(true)), (d, 9, int(true) + 118), (d, 15, int(true) + 2), (d, 21, int(true) + 95)]
m4 = run("4. Steady depletion, 3.0 mm/day, with far echoes", ev, empty_mm=400)
p = m4.predict(base_ms("2026-04-30") + 21 * HOUR)
check("slope recovered within 0.3 mm/day of truth", abs(p['slope'] - true_slope) < 0.3, f"slope={p['slope']}")
expected_days = (400 - (200 + true_slope * 29)) / true_slope
check("days-remaining within 2 days of truth", abs(p['days'] - expected_days) <= 2,
      f"predicted={p['days']} expected={expected_days:.1f}")

# 5. Out-of-range guards.
m5 = run("5. Out-of-range readings", [("2026-05-01", 6, 5), ("2026-05-01", 7, 9000),
                                      ("2026-05-01", 8, 250), ("2026-05-01", 9, 251),
                                      ("2026-05-01", 10, 252)])
check("2 of 5 readings rejected", m5.rejected == 2, f"rejected={m5.rejected}")
check("estimate uses only the valid readings", m5.current(base_ms("2026-05-01") + 10 * HOUR) == 250)

# 6. dayNum arithmetic across boundaries and a leap day.
check("day_num month boundary", day_num("2026-03-01") - day_num("2026-02-28") == 1)
check("day_num year boundary", day_num("2027-01-01") - day_num("2026-12-31") == 1)
check("day_num leap day 2028", day_num("2028-03-01") - day_num("2028-02-28") == 2)
check("day_num spans a year correctly", day_num("2027-01-01") - day_num("2026-01-01") == 365)

# 7. A real (small) decrease with no refill must be flagged as a violation.
ev = []
for i, mm in enumerate([300, 305, 310, 292, 315, 320]):
    d = (dt.date(2026, 2, 1) + dt.timedelta(days=i)).isoformat()
    ev += [(d, 6, mm), (d, 12, mm + 1), (d, 18, mm + 110)]
m7 = run("7. Unexplained 18 mm decrease", ev)
check("one violation flagged", m7.violations == 1, f"violations={m7.violations}")
check("not misread as a refill", m7.refill_count == 0)

# 8. Gap in the data must not compress the slope (real time axis, not row index).
ev = []
for i in [0, 1, 2, 3, 4, 5, 6, 7, 20, 21]:
    d = (dt.date(2026, 1, 1) + dt.timedelta(days=i)).isoformat()
    v = int(200 + 2.0 * i)
    ev += [(d, 6, v), (d, 12, v + 1), (d, 18, v + 120)]
m8 = run("8. Ten days of data spanning 22 calendar days", ev, empty_mm=400)
p8 = m8.predict(base_ms("2026-01-22") + 18 * HOUR)
check("slope uses calendar days, not row count", abs(p8['slope'] - 2.0) < 0.2, f"slope={p8['slope']}")

print("\n" + "=" * 60)
print("FAILURES:", FAIL if FAIL else "none - all checks passed")
