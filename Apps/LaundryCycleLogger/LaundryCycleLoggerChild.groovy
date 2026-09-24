/**
 *  Laundry Cycle Logger Child
 *
 *  Copyright 2026 J.R. Farrar
 *
 *  ONE JOB: tell someone the washer has finished, and be honest when it can't.
 *
 *  Everything here serves that. A per-cycle record is kept because it is the
 *  only way to know whether the alert was RIGHT - falseAlerts, the end-transition
 *  drop and the dip statistics are what tuned notifyDelaySec on evidence instead
 *  of assumption, and they are cheap. What was removed in v0.3.0 was never read
 *  by the detector: see the version history below.
 *
 *  Design notes:
 *    - In-flight accumulation lives in `state` as flat scalars. It used to live
 *      in a @Field static map because the 30 s profile buckets meant ~1700
 *      samples per cycle and state is re-serialized on every change. With the
 *      buckets gone there are about fifteen numbers, so state is the better
 *      home: it survives a hub reboot and a code save, which removes the
 *      truncated-cycle failure mode entirely rather than merely reporting it.
 *      Flat keys, not a nested map - mutating a map inside state does not
 *      reliably persist without reassigning it.
 *    - Cycle start/end are recorded at the ACTUAL power transition, not when
 *      the confirmation delay expires, so durations are not inflated by the
 *      debounce.
 *    - The alert and the record are deliberately on SEPARATE timers. A wrongly
 *      closed record is permanent; a wrongly sent message costs a glance at the
 *      machine. So the message is allowed to be faster and braver.
 *
 *  v0.1.0  2026-08-30  Initial release.
 *  v0.1.1  2026-08-31  Fixes found on the first captured cycle: bucket mean
 *                      double-counted, spin timer reset on any dip, reference
 *                      switch "off" lost to a race, energyStart taken late.
 *  v0.1.2  2026-09-04  Spin-down was gated on the single sample that closed the
 *                      grace window; post-spin drain swings 7-350 W so that was
 *                      a coin flip. Grace window alone now ends the spin.
 *  v0.2.0  2026-09-14  First action the app takes: a cycle-complete notification
 *                      on the END TRANSITION, on its own timer (notifyDelaySec)
 *                      separate from the recording confirmation (offDelayMin).
 *                      The spin-down PREDICTOR was dropped - over 9 cycles the
 *                      lead time ran 4-77 s and several cycles ended straight
 *                      out of the spin, so there was nothing to predict on.
 *                      Premature alerts counted in falseAlerts.
 *  v0.2.1  2026-09-21  Deletes orphaned settings rows via app.removeSetting().
 *  v0.3.0  2026-09-24  STRIPPED TO THE JOB, then proved unchanged.
 *
 *                      Removed - none of it was ever read by the start, end or
 *                      notify path, and all of it is still recoverable:
 *                        - spin detection (spinWatts/spinSustainSec/
 *                          spinGraceSec, spinDown*, spinHeldSec, spinLeadSec).
 *                          The predictor died in v0.2.0; spinWatts was closed
 *                          unsettleable on 2026-09-24 after four withdrawn
 *                          readings. Diagnostics for a question nobody asks.
 *                        - the 30 s profile CSVs and the File Manager plumbing
 *                          (bucketSec, writeFiles, keepFiles). hubitat-tsdb now
 *                          archives every raw event at full resolution, which is
 *                          strictly better than a downsampled copy the hub has
 *                          to prune.
 *                        - the band histogram, the reference-switch comparison
 *                          against app 2820 (settled - 2820 stamps laston late,
 *                          this app is the source of truth) and the merged-run
 *                          flag (derivable from TSDB after the fact).
 *
 *                      Added - health, because the failure that matters is the
 *                      SILENT one. If the plug dies or the plateau drifts, the
 *                      old app simply stopped alerting and said nothing:
 *                        - dead-meter liveness. No power event for deadMeterMin
 *                          (default 20 min = four missed periodic reports at
 *                          configParam171=5) means the plug or the mesh is down
 *                          and no alert will ever arrive. Reported ONCE to
 *                          healthDevices, which is deliberately a different
 *                          input from notifyDevices - the person who fixes the
 *                          plug is not the person waiting on the laundry.
 *                        - stuck-open cycle. Closed after maxCycleMin and
 *                          flagged, instead of staying open forever.
 *                        - endGapSec, the event gap across the end transition.
 *                          Measured 1-11 s over 13 real cycles; a backstop-
 *                          caught end (configParam151 too high to hear the final
 *                          drop) shows as ~300. This monitors the assumption
 *                          the whole prompt alert rests on.
 *                        - phantom guard. All 13 real cycles peaked at 486 W or
 *                          more, including the short drain/spin ones, so a cycle
 *                          peaking under notifyMinPeakW (100 W) did not happen
 *                          and gets no message. Standby was measured reaching
 *                          9.99 W against a 10 W threshold - never for more than
 *                          two consecutive readings, and only within an hour of
 *                          real use, so onDelayMin already covers it. This is
 *                          the belt to that braces.
 *                        - re-arms its own timers in initialize(). Pressing Done
 *                          mid-cycle used to unschedule endCycle and leave the
 *                          cycle open indefinitely.
 *
 *                      VALIDATED BEFORE DEPLOY, not after. harness.py replays
 *                      23,425 real power events from hubitat-tsdb through both
 *                      the v0.2.1 and v0.3.0 detectors: 13 of 13 cycles agree to
 *                      the millisecond on start, end, notified, falseAlerts,
 *                      dipCount and longestDipSec. The harness itself was first
 *                      validated against the live app's own recorded output.
 *                      v0.2.1 remains in git at 59f1501.
 */

import groovy.transform.Field
import java.text.SimpleDateFormat

@Field static final String VERSION = "0.3.0"

// Inputs removed from the page in earlier versions. Their stored rows are
// deleted by retireSettings(). Append, never remove - a name that leaves this
// list would stop being cleaned on a future reinstall-from-backup.
@Field static final List RETIRED_SETTINGS = [
    "spinEndWatts",                                     // retired in v0.1.2
    "spinWatts", "spinSustainSec", "spinGraceSec",       // retired in v0.3.0
    "bucketSec", "writeFiles", "keepFiles",              // retired in v0.3.0
    "idleWatts", "mergeGapSec", "refSwitch"              // retired in v0.3.0
]

// State keys written by earlier versions and no longer read. Same reasoning as
// RETIRED_SETTINGS: an orphaned key in statusJson reads like live state, and
// that is exactly how analysis has been misled here before. `open` matters most
// - v0.3.0 tracks an in-flight cycle in state.startMs, so a leftover `open`
// would sit there looking like a cycle that never finished.
@Field static final List RETIRED_STATE = ["open", "lastRefOnMs", "lastRefOffMs"]

definition(
    name: "Laundry Cycle Logger Child",
    namespace: "jrfarrar",
    author: "J.R. Farrar",
    description: "Tells you when one appliance has finished, and says so when it can't. Child of Laundry Cycle Logger.",
    category: "",
    parent: "jrfarrar:Laundry Cycle Logger",
    iconUrl: "",
    iconX2Url: "",
    iconX3Url: "",
    singleThreaded: true,
    importUrl: "https://raw.githubusercontent.com/jrfarrar/hubitat/master/Apps/LaundryCycleLogger/LaundryCycleLoggerChild.groovy"
)

preferences {
    page(name: "mainPage")
}

/* ------------------------------------------------------------------ UI -- */

def mainPage() {
    dynamicPage(name: "mainPage", title: " ", install: true, uninstall: true) {
        section("<b>Appliance</b>") {
            input "thisName", "text", title: "Name for this logger", submitOnChange: true, required: true
            if (thisName) app.updateLabel(thisName)
            input "meter", "capability.powerMeter", title: "Power meter device", required: true, multiple: false
        }

        section("<b>Cycle detection</b>") {
            input "startWatts", "decimal", title: "Consider it running above this many watts", defaultValue: 10, required: true
            input "onDelayMin", "decimal", title: "Confirm a start after this many minutes above", defaultValue: 3, required: true
            input "offDelayMin", "decimal", title: "Confirm an end after this many minutes below", defaultValue: 3, required: true
            paragraph "<i>Start and end are timestamped at the actual power transition, not when " +
                      "the confirmation delay expires, so the recorded duration is the real run length.</i>"
        }

        section("<b>Cycle-complete notification</b>") {
            paragraph "<i>Fires on the END TRANSITION, sooner than the recording confirmation above. " +
                      "The record still waits the full confirm delay; this is a faster, independent " +
                      "alert, because a wrongly-closed record is permanent but a wrongly-sent message " +
                      "only costs a glance at the machine.</i>"
            input "notifyEnable", "bool", title: "Send a notification when a cycle finishes", defaultValue: false, submitOnChange: true
            if (notifyEnable) {
                input "notifyDevices", "capability.notification", title: "Notify these devices",
                      required: false, multiple: true
                input "notifySwitch", "capability.switch", title: "...and/or turn this switch ON (optional)",
                      required: false, multiple: false
                input "notifyDelaySec", "number",
                      title: "Confirm the end after this many seconds below the running threshold",
                      defaultValue: 90, required: true
                paragraph "<i>Longest mid-cycle dip measured across 13 replayed cycles is 48 s, so 90 s " +
                          "is about 1.9x margin. Lower it and a long pause can fire a false 'done'; " +
                          "raise it and you give the time back. Premature fires are counted in " +
                          "falseAlerts on the record, so this is tuned on evidence.</i>"
                input "notifyMinMin", "decimal", title: "Only notify if the cycle ran at least this many minutes",
                      defaultValue: 5, required: true
                input "notifyMinPeakW", "decimal", title: "...and only if it peaked above this many watts",
                      defaultValue: 100, required: true
                paragraph "<i>Phantom guard. All 13 real cycles peaked at 486 W or more, including the " +
                          "short drain/spin ones, so anything under 100 W was not a wash and earns no " +
                          "message.</i>"
                input "notifyText", "text", title: "Message",
                      defaultValue: "Washing machine is done", required: true
                input "notifyStats", "bool", title: "Append duration and kWh to the message", defaultValue: true
            }
        }

        section("<b>Health</b>") {
            paragraph "<i>The failure that matters is the silent one: if the plug dies, the app simply " +
                      "stops alerting. These go to you, not to whoever is waiting on the laundry.</i>"
            input "healthDevices", "capability.notification", title: "Send health warnings to these devices",
                  required: false, multiple: true
            input "deadMeterMin", "number", title: "Warn if no power report arrives for this many minutes",
                  defaultValue: 20, required: true
            input "maxCycleMin", "number", title: "Close a cycle still open after this many minutes",
                  defaultValue: 240, required: true
        }

        section("<b>Data</b>") {
            input "keepCycles", "number", title: "Keep this many cycle summaries in state", defaultValue: 30, required: true
            paragraph "<i>Full-resolution event history lives in hubitat-tsdb, so this app no longer " +
                      "writes profile CSVs to File Manager.</i>"
        }

        section("<b>Status</b>") {
            paragraph statusText()
        }

        section("<b>Logging</b>") {
            input "logEnable", "bool", title: "Debug logging", defaultValue: false
            input "txtEnable", "bool", title: "Description text logging", defaultValue: true
        }
    }
}

private String statusText() {
    StringBuilder sb = new StringBuilder()
    sb.append("Version ${VERSION}<br>")
    sb.append("Cycle in progress: <b>${state.startMs ? 'yes, since ' + isoOf(state.startMs) : 'no'}</b><br>")
    sb.append("Last meter event: <b>${state.lastEventSeen ? isoOf(state.lastEventSeen) : 'none yet'}</b>")
    if (state.lastEventSeen) {
        Long age = (long)((now() - (state.lastEventSeen as Long)) / 60000L)
        sb.append(" <i>(${age} min ago)</i>")
    }
    sb.append("<br>")
    if (state.healthAlert) sb.append("<b style='color:#b00'>HEALTH: ${state.healthAlert}</b><br>")
    sb.append("Cycles recorded: <b>${state.cycles?.size() ?: 0}</b><br>")
    if (state.cycles) {
        sb.append("<br><table style='width:100%'><tr>" +
                  "<th align='left'>start</th><th align='right'>min</th><th align='right'>kWh</th>" +
                  "<th align='right'>peak W</th><th align='right'>end drop W</th>" +
                  "<th align='right'>max dip s</th><th align='right'>gap s</th><th>flags</th></tr>")
        state.cycles.reverse().take(10).each { c ->
            List flags = []
            if (c.notified) flags << "notified"
            if (c.falseAlerts) flags << "false x${c.falseAlerts}"
            if (c.stuck) flags << "STUCK"
            if (c.phantom) flags << "PHANTOM"
            if (c.truncated) flags << "truncated"
            sb.append("<tr><td>${isoOf(c.startMs)}</td>" +
                      "<td align='right'>${c.durationMin}</td>" +
                      "<td align='right'>${c.kWh ?: '-'}</td>" +
                      "<td align='right'>${c.peakW}</td>" +
                      "<td align='right'>${c.endTransitionDrop ?: '-'}</td>" +
                      "<td align='right'>${c.longestDipSec ?: 0}</td>" +
                      "<td align='right'>${c.endGapSec == null ? '-' : c.endGapSec}</td>" +
                      "<td>${flags.join(', ')}</td></tr>")
        }
        sb.append("</table>")
    }
    return sb.toString()
}

/* -------------------------------------------------------------- lifecycle */

def installed() {
    state.cycles = []
    initialize()
}

def updated() {
    unsubscribe()
    unschedule()
    initialize()
}

def initialize() {
    if (state.cycles == null) state.cycles = []

    subscribe(meter, "power", powerHandler)
    subscribe(meter, "energy", energyHandler)
    subscribe(meter, "switch", switchHandler)

    // Pressing Done mid-cycle runs updated(), which unschedules everything. In
    // v0.2.1 that left the cycle open indefinitely: the end timer was gone and
    // nothing re-armed it. Re-arm from the state we still hold.
    if (state.startMs) {
        Long elapsed = now() - (state.startMs as Long)
        Long remain = (maxCycleMin ?: 240) as Long
        remain = remain * 60000L - elapsed
        runIn(remain > 60000L ? (int)(remain / 1000L) : 60, "stuckCycle")
        if (state.belowSince) {
            Integer left = (int)(delaySecs(offDelayMin, 180) - (now() - (state.belowSince as Long)) / 1000L)
            runIn(left > 15 ? left : 15, "endCycle")
            if (notifyEnable && state.notified != true) {
                Integer nl = (int)(notifyDelay() - (now() - (state.belowSince as Long)) / 1000L)
                runIn(nl > 15 ? nl : 15, "notifyDone")
            }
        }
        logInfo "re-armed timers for the cycle open since ${isoOf(state.startMs)}"
    }

    runEvery10Minutes("healthCheck")

    retireOrphans()
    state.settingsRetiredFor = VERSION
    logInfo "initialized v${VERSION} watching ${meter?.displayName}"
}

def uninstalled() {
    logInfo "removed"
}

/* -------------------------------------------------- retired preferences */

// Inputs removed from the page keep their stored rows forever unless deleted.
// Harmless to the code, but a stale row in /installedapp/statusJson reads as
// though the setting is still live - which is exactly how orphaned state has
// misled analysis here before. Add a name to RETIRED_SETTINGS (top of file)
// when an input is retired.
//
// Runs from initialize() and, once per version, from the first powerHandler
// event after a code save - so it takes effect within one periodic power report
// without anyone having to open the page and press Done. Never called during a
// page submit, which is the one documented way removeSetting misbehaves.
private void retireOrphans() {
    try {
        RETIRED_SETTINGS.each { String n ->
            if (settings[n] != null) {
                app.removeSetting(n)
                logInfo "removed retired setting '${n}'"
            }
        }
    } catch (ex) {
        log.warn "${app.label}: retiring old settings failed: ${ex.message}"
    }
    try {
        RETIRED_STATE.each { String n ->
            if (state.containsKey(n)) {
                state.remove(n)
                logInfo "removed retired state key '${n}'"
            }
        }
    } catch (ex) {
        log.warn "${app.label}: retiring old state failed: ${ex.message}"
    }
}

// Saving code does NOT re-run initialize(), so immediately after an upgrade the
// app is still running the PREVIOUS version's subscriptions and scheduled jobs.
// For v0.3.0 that is not cosmetic: v0.2.1 subscribed to a reference switch whose
// handler no longer exists, so every event on that device would throw, and
// v0.2.1's `checkpoint` job would keep firing into a method that is gone. The
// documented fix is a Done press, which needs a browser. Doing it here instead
// means the upgrade completes on its own within one periodic power report.
private void migrate() {
    // FIRST, so the initialize() below cannot recurse back into here, and so a
    // failure can never become a retry loop in the power handler's hot path.
    state.settingsRetiredFor = VERSION
    try {
        log.warn "${app.label}: upgrading to v${VERSION} - rebuilding subscriptions and jobs"
        unsubscribe()
        unschedule()
        initialize()
    } catch (ex) {
        log.warn "${app.label}: migration failed: ${ex.message}"
    }
}

/* -------------------------------------------------------------- handlers */

def powerHandler(evt) {
    Double w
    try { w = Double.parseDouble(evt.value) } catch (ex) { return }

    Long ms = evt.getDate()?.getTime() ?: now()
    state.lastEventSeen = ms

    // The meter is reporting again - clear a standing liveness warning.
    if (state.healthAlert) {
        logInfo "meter is reporting again (was: ${state.healthAlert})"
        state.remove("healthAlert")
    }

    // A code save does not re-run initialize(), so finish the upgrade on the
    // first event after it. One string compare per event thereafter.
    if (state.settingsRetiredFor != VERSION) migrate()

    Double thr = num(startWatts, 10.0d)
    boolean above = (w >= thr)

    // Accumulate from the moment a start becomes POSSIBLE, so the onDelayMin
    // minutes before confirmation are not lost, and keep accumulating through
    // the tail. This is tested BEFORE pendingStartMs is set below, which means
    // the first candidate sample is not counted in `events`. That is an
    // off-by-one inherited from v0.2.1 and kept on purpose: `events` has 17
    // cycles of history behind it and shifting its meaning by one would make
    // the new records incomparable with the old for no gain. peakW is
    // unaffected - the first sample over the threshold is never a wash's peak.
    if (state.startMs || state.pendingStartMs) {
        state.events = (state.events ?: 0) + 1
        if (w > num(state.peakW, 0.0d)) state.peakW = w
        state.sumW = num(state.sumW, 0.0d) + w
        state.nSamples = (state.nSamples ?: 0) + 1
        // Baseline the meter at the first sample of the run, not when the start
        // is confirmed three minutes later, or the first minutes are lost.
        if (state.energyStart == null) {
            state.energyStart = state.energyLast ?: dec(meter?.currentValue("energy"))
        }
    } else if (!above && w > num(state.idleMaxW, 0.0d)) {
        // Health gauge: how close STANDBY creeps to the running threshold.
        // The "!above" test matters. Without it this catches the first running
        // sample of each start candidate - measured 51 to 942 W - and tells you
        // nothing about the plateau. Measured properly it reads 0.1 to 8.2 W
        // against a 10 W bar.
        state.idleMaxW = w
    }

    // End-of-cycle transition: the last reading at or above threshold, the drop
    // from it, and (via lastAboveMs) the event gap across it. That gap is the
    // health check on configParam151 still being low enough that the hub HEARS
    // the final drop instead of waiting for the 5-minute periodic report.
    if (above) {
        state.lastAboveW = w
        state.lastAboveMs = ms
    } else if (state.lastAboveW != null) {
        state.endTransW = state.lastAboveW
        state.endTransDelta = num(state.lastAboveW, 0.0d) - w
        state.remove("lastAboveW")
    }

    if (above) {
        if (state.startMs) {
            if (state.belowSince != null) {
                Long dip = ms - (state.belowSince as Long)
                if (dip > lng(state.longestDipMs, 0L)) state.longestDipMs = dip
                state.dipCount = (state.dipCount ?: 0) + 1
                state.remove("belowSince")
                unschedule("endCycle")
                unschedule("notifyDone")     // it was only a dip after all
                // If the alert already went out and power came back, we told
                // them too early. Count it - this is how notifyDelaySec gets
                // validated against real cycles instead of assumed.
                if (state.notified == true) {
                    state.falseAlerts = (state.falseAlerts ?: 0) + 1
                    state.notified = false
                    log.warn "${app.label}: cycle-complete alert was premature - ran again after ${(int)(dip / 1000L)}s below"
                }
            }
        } else if (!state.pendingStartMs) {
            resetRun()
            state.pendingStartMs = ms
            runIn(delaySecs(onDelayMin, 180), "startCycle")
            logDebug "possible start at ${isoOf(ms)} (${w} W)"
        }
    } else {
        if (state.startMs) {
            if (state.belowSince == null) {
                state.belowSince = ms
                runIn(delaySecs(offDelayMin, 180), "endCycle")
                // Faster, independent alert timer. The RECORD still waits for
                // offDelayMin; this only decides when to tell someone.
                if (notifyEnable && state.notified != true) runIn(notifyDelay(), "notifyDone")
            }
        } else if (state.pendingStartMs) {
            state.remove("pendingStartMs")
            unschedule("startCycle")
            logDebug "start candidate cancelled"
        }
    }
}

def energyHandler(evt) {
    BigDecimal e = dec(evt.value)
    if (e == null) return
    state.lastEventSeen = evt.getDate()?.getTime() ?: now()
    state.energyLast = e
}

def switchHandler(evt) {
    logDebug "meter switch ${evt.value}"
    // Relay turned off at the wall is not a cycle end; note it on the record.
    if (state.startMs && evt.value == "off") {
        state.relayOffMs = evt.getDate()?.getTime() ?: now()
    }
}

/* ------------------------------------------------------- cycle lifecycle */

// Reset when a start CANDIDATE opens, not when it is confirmed - see the
// accumulation comment in powerHandler. idleMaxW is deliberately NOT reset
// here: it is the standby gauge for the idle period preceding this run and
// belongs in that run's record. It is cleared once the record is written.
private void resetRun() {
    state.longestDipMs = 0L
    state.dipCount = 0
    state.falseAlerts = 0
    state.peakW = 0.0d
    state.sumW = 0.0d
    state.nSamples = 0
    state.events = 0
    state.remove("energyStart")
    state.remove("relayOffMs")
    state.remove("endTransW")
    state.remove("endTransDelta")
}

def startCycle() {
    Long startMs = (state.pendingStartMs ?: now()) as Long
    state.remove("pendingStartMs")
    state.startMs = startMs
    state.notified = false
    if (state.energyStart == null) state.energyStart = dec(meter?.currentValue("energy"))
    unschedule("notifyDone")
    // Nothing else closes a cycle if the end transition is never heard.
    runIn(((maxCycleMin ?: 240) as Integer) * 60, "stuckCycle")
    logInfo "cycle started ${isoOf(startMs)}"
}

/* -------------------------------------------------- cycle-complete alert */

// Fires on the END TRANSITION, independently of endCycle(). endCycle() waits
// offDelayMin because a wrongly-closed RECORD is permanent; this waits only
// notifyDelaySec because a wrongly-sent MESSAGE costs a glance at the machine.
// Any reading back above startWatts unschedules this, so it only fires if the
// power stayed down for the whole window.
def notifyDone() {
    if (!state.startMs) return                      // endCycle already closed it
    if (!notifyEnable) return
    if (state.notified == true) return              // already told them

    Long endMs = (state.belowSince ?: now()) as Long
    Double mins = (endMs - (state.startMs as Long)) / 60000.0d
    Double floor = num(notifyMinMin, 5.0d)
    if (mins < floor) {
        logInfo "cycle-complete alert suppressed: ran ${fmt2(mins)} min, floor is ${fmt2(floor)}"
        return
    }

    // Phantom guard. If standby power ever drifts above startWatts the app
    // would open cycles on nothing and announce a wash that never happened.
    // All 13 replayed cycles peaked at 486 W or more, including the short
    // drain/spin ones, so a 100 W floor clears the lowest real wash by ~4.9x.
    Double peak = num(state.peakW, 0.0d)
    Double peakFloor = num(notifyMinPeakW, 100.0d)
    if (peak < peakFloor) {
        log.warn "${app.label}: cycle-complete alert suppressed - peaked at only ${fmt2(peak)} W " +
                 "(floor ${fmt2(peakFloor)} W). That was not a wash; check the running threshold."
        return
    }

    String msg = (notifyText ?: "Washing machine is done")
    if (notifyStats != false) {
        BigDecimal kwh = kWhSoFar()
        msg += " (${Math.round(mins)} min" + (kwh != null ? ", ${fmt4(kwh)} kWh" : "") + ")"
    }

    notifyDevices?.each { d ->
        try { d.deviceNotification(msg) }
        catch (ex) { log.warn "${app.label}: notify failed on ${d?.displayName}: ${ex.message}" }
    }
    try { notifySwitch?.on() }
    catch (ex) { log.warn "${app.label}: notify switch failed: ${ex.message}" }

    state.notified = true
    logInfo "cycle-complete alert sent at ${isoOf(now())}: ${msg}"
}

def endCycle() {
    if (!state.startMs) return
    unschedule("notifyDone")
    // Backstop: if notifyDelaySec was set longer than offDelayMin the alert
    // timer has not fired yet. Send it now rather than never.
    if (notifyEnable && state.notified != true) notifyDone()
    closeRun(false)
}

// The cycle never ended as far as the app could tell. Close it rather than
// leave it open forever - an open cycle blocks every future start.
def stuckCycle() {
    if (!state.startMs) return
    if (state.belowSince == null) state.belowSince = now()
    log.warn "${app.label}: cycle open since ${isoOf(state.startMs)} exceeded ${maxCycleMin ?: 240} min - closing it as stuck"
    healthNotify("laundry cycle stuck open since ${isoOf(state.startMs)} - closed automatically")
    closeRun(true)
}

private void closeRun(boolean stuck) {
    Long endMs = (state.belowSince ?: now()) as Long
    Long startMs = state.startMs as Long
    unschedule("endCycle")
    unschedule("notifyDone")
    unschedule("stuckCycle")

    Integer gapSec = null
    if (state.lastAboveMs != null && state.belowSince != null) {
        gapSec = (int)(((state.belowSince as Long) - (state.lastAboveMs as Long)) / 1000L)
    }

    Double peak = num(state.peakW, 0.0d)
    Integer n = (state.nSamples ?: 0) as Integer
    Map rec = [
        startMs          : startMs,
        endMs            : endMs,
        durationMin      : fmt2((endMs - startMs) / 60000.0d),
        peakW            : fmt2(peak),
        meanW            : n ? fmt2(num(state.sumW, 0.0d) / n) : null,
        kWh              : fmt4(kWhSoFar()),
        events           : state.events ?: 0,
        endTransitionW   : fmt2(state.endTransW),
        endTransitionDrop: fmt2(state.endTransDelta),
        endGapSec        : gapSec,
        longestDipSec    : (int)(lng(state.longestDipMs, 0L) / 1000L),
        dipCount         : state.dipCount ?: 0,
        idleMaxW         : fmt2(state.idleMaxW),
        notified         : (state.notified == true),
        falseAlerts      : state.falseAlerts ?: 0,
        phantom          : peak < num(notifyMinPeakW, 100.0d),
        stuck            : stuck,
        relayOffMs       : state.relayOffMs,
        version          : VERSION
    ]
    pushCycle(rec)

    // Clear the run. idleMaxW resets here, with its value now safely on the
    // record, so it measures the NEXT idle period.
    ["startMs", "belowSince", "notified", "longestDipMs", "dipCount", "falseAlerts",
     "peakW", "sumW", "nSamples", "events", "energyStart", "relayOffMs",
     "lastAboveW", "lastAboveMs", "endTransW", "endTransDelta"].each { state.remove(it) }
    state.idleMaxW = 0.0d

    // endGapSec is the one health number that only a real cycle can produce,
    // so judge it here rather than on a timer.
    if (gapSec != null && gapSec > 120) {
        log.warn "${app.label}: the end of this cycle was not reported as a transition - " +
                 "${gapSec}s gap, so it was caught by the periodic report. configParam151 " +
                 "may be too high to hear the final drop, which makes the alert late."
    }

    logInfo "cycle ended ${isoOf(endMs)} - ${rec.durationMin} min, peak ${rec.peakW} W, " +
            "${rec.kWh ?: '?'} kWh, ${rec.events} events, end gap ${gapSec == null ? '?' : gapSec}s" +
            (stuck ? " [STUCK]" : "") + (rec.phantom ? " [PHANTOM]" : "")
}

private void pushCycle(Map rec) {
    List c = state.cycles ?: []
    c << rec
    Integer keep = (keepCycles ?: 30) as Integer
    while (c.size() > keep) c.remove(0)
    state.cycles = c
}

/* ------------------------------------------------------------- health -- */

// The failure this app cannot afford is the SILENT one. If the plug dies or the
// mesh drops it, nothing is wrong with the code - no alert simply never
// arrives, and nobody learns that until a load sits wet. Reported to
// healthDevices, deliberately a different input from notifyDevices: the person
// who fixes a plug is not the person waiting on the laundry.
def healthCheck() {
    if (state.lastEventSeen == null) return

    Integer deadMin = (deadMeterMin ?: 20) as Integer
    Long ageMin = (long)((now() - (state.lastEventSeen as Long)) / 60000L)
    if (ageMin >= deadMin && state.healthAlert == null) {
        String m = "no power report from ${meter?.displayName} for ${ageMin} min " +
                   "(expected every 5) - the cycle-complete alert cannot fire"
        state.healthAlert = m
        log.warn "${app.label}: ${m}"
        healthNotify("${app.label}: ${m}")
    }

    // Standby creeping up to the running threshold would make the app believe
    // the washer is permanently running - it would never alert again. Measured
    // standby peaks at 8.2 W against a 10 W bar, so this is quiet today; warn
    // once per new high so a real drift is not lost in repetition.
    Double thr = num(startWatts, 10.0d)
    Double idle = num(state.idleMaxW, 0.0d)
    if (idle >= thr && idle > num(state.plateauWarnedW, 0.0d)) {
        state.plateauWarnedW = idle
        String m = "standby power reached ${fmt2(idle)} W, at or above the ${fmt2(thr)} W " +
                   "running threshold - raise the threshold or cycles will be detected on nothing"
        log.warn "${app.label}: ${m}"
        healthNotify("${app.label}: ${m}")
    }
}

private void healthNotify(String msg) {
    healthDevices?.each { d ->
        try { d.deviceNotification(msg) }
        catch (ex) { log.warn "${app.label}: health notify failed on ${d?.displayName}: ${ex.message}" }
    }
}

/* ----------------------------------------------------------------- utils */

// kWh consumed since the baseline taken at the first sample of the run.
private BigDecimal kWhSoFar() {
    BigDecimal eNow = dec(state.energyLast) ?: dec(meter?.currentValue("energy"))
    BigDecimal eStart = dec(state.energyStart)
    if (eNow == null || eStart == null) return null
    BigDecimal k = eNow - eStart
    return k < 0 ? null : k        // meter was reset mid-cycle
}

private Integer notifyDelay() {
    Integer nd = (notifyDelaySec ?: 90) as Integer
    return nd < 15 ? 15 : nd
}

private Integer delaySecs(def minutes, Integer fallback) {
    try {
        Integer s = (int) Math.round(num(minutes, 3.0d) * 60.0d)
        return s > 0 ? s : fallback
    } catch (ex) { return fallback }
}

private Double num(def v, Double dflt) {
    if (v == null) return dflt
    try { return ((v as Number).doubleValue()) } catch (ex) { return dflt }
}

private Long lng(def v, Long dflt) {
    if (v == null) return dflt
    try { return ((v as Number).longValue()) } catch (ex) { return dflt }
}

private BigDecimal dec(def v) {
    if (v == null) return null
    try { return new BigDecimal(v.toString()) } catch (ex) { return null }
}

// Number-safe formatting. BigDecimal division can be awkward on the hub, so
// everything is taken to a double before formatting rather than after.
private String fmt2(def v) {
    if (v == null) return null
    try { return String.format("%.2f", ((v as Number).doubleValue())) } catch (ex) { return null }
}

private String fmt4(def v) {
    if (v == null) return null
    try { return String.format("%.4f", ((v as Number).doubleValue())) } catch (ex) { return null }
}

private String isoOf(def ms) {
    if (ms == null) return ""
    SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss")
    if (location?.timeZone) f.setTimeZone(location.timeZone)
    return f.format(new Date(ms as Long))
}

private void logDebug(String m) { if (logEnable) log.debug "${app.label}: ${m}" }

private void logInfo(String m) { if (txtEnable != false) log.info "${app.label}: ${m}" }
