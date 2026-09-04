/**
 *  Laundry Cycle Logger Child
 *
 *  Copyright 2026 J.R. Farrar
 *
 *  Observes one power-metered appliance and records each run:
 *    - a compact summary per cycle in app state (rolling window)
 *    - a downsampled power/energy profile written once per cycle to File Manager
 *
 *  DELIBERATELY PASSIVE. It subscribes, accumulates and records. It sends no
 *  device commands, fires no notifications and changes no settings, so it can
 *  run alongside an existing power-monitor app with no risk of interfering.
 *
 *  Design notes:
 *    - In-flight accumulation lives in a @Field static map keyed by app id.
 *      Hubitat event handlers are separate executions, so ordinary locals do
 *      not survive between events, and putting ~1700 samples in state would
 *      re-serialize the whole map on every event.
 *    - The accumulator is checkpointed to state once a minute so a reboot
 *      costs at most a minute of a cycle, and any cycle left open is closed
 *      out and flagged truncated on the next initialize().
 *    - Cycle start/end are recorded at the ACTUAL power transition, not when
 *      the confirmation delay expires, so durations are not inflated by the
 *      debounce.
 *
 *  v0.1.0  2026-08-30  Initial release.
 *  v0.1.1  2026-08-31  Fixes found on the first captured cycle:
 *                      - bucket mean counted its first sample twice
 *                      - spin detection reset on any single dip below the
 *                        threshold, so the sustained timer never matured
 *                      - reference-switch "off" was lost to a race against
 *                        the cycle closing; now reconciled after the fact
 *                      - energyStart was taken at confirmation, not at the
 *                        first sample, undercounting kWh
 *  v0.1.2  2026-09-04  Spin-down was never recorded (spinDownCount 0 on both
 *                      captured cycles, one of which held 300+ W for 4.5 min).
 *                      The record was gated on the single sample that closed
 *                      the grace window being below spinEndWatts, but the
 *                      post-spin drain swings from ~7 W to ~350 W, so that
 *                      sample's value was effectively a coin flip - and on a
 *                      miss the sustained period was discarded with no retry.
 *                      The grace window alone now defines the end of the spin;
 *                      spinEndWatts is retired. Adds spinHeldSec to the record.
 */

import groovy.transform.Field
import java.text.SimpleDateFormat

@Field static final String VERSION = "0.1.2"

// Shared across all instances of this child app; keyed by app.id.
// Lost on hub reboot or code save - that is what the state checkpoint is for.
@Field static java.util.concurrent.ConcurrentHashMap laundryBuffers = new java.util.concurrent.ConcurrentHashMap()

definition(
    name: "Laundry Cycle Logger Child",
    namespace: "jrfarrar",
    author: "J.R. Farrar",
    description: "Records one appliance's power cycles. Child of Laundry Cycle Logger.",
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
            input "refSwitch", "capability.switch", title: "Reference switch from your existing monitor (optional - logged for comparison)",
                  required: false, multiple: false
        }

        section("<b>Cycle detection</b>") {
            input "startWatts", "decimal", title: "Consider it running above this many watts", defaultValue: 10, required: true
            input "onDelayMin", "decimal", title: "Confirm a start after this many minutes above", defaultValue: 3, required: true
            input "offDelayMin", "decimal", title: "Confirm an end after this many minutes below", defaultValue: 3, required: true
            paragraph "<i>Start and end are timestamped at the actual power transition, not when " +
                      "the confirmation delay expires, so the recorded duration is the real run length.</i>"
        }

        section("<b>End-of-cycle signature</b>") {
            paragraph "<i>Recorded only. Nothing fires on it - this is here so the heuristic can be " +
                      "checked against real cycles before anything depends on it.</i>"
            input "spinWatts", "decimal", title: "Spin considered active above (watts)", defaultValue: 300, required: true
            input "spinSustainSec", "number", title: "...once sustained for at least (seconds)", defaultValue: 60, required: true
            input "spinGraceSec", "number", title: "...and is over once it has stayed below that for (seconds)", defaultValue: 15, required: true
        }

        section("<b>Data</b>") {
            input "bucketSec", "number", title: "Profile bucket size (seconds)", defaultValue: 30, required: true
            input "writeFiles", "bool", title: "Write a profile CSV per cycle to File Manager", defaultValue: true
            input "keepFiles", "number", title: "Keep this many profile files", defaultValue: 30, required: true
            input "keepCycles", "number", title: "Keep this many cycle summaries in state", defaultValue: 30, required: true
            input "idleWatts", "decimal", title: "Treat below this as fully idle (watts)", defaultValue: 2, required: true
            input "mergeGapSec", "number", title: "Flag a possible merged run after idle for (seconds)", defaultValue: 90, required: true
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
    sb.append("Cycle in progress: <b>${state.open ? 'yes, since ' + isoOf(state.open.startMs) : 'no'}</b><br>")
    sb.append("Last meter event: <b>${state.lastEventSeen ? isoOf(state.lastEventSeen) : 'none yet'}</b><br>")
    sb.append("Cycles recorded: <b>${state.cycles?.size() ?: 0}</b><br>")
    if (state.cycles) {
        sb.append("<br><table style='width:100%'><tr>" +
                  "<th align='left'>start</th><th align='right'>min</th><th align='right'>kWh</th>" +
                  "<th align='right'>peak W</th><th align='right'>end W</th><th align='right'>max dip s</th><th>flags</th></tr>")
        state.cycles.reverse().take(10).each { c ->
            List flags = []
            if (c.truncated) flags << "truncated"
            if (c.possibleMergedRun) flags << "merged?"
            if (c.spinDownMs) flags << "spin-down"
            sb.append("<tr><td>${isoOf(c.startMs)}</td>" +
                      "<td align='right'>${c.durationMin}</td>" +
                      "<td align='right'>${c.kWh}</td>" +
                      "<td align='right'>${c.peakW}</td>" +
                      "<td align='right'>${c.endTransitionW ?: '-'}</td>" +
                      "<td align='right'>${c.longestDipSec ?: 0}</td>" +
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

    // A cycle left open by a reboot or a code save can no longer be completed:
    // the in-memory buckets are gone. Close it from the checkpoint and flag it.
    if (state.open && bufPeek()?.lastMs == null) {
        closeTruncated()
    }

    subscribe(meter, "power", powerHandler)
    subscribe(meter, "energy", energyHandler)
    subscribe(meter, "switch", switchHandler)
    if (refSwitch) subscribe(refSwitch, "switch", refSwitchHandler)

    if (state.open) runEvery1Minute("checkpoint")

    logInfo "initialized v${VERSION} watching ${meter?.displayName}"
}

def uninstalled() {
    laundryBuffers.remove(bufKey())
    logInfo "removed"
}

/* ---------------------------------------------------------------- buffer */

private String bufKey() { return "app-${app.id}" }

private Map bufPeek() { return laundryBuffers.get(bufKey()) }

private Map bufGet() {
    Map b = laundryBuffers.get(bufKey())
    if (b == null) {
        b = [
            pendingStartMs: null,
            buckets       : [],
            cur           : null,
            lastMs        : null,
            lastW         : null,
            peakW         : 0.0,
            sumW          : 0.0,
            nSamples      : 0,
            bands         : [0L, 0L, 0L, 0L, 0L],   // <10, 10-100, 100-300, 300-600, 600+
            belowSince    : null,
            longestDipMs  : 0L,
            dipCount      : 0,
            idleSince     : null,
            possibleMerged: false,
            mergeSplitMs  : null,
            lastAboveW    : null,
            endTransW     : null,
            endTransDelta : null,
            highSince     : null,
            highLastMs    : null,
            spinDownMs    : null,
            spinDownCount : 0,
            spinHeldSec   : null,
            energyStart   : null,
            energyLast    : null,
            eventCount    : 0
        ]
        laundryBuffers.put(bufKey(), b)
    }
    return b
}

private void bufClear() { laundryBuffers.remove(bufKey()) }

/* -------------------------------------------------------------- handlers */

def powerHandler(evt) {
    BigDecimal w
    try { w = new BigDecimal(evt.value) } catch (ex) { return }

    Long ms = evt.getDate()?.getTime() ?: now()
    state.lastEventSeen = ms

    Map b = bufGet()
    BigDecimal thr = (startWatts ?: 10) as BigDecimal
    boolean above = (w >= thr)

    // Accumulate from the moment a start becomes possible, so the first
    // onDelay minutes are not lost, and keep accumulating through the tail.
    if (state.open || b.pendingStartMs != null) {
        accumulate(b, ms, w)
    }

    if (above) {
        if (state.open) {
            if (b.belowSince != null) {
                Long dip = ms - b.belowSince
                if (dip > b.longestDipMs) b.longestDipMs = dip
                b.dipCount = (b.dipCount ?: 0) + 1
                b.belowSince = null
                unschedule("endCycle")
            }
        } else if (b.pendingStartMs == null) {
            b.pendingStartMs = ms
            runIn(delaySecs(onDelayMin, 180), "startCycle")
            logDebug "possible start at ${isoOf(ms)} (${w} W)"
        }
    } else {
        if (state.open) {
            if (b.belowSince == null) {
                b.belowSince = ms
                runIn(delaySecs(offDelayMin, 180), "endCycle")
            }
        } else if (b.pendingStartMs != null) {
            b.pendingStartMs = null
            unschedule("startCycle")
            bufClear()
            logDebug "start candidate cancelled"
        }
    }
}

def energyHandler(evt) {
    BigDecimal e = safeDec(evt.value)
    if (e == null) return
    state.lastEventSeen = evt.getDate()?.getTime() ?: now()
    // Do not create a buffer just for an idle energy report.
    Map b = bufPeek()
    if (b != null) b.energyLast = e
}

def switchHandler(evt) {
    logDebug "meter switch ${evt.value}"
    // Relay turned off at the wall is not a cycle end; note it on the record.
    if (state.open && evt.value == "off") {
        Map o = state.open
        o.relayOffMs = evt.getDate()?.getTime() ?: now()
        state.open = o
    }
}

def refSwitchHandler(evt) {
    // The incumbent monitor's own view, recorded so the two can be compared.
    // Recorded unconditionally: both apps use the same confirmation delay, so
    // its "off" lands within milliseconds of endCycle() clearing state.open
    // and would otherwise be lost to the race.
    Long ms = evt.getDate()?.getTime() ?: now()
    if (evt.value == "on") {
        state.lastRefOnMs = ms
    } else if (evt.value == "off") {
        state.lastRefOffMs = ms
    }
    if (state.open) {
        Map o = state.open
        if (evt.value == "on" && o.refOnMs == null) {
            o.refOnMs = ms
        } else if (evt.value == "off") {
            o.refOffMs = ms
        }
        state.open = o
    }
    logDebug "reference switch ${evt.value}"
}

/* ------------------------------------------------------------ accumulate */

private void accumulate(Map b, Long ms, BigDecimal w) {
    b.eventCount = (b.eventCount ?: 0) + 1

    // Baseline the meter at the first sample of the run, not when the start
    // is confirmed three minutes later, or the first minutes are lost.
    if (b.energyStart == null) b.energyStart = (b.energyLast ?: safeDec(meter?.currentValue("energy")))

    // time-weighted band histogram, attributed to the value we were holding
    if (b.lastMs != null && b.lastW != null) {
        Long dt = ms - b.lastMs
        if (dt > 0 && dt < 600000L) {
            b.bands[bandOf(b.lastW)] += dt
        }
    }

    // end-of-cycle transition: the last reading at or above threshold, and the drop from it
    BigDecimal thr = (startWatts ?: 10) as BigDecimal
    if (w >= thr) {
        b.lastAboveW = w
    } else if (b.lastAboveW != null) {
        b.endTransW = b.lastAboveW
        b.endTransDelta = b.lastAboveW - w
        b.lastAboveW = null
    }

    // Sustained spin, then collapse => spin-down candidate.
    // Spin power oscillates across the threshold, so a single dip must not
    // reset the sustained timer - the spin is only over once power has stayed
    // below the threshold for the whole grace period. That grace window is
    // the ONLY test for "the spin ended": the sample that happens to close
    // the window is not required to be low itself, because the post-spin
    // drain swings between single-digit and several-hundred watts and that
    // one sample's value is effectively random.
    BigDecimal sw = (spinWatts ?: 300) as BigDecimal
    Long graceMs = ((spinGraceSec ?: 15) as Long) * 1000L
    if (w >= sw) {
        if (b.highSince == null) b.highSince = ms
        b.highLastMs = ms
    } else if (b.highSince != null && b.highLastMs != null && (ms - (b.highLastMs as Long)) >= graceMs) {
        Long held = (b.highLastMs as Long) - (b.highSince as Long)
        if (held >= ((spinSustainSec ?: 60) as Long) * 1000L) {
            // keep the LAST sustained high period - a later, longer spin
            // supersedes an earlier one
            b.spinDownMs = b.highLastMs          // when the spin actually ended
            b.spinDownCount = (b.spinDownCount ?: 0) + 1
            b.spinHeldSec = (int)(held / 1000L)
            logDebug "spin-down candidate #${b.spinDownCount} at ${isoOf(b.highLastMs)} after ${(int)(held/1000)}s above ${sw} W"
        }
        b.highSince = null
        b.highLastMs = null
    }

    // fully-idle stretch inside a run => possible merged back-to-back loads
    BigDecimal idle = (idleWatts ?: 2) as BigDecimal
    if (w < idle) {
        if (b.idleSince == null) b.idleSince = ms
    } else {
        if (b.idleSince != null) {
            Long gap = ms - b.idleSince
            if (gap >= ((mergeGapSec ?: 90) as Long) * 1000L) {
                b.possibleMerged = true
                if (b.mergeSplitMs == null) b.mergeSplitMs = b.idleSince
            }
            b.idleSince = null
        }
    }

    // running stats
    if (w > (b.peakW as BigDecimal)) b.peakW = w
    b.sumW = (b.sumW as BigDecimal) + w
    b.nSamples = (b.nSamples ?: 0) + 1

    // downsampled profile bucket
    Long span = ((bucketSec ?: 30) as Long) * 1000L
    Long bStart = (ms.intdiv(span)) * span
    Map cur = b.cur
    if (cur == null || cur.ms != bStart) {
        if (cur != null) b.buckets << cur
        cur = [ms: bStart, min: w, max: w, sum: 0.0, n: 0, kwh: b.energyLast]
        b.cur = cur
    }
    if (w < (cur.min as BigDecimal)) cur.min = w
    if (w > (cur.max as BigDecimal)) cur.max = w
    cur.sum = (cur.sum as BigDecimal) + w
    cur.n = (cur.n ?: 0) + 1
    cur.kwh = b.energyLast

    b.lastMs = ms
    b.lastW = w
}

private int bandOf(BigDecimal w) {
    if (w < 10) return 0
    if (w < 100) return 1
    if (w < 300) return 2
    if (w < 600) return 3
    return 4
}

/* ------------------------------------------------------- cycle lifecycle */

def startCycle() {
    Map b = bufGet()
    Long startMs = b.pendingStartMs ?: now()
    b.pendingStartMs = null

    state.open = [
        startMs     : startMs,
        energyStart : safeDec(meter?.currentValue("energy")),
        refOnMs     : null,
        refOffMs    : null,
        relayOffMs  : null
    ]
    runEvery1Minute("checkpoint")
    logInfo "cycle started ${isoOf(startMs)}"
}

def endCycle() {
    Map b = bufPeek()
    Map open = state.open
    if (open == null) { unschedule("checkpoint"); return }

    Long endMs = (b?.belowSince) ?: now()
    unschedule("checkpoint")

    if (b == null) { closeTruncated(); return }

    // close the final bucket
    if (b.cur != null) { b.buckets << b.cur; b.cur = null }

    BigDecimal energyEnd = safeDec(meter?.currentValue("energy"))
    BigDecimal kWh = null
    if (energyEnd != null && open.energyStart != null) {
        kWh = energyEnd - (open.energyStart as BigDecimal)
        if (kWh < 0) kWh = null   // meter was reset mid-cycle
    }

    Long durMs = endMs - (open.startMs as Long)
    Map rec = [
        startMs          : open.startMs,
        endMs            : endMs,
        durationMin      : fmt2(durMs / 60000.0d),
        peakW            : fmt2(b.peakW),
        meanW            : b.nSamples ? fmt2((b.sumW as Number).doubleValue() / (b.nSamples as int)) : null,
        kWh              : kWh == null ? null : fmt4(kWh),
        events           : b.eventCount,
        endTransitionW   : b.endTransW == null ? null : fmt2(b.endTransW),
        endTransitionDrop: b.endTransDelta == null ? null : fmt2(b.endTransDelta),
        longestDipSec    : (int)((b.longestDipMs ?: 0L) / 1000L),
        dipCount         : b.dipCount ?: 0,
        bandSecs         : b.bands.collect { (int)(it / 1000L) },
        spinDownMs       : b.spinDownMs,
        spinDownCount    : b.spinDownCount ?: 0,
        spinHeldSec      : b.spinHeldSec,
        spinLeadSec      : b.spinDownMs ? (int)((endMs - (b.spinDownMs as Long)) / 1000L) : null,
        possibleMergedRun: b.possibleMerged ?: false,
        mergeSplitMs     : b.mergeSplitMs,
        refOnMs          : open.refOnMs ?: state.lastRefOnMs,
        refOffMs         : open.refOffMs,
        refLagSec        : open.refOffMs ? (int)(((open.refOffMs as Long) - endMs) / 1000L) : null,
        relayOffMs       : open.relayOffMs,
        truncated        : false,
        profile          : null
    ]

    if (writeFiles != false) {
        rec.profile = writeProfile(open, endMs, b.buckets)
    }

    pushCycle(rec)
    state.remove("open")
    bufClear()

    // The incumbent's switch may flip a moment after we close; catch it.
    if (rec.refOffMs == null && refSwitch) runIn(360, "reconcileRef")

    logInfo "cycle ended ${isoOf(endMs)} - ${rec.durationMin} min, peak ${rec.peakW} W, " +
            "${rec.kWh ?: '?'} kWh, ${rec.events} events" +
            (rec.spinLeadSec != null ? ", spin-down ${rec.spinLeadSec}s before end" : "") +
            (rec.possibleMergedRun ? " [POSSIBLE MERGED RUN]" : "")
}

def reconcileRef() {
    List c = state.cycles ?: []
    if (!c) return
    Map last = c[-1]
    if (last.refOffMs != null || last.endMs == null) return
    Long ro = state.lastRefOffMs as Long
    Long end = last.endMs as Long
    if (ro != null && ro >= (last.startMs as Long) && ro <= end + 900000L) {
        last.refOffMs = ro
        last.refLagSec = (int)((ro - end) / 1000L)
        c[-1] = last
        state.cycles = c
        logInfo "reference switch off reconciled - incumbent lagged ${last.refLagSec}s"
    }
}

private void closeTruncated() {
    Map open = state.open
    if (open == null) return
    Map rec = [
        startMs    : open.startMs,
        endMs      : state.lastEventSeen ?: now(),
        durationMin: null,
        truncated  : true,
        note       : "closed by initialize(); in-flight buffer was lost"
    ]
    pushCycle(rec)
    state.remove("open")
    bufClear()
    unschedule("checkpoint")
    log.warn "${app.label}: cycle started ${isoOf(open.startMs)} closed as truncated"
}

private void pushCycle(Map rec) {
    List c = state.cycles ?: []
    c << rec
    Integer keep = (keepCycles ?: 30) as Integer
    while (c.size() > keep) c.remove(0)
    state.cycles = c
}

def checkpoint() {
    Map b = bufPeek()
    if (b == null || state.open == null) return
    Map o = state.open
    o.cp = [
        atMs         : now(),
        peakW        : fmt2(b.peakW),
        events       : b.eventCount,
        buckets      : (b.buckets?.size() ?: 0),
        longestDipSec: (int)((b.longestDipMs ?: 0L) / 1000L)
    ]
    state.open = o
}

/* ------------------------------------------------------------ file output */

private String writeProfile(Map open, Long endMs, List buckets) {
    try {
        String fname = fileNameFor(open.startMs as Long)
        StringBuilder sb = new StringBuilder()
        sb.append("# laundry-cycle-logger v${VERSION} app=${app.label} device=${meter?.displayName}\n")
        sb.append("# start=${isoOf(open.startMs)} end=${isoOf(endMs)} bucketSec=${bucketSec ?: 30}\n")
        sb.append("epochMs,iso,meanW,minW,maxW,kWh\n")
        buckets.each { bk ->
            Double mean = bk.n ? ((bk.sum as Number).doubleValue() / (bk.n as int)) : 0.0d
            sb.append("${bk.ms},${isoOf(bk.ms)},${fmt2(mean)},${fmt2(bk.min)},${fmt2(bk.max)},")
            sb.append(bk.kwh == null ? "" : fmt4(bk.kwh))
            sb.append("\n")
        }
        uploadHubFile(fname, sb.toString().getBytes("UTF-8"))
        pruneFiles()
        logInfo "wrote ${fname} (${buckets.size()} rows)"
        return fname
    } catch (ex) {
        log.warn "${app.label}: profile write failed - ${ex.message}"
        return null
    }
}

private String filePrefix() {
    String slug = (app.label ?: "laundry").toLowerCase().replaceAll("[^a-z0-9]+", "_").replaceAll("^_|_\$", "")
    return "laundry_${slug}_"
}

private String fileNameFor(Long ms) {
    SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd_HHmm")
    if (location?.timeZone) f.setTimeZone(location.timeZone)
    return "${filePrefix()}${f.format(new Date(ms))}.csv"
}

private void pruneFiles() {
    try {
        Integer keep = (keepFiles ?: 30) as Integer
        String prefix = filePrefix()
        List names = []
        getHubFiles()?.each { f ->
            String n = (f instanceof Map) ? (f.name ?: f.fileName ?: f.get("name")) : "${f}"
            if (n && n.startsWith(prefix)) names << n
        }
        names = names.sort()
        while (names.size() > keep) {
            String victim = names.remove(0)
            deleteHubFile(victim)
            logDebug "pruned ${victim}"
        }
    } catch (ex) {
        log.warn "${app.label}: prune failed - ${ex.message}"
    }
}

/* ----------------------------------------------------------------- utils */

private Integer delaySecs(def minutes, Integer fallback) {
    try {
        Integer s = (int) Math.round(((minutes ?: 3) as BigDecimal).doubleValue() * 60.0d)
        return s > 0 ? s : fallback
    } catch (ex) { return fallback }
}

private BigDecimal safeDec(def v) {
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
