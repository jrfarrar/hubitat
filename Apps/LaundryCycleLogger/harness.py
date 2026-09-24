"""
Harness for Laundry Cycle Logger Child.

Ports the app's cycle detector exactly - the startWatts crossing, the
onDelay/offDelay confirmation timers, the notification timer and its
falseAlerts counter - and replays it against the REAL captured power events
before any rewrite goes near the hub.

Precedent: running a harness on the garden-moisture app found six production
bugs a careful read-through had missed. The Salt Monitor followed the same
pattern (Apps/Softener/harness.py).

WHY THIS ONE IS DIFFERENT: this app is TIMER driven, not reading driven. The
end of a cycle is decided by a runIn() that fires on wall-clock time, and is
cancelled by any reading back above threshold. A replay that ignores timers
cannot reproduce it, so Sched below models Hubitat's semantics:
  - runIn(name) REPLACES any pending job of the same name
  - unschedule(name) cancels it
  - a timer fires at its due time, between events, and may schedule others

VALIDATION FIRST, CONCLUSIONS SECOND: before this harness is used to judge new
code, it must reproduce the CURRENT app's own recorded output - 17 cycle
boundaries and 8 notifications. A 2026-09-24 attempt to re-derive spinWatts
from these same events reproduced the app on only 3 of 9 cycles; that replay
was never validated, so its conclusions were worthless. Do not repeat that.
Run validate() and read the verdict before trusting anything else here.
"""
import io, glob, json, urllib.request, datetime

MIN = 60_000


class Sched:
    """Hubitat runIn/unschedule semantics on a simulated clock."""

    def __init__(self):
        self.jobs = {}          # name -> due_ms

    def run_in(self, secs, name, now_ms):
        self.jobs[name] = now_ms + int(secs * 1000)      # replaces

    def cancel(self, name):
        self.jobs.pop(name, None)

    def due(self, upto_ms):
        """Names due at or before upto_ms, in fire order. Removed as yielded."""
        while True:
            ready = [(t, n) for n, t in self.jobs.items() if t <= upto_ms]
            if not ready:
                return
            ready.sort()
            t, n = ready[0]
            del self.jobs[n]
            yield n, t


class Detector:
    """Port of LaundryCycleLoggerChild v0.2.1 powerHandler/startCycle/endCycle/
    notifyDone. Field names mirror the Groovy so the two can be diffed by eye."""

    def __init__(self, startWatts=10, onDelayMin=3, offDelayMin=3,
                 notifyEnable=True, notifyDelaySec=90, notifyMinMin=5):
        self.startWatts = startWatts
        self.onDelayMin, self.offDelayMin = onDelayMin, offDelayMin
        self.notifyEnable = notifyEnable
        self.notifyDelaySec, self.notifyMinMin = notifyDelaySec, notifyMinMin
        self.s = Sched()
        self.open = None            # {'startMs':..}
        self.pendingStartMs = None
        self.belowSince = None
        self.notified = False
        self.longestDipMs = 0
        self.dipCount = 0
        self.falseAlerts = 0
        self.lastAboveW = None
        self.endTransW = self.endTransDelta = None
        self.cycles = []            # completed records
        self.notifications = []     # (ms, minutes)
        self.now = 0

    # ---- timer targets -----------------------------------------------------
    def startCycle(self, ms):
        self.open = {'startMs': self.pendingStartMs or ms}
        self.pendingStartMs = None
        self.notified = False
        self.longestDipMs = 0
        self.dipCount = 0
        self.falseAlerts = 0

    def notifyDone(self, ms):
        if self.open is None or not self.notifyEnable or self.notified:
            return
        end = self.belowSince or ms
        mins = (end - self.open['startMs']) / 60000.0
        if mins < self.notifyMinMin:
            return
        self.notifications.append((ms, round(mins, 2)))
        self.notified = True

    def endCycle(self, ms):
        if self.open is None:
            return
        end = self.belowSince or ms
        self.s.cancel('notifyDone')
        if self.notifyEnable and not self.notified:
            self.notifyDone(ms)                       # v0.2.1 backstop
        self.cycles.append({
            'startMs': self.open['startMs'], 'endMs': end,
            'durationMin': round((end - self.open['startMs']) / 60000.0, 2),
            'longestDipSec': int(self.longestDipMs / 1000),
            'dipCount': self.dipCount,
            'endTransitionDrop': (None if self.endTransDelta is None
                                  else round(self.endTransDelta, 2)),
            'notified': self.notified, 'falseAlerts': self.falseAlerts,
        })
        self.open = None
        self.belowSince = None
        self.notified = False
        self.endTransW = self.endTransDelta = None
        self.lastAboveW = None


    # ---- event intake ------------------------------------------------------
    def advance_to(self, ms):
        """Fire any timers due before this moment, as the hub would."""
        for name, due in self.s.due(ms):
            self.now = due
            getattr(self, name)(due)
        self.now = ms

    def power(self, ms, w):
        self.advance_to(ms)
        above = w >= self.startWatts

        # end-of-cycle transition, same as the app's accumulate()
        if above:
            self.lastAboveW = w
        elif self.lastAboveW is not None:
            self.endTransW = self.lastAboveW
            self.endTransDelta = self.lastAboveW - w
            self.lastAboveW = None

        if above:
            if self.open:
                if self.belowSince is not None:
                    dip = ms - self.belowSince
                    self.longestDipMs = max(self.longestDipMs, dip)
                    self.dipCount += 1
                    self.belowSince = None
                    self.s.cancel('endCycle')
                    self.s.cancel('notifyDone')
                    if self.notified:               # told them, then it ran on
                        self.falseAlerts += 1
                        self.notified = False
            elif self.pendingStartMs is None:
                self.pendingStartMs = ms
                self.s.run_in(self.onDelayMin * 60, 'startCycle', ms)
        else:
            if self.open:
                if self.belowSince is None:
                    self.belowSince = ms
                    self.s.run_in(self.offDelayMin * 60, 'endCycle', ms)
                    if self.notifyEnable and not self.notified:
                        self.s.run_in(self.notifyDelaySec, 'notifyDone', ms)
            elif self.pendingStartMs is not None:
                self.pendingStartMs = None
                self.s.cancel('startCycle')

    def finish(self, ms):
        """Drain timers at the end of the capture."""
        self.advance_to(ms + 10 * MIN)


# ---- real data --------------------------------------------------------------

def load_events(path=r'C:\CLAUDE\Hubitat\GH\Apps\LaundryCycleLogger\tsdb_power.csv'):
    """Power events from hubitat-tsdb (_scratch\\tsdb_pull.py writes this file).

    NOT the collector's events_*.csv: the collector only captures while it
    believes the washer is busy, so the below-threshold events that END a
    cycle are exactly the ones it drops. Replaying that stream made ends
    drift by hours (2026-09-24). TSDB logs every event, holes included only
    where the hub itself was down.
    """
    ev = []
    for ln in io.open(path, encoding='utf-8').read().splitlines()[1:]:
        p = ln.split(',')
        if len(p) >= 2:
            try:
                ev.append((int(p[0]), float(p[1])))
            except Exception:
                pass
    ev.sort()
    return ev


def load_events_collector(pattern=r'C:\CLAUDE\Hubitat\_diag\wash\events_*.csv'):
    """The old, incomplete source. Kept for comparison only - see load_events."""
    ev = []
    for f in sorted(glob.glob(pattern)):
        for ln in io.open(f, encoding='utf-8').read().splitlines()[1:]:
            p = ln.split(',')
            if len(p) >= 3 and p[1] == 'power':
                try:
                    t = datetime.datetime.strptime(p[0][:23], '%Y-%m-%dT%H:%M:%S.%f')
                    ev.append((int(t.timestamp() * 1000), float(p[2])))
                except Exception:
                    pass
    ev.sort()
    return ev


def load_truth(app=5251, hub='192.168.13.40'):
    d = json.loads(urllib.request.urlopen(
        'http://%s/installedapp/statusJson/%d' % (hub, app), timeout=25).read().decode())
    for x in (d.get('appState') or []):
        if x.get('name') == 'cycles':
            return json.loads(x['value']) if isinstance(x['value'], str) else x['value']
    return []


def hhmm(ms):
    return datetime.datetime.fromtimestamp(ms / 1000).strftime('%m-%d %H:%M:%S')


def validate(tol_sec=90):
    """Reproduce the live app from its own event stream. Until this passes, the
    harness cannot be used to judge anything."""
    ev = load_events()
    truth_all = load_truth()
    # only cycles wholly inside the capture window can be judged
    lo, hi = ev[0][0], ev[-1][0]
    truth = [t for t in truth_all
             if int(t['startMs']) >= lo and int(t['endMs']) <= hi]
    det_all = Detector()
    for ms, w in ev:
        det_all.power(ms, w)
    det_all.finish(ev[-1][0])
    det = det_all

    print('capture window  : %s .. %s' % (hhmm(lo), hhmm(hi)))
    print('app cycles      : %d total, %d inside the window' % (len(truth_all), len(truth)))
    print('events replayed : %d' % len(ev))
    print('cycles: app recorded %d, harness detected %d' % (len(truth), len(det.cycles)))
    # Only cycles logged AFTER v0.2.0 carry a 'notified' key; earlier records
    # predate the feature, so the harness's notification on those is untestable
    # rather than wrong. Compare only where the app actually recorded one.
    notif_truth = [t for t in truth if 'notified' in t]
    print('notification records comparable: %d of %d cycles\n'
          % (len(notif_truth), len(truth)))

    # match harness cycles to app cycles by nearest start
    print('%-17s %-17s %8s %8s   %s' % ('app start', 'harness start', 'dStart', 'dEnd', 'verdict'))
    ok = miss = 0
    for t in truth:
        ts, te = int(t['startMs']), int(t['endMs'])
        best = min(det.cycles, key=lambda c: abs(c['startMs'] - ts), default=None)
        if best is None or abs(best['startMs'] - ts) > tol_sec * 1000:
            print('%-17s %-17s %8s %8s   NO MATCH' % (hhmm(ts), '-', '-', '-'))
            miss += 1
            continue
        ds = (best['startMs'] - ts) / 1000.0
        de = (best['endMs'] - te) / 1000.0
        good = abs(ds) <= tol_sec and abs(de) <= tol_sec
        ok += 1 if good else 0
        miss += 0 if good else 1
        print('%-17s %-17s %+8.1f %+8.1f   %s'
              % (hhmm(ts), hhmm(best['startMs']), ds, de, 'ok' if good else 'DRIFT'))

    print('\nboundaries within %ds: %d of %d' % (tol_sec, ok, len(truth)))

    # field-level agreement on the cycles the app recorded fully
    print('\n%-14s %-22s %-22s' % ('app start', 'app', 'harness'))
    fld_ok = fld_bad = 0
    for t in notif_truth:
        ts = int(t['startMs'])
        best = min(det.cycles, key=lambda c: abs(c['startMs'] - ts))
        a = (t.get('notified'), t.get('falseAlerts', 0), t.get('dipCount'))
        h = (best['notified'], best['falseAlerts'], best['dipCount'])
        same = a == h
        fld_ok += same
        fld_bad += not same
        print('%-14s notif=%-5s fA=%-2s dips=%-4s notif=%-5s fA=%-2s dips=%-4s %s'
              % (hhmm(ts), a[0], a[1], a[2], h[0], h[1], h[2],
                 'ok' if same else 'DIFF'))
    print('fields matching: %d of %d' % (fld_ok, len(notif_truth)))

    verdict = (ok == len(truth) and len(det.cycles) == len(truth)
               and fld_bad == 0)
    print('VERDICT: harness %s reproduce the live app'
          % ('DOES' if verdict else 'DOES NOT'))
    if not verdict:
        print('  -> do NOT use it to judge new code until this is understood')
    return det, truth, verdict


if __name__ == '__main__':
    validate()
