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
    """Port of v1.2.0: quarantine + pending refill + liveness-gated polling."""

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
        self.last_refill_mm = None
        self.violations = 0
        self.refill_count = 0
        self.rejected = 0
        self.log = []
        # v1.2.0
        self.quarantine = []          # (t, mm) held aside
        self.low_days = {}            # daykey -> dict(mm, max, n) of held readings
        self.today_quarantined = 0
        self.pending = None           # dict(since, ref, mm, low_days)
        self.bad_bursts = 0
        self.refill_history = []
        self.ever_min = None
        self.last_device_mark = None
        self.polls_skipped = 0
        self.now = 0

    # -- liveness-gated poll (pollSensor + deviceSpokeSinceLastPoll)
    def poll(self, mm, t, daykey, device_mark):
        """device_mark = the driver's lastUpdate attribute at poll time."""
        if device_mark is not None and device_mark == self.last_device_mark:
            self.polls_skipped += 1
            return
        self.last_device_mark = device_mark
        self.reading(mm, t, daykey)

    # -- intake
    def reading(self, mm, t, daykey):
        if mm < self.floor_mm or mm > self.ceil_mm:
            self.rejected += 1
            return
        if self.today and self.today != daykey:
            self.rollup()
        if self.today is None:
            self.today = daykey
        self.now = t
        widest = max(self.min_window_h, self.refill_window_h)
        cutoff = t - (widest + 6) * HOUR

        thr = self.refill_threshold()
        if thr is not None and mm <= thr:
            self.quarantine.append((t, mm))
            self.quarantine = [r for r in self.quarantine if r[0] >= cutoff][-400:]
            self.today_quarantined += 1
            e = self.low_days.setdefault(daykey, dict(mm=mm, max=mm, n=0))
            e['mm'] = min(e['mm'], mm); e['max'] = max(e['max'], mm); e['n'] += 1
            self.check_refill(t, daykey)
            return

        self.readings.append((t, mm))
        self.readings = [r for r in self.readings if r[0] >= cutoff][-400:]
        self.today_count += 1
        self.today_min = mm if self.today_min is None else min(self.today_min, mm)
        self.today_max = mm if self.today_max is None else max(self.today_max, mm)
        self.ever_min = mm if self.ever_min is None else min(self.ever_min, mm)

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

    def refill_threshold(self):
        s = self.since_refill()
        return None if not s else s[-1]['mm'] - self.refill_drop_mm

    # -- stage 1: open a pending refill
    def check_refill(self, t, daykey):
        if self.pending:
            if self.quarantine:
                self.pending['mm'] = min(self.pending['mm'], min(mm for _, mm in self.quarantine))
            return
        ref = self.reference_level()
        if ref is None:
            return
        threshold = ref - self.refill_drop_mm
        cutoff = t - self.refill_window_h * HOUR
        confirming = [mm for (ts, mm) in self.quarantine if ts >= cutoff and mm <= threshold]
        if len(confirming) < self.refill_confirm:
            return
        self.pending = dict(since=daykey, ref=ref, mm=min(confirming), low_days=0)
        self.log.append(f"PENDING refill on {daykey}: {ref} -> {min(confirming)} mm, "
                        f"{len(confirming)} readings <= {threshold}")

    # -- stage 2a: confirmed
    def confirm_refill(self, how, todaykey):
        p = self.pending
        by_day = self.low_days
        if not p or not by_day:
            self.pending = None
            return
        days = sorted(by_day, key=day_num)
        first = days[0]
        new_mm = by_day[first]['mm']
        self.register_refill(new_mm, first, f"{how}: {p['ref']} -> {new_mm}")
        series = [r for r in self.daily if r['d'] not in days or r['d'] == todaykey]
        for d in days:
            if d == todaykey:
                continue
            e = by_day[d]
            series.append(dict(d=d, mm=e['mm'], max=e['max'], n=e['n'], refill=(d == first), violation=False))
        self.daily = sorted(series, key=lambda r: day_num(r['d']))[-400:]
        if todaykey in by_day:
            e = by_day[todaykey]
            self.today_min = e['mm'] if self.today_min is None else min(self.today_min, e['mm'])
            self.today_max = e['max'] if self.today_max is None else max(self.today_max, e['max'])
            self.today_count += e['n']
            self.refill_flag_today = (first == todaykey)
        self.readings = sorted(self.readings + self.quarantine)
        self.ever_min = new_mm if self.ever_min is None else min(self.ever_min, new_mm)
        self.quarantine = []
        self.low_days = {}
        self.today_quarantined = 0
        self.pending = None

    # -- stage 2b: bad data
    def dismiss_refill(self, how):
        n = len(self.quarantine)
        self.quarantine = []
        self.low_days = {}
        self.today_quarantined = 0
        self.pending = None
        self.bad_bursts += 1
        self.log.append(f"DISMISSED ({how}): {n} low readings discarded")

    def register_refill(self, mm, on_date, why):
        self.refill_history.append(dict(date=on_date, mm=mm, prev_date=self.last_refill_date,
                                        prev_mm=self.last_refill_mm, prev_ref=self.reference_level()))
        self.last_refill_date = on_date
        self.last_refill_mm = mm
        self.refill_count += 1
        if on_date == self.today:
            self.refill_flag_today = True
        for r in self.daily:
            if r['d'] == on_date:
                r['refill'] = True
        self.log.append(f"REFILL on {on_date}: {why}")

    def undo_last_refill(self, todaykey):
        if not self.last_refill_date:
            return "no refill to undo"
        date = self.last_refill_date
        h = self.refill_history[-1] if self.refill_history else None
        anchor = day_num(date)
        prev_ref = h['prev_ref'] if h and h['prev_ref'] is not None else None
        if prev_ref is None:
            before = [r for r in self.daily if day_num(r['d']) < anchor]
            prev_ref = before[-1]['mm'] if before else None
        if prev_ref is None:
            return "cannot undo"
        thr = prev_ref - self.refill_drop_mm
        dropped = [r for r in self.daily if day_num(r['d']) >= anchor and r['mm'] <= thr]
        self.daily = [r for r in self.daily if r not in dropped]
        for r in self.daily:
            if r['d'] == date:
                r['refill'] = False
        self.readings = [(t, mm) for (t, mm) in self.readings if mm > thr]
        tq = [mm for (t, mm) in self.readings
              if dt.datetime.fromtimestamp(t / 1000, dt.timezone.utc).date().isoformat() == todaykey]
        self.today_min = min(tq) if tq else None
        self.today_max = max(tq) if tq else None
        self.today_count = len(tq)
        self.refill_flag_today = False
        self.last_refill_date = h['prev_date'] if h else None
        self.last_refill_mm = h['prev_mm'] if h else None
        self.refill_count = max(0, self.refill_count - 1)
        if h:
            self.refill_history.pop()
        mins = [r['mm'] for r in self.daily] + ([self.today_min] if self.today_min is not None else [])
        self.ever_min = min(mins) if mins else None
        msg = f"undid refill of {date}: dropped {len(dropped)} day(s) <= {thr} ({[(r['d'][5:], r['mm']) for r in dropped]}), ref back to {prev_ref}"
        self.log.append(msg)
        return msg

    def _reset_day(self):
        if not self.pending and self.low_days and self.today:
            c = day_num(self.today)
            self.low_days = {k: v for k, v in self.low_days.items() if day_num(k) >= c}
        self.today = None
        self.today_min = self.today_max = None
        self.today_count = 0
        self.today_quarantined = 0
        self.refill_flag_today = False

    def rollup(self):
        closing = self.today
        # pending refill judged on the whole day, before the day is closed
        if self.pending:
            low_n, old_n = self.today_quarantined, self.today_count
            if low_n + old_n >= self.min_reads_per_day:
                if low_n > old_n:
                    self.pending['low_days'] += 1
                    if self.pending['low_days'] >= 3:
                        # rollup runs on the NEXT day in the app; the closing day's
                        # quarantined readings therefore become a full row.
                        self.confirm_refill(f"automatic after {self.pending['low_days']} days", todaykey=None)
                else:
                    self.dismiss_refill(f"old level {old_n} vs {low_n} low on {closing}")
        if any(r['d'] == closing for r in self.daily):
            self._reset_day()
            return
        if self.today_min is None or self.today_count < self.min_reads_per_day:
            self.skipped += 1
            self.log.append(f"SKIP {closing}: only {self.today_count} reading(s)")
            self._reset_day()
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
        self._reset_day()

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
m2 = run("2. Genuine refill 400 -> 150 (auto-confirms after 3 whole days low)", ev)
check("refill confirmed exactly once", m2.refill_count == 1, f"count={m2.refill_count}")
check("refill did not count as a violation", m2.violations == 0)
check("refill is dated the first low day, not the confirmation day",
      m2.last_refill_date == "2026-07-11", f"date={m2.last_refill_date}")
check("the pending days were rewritten to the new level",
      [r['mm'] for r in m2.daily if r['d'] >= "2026-07-11"][:3] == [150, 152, 153],
      str([(r['d'][5:], r['mm']) for r in m2.daily if r['d'] >= "2026-07-11"]))
check("nothing left pending after confirmation", m2.pending is None)

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
check("a corroborated drop opens a PENDING refill, not a refill",
      m3b.refill_count == 0 and (m3b.pending is not None or m3b.bad_bursts == 1),
      f"count={m3b.refill_count} pending={m3b.pending} bursts={m3b.bad_bursts}")
check("the low readings never reached the daily minima",
      all(r['mm'] >= 300 for r in m3b.daily), str([r['mm'] for r in m3b.daily]))

# 9. THE REAL 2026-10-31 EVENT: settled 445, three bursts of 192/197 (15-35 min,
#    3-6 readings) across two days, straight back to 445 each time. Under v1.1
#    this registered a refill and anchored the forecast on 197 mm.
ev = []
for i in range(5):
    d = (dt.date(2026, 10, 26) + dt.timedelta(days=i)).isoformat()
    for q in range(96):
        ev.append((d, q * 0.25, 420 if i < 4 else 445))
d = "2026-10-31"
for q in range(96):
    h = q * 0.25
    mm = 445
    if 13.5 <= h < 14.0: mm = 197          # burst 1: 3 readings
    if 18.0 <= h < 18.75: mm = 192         # burst 2: 6 readings (two cycles)
    ev.append((d, h, mm))
d = "2026-11-01"
for q in range(96):
    h = q * 0.25
    ev.append((d, h, 197 if 22.75 <= h < 23.5 else 445))
for q in range(48):
    ev.append(("2026-11-02", q * 0.25, 445))
m9 = run("9. REAL 10-31 under-report bursts against a settled 445", ev, empty_mm=559)
check("no refill registered from the bursts", m9.refill_count == 0, f"count={m9.refill_count}")
check("both burst days discarded as bad data at rollup", m9.bad_bursts == 2, f"bursts={m9.bad_bursts}")
check("10-31 and 11-01 minima are the true 445, not 192/197",
      [r['mm'] for r in m9.daily if r['d'] in ("2026-10-31", "2026-11-01")] == [445, 445],
      str([(r['d'][5:], r['mm']) for r in m9.daily]))
check("everMin untouched by the bursts", m9.ever_min == 420, f"everMin={m9.ever_min}")
check("current estimate is 445", m9.current(base_ms("2026-11-02") + 12 * HOUR) == 445)

# 10. UNDO of a v1.1-style false refill already in state (what the hub holds now).
m10 = SaltMonitor(empty_mm=559)
for i, mm in enumerate([420, 420, 420, 420, 420]):
    d = (dt.date(2026, 10, 26) + dt.timedelta(days=i)).isoformat()
    m10.daily.append(dict(d=d, mm=mm, max=mm, n=96, refill=False, violation=False))
m10.daily.append(dict(d="2026-10-31", mm=192, max=464, n=102, refill=True, violation=False))
m10.daily.append(dict(d="2026-11-01", mm=197, max=445, n=98, refill=False, violation=False))
m10.last_refill_date, m10.last_refill_mm, m10.refill_count, m10.ever_min = "2026-10-31", 197, 1, 192
m10.today = "2026-11-02"
t0 = base_ms("2026-11-02")
m10.readings = [(t0 - 1 * HOUR, 197)] + [(t0 + h * HOUR, 445) for h in range(0, 12)]
m10.today_min, m10.today_max, m10.today_count = 445, 445, 12
msg = m10.undo_last_refill("2026-11-02")
print("\n===== 10. Undo the real false refill =====\n  ", msg)
check("the two phantom days are dropped", [r['d'][5:] for r in m10.daily] == ["10-26", "10-27", "10-28", "10-29", "10-30"],
      str([r['d'][5:] for r in m10.daily]))
check("anchor cleared (no earlier refill to restore)", m10.last_refill_date is None and m10.refill_count == 0)
check("everMin recomputed to 420", m10.ever_min == 420, f"everMin={m10.ever_min}")
check("the 197 purged from the live buffer, today's tally intact",
      all(mm > 370 for _, mm in m10.readings) and m10.today_min == 445 and m10.today_count == 12,
      f"today_min={m10.today_min} n={m10.today_count}")
check("forecast series is the five 420 days again", [r['mm'] for r in m10.since_refill()] == [420] * 5)

# 11. Liveness-gated polling: a dead sensor must not produce readings.
m11 = SaltMonitor()
d = "2026-10-29"
for q in range(96):
    # device reported at 08:00 and 08:07 only; every other poll sees the same lastUpdate mark
    h = q * 0.25
    mark = "08:07" if h >= 8.25 else ("08:00" if h >= 8.0 else "yesterday")
    m11.poll(420, base_ms(d) + h * HOUR, d, mark)
m11.rollup()
print("\n===== 11. Polling a sensor that reported twice all day =====")
print(f"   polls skipped={m11.polls_skipped} recorded={96 - m11.polls_skipped} daily={m11.daily} skipped_days={m11.skipped}")
check("only the polls after a fresh report count (3 of 96)", 96 - m11.polls_skipped == 3,
      f"recorded={96 - m11.polls_skipped}")
check("a day with 3 readings still clears the 3-reading gate; 2 would not",
      len(m11.daily) == 1 and m11.skipped == 0)
m11b = SaltMonitor()
m11b.last_device_mark = "yesterday"     # initialize() seeds the mark from the device
for q in range(96):
    m11b.poll(420, base_ms(d) + q * 0.25 * HOUR, d, "yesterday")
m11b.rollup()
check("a sensor that never reports yields a GAP day, not 96 readings",
      m11b.polls_skipped == 96 and m11b.daily == [] and m11b.skipped == 1,
      f"skipped_polls={m11b.polls_skipped} daily={m11b.daily}")

# 12. Owner confirms a pending refill by hand on day 1, before any auto rule.
ev = []
for i in range(8):
    d = (dt.date(2026, 3, 1) + dt.timedelta(days=i)).isoformat()
    ev += [(d, 6, 400), (d, 12, 401), (d, 18, 510)]
d = "2026-03-09"
ev += [(d, 6, 401), (d, 8, 160), (d, 9, 162), (d, 10, 270)]
m12 = SaltMonitor()
for (dd, h, mm) in ev:
    m12.reading(mm, base_ms(dd) + h * HOUR, dd)
print("\n===== 12. Owner presses Yes on the day of the refill (before rollup) =====")
check("pending opened", m12.pending is not None, str(m12.pending))
m12.confirm_refill("owner", todaykey="2026-03-09")
check("today's open tally absorbs the held readings",
      m12.today_min == 160 and m12.today_count == 4 and m12.refill_flag_today,
      f"min={m12.today_min} n={m12.today_count} flag={m12.refill_flag_today}")
check("refill registered once, dated today", m12.refill_count == 1 and m12.last_refill_date == "2026-03-09")
m12.rollup()
check("rollup records the refill day at the new level with the REFILL flag",
      m12.daily[-1]['mm'] == 160 and m12.daily[-1]['refill'] and not m12.daily[-1]['violation'],
      str(m12.daily[-1]))

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
