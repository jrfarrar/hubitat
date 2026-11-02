/**
 *  Water Softener Salt Monitor
 *
 *  Tracks brine-tank salt level from a Shelly BLU Distance (BTHome) sensor whose
 *  readings are known-unreliable, and predicts when salt will run low.
 *
 *  ---------------------------------------------------------------------------
 *  WHY THIS APP DOES NOT SMOOTH
 *  ---------------------------------------------------------------------------
 *  Measured 2026-09-05 on device "Water Softener (BLE)": rawDistance does not
 *  drift, it FLIPS between two tight clusters several times a day --
 *
 *      241-246 mm  (9.5-9.7 in)   and   351-355 mm  (13.8-14.0 in)
 *
 *  a 110 mm / 4.5 in swing, with nothing physically in the tank 4.5 in below the
 *  salt. The far cluster is a far-echo artifact: a time-of-flight sensor that
 *  gets a weak or scattered return off a lumpy, angled salt surface falls back to
 *  a LONGER path. Light cannot arrive early, so the error is ONE-SIDED -- the
 *  sensor can only ever over-report distance, never under-report it.
 *
 *  Every smoothing mode in the driver (stable / average / outlier) assumes
 *  symmetric noise around a true value. Averaging 241 and 351 yields 296 mm, a
 *  number that has never been true. Outlier rejection with a 50 mm threshold
 *  against a 110 mm split locks onto whichever cluster it saw first and then
 *  rejects the other FOREVER, including genuine salt movement -- a silent wrong
 *  answer, which is worse than noisy data.
 *
 *  The correct estimator for one-sided error is the MINIMUM over a window.
 *  The far-echo readings simply never win. Leave the driver's smoothingMode on
 *  "none" and feed this app the unsmoothed rawDistance.
 *
 *  ---------------------------------------------------------------------------
 *  THE SELF-CHECK  (this app tells you when its own assumption is wrong)
 *  ---------------------------------------------------------------------------
 *  If the error really is one-sided, then between refills the daily minimum can
 *  only INCREASE (salt depletes -> sensor is farther away). Any daily minimum
 *  that falls by more than a few mm without a refill-sized step contradicts the
 *  model. Those are counted in state.assumptionViolations and surfaced on the
 *  status page. Zero after a few weeks means the model holds. A rising count
 *  means the far-cluster reasoning is wrong and the estimator needs rethinking
 *  -- you get told, instead of quietly getting a bad forecast.
 *
 *  ---------------------------------------------------------------------------
 *  WHAT THE FIRST MONTH ON REAL HARDWARE TAUGHT (2026-11-02)
 *  ---------------------------------------------------------------------------
 *  Two things the harness could not know, because they are belief bugs, not
 *  logic bugs:
 *
 *  1. The sensor goes dark for a day or two at a time (fixed by re-seating its
 *     battery). A poll of currentValue() on a dead device returns the LAST value
 *     it ever sent, so v1.1 logged 96 "readings" on a day the device sent none.
 *     The stale alert could therefore never fire while polling was on, and a
 *     dead day sailed through the minReadingsPerDay gate. v1.2 only records a
 *     poll when the driver's lastUpdate attribute has advanced since the last
 *     poll -- that attribute moves on every report even when the distance does
 *     not, and it crosses Hub Mesh.
 *
 *  2. The error is NOT purely one-sided. On 10-31 and 11-01 the sensor produced
 *     three short bursts (15-35 min, 3-6 readings each) of 192-197 mm against a
 *     settled 445 mm, then went straight back. That is an UNDER-report, and the
 *     v1.1 refill rule (two readings 50 mm below the reference inside 6 h) took
 *     it as a refill. A real refill is permanent; a burst is not. v1.2 therefore
 *     QUARANTINES refill-sized drops: they are held aside as a PENDING refill,
 *     the owner is asked to confirm, and the daily series is only rewritten to
 *     the new level once the refill is confirmed (by the owner, or automatically
 *     after the low readings have dominated three whole days). A pending refill
 *     whose day ends with the old level still winning is discarded as bad data.
 *     Nothing in the minimum estimator changed.
 *
 *  ---------------------------------------------------------------------------
 *  Author: J.R. Farrar
 *
 *  Version: 1.2.0  (2026-11-02)
 *    - Polls are gated on the driver's lastUpdate attribute: a dead sensor no
 *      longer produces readings, so the stale alert and the min-readings gate
 *      work as designed.
 *    - Refill detection is now two-stage. A refill-sized drop opens a PENDING
 *      refill (notification + Yes/No buttons on the app page) and its readings
 *      are quarantined until confirmed. Confirmed by the owner, or automatically
 *      after 3 whole days at the new level; dismissed automatically if the old
 *      level still dominates at the day's rollup. Quarantined readings never
 *      touch the daily minima, everMin or the trend.
 *    - "Undo last refill" button: reverts the refill, drops the daily rows it
 *      produced (and the matching CSV rows), restores the previous reference.
 *      Written for the false refill of 2026-10-31.
 *    - state.refillHistory (newest last) so undo can restore the previous anchor.
 *
 *  Version: 1.1.0  (2026-09-21)
 *    - REMOVED regeneration tracking (vibration handler, septic-pump switch
 *      correlation, regen CSV, the regen state keys and status block). It
 *      existed to test whether a softener regeneration was what made the septic
 *      pump run late at night. The septic pump measured normal -- 1.96 runs/day
 *      baseline vs 2.07 over the trailing fortnight -- so the question is closed
 *      and a regeneration is 2-5 % of daily flow either way. The code was
 *      answering a question nobody is asking any more. Estimator untouched.
 *    - ADDED importUrl (this app was hub-only with no import path).
 *
 *  Version: 1.0.0  (2026-09-05)
 */

import java.math.RoundingMode

definition(
    name: "Water Softener Salt Monitor",
    namespace: "jrfarrar",
    author: "J.R. Farrar",
    description: "Brine-tank salt level and refill prediction, using a one-sided minimum estimator that tolerates a flaky ToF distance sensor.",
    category: "Convenience",
    iconUrl: "",
    iconX2Url: "",
    iconX3Url: "",
    importUrl: "https://raw.githubusercontent.com/jrfarrar/hubitat/master/Apps/Softener/SaltMonitor.groovy"
)

preferences {
    page(name: "pageConfig")
}

// ============================================================================
//   CONFIGURATION PAGE
// ============================================================================

def pageConfig() {
    dynamicPage(name: "pageConfig", title: "", install: true, uninstall: true, refreshInterval: 0) {

        section(getFormat("header-green", "Status")) {
            paragraph getStatusDashboardHtml()
        }

        if (state.pendingRefill) {
            section(getFormat("header-green", "Was the tank refilled?")) {
                paragraph getPendingRefillHtml()
                input "btnRefillYes", "button", title: "Yes, I added salt"
                input "btnRefillNo", "button", title: "No, that is bad data"
                paragraph "<small style='color:#666;'>Until you answer, the low readings are held " +
                          "aside and the trend keeps using the old level. If the low readings win " +
                          "three whole days in a row the refill is confirmed automatically; if the old " +
                          "level wins a day, the low readings are discarded as bad data.</small>"
            }
        }

        section(getFormat("header-green", "Sensor")) {
            input "saltSensor", "capability.sensor", title: "Brine tank distance sensor",
                    required: true, submitOnChange: true
            input "distAttr", "enum", title: "Distance attribute to read",
                    options: [["rawDistance": "rawDistance (unsmoothed, recommended)"],
                              ["distanceMm": "distanceMm (driver-smoothed)"]],
                    defaultValue: "rawDistance", required: true, submitOnChange: true
            paragraph "<small style='color:#666;'>Use <b>rawDistance</b> and leave the driver's " +
                      "smoothing on <b>none</b>. This app does its own filtering, and it is a " +
                      "different kind of filtering than the driver offers -- see the notes at the " +
                      "top of the app code.</small>"
            input "floorMm", "number", title: "Reject readings below this (mm)",
                    defaultValue: 40, required: true
            input "ceilMm", "number", title: "Reject readings above this (mm)",
                    defaultValue: 1500, required: true
            input "staleHours", "number", title: "Warn if no reading for this many hours",
                    defaultValue: 24, required: true
            input "pollMinutes", "number", title: "Poll the sensor every X minutes",
                    defaultValue: 15, required: true
            paragraph "<small style='color:#666;'><b>Polling is not optional here.</b> Hubitat only " +
                      "raises an event when a value <i>changes</i>, so a day the reading sits still " +
                      "produces no data at all, and a day it happens to sit on a far echo produces " +
                      "a single poisoned sample that becomes that day's \"minimum\". Polling gives " +
                      "the minimum enough samples to be worth taking. Measured on real data: " +
                      "subscription alone yielded 1-3 samples a day and one fully poisoned day in six.</small>"
        }

        section(getFormat("header-green", "Estimator")) {
            input "minWindowHours", "number", title: "Salt estimate = minimum over the last X hours",
                    defaultValue: 24, required: true
            input "refillDropMm", "number", title: "A drop of at least this much (mm) is a refill",
                    defaultValue: 50, required: true
            input "refillWindowHours", "number", title: "Refill confirmation window (hours)",
                    defaultValue: 6, required: true
            input "refillConfirmReadings", "number", title: "Readings that must confirm the new low",
                    defaultValue: 2, required: true
            input "minReadingsPerDay", "number", title: "Skip a day with fewer than X readings",
                    defaultValue: 3, required: true
            input "violationEpsilonMm", "number", title: "Ignore daily-minimum decreases smaller than (mm)",
                    defaultValue: 5, required: true
            paragraph "<small style='color:#666;'>A refill needs at least " +
                      "<b>${settings.refillConfirmReadings ?: 2}</b> readings that are themselves " +
                      "below the new level -- not merely that many readings in the window, which a " +
                      "single fluke can satisfy. A day with fewer than " +
                      "<b>${settings.minReadingsPerDay ?: 3}</b> readings is recorded as a gap " +
                      "rather than a data point, because one far echo would otherwise become that " +
                      "whole day's minimum. Decreases smaller than the epsilon are sensor " +
                      "quantisation; anything larger that is not a refill is an assumption violation.</small>"
        }

        section(getFormat("header-green", "Calibration")) {
            paragraph getCalibrationHtml()
            input "fullMm", "decimal", title: "Distance when the tank is FULL (mm)", required: false
            input "emptyMm", "decimal", title: "Distance when the tank NEEDS SALT (mm)", required: false
            input "btnMarkFilled", "button", title: "I just filled the tank"
            input "btnCaptureFull", "button", title: "Capture current reading as FULL"
            input "btnCaptureEmpty", "button", title: "Capture current reading as NEEDS SALT"
            if (state.lastRefillDate) {
                input "btnUndoRefill", "button",
                        title: "Undo last refill (${state.lastRefillDate}) and drop its data"
            }
            if (state.undoResult) paragraph "<small><b>Last undo:</b> ${state.undoResult}</small>"
            paragraph "<small style='color:#666;'>You do not need these to start -- the app logs " +
                      "level and depletion rate regardless. They are what turn the trend into a " +
                      "percentage and a predicted date. If you do not know them yet, press " +
                      "<b>I just filled the tank</b> at your next top-off and " +
                      "<b>Capture current reading as NEEDS SALT</b> when it next looks low.</small>"
        }

        section(getFormat("header-green", "Alerts")) {
            input "notifyDevice", "capability.notification", title: "Send alerts to",
                    required: false, multiple: true, submitOnChange: true
            input "lowSwitch", "capability.switch", title: "Low-salt switch (optional, latching)",
                    required: false
            input "warnDays", "number", title: "Warn when predicted to run out within X days",
                    defaultValue: 14, required: true
            input "notifyLow", "bool", title: "Notify when salt is at or below the NEEDS SALT point",
                    defaultValue: true
            input "notifyPredicted", "bool", title: "Notify on predicted runout", defaultValue: true
            input "notifyStale", "bool", title: "Notify if the sensor goes quiet", defaultValue: true
            input "btnClearLow", "button", title: "Acknowledge & clear low-salt alert"
        }

        if (state.dailySeries) {
            section(getFormat("header-green", "Recent Daily Minimums")) {
                paragraph getDailyTableHtml(14)
            }
        }

        section(getFormat("header-green", "Logging & Maintenance")) {
            input "enableCsvLogging", "bool", title: "Enable CSV file logging",
                    defaultValue: true, submitOnChange: true
            input "btnRebuild", "button", title: "Rebuild history from CSV"
            paragraph "<small style='color:#666;'>Recomputes the daily series, refills and " +
                      "violation count from this app's own CSV logs. Safe: reads the files, does " +
                      "not modify them." +
                      (state.rebuildResult ? "<br><b>Last run:</b> ${state.rebuildResult}" : "") +
                      "</small>"
            input "btnResetHistory", "button", title: "Reset ALL history"
            input "thisName", "text", title: "Name this app", submitOnChange: true, required: false
            if (thisName) app.updateLabel(thisName)
            input "logLevel", "enum", title: "Logging level", options: getLogLevels(),
                    defaultValue: "1", required: true
            input "btnRefresh", "button", title: "Refresh subscriptions"
        }
    }
}

// ============================================================================
//   DISPLAY
// ============================================================================

def getStatusDashboardHtml() {
    def cur = currentSaltMm()
    if (cur == null) {
        return "<div style='background:#f0f0f0;padding:10px;border-radius:5px;'>" +
               "<b>Waiting for a reading.</b><br><small>No valid distance seen yet. " +
               "Check the sensor is selected above and reporting.</small></div>"
    }

    def pct = saltPercent(cur)
    def pred = predictRunout()
    def color = "#27ae60"
    def head = "OK"
    if (pct != null && pct <= 15) { color = "#e74c3c"; head = "LOW" }
    else if (pct != null && pct <= 30) { color = "#e67e22"; head = "GETTING LOW" }
    if (pred?.days != null && pred.days <= (warnDays ?: 14)) { color = "#e67e22"; head = "REFILL SOON" }
    if (isStale()) { color = "#95a5a6"; head = "SENSOR QUIET" }

    def sb = new StringBuilder()
    sb << "<div style='background:${color};color:#fff;padding:10px;border-radius:5px;'>"
    sb << "<div style='font-size:1.2em;font-weight:bold;'>${head}</div>"
    sb << "<div>Salt distance: <b>${cur} mm</b> (${mmToIn(cur)} in)"
    if (pct != null) sb << " &nbsp;|&nbsp; <b>${pct}%</b> full"
    sb << "</div>"
    if (pred?.days != null) {
        sb << "<div>Predicted to need salt in <b>${pred.days} days</b> (${pred.date})"
        sb << " &nbsp;|&nbsp; using ${pred.slope} mm/day over ${pred.points} days</div>"
    } else if (pred?.reason) {
        sb << "<div><small>Prediction: ${pred.reason}</small></div>"
    }
    sb << "</div>"

    sb << "<div style='margin-top:8px;font-size:0.95em;'>"
    sb << "Readings today: <b>${state.todayCount ?: 0}</b>"
    if (state.todayMax && state.todayMin) {
        sb << " &nbsp;(min ${state.todayMin} / max ${state.todayMax} mm -- "
        sb << "a wide spread here is normal and is exactly what the minimum filters out)"
    }
    sb << "<br>Last reading: ${state.lastReadingAt ?: 'never'}"
    if (state.lastRefillDate) sb << "<br>Last refill: <b>${state.lastRefillDate}</b>"
    if (state.refillCount) sb << " (${state.refillCount} logged)"
    if (state.badDataBursts) sb << "<br>Bad-data bursts discarded: <b>${state.badDataBursts}</b>"
    sb << "</div>"

    // The self-check, always visible.
    def v = state.assumptionViolations ?: 0
    def vcol = v == 0 ? "#27ae60" : "#e67e22"
    sb << "<div style='margin-top:8px;padding:6px;border-left:4px solid ${vcol};background:#fafafa;'>"
    sb << "<b>Model self-check:</b> ${v} unexplained decrease${v == 1 ? '' : 's'} "
    sb << "in the daily minimum."
    if (v == 0) {
        sb << " <span style='color:#27ae60;'>The one-sided-error assumption is holding.</span>"
    } else {
        sb << " <span style='color:#e67e22;'>If this keeps climbing, the minimum estimator is the " +
              "wrong model for this sensor and the prediction should not be trusted.</span>"
        if (state.lastViolation) sb << "<br><small>Most recent: ${state.lastViolation}</small>"
    }
    sb << "</div>"
    return sb.toString()
}

def getPendingRefillHtml() {
    def p = state.pendingRefill
    if (!p) return ""
    def q = state.quarantine ?: []
    return "<div style='background:#2980b9;color:#fff;padding:10px;border-radius:5px;'>" +
           "<b>Possible refill since ${p.since}</b><br>" +
           "The level was <b>${p.ref} mm</b> and the sensor has read as low as <b>${p.mm} mm</b> " +
           "(${q.size()} low reading${q.size() == 1 ? '' : 's'} held aside, " +
           "${p.lowDays ?: 0} whole day${(p.lowDays ?: 0) == 1 ? '' : 's'} at the new level so far).</div>"
}

def getCalibrationHtml() {
    def cur = currentSaltMm()
    def sb = new StringBuilder()
    sb << "<div style='background:#f7f7f7;padding:8px;border-radius:4px;'>"
    sb << "Current estimate: <b>${cur != null ? "${cur} mm (${mmToIn(cur)} in)" : 'n/a'}</b><br>"
    sb << "FULL point: <b>${settings.fullMm ?: 'not set'}</b> &nbsp;|&nbsp; "
    sb << "NEEDS SALT point: <b>${settings.emptyMm ?: 'not set'}</b><br>"
    sb << "Observed range so far: <b>${state.everMin ?: '?'} - ${state.everMax ?: '?'} mm</b>"
    sb << "</div>"
    return sb.toString()
}

def getDailyTableHtml(int n) {
    def series = (state.dailySeries ?: [])
    if (!series) return "<i>No days recorded yet.</i>"
    def rows = series.size() > n ? series[-n..-1] : series
    def sb = new StringBuilder()
    sb << "<table style='width:100%;border-collapse:collapse;font-size:0.9em;'>"
    sb << "<tr style='background:#eee;'><th style='text-align:left;padding:4px;'>Date</th>"
    sb << "<th style='text-align:right;padding:4px;'>Min mm</th>"
    sb << "<th style='text-align:right;padding:4px;'>Max mm</th>"
    sb << "<th style='text-align:right;padding:4px;'>Readings</th>"
    sb << "<th style='text-align:right;padding:4px;'>Change</th>"
    sb << "<th style='text-align:left;padding:4px;'>Note</th></tr>"
    def prev = null
    rows.each { r ->
        def delta = (prev != null) ? (r.mm - prev) : null
        def note = r.refill ? "<b style='color:#2980b9;'>REFILL</b>" : (r.violation ? "<b style='color:#e67e22;'>decrease</b>" : "")
        sb << "<tr style='border-bottom:1px solid #ddd;'>"
        sb << "<td style='padding:4px;'>${r.d}</td>"
        sb << "<td style='text-align:right;padding:4px;'>${r.mm}</td>"
        sb << "<td style='text-align:right;padding:4px;color:#999;'>${r.max ?: ''}</td>"
        sb << "<td style='text-align:right;padding:4px;'>${r.n ?: ''}</td>"
        sb << "<td style='text-align:right;padding:4px;'>${delta != null ? (delta > 0 ? '+' : '') + delta : ''}</td>"
        sb << "<td style='padding:4px;'>${note}</td></tr>"
        prev = r.mm
    }
    sb << "</table>"
    return sb.toString()
}

// ============================================================================
//   LIFECYCLE
// ============================================================================

def installed() { infolog "installed"; initialize() }

def updated() {
    infolog "updated"
    unsubscribe()
    unschedule()
    initialize()
}

def initialize() {
    infolog "initialize - v1.2.0"

    state.readings          = state.readings ?: []
    state.dailySeries       = state.dailySeries ?: []
    state.todayCount        = state.todayCount ?: 0
    state.refillCount       = state.refillCount ?: 0
    state.assumptionViolations = state.assumptionViolations ?: 0
    state.today             = state.today ?: todayKey()
    // v1.2.0
    state.quarantine        = state.quarantine ?: []
    state.lowDays           = state.lowDays ?: [:]
    state.todayQuarantined  = state.todayQuarantined ?: 0
    state.badDataBursts     = state.badDataBursts ?: 0
    state.refillHistory     = state.refillHistory ?: []

    // v1.1.0 removed regeneration tracking; drop its state so an app upgraded in
    // place does not carry dead keys forever.
    ["regens", "regenCount", "regenCorrelated", "regenIntervalDays",
     "lastRegenMs", "pendingRegenMs"].each { state.remove(it) }

    if (saltSensor) {
        subscribe(saltSensor, (settings.distAttr ?: "rawDistance"), distanceHandler)
    }

    // Daily rollup just after midnight, hourly evaluation for alerts.
    schedule("0 4 0 * * ?", "rollupDay")
    schedule("0 23 * * * ?", "hourlyCheck")

    // Poll. Subscription alone is not enough: Hubitat suppresses unchanged
    // values, so a steady reading produces silence and a day that happens to sit
    // on a far echo produces one poisoned sample. Polling is what gives the
    // window minimum enough samples to mean anything.
    int pm = (settings.pollMinutes ?: 15) as int
    if (pm <= 1)       runEvery1Minute("pollSensor")
    else if (pm <= 5)  runEvery5Minutes("pollSensor")
    else if (pm <= 10) runEvery10Minutes("pollSensor")
    else if (pm <= 15) runEvery15Minutes("pollSensor")
    else if (pm <= 30) runEvery30Minutes("pollSensor")
    else               runEvery1Hour("pollSensor")

    // Seed the liveness mark from the device so a restart does not count the
    // device's last-ever value as a fresh reading; the first real report after
    // this point is the first reading.
    if (saltSensor) {
        def seed = saltSensor.currentValue("lastUpdate")?.toString()
        if (seed) state.lastDeviceMark = seed
    }
    pollSensor()

    infolog "subscribed to ${saltSensor?.displayName} [${settings.distAttr ?: 'rawDistance'}], " +
            "polling every ${pm} min"
}

// ============================================================================
//   READING INTAKE
// ============================================================================

def distanceHandler(evt) {
    def mm = safeToBigDecimal(evt.value).intValue()
    recordReading(mm, now())
}

/**
 *  Scheduled read of the current value -- see the note in initialize().
 *
 *  Gated on liveness. currentValue() on a device that has stopped reporting
 *  returns its last value forever, which v1.1 counted as a fresh reading (96
 *  of them on 2026-10-29, a day the device sent nothing). The driver's
 *  lastUpdate attribute advances on every report -- including reports where
 *  the distance did not change, which rawDistance's own event date does not --
 *  and it is carried across Hub Mesh. If it has not moved since the last poll,
 *  the device has not spoken, and we record nothing.
 */
def pollSensor() {
    if (!saltSensor) return
    def v = saltSensor.currentValue(settings.distAttr ?: "rawDistance")
    if (v == null) return
    if (!deviceSpokeSinceLastPoll()) {
        debuglog "poll skipped: device has not reported since the last poll"
        return
    }
    recordReading(safeToBigDecimal(v).intValue(), now())
}

/** True if the device has reported since the previous poll (and remembers this one). */
private boolean deviceSpokeSinceLastPoll() {
    def mark = saltSensor.currentValue("lastUpdate")?.toString()
    if (!mark) {
        // Driver without a lastUpdate attribute: fall back to the hub's own
        // last-activity stamp, which also moves on every report.
        def la = null
        try { la = saltSensor.getLastActivity() } catch (e) { la = null }
        mark = la ? la.time.toString() : null
    }
    if (!mark) return true                         // no liveness signal at all: fail open
    if (mark == state.lastDeviceMark) return false
    state.lastDeviceMark = mark
    return true
}

private void recordReading(Integer mm, Long t) {
    int lo = (settings.floorMm ?: 40) as int
    int hi = (settings.ceilMm ?: 1500) as int
    if (mm < lo || mm > hi) {
        debuglog "rejected out-of-range ${mm} mm (valid ${lo}-${hi})"
        return
    }

    // Day rollover safety net: if the scheduled rollup was missed (hub reboot,
    // app disabled), close the previous day off the first reading of the new one.
    def tk = todayKey()
    if (state.today && state.today != tk) rollupDay()

    // Keep a little more than the longest window we ever look back over -- both
    // windows are user-settable and either one can be the larger.
    long widest = Math.max((settings.minWindowHours ?: 24) as long,
                           (settings.refillWindowHours ?: 6) as long)
    long cutoff = t - (widest + 6L) * 3600000L

    // Refill-sized drops are QUARANTINED, not recorded. A real refill is
    // permanent and will be confirmed; a burst of under-reports (measured
    // 2026-10-31: three bursts of 192-197 mm against 445) is not, and must
    // never reach the daily minimum, everMin or the trend.
    def thr = refillThreshold()
    if (thr != null && mm <= thr) {
        def q = state.quarantine ?: []
        q << [t: t, mm: mm]
        q = q.findAll { (it.t as long) >= cutoff }
        if (q.size() > 400) q = q[-400..-1]
        state.quarantine = q
        state.todayQuarantined = (state.todayQuarantined ?: 0) + 1
        // Per-day tally of the held readings, so a confirmation can rewrite every
        // day of the pending period even after the raw buffer has aged out.
        def ld = state.lowDays ?: [:]
        def e = ld[tk] ?: [mm: mm, max: mm, n: 0]
        e.mm = Math.min(e.mm as int, mm); e.max = Math.max(e.max as int, mm); e.n = (e.n as int) + 1
        ld[tk] = e
        state.lowDays = ld
        state.lastReadingMs = t                     // the device IS alive
        state.lastReadingAt = new Date(t).format("yyyy-MM-dd HH:mm:ss", location.timeZone)
        debuglog "quarantined ${mm} mm (<= ${thr}); ${q.size()} held"
        checkRefill()
        return
    }

    def buf = state.readings ?: []
    buf << [t: t, mm: mm]
    buf = buf.findAll { (it.t as long) >= cutoff }
    if (buf.size() > 400) buf = buf[-400..-1]
    state.readings = buf

    state.todayCount = (state.todayCount ?: 0) + 1
    state.todayMin = (state.todayMin == null) ? mm : Math.min(state.todayMin as int, mm)
    state.todayMax = (state.todayMax == null) ? mm : Math.max(state.todayMax as int, mm)
    state.everMin  = (state.everMin  == null) ? mm : Math.min(state.everMin  as int, mm)
    state.everMax  = (state.everMax  == null) ? mm : Math.max(state.everMax  as int, mm)
    state.lastReadingMs = t
    state.lastReadingAt = new Date(t).format("yyyy-MM-dd HH:mm:ss", location.timeZone)

    debuglog "reading ${mm} mm (today min ${state.todayMin}, max ${state.todayMax}, n ${state.todayCount})"
}

// ============================================================================
//   REFILL DETECTION  (two-stage since v1.2.0)
// ============================================================================

/**
 *  Readings at or below this are refill-sized drops; null until there is a
 *  CLOSED day to measure from. A partial first day is not a reference: its
 *  running minimum can itself be a far echo, and quarantining everything below
 *  it would hold the true readings hostage (harness 1b caught exactly that --
 *  first poll 351, every 241 after it quarantined, phantom refill three days on).
 */
private Integer refillThreshold() {
    def series = sinceRefill()
    if (!series) return null
    return (series[-1].mm as int) - ((settings.refillDropMm ?: 50) as int)
}

/**
 *  Stage 1 -- open a PENDING refill.
 *
 *  Deliberately built on the minimum over a window rather than on CONSECUTIVE
 *  readings: the sensor alternates between clusters, so a "3 in a row below X"
 *  rule would be defeated by one far echo landing mid-refill.
 *
 *  The confirmation count must be of readings that are THEMSELVES below the new
 *  level -- not merely the number of readings in the window. Harness scenario 3
 *  caught that: one fluke reading of 120 mm alongside an ordinary 305 mm reading
 *  satisfied "2 readings in the window" and declared a refill that never happened.
 *
 *  Since v1.2.0 this no longer registers the refill. It opens a pending one, asks
 *  the owner, and leaves the decision to confirmRefill() / dismissRefill() or to
 *  the rollup's automatic rules.
 */
private void checkRefill() {
    if (state.pendingRefill) { updatePendingLow(); return }
    def floorRef = referenceLevel()
    if (floorRef == null) return

    int need = (settings.refillConfirmReadings ?: 2) as int
    int threshold = refillThreshold()

    def q = state.quarantine ?: []
    long cutoff = now() - ((settings.refillWindowHours ?: 6) as long) * 3600000L
    def confirming = q.findAll { (it.t as long) >= cutoff && (it.mm as int) <= threshold }
    if (confirming.size() < need) return

    int newMm = confirming.collect { it.mm as int }.min()
    state.pendingRefill = [since: todayKey(), ref: floorRef as int, mm: newMm, lowDays: 0,
                           openedMs: now()]
    infolog "POSSIBLE REFILL: ${floorRef} -> ${newMm} mm, ${confirming.size()} readings at or " +
            "below ${threshold} mm -- awaiting confirmation"
    notifyOnce("refillq", "${appLabel()}: looks like the salt tank was refilled " +
                          "(${floorRef} -> ${newMm} mm). Open the app and confirm Yes or No.")
}

private void updatePendingLow() {
    def p = state.pendingRefill
    def q = state.quarantine ?: []
    if (!p || !q) return
    int lowest = q.collect { it.mm as int }.min()
    if (lowest < (p.mm as int)) { p.mm = lowest; state.pendingRefill = p }
}

/** Stage 2a -- the refill is real. Rewrite the quarantined days to the new level. */
private void confirmRefill(String how) {
    def p = state.pendingRefill
    if (!p) return
    def q = state.quarantine ?: []
    def byDay = state.lowDays ?: [:]
    if (!byDay) { state.pendingRefill = null; return }
    def days = byDay.keySet().collect { it as String }.sort { dayNum(it) }
    def firstDay = days[0]
    int newMm = byDay[firstDay].mm as int

    registerRefill(newMm, firstDay, "${how}: ${p.ref} -> ${newMm} mm")

    // Days already rolled up while pending were recorded at the OLD level (only
    // clean readings counted). Replace them with the quarantined minima.
    def series = (state.dailySeries ?: []).findAll { !(it.d in days) || it.d == todayKey() }
    days.each { d ->
        if (d == todayKey()) return
        def e = byDay[d]
        series << [d: d, mm: e.mm, max: e.max, n: e.n, refill: (d == firstDay), violation: false]
    }
    series = series.sort { dayNum(it.d as String) ?: 0L }
    if (series.size() > 400) series = series[-400..-1]
    state.dailySeries = series

    // Today's open tally absorbs its quarantined readings.
    if (byDay[todayKey()]) {
        def e = byDay[todayKey()]
        state.todayMin   = (state.todayMin == null) ? e.mm : Math.min(state.todayMin as int, e.mm as int)
        state.todayMax   = (state.todayMax == null) ? e.max : Math.max(state.todayMax as int, e.max as int)
        state.todayCount = (state.todayCount ?: 0) + (e.n as int)
        state.refillFlagToday = (firstDay == todayKey())
    }
    def buf = (state.readings ?: []) + q
    state.readings = buf.sort { it.t as long }
    state.everMin = (state.everMin == null) ? newMm : Math.min(state.everMin as int, newMm)

    state.quarantine = []
    state.lowDays = [:]
    state.todayQuarantined = 0
    state.pendingRefill = null
    if (settings.enableCsvLogging) rewriteCsvDays(days.findAll { it != todayKey() }, series)
}

/** Stage 2b -- bad data. Drop the quarantined readings and forget the question. */
private void dismissRefill(String how) {
    def p = state.pendingRefill
    int n = (state.quarantine ?: []).size()
    state.quarantine = []
    state.lowDays = [:]
    state.todayQuarantined = 0
    state.pendingRefill = null
    state.badDataBursts = (state.badDataBursts ?: 0) + 1
    def sent = state.notifiedOn ?: [:]; sent.remove("refillq"); state.notifiedOn = sent
    infolog "possible refill DISMISSED (${how}): ${n} reading(s) at/below ${p?.mm} mm discarded as bad data"
}

private void registerRefill(Integer mm, String onDate, String why) {
    // Remember the previous anchor so an undo can restore it.
    def hist = state.refillHistory ?: []
    hist << [date: onDate, mm: mm, prevDate: state.lastRefillDate, prevMm: state.lastRefillMm,
             prevRef: referenceLevel()]
    if (hist.size() > 20) hist = hist[-20..-1]
    state.refillHistory = hist

    state.lastRefillDate = onDate
    state.lastRefillMm = mm
    state.refillCount = (state.refillCount ?: 0) + 1
    state.trendAnchorMm = mm
    if (onDate == todayKey()) state.refillFlagToday = true
    // Everything before a refill describes a different tank-load; the regression
    // must start over or it will average across the step and predict nonsense.
    infolog "REFILL on ${onDate}: ${why}"
    def series = state.dailySeries ?: []
    def row = series.find { it.d == onDate }
    if (row) row.refill = true
    state.dailySeries = series
    def sent = state.notifiedOn ?: [:]; sent.remove("refillq"); state.notifiedOn = sent
    clearLowAlert()
}

/**
 *  Undo the most recent refill. Reverts the anchor to what it was, drops every
 *  daily row since the refill date that sits at the refill level (they describe
 *  the phantom tank-load), purges such readings from the live buffer, rewrites
 *  the CSV, and recomputes everMin. Written for the false refill of 2026-10-31.
 */
private String undoLastRefill() {
    if (!state.lastRefillDate) return "no refill to undo"
    String date = state.lastRefillDate
    int drop = (settings.refillDropMm ?: 50) as int
    def hist = state.refillHistory ?: []
    def h = hist ? hist[-1] : null
    Long anchor = dayNum(date)

    // The level before the refill: from history if we have it, else the last
    // series row before the refill date.
    Integer prevRef = h?.prevRef != null ? (h.prevRef as int) : null
    if (prevRef == null) {
        def before = (state.dailySeries ?: []).findAll { def n = dayNum(it.d as String); n != null && n < anchor }
        prevRef = before ? (before[-1].mm as int) : null
    }
    if (prevRef == null) return "cannot undo: no level before ${date} to restore"
    int thr = prevRef - drop

    def series = state.dailySeries ?: []
    def dropped = series.findAll { def n = dayNum(it.d as String); n != null && n >= anchor && (it.mm as int) <= thr }
    series = series.findAll { !(it in dropped) }
    series.each { if (it.d == date) it.refill = false }
    state.dailySeries = series

    state.readings = (state.readings ?: []).findAll { (it.mm as int) > thr }
    def tq = (state.readings ?: []).findAll {
        new Date(it.t as long).format("yyyy-MM-dd", location.timeZone) == todayKey() }
    state.todayMin = tq ? tq.collect { it.mm as int }.min() : null
    state.todayMax = tq ? tq.collect { it.mm as int }.max() : null
    state.todayCount = tq.size()
    state.refillFlagToday = false

    state.lastRefillDate = h ? h.prevDate : null
    state.lastRefillMm   = h ? h.prevMm : null
    state.trendAnchorMm  = h ? h.prevMm : null
    state.refillCount = Math.max(0, ((state.refillCount ?: 1) as int) - 1)
    if (hist) { hist = hist[0..<(hist.size() - 1)]; state.refillHistory = hist }

    def allMins = series.collect { it.mm as int } + (state.todayMin != null ? [state.todayMin as int] : [])
    state.everMin = allMins ? allMins.min() : null

    if (settings.enableCsvLogging && dropped) rewriteCsvDays(dropped.collect { it.d as String }, series)
    def msg = "undid refill of ${date}: dropped ${dropped.size()} day(s) at or below ${thr} mm " +
              "(${dropped.collect { it.d[5..-1] + '=' + it.mm }.join(', ')}), reference back to ${prevRef} mm"
    log.warn "${appLabel()}: ${msg}"
    return msg
}

/**
 *  The level the current tank-load has settled at -- what a refill drops away from.
 *
 *  Uses the most recent daily minimum rather than the maximum of them. Under the
 *  one-sided-error model the minima are non-decreasing, so normally these are the
 *  same value; where they differ, the max keeps an old inflated day alive forever
 *  and biases every future refill test, while the latest point ages out.
 */
private Integer referenceLevel() {
    def series = sinceRefill()
    if (series) return series[-1].mm as int
    return (state.todayMin != null) ? (state.todayMin as int) : null
}

// ============================================================================
//   WINDOW MINIMUM  (the estimator)
// ============================================================================

private Map minOverHours(int hours) {
    def buf = state.readings ?: []
    if (!buf) return null
    long cutoff = now() - (hours as long) * 3600000L
    def inWin = buf.findAll { (it.t as long) >= cutoff }
    if (!inWin) return null
    return [mm: inWin.collect { it.mm as int }.min(), n: inWin.size()]
}

/** Best current estimate of the salt surface distance. */
def currentSaltMm() {
    def w = minOverHours((settings.minWindowHours ?: 24) as int)
    if (w != null) return w.mm
    if (state.todayMin != null) return state.todayMin as int
    def series = state.dailySeries ?: []
    return series ? (series[-1].mm as int) : null
}

def saltPercent(Integer cur) {
    if (cur == null || settings.fullMm == null || settings.emptyMm == null) return null
    BigDecimal full = safeToBigDecimal(settings.fullMm)
    BigDecimal empty = safeToBigDecimal(settings.emptyMm)
    if (empty == full) return null
    // Distance grows as salt drops, so full is the SMALLER number.
    BigDecimal pct = ((empty - cur) / (empty - full)) * 100
    if (pct < 0) pct = 0
    if (pct > 100) pct = 100
    return pct.setScale(0, RoundingMode.HALF_UP).intValue()
}

// ============================================================================
//   DAILY ROLLUP
// ============================================================================

def rollupDay() {
    def closing = state.today ?: todayKey()
    int minReads = (settings.minReadingsPerDay ?: 3) as int

    // A pending refill is judged on the whole day before the day is closed.
    // Real refill: the low readings own the day (and will own the next ones).
    // Burst: the old level still wins the day -> bad data, discard.
    if (state.pendingRefill) {
        int lowN = (state.todayQuarantined ?: 0) as int
        int oldN = (state.todayCount ?: 0) as int
        if (lowN + oldN >= minReads) {
            if (lowN > oldN) {
                def p = state.pendingRefill
                p.lowDays = ((p.lowDays ?: 0) as int) + 1
                state.pendingRefill = p
                if ((p.lowDays as int) >= 3) {
                    confirmRefill("automatic after ${p.lowDays} whole days at the new level")
                } else {
                    notifyOnce("refillq", "${appLabel()}: still looks refilled (${p.ref} -> ${p.mm} mm, " +
                                          "${p.lowDays} day${p.lowDays == 1 ? '' : 's'}). Confirm Yes or No in the app.")
                }
            } else {
                dismissRefill("old level ${oldN} vs ${lowN} low readings on ${closing}")
            }
        }
    }

    // confirmRefill() may already have written this day's row from the
    // quarantined readings; the clean readings that day were the OLD level and
    // must not overwrite it.
    if ((state.dailySeries ?: []).any { it.d == closing }) {
        debuglog "rollup ${closing}: row already written by refill confirmation"
        state.today = todayKey()
        state.todayMin = null
        state.todayMax = null
        state.todayCount = 0
        state.todayQuarantined = 0
        state.refillFlagToday = false
        return
    }

    if (state.todayMin == null || (state.todayCount ?: 0) < minReads) {
        // Not enough samples to trust a minimum. Record nothing rather than a
        // guess: with one or two samples the "minimum" can simply BE a far echo,
        // which then reads as a huge jump followed by a huge drop and can trip
        // the refill test. Harness scenario 1 caught this on real data -- one day
        // in six had a single far-echo reading and became a 351 mm data point.
        // The regression uses real day numbers, so a skipped day is a gap, not a
        // compressed time axis.
        debuglog "rollup ${closing}: only ${state.todayCount ?: 0} reading(s), " +
                 "need ${minReads} -- recorded as a gap"
        state.skippedDays = (state.skippedDays ?: 0) + 1
    } else {
        def entry = [d: closing, mm: state.todayMin as int, max: state.todayMax as int,
                     n: state.todayCount as int, refill: (state.refillFlagToday == true),
                     violation: false]

        def series = state.dailySeries ?: []
        def prev = series ? series[-1] : null
        if (prev != null && !entry.refill) {
            int delta = (entry.mm as int) - (prev.mm as int)
            int eps = (settings.violationEpsilonMm ?: 5) as int
            int drop = (settings.refillDropMm ?: 50) as int
            if (delta < -eps && Math.abs(delta) < drop) {
                // Between refills the minimum should only ever rise. It didn't.
                entry.violation = true
                state.assumptionViolations = (state.assumptionViolations ?: 0) + 1
                state.lastViolation = "${closing}: minimum fell ${Math.abs(delta)} mm " +
                                      "(${prev.mm} -> ${entry.mm}) with no refill"
                log.warn "${appLabel()}: ${state.lastViolation} -- one-sided-error assumption " +
                         "questioned; see the model self-check on the app page"
            }
        }

        series << entry
        if (series.size() > 400) series = series[-400..-1]
        state.dailySeries = series
        if (settings.enableCsvLogging) logDayToCsv(entry)
    }

    // Lone flukes that never became a pending refill: forget their day tallies.
    if (!state.pendingRefill && state.lowDays) {
        Long c = dayNum(closing)
        state.lowDays = (state.lowDays as Map).findAll { k, v -> def n = dayNum(k as String); n != null && n >= c }
    }

    state.today = todayKey()
    state.todayMin = null
    state.todayMax = null
    state.todayCount = 0
    state.todayQuarantined = 0
    state.refillFlagToday = false
}

// ============================================================================
//   PREDICTION
// ============================================================================

/** Daily entries belonging to the current tank-load. */
private List sinceRefill() {
    def series = state.dailySeries ?: []
    if (!series) return []
    if (!state.lastRefillDate) return series
    Long anchor = dayNum(state.lastRefillDate as String)
    if (anchor == null) return series
    return series.findAll { def n = dayNum(it.d as String); n != null && n >= anchor }
}

def predictRunout() {
    if (settings.emptyMm == null) return [reason: "set the NEEDS SALT point to get a date"]
    def series = sinceRefill()
    if (series.size() < 7) {
        return [reason: "need 7 days since the last refill, have ${series.size()}"]
    }

    // Least squares on (day number, mm).
    def pts = []
    series.each { r ->
        Long n = dayNum(r.d as String)
        if (n != null) pts << [x: n as double, y: (r.mm as int) as double]
    }
    if (pts.size() < 7) return [reason: "not enough dated points"]

    double n = pts.size()
    double sx = 0, sy = 0, sxx = 0, sxy = 0
    pts.each { p -> sx += p.x; sy += p.y; sxx += p.x * p.x; sxy += p.x * p.y }
    double denom = (n * sxx) - (sx * sx)
    if (denom == 0) return [reason: "degenerate fit"]
    double slope = ((n * sxy) - (sx * sy)) / denom

    if (slope <= 0.05) {
        return [reason: "no measurable depletion yet (${round1(slope)} mm/day)"]
    }

    def cur = currentSaltMm()
    if (cur == null) return [reason: "no current reading"]
    BigDecimal empty = safeToBigDecimal(settings.emptyMm)
    double remaining = (empty.doubleValue() - (cur as double))
    if (remaining <= 0) return [days: 0, date: todayKey(), slope: round1(slope), points: pts.size()]

    int days = Math.round(remaining / slope) as int
    def when = new Date(now() + (days as long) * 86400000L).format("yyyy-MM-dd", location.timeZone)
    return [days: days, date: when, slope: round1(slope), points: pts.size()]
}

// ============================================================================
//   ALERTS
// ============================================================================

def hourlyCheck() {
    def cur = currentSaltMm()

    if (isStale()) {
        if (settings.notifyStale) {
            notifyOnce("stale", "${appLabel()}: no reading from ${saltSensor?.displayName} in " +
                                "over ${staleHours ?: 24} hours.")
        }
        return
    }

    if (cur == null) return

    if (settings.emptyMm != null && cur >= safeToBigDecimal(settings.emptyMm).intValue()) {
        if (lowSwitch && lowSwitch.currentValue("switch") != "on") lowSwitch.on()
        if (settings.notifyLow) {
            notifyOnce("low", "${appLabel()}: salt is low -- ${cur} mm (${mmToIn(cur)} in), " +
                              "at or past the refill point.")
        }
        return
    }

    def pred = predictRunout()
    if (pred?.days != null && pred.days <= (warnDays ?: 14)) {
        if (settings.notifyPredicted) {
            notifyOnce("predicted", "${appLabel()}: salt predicted to run low in ${pred.days} days " +
                                    "(around ${pred.date}), depleting ${pred.slope} mm/day.")
        }
    }
}

private boolean isStale() {
    if (!state.lastReadingMs) return false
    long h = ((settings.staleHours ?: 24) as long) * 3600000L
    return (now() - (state.lastReadingMs as long)) > h
}

/** At most one notification per condition per day, so a persistent state does not nag. */
private void notifyOnce(String key, String msg) {
    def sent = state.notifiedOn ?: [:]
    def tk = todayKey()
    if (sent[key] == tk) return
    sent[key] = tk
    state.notifiedOn = sent
    if (notifyDevice) notifyDevice*.deviceNotification(msg)
    infolog "notify [${key}]: ${msg}"
}

private void clearLowAlert() {
    if (lowSwitch && lowSwitch.currentValue("switch") == "on") lowSwitch.off()
    def sent = state.notifiedOn ?: [:]
    sent.remove("low"); sent.remove("predicted")
    state.notifiedOn = sent
}

// ============================================================================
//   CSV LOGGING   (download-append-upload; appendHubFile() does not exist)
// ============================================================================

private String cleanName() {
    return saltSensor?.displayName?.replaceAll(/[^a-zA-Z0-9]/, "") ?: "Softener"
}

def getLevelFileName(year = null) {
    return "salt-level-${cleanName()}-${year ?: new Date().format('yyyy', location.timeZone)}.csv"
}

def logDayToCsv(entry) {
    def pct = saltPercent(entry.mm as int)
    def pred = predictRunout()
    def line = "${entry.d},${entry.mm},${entry.max ?: ''},${entry.n ?: ''}," +
               "${entry.refill ? 'YES' : 'NO'},${entry.violation ? 'YES' : 'NO'}," +
               "${pct != null ? pct : ''},${pred?.slope != null ? pred.slope : ''}," +
               "${pred?.days != null ? pred.days : ''}"
    appendCsv(getLevelFileName(),
              "Date,MinMm,MaxMm,Readings,Refill,Violation,PctFull,SlopeMmPerDay,DaysRemaining\n",
              line)
}

private void appendCsv(String fileName, String header, String line) {
    def content = ""
    try {
        def b = downloadHubFile(fileName)
        if (b) content = new String(b)
    } catch (e) {
        debuglog "creating new log file: ${fileName}"
    }
    if (!content) content = header
    try {
        content += "${line}\n"
        uploadHubFile(fileName, content.toString().getBytes("UTF-8"))
        debuglog "logged to ${fileName}"
    } catch (e) {
        log.error "${appLabel()}: CSV write failed (${fileName}): ${e.message}"
    }
}

/**
 *  Replace the CSV rows for the given dates with whatever the series now holds
 *  for them (nothing, if the day was dropped). Download-filter-append-upload per
 *  year file, same mechanics as appendCsv().
 */
private void rewriteCsvDays(List dates, List series) {
    if (!dates) return
    def years = dates.collect { it.toString()[0..3] }.unique()
    years.each { y ->
        def fname = getLevelFileName(y)
        def content = ""
        try { def b = downloadHubFile(fname); if (b) content = new String(b) } catch (e) { return }
        if (!content) return
        def kept = content.split("\n").findAll { l ->
            def d = l?.split(",", -1)?.getAt(0)?.trim()
            !(d in dates)
        }
        def out = new StringBuilder(kept.join("\n").trim() + "\n")
        series.findAll { it.d in dates && it.d.toString().startsWith(y) }.each { e ->
            out << "${e.d},${e.mm},${e.max ?: ''},${e.n ?: ''},${e.refill ? 'YES' : 'NO'}," +
                   "${e.violation ? 'YES' : 'NO'},,,\n"
        }
        try {
            uploadHubFile(fname, out.toString().getBytes("UTF-8"))
            infolog "rewrote ${fname}: ${dates} replaced"
        } catch (e) {
            log.error "${appLabel()}: CSV rewrite failed (${fname}): ${e.message}"
        }
    }
}

/**
 *  Rebuild the daily series, refills and violation count from this app's own
 *  CSVs. State only holds whatever accumulated while the app was running; the
 *  CSV is the complete record. Uses the same rules as rollupDay() so the
 *  rebuilt values are directly comparable -- this recomputes, it does not
 *  redefine.
 */
def rebuildFromCsv() {
    def series = []
    def filesRead = []
    int thisYear = new Date().format("yyyy", location.timeZone).toInteger()

    for (int y = thisYear - 3; y <= thisYear; y++) {
        def fname = getLevelFileName(y)
        def text = null
        try {
            def b = downloadHubFile(fname)
            if (b) text = new String(b)
        } catch (e) { /* absent year */ }
        if (!text) continue
        filesRead << y
        text.split("\n").each { rawLine ->
            def l = rawLine?.trim()
            if (!l || l.startsWith("Date,")) return
            def p = l.split(",", -1)
            if (p.size() < 6) return
            try {
                series << [d: p[0].trim(), mm: p[1].trim().toInteger(),
                           max: p[2].trim() ? p[2].trim().toInteger() : null,
                           n: p[3].trim() ? p[3].trim().toInteger() : null,
                           refill: (p[4].trim() == "YES"), violation: false]
            } catch (e) { /* skip malformed row */ }
        }
    }

    if (!series) {
        state.rebuildResult = "No CSV rows found (looked for ${getLevelFileName()})."
        return
    }

    series = series.sort { dayNum(it.d as String) ?: 0L }

    // Recompute violations and the last refill from scratch.
    int violations = 0
    String lastRefill = null
    int eps = (settings.violationEpsilonMm ?: 5) as int
    int drop = (settings.refillDropMm ?: 50) as int
    def prev = null
    series.each { r ->
        if (prev != null) {
            int delta = (r.mm as int) - (prev.mm as int)
            if (delta <= -drop) {
                r.refill = true
            } else if (delta < -eps) {
                r.violation = true
                violations++
            }
        }
        if (r.refill) lastRefill = r.d
        prev = r
    }

    state.dailySeries = (series.size() > 400) ? series[-400..-1] : series
    state.assumptionViolations = violations
    if (lastRefill) state.lastRefillDate = lastRefill
    state.everMin = series.collect { it.mm as int }.min()
    state.everMax = series.collect { it.max ?: it.mm }.collect { it as int }.max()

    def pred = predictRunout()
    state.rebuildResult = "Rebuilt ${series.size()} days from ${filesRead}. " +
                          "${violations} assumption violation${violations == 1 ? '' : 's'}, " +
                          "last refill ${lastRefill ?: 'none found'}. " +
                          (pred?.days != null ? "Predicts ${pred.days} days at ${pred.slope} mm/day."
                                              : "Prediction: ${pred?.reason}")
    infolog state.rebuildResult
}

// ============================================================================
//   BUTTONS
// ============================================================================

def appButtonHandler(btn) {
    switch (btn) {
        case "btnMarkFilled":
            if (state.pendingRefill) {
                confirmRefill("marked filled by hand")
            } else {
                registerRefill(currentSaltMm(), todayKey(), "marked by hand")
            }
            break

        case "btnRefillYes":
            confirmRefill("confirmed by owner")
            break

        case "btnRefillNo":
            dismissRefill("owner said no")
            break

        case "btnUndoRefill":
            state.undoResult = undoLastRefill()
            break

        case "btnCaptureFull":
            def cur = currentSaltMm()
            if (cur != null) { app.updateSetting("fullMm", [type: "decimal", value: cur]); infolog "FULL point set to ${cur} mm" }
            break

        case "btnCaptureEmpty":
            def cur = currentSaltMm()
            if (cur != null) { app.updateSetting("emptyMm", [type: "decimal", value: cur]); infolog "NEEDS SALT point set to ${cur} mm" }
            break

        case "btnClearLow":
            clearLowAlert()
            infolog "low-salt alert acknowledged"
            break

        case "btnRebuild":
            rebuildFromCsv()
            break

        case "btnResetHistory":
            log.warn "${appLabel()}: RESETTING ALL HISTORY"
            state.dailySeries = []
            state.readings = []
            state.refillCount = 0
            state.assumptionViolations = 0
            state.lastViolation = null
            state.lastRefillDate = null
            state.rebuildResult = null
            state.everMin = null
            state.everMax = null
            state.todayMin = null
            state.todayMax = null
            state.todayCount = 0
            state.quarantine = []
            state.lowDays = [:]
            state.todayQuarantined = 0
            state.pendingRefill = null
            state.badDataBursts = 0
            state.refillHistory = []
            state.lastRefillMm = null
            state.trendAnchorMm = null
            state.undoResult = null
            break

        case "btnRefresh":
            updated()
            break
    }
}

// ============================================================================
//   HELPERS
// ============================================================================

private String appLabel() { return thisName ?: "SaltMonitor" }

private String todayKey() { return new Date().format("yyyy-MM-dd", location.timeZone) }

private String mmToIn(mm) {
    if (mm == null) return "?"
    return (safeToBigDecimal(mm) / 25.4).setScale(1, RoundingMode.HALF_UP).toString()
}

private String round1(double d) {
    return new BigDecimal(d).setScale(1, RoundingMode.HALF_UP).toString()
}

/**
 *  Days since the civil epoch, by pure arithmetic (Howard Hinnant's algorithm).
 *  Avoids Date.parse(), which is fragile in the Hubitat sandbox, and lets the
 *  regression use a real time axis instead of a row index -- so a gap in the
 *  data does not silently compress the slope.
 */
private Long dayNum(String s) {
    if (!s) return null
    def p = s.tokenize("-")
    if (p.size() != 3) return null
    try {
        int y = p[0].toInteger()
        int m = p[1].toInteger()
        int d = p[2].toInteger()
        y -= (m <= 2) ? 1 : 0
        int era = ((y >= 0) ? y : (y - 399)).intdiv(400)
        int yoe = y - era * 400
        int doy = (153 * (m + ((m > 2) ? -3 : 9)) + 2).intdiv(5) + d - 1
        int doe = yoe * 365 + yoe.intdiv(4) - yoe.intdiv(100) + doy
        return (era as long) * 146097L + (doe as long) - 719468L
    } catch (e) {
        return null
    }
}

def safeToBigDecimal(val) {
    if (val == null) return BigDecimal.ZERO
    try {
        if (val instanceof BigDecimal) return val
        def clean = val.toString().replaceAll("[^0-9.-]", "")
        return clean ? new BigDecimal(clean) : BigDecimal.ZERO
    } catch (e) {
        return BigDecimal.ZERO
    }
}

def getFormat(type, text = "") {
    if (type == "header-green") {
        return "<div style='color:#fff;font-weight:bold;background:#81BC00;border:1px solid;box-shadow:2px 3px #A9A9A9;padding:5px'>${text}</div>"
    }
    return text
}

def debuglog(msg) {
    if ((logLevel ?: "0").toInteger() >= 2) log.debug "${appLabel()}: ${msg}"
}

def infolog(msg) {
    if ((logLevel ?: "0").toInteger() >= 1) log.info "${appLabel()}: ${msg}"
}

def getLogLevels() {
    return [["0": "None"], ["1": "Info"], ["2": "Debug"]]
}
