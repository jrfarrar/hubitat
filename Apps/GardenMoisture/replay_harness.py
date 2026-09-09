#!/usr/bin/env python3
"""
replay_harness.py - replay REAL recorded events through a port of the garden
app's logic, to find out what that logic actually did.

WHY THIS EXISTS
---------------
The Groovy simulator (GardenMoistureSim + GardenSimSensor) found ~18 defects,
six of them silent data-corrupters. Then real hardware found four more in a
week, and every one was invisible to the simulator for the same structural
reason: *a simulation encodes what the author BELIEVED the hardware does, so it
can only find bugs in the logic, never bugs in the belief.*

  - the sim device always started at a plausible value  -> never saw a device
    reading 0 before its first data, which banked a stress anchor of 0%
  - the sim soil model capped at field capacity          -> never saw saturation
    above FC, which the daily estimator would have banked as FC
  - the sim rain counter only ever increased            -> never saw the WS90
    reset its event counter mid-storm, which attributed a whole day's rain to
    one event
  - the sim device never went away                      -> never saw the probe
    orphan 43 times in four days while currentValue() kept returning a frozen
    reading that the sampler wrote to CSV as though fresh

Real recorded events contain all of those, because they happened. This harness
replays them.

It is a PORT, not the Groovy. That is deliberate and J.R. agreed the tradeoff:
"you are just trying to determine what the logic did." It is a diagnostic, not
a proof of the shipped code. Which makes VALIDATION the important part - see
--validate below.

SOURCE OF TRUTH
---------------
TimescaleDB at 192.168.13.33:5432 (db `hubitat`, user `reader`, password in
%USERPROFILE%\\hubitat-tsdb.env). Every event from all five hubs.
  hub 'zwave' = .38 (native Ecowitt devices)   hub 'control' = .40
  device 3243 = Garden Moisture Sensor  (soilAD, humidity, orphaned, status)
  device 3242 = PWS / WS90              (raining, rainRate, temperature, ...)

CAVEATS, so nobody trusts this further than it deserves
-------------------------------------------------------
1. The TSDB starts 2026-09-05. The 2026-09-02 storms - including `ev1`, whose
   silent rejection is still unexplained - CANNOT be replayed from here.
2. `control` (.40) mesh mirrors emit NO rows into the TSDB. Everything comes
   from the .38 native devices. The app subscribes to the mirrors, so event
   TIMING may differ slightly across the mesh hop.
3. The TSDB has a `gap` table recording its own blind windows. A quiet stretch
   is not proof of a quiet sensor - check it.
4. `source='SEED'` rows are startup snapshots, not events. Always filtered out.

USAGE
    python replay_harness.py --since 2026-09-05          # replay + report
    python replay_harness.py --validate                  # compare vs the app
    python replay_harness.py --orphan-report             # data-integrity audit
"""

import os
import sys
import json
import argparse
import datetime as dt
from urllib.request import urlopen

TSDB_HOST = "192.168.13.33"
TSDB_PORT = 5432
TSDB_DB = "hubitat"
TSDB_USER = "reader"

SOIL_HUB, SOIL_DEV = "zwave", 3243
RAIN_HUB, RAIN_DEV = "zwave", 3242

APP_STATUS_URL = "http://192.168.13.40/installedapp/statusJson/5259"


# --------------------------------------------------------------- data load --

def _password():
    envp = os.path.join(os.environ["USERPROFILE"], "hubitat-tsdb.env")
    for line in open(envp):
        line = line.strip()
        if line and not line.startswith("#") and "=" in line:
            k, v = line.split("=", 1)
            if k.strip() == "READER_PASSWORD":
                return v.strip().strip('"').strip("'")
    raise RuntimeError("READER_PASSWORD not found in " + envp)


def load_events(since):
    """Every relevant event, in timestamp order, as (ts, attr, value_text)."""
    import psycopg
    conn = psycopg.connect(host=TSDB_HOST, port=TSDB_PORT, dbname=TSDB_DB,
                           user=TSDB_USER, password=_password(), connect_timeout=15)
    cur = conn.cursor()
    cur.execute("""
        select ts, attr, value_text, value_num
          from event
         where coalesce(source,'') <> 'SEED'
           and ts >= %s
           and ( (hub=%s and device_id=%s and attr in ('soilAD','humidity','orphaned','battery'))
              or (hub=%s and device_id=%s and attr in ('raining','rainRate','temperature')) )
         order by ts
    """, (since, SOIL_HUB, SOIL_DEV, RAIN_HUB, RAIN_DEV))
    rows = cur.fetchall()
    # The TSDB's own blind windows - a quiet stretch here may be the COLLECTOR,
    # not the sensor. Never silently treat one as the other.
    gaps = []
    try:
        cur.execute("select * from gap limit 200")
        cols = [d[0] for d in cur.description]
        gaps = [dict(zip(cols, r)) for r in cur.fetchall()]
    except Exception as e:
        print("  (gap table unreadable: %s)" % e, file=sys.stderr)
    conn.close()
    return rows, gaps


# ------------------------------------------------------------ ported logic --

class GardenLogic:
    """
    A port of the parts of GardenMoistureLoggerChild that decide things:
    rise detection, event close, rain attribution, orphan handling, staleness.

    Deliberately NOT ported: CSV writing, file manager, forecast fetch, the
    Hubitat scheduler. Those are plumbing, not decisions.

    Defaults mirror the app's settings as configured on 2026-09-08.
    """

    def __init__(self, rise_threshold=4, rise_window_min=45, settle_min=60,
                 out_of_ground_pct=6, stale_hours=6, stale_max_hours=48,
                 orphan_guard=True):
        self.rise_threshold = rise_threshold
        self.rise_window_min = rise_window_min
        self.settle_min = settle_min
        self.out_of_ground_pct = out_of_ground_pct
        self.stale_hours = stale_hours
        self.stale_max_hours = stale_max_hours
        self.orphan_guard = orphan_guard          # False = pre-v0.5.0 behaviour

        self.pct = None
        self.ad = None
        self.raining = "false"
        self.rain_daily = None
        self.temp_f = None
        self.recent = []            # [{ts, pct, ad, rain_daily}]
        self.open_event = None
        self.events = []
        self.orphaned = False
        self.orphan_since = None
        self.orphan_spans = []      # [(start, end)]
        self.stale_periods = []
        self.change_gaps = []
        self.last_change = None
        self.last_event_ts = None
        self.frozen_samples = 0     # EVENTS arriving while orphaned
        self.frozen_polls = 0       # 5-min POLLS that used a frozen value
        self.window_start = None

    # -- helpers -----------------------------------------------------------

    def _orphan_spanned(self, t0):
        if self.orphaned:
            return True
        return any(end >= t0 for _, end in self.orphan_spans)

    def _push_recent(self, ts):
        # Only rows with a real percentage. soilAD arrives before the first
        # humidity event, so early rows would carry pct=None - the same "value
        # before the first real data" shape that banked a 0% stress anchor on
        # the live install.
        if self.pct is None:
            return
        self.recent.append({"ts": ts, "pct": self.pct, "ad": self.ad,
                            "rain_daily": self.rain_daily})
        cutoff = ts - dt.timedelta(minutes=self.rise_window_min + 10)
        self.recent = [r for r in self.recent if r["ts"] >= cutoff]

    # -- the decisions -----------------------------------------------------

    def _check_rise(self, ts):
        if self.pct is None or not self.recent:
            return
        low = min(self.recent, key=lambda r: (r["pct"], r["ts"]))
        # last sample tying the minimum, not the first (a real bug, v0.1.9)
        low = [r for r in self.recent if r["pct"] == low["pct"]][-1]
        rise = self.pct - low["pct"]

        if self.open_event is None:
            # a rise from a probe-out / no-data baseline is not a wetting event
            if low["pct"] <= self.out_of_ground_pct:
                return
            if rise >= self.rise_threshold:
                self.open_event = {
                    "t0": low["ts"], "start_pct": low["pct"], "start_ad": low["ad"],
                    "rain_daily_t0": low["rain_daily"],
                    "peak_pct": self.pct, "peak_ts": ts,
                }
        else:
            if self.pct > self.open_event["peak_pct"]:
                self.open_event["peak_pct"] = self.pct
                self.open_event["peak_ts"] = ts
            elif (ts - self.open_event["peak_ts"]).total_seconds() > self.settle_min * 60:
                self._close_event(ts)

    def _close_event(self, ts):
        oe = self.open_event
        self.open_event = None
        mag = oe["peak_pct"] - oe["start_pct"]

        # rain attribution: rainDaily IS monotonic within a day; rainEvent is
        # NOT (the WS90 resets it after a dry gap - v0.3.1).
        rain_in, rain_src = None, "unavailable"
        before, after = oe["rain_daily_t0"], self.rain_daily
        if before is not None and after is not None and after >= before:
            rain_in, rain_src = round(after - before, 3), "daily-delta"

        spanned = self._orphan_spanned(oe["t0"])
        self.events.append({
            "t0": oe["t0"], "closed": ts,
            "start_pct": oe["start_pct"], "peak_pct": oe["peak_pct"],
            "magnitude": mag,
            "rain_inches": rain_in, "rain_source": rain_src,
            "classification": "rain" if self.raining == "true" or (rain_in or 0) > 0 else "manual",
            "spanned_orphan": spanned,
            "rise_min": round((oe["peak_ts"] - oe["t0"]).total_seconds() / 60.0, 1),
        })

    def _check_stale(self, ts):
        if self.last_event_ts is None:
            return
        # v0.4.1/0.4.2: window adapts to how often this sensor really changes;
        # below three recorded changes there is no basis, so only the ceiling.
        if len(self.change_gaps) >= 3:
            med = sorted(self.change_gaps)[len(self.change_gaps) // 2]
            win = max(self.stale_hours * 3600, med * 3)
        else:
            win = self.stale_max_hours * 3600
        win = min(win, self.stale_max_hours * 3600)
        quiet = (ts - self.last_event_ts).total_seconds()
        if quiet > win:
            self.stale_periods.append({"at": ts, "quiet_h": round(quiet / 3600, 2),
                                       "window_h": round(win / 3600, 2)})

    def sample(self, ts):
        """One sampleTick(). THE APP IS NOT PURELY EVENT-DRIVEN - it polls
        currentValue() every 5 minutes and pushes the result into `recent`.

        Replaying only the event stream misses this entirely: on 2026-09-09 the
        app opened an event from startPct 47, but NO humidity event ever carried
        47 - it came from a poll. An earlier version of this harness reported
        "0 wetting events" as a finding when it was a defect in the harness.
        Caught only by --validate against the app's own records.

        It also models the data-integrity problem exactly: currentValue() keeps
        returning the last reading while the probe is orphaned, so the poll
        writes a frozen number as though it were fresh.
        """
        if self.pct is None:
            return
        if self.orphaned:
            self.frozen_polls += 1
            if self.orphan_guard:
                return
        self._push_recent(ts)
        self._check_rise(ts)
        self._check_stale(ts)

    # -- the event pump ----------------------------------------------------

    def feed(self, ts, attr, value):
        if attr == "orphaned":
            now_orph = (value == "true")
            if now_orph and not self.orphaned:
                self.orphaned, self.orphan_since = True, ts
            elif not now_orph and self.orphaned:
                self.orphaned = False
                self.orphan_spans.append((self.orphan_since, ts))
                self.orphan_since = None
            return

        if attr == "raining":
            self.raining = value
            return
        if attr == "temperature":
            self.temp_f = float(value)
            return
        if attr == "rainRate":
            return

        if attr in ("humidity", "soilAD"):
            self.last_event_ts = ts
            prev = (self.pct, self.ad)
            if attr == "humidity":
                self.pct = float(value)
            else:
                self.ad = float(value)
            if (self.pct, self.ad) != prev:
                if self.last_change is not None:
                    self.change_gaps.append((ts - self.last_change).total_seconds())
                    self.change_gaps = self.change_gaps[-10:]
                self.last_change = ts

            # THE POINT OF v0.5.0: while orphaned the app's sampler kept writing
            # the frozen value as though it were fresh. Count what that cost.
            if self.orphaned:
                self.frozen_samples += 1
                if self.orphan_guard:
                    return

            self._push_recent(ts)
            self._check_rise(ts)
            self._check_stale(ts)


# ----------------------------------------------------------------- reports --

def replay(since, orphan_guard=True, sample_min=5):
    """Replay events AND the 5-minute sampler, interleaved in timestamp order.

    Both are needed: the app is fed by subscriptions and by a polling tick, and
    its rise detection reads a buffer filled by both.
    """
    rows, gaps = load_events(since)
    g = GardenLogic(orphan_guard=orphan_guard)
    if not rows:
        return g, rows, gaps

    ticks = []
    t, end = rows[0][0], rows[-1][0]
    step = dt.timedelta(minutes=sample_min)
    while t <= end:
        ticks.append(t)
        t += step

    g.window_start = rows[0][0]
    i = 0
    for ts, attr, vt, vn in rows:
        while i < len(ticks) and ticks[i] <= ts:
            g.sample(ticks[i])
            i += 1
        g.feed(ts, attr, vt if vt is not None else vn)
    while i < len(ticks):
        g.sample(ticks[i])
        i += 1
    return g, rows, gaps


def report(g, rows, gaps):
    print("events replayed      : %d" % len(rows))
    print("TSDB blind windows   : %d %s" % (len(gaps), "(check before trusting quiet stretches)" if gaps else ""))
    print()
    print("ORPHAN OUTAGES       : %d" % len(g.orphan_spans))
    if g.orphan_spans:
        tot = sum((b - a).total_seconds() for a, b in g.orphan_spans) / 3600.0
        longest = max((b - a).total_seconds() for a, b in g.orphan_spans) / 60.0
        print("  total offline      : %.1f h    longest: %.0f min" % (tot, longest))
        print("  5-min POLLS that used a frozen value as though fresh: %d" % g.frozen_polls)
        print("     (= rows written to the CSV that are not measurements)")
    print()
    print("WETTING EVENTS       : %d closed" % len(g.events))
    if g.open_event:
        oe = g.open_event
        print("  STILL OPEN at end of data: t0 %s  start %.0f%% (AD %s) -> peak %.0f%% at %s" % (
            oe["t0"].strftime("%m-%d %H:%M:%S"), oe["start_pct"], oe["start_ad"],
            oe["peak_pct"], oe["peak_ts"].strftime("%H:%M:%S")))
        print("     (an event is only recorded on CLOSE, %d min after its peak)" % g.settle_min)
    for e in g.events:
        print("  %s  %2.0f -> %2.0f (+%2.0f)  rain=%-6s src=%-12s %s" % (
            e["t0"].strftime("%m-%d %H:%M"), e["start_pct"], e["peak_pct"], e["magnitude"],
            e["rain_inches"], e["rain_source"],
            "SPANS OUTAGE - excluded from anchors" if e["spanned_orphan"] else ""))
    print()
    print("STALENESS would fire : %d times" % len(g.stale_periods))
    for s in g.stale_periods[:5]:
        print("  %s quiet %.1f h against a %.1f h window" % (
            s["at"].strftime("%m-%d %H:%M"), s["quiet_h"], s["window_h"]))


def validate(g):
    """Compare the port against what the app ACTUALLY recorded.

    This is what makes the port worth anything. If it cannot reproduce the
    app's own events over the same window, its conclusions are just a second
    guess wearing a lab coat.
    """
    st = {s["name"]: s.get("value") for s in
          json.loads(urlopen(APP_STATUS_URL, timeout=30).read().decode())["appState"]}
    real = st.get("events") or []
    # Horizon is the REPLAY WINDOW, not the port's own findings - deriving it
    # from g.events made an empty port silently compare against nothing.
    horizon = g.window_start
    if horizon is None:
        print("  nothing replayed"); return
    real = [e for e in real
            if dt.datetime.fromtimestamp(e["t0"] / 1000, tz=horizon.tzinfo) >= horizon]

    print("app recorded %d event(s) in the replay window; port found %d" % (len(real), len(g.events)))
    for e in real:
        t0 = dt.datetime.fromtimestamp(e["t0"] / 1000, tz=horizon.tzinfo)
        match = [p for p in g.events if abs((p["t0"] - t0).total_seconds()) < 900]
        if not match:
            print("  MISSED  app event %s (mag %s) has no counterpart in the port" %
                  (t0.strftime("%m-%d %H:%M"), e.get("magnitude")))
            continue
        p = match[0]
        flag = "ok " if p["magnitude"] == e.get("magnitude") else "DIFF"
        print("  %s app %s mag=%s rain=%s | port mag=%s rain=%s" % (
            flag, t0.strftime("%m-%d %H:%M"), e.get("magnitude"), e.get("rainInches"),
            p["magnitude"], p["rain_inches"]))
    for p in g.events:
        if not any(abs((p["t0"] - dt.datetime.fromtimestamp(e["t0"] / 1000, tz=horizon.tzinfo)).total_seconds()) < 900
                   for e in real):
            print("  EXTRA   port invented an event at %s that the app never recorded" %
                  p["t0"].strftime("%m-%d %H:%M"))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--since", default="2026-09-05")
    ap.add_argument("--validate", action="store_true")
    ap.add_argument("--orphan-report", action="store_true")
    ap.add_argument("--no-orphan-guard", action="store_true",
                    help="replay with pre-v0.5.0 behaviour, to see what the guard changes")
    a = ap.parse_args()

    g, rows, gaps = replay(a.since, orphan_guard=not a.no_orphan_guard)
    report(g, rows, gaps)

    if a.validate:
        print("\n--- VALIDATION against the app's own recorded events ---")
        validate(g)

    if a.orphan_report:
        print("\n--- ORPHAN / DATA INTEGRITY ---")
        g2, _, _ = replay(a.since, orphan_guard=False)
        print("with the v0.5.0 orphan guard : %d events" % len(g.events))
        print("without it (pre-v0.5.0)      : %d events" % len(g2.events))
        extra = len(g2.events) - len(g.events)
        if extra:
            print("  -> the guard suppresses %d event(s) that were measured across a frozen reading" % extra)
        else:
            print("  -> no difference over this window; the guard costs nothing here")


if __name__ == "__main__":
    main()
