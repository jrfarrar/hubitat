/**
 *  Humidor Pack Tracker
 *
 *  Tracks Boveda (or any) humidity pack replacements per humidor.
 *  Each selected humidity sensor IS a humidor; the sensor's display name
 *  is used as the humidor name. Logs pack size (grams) and quantity per
 *  replacement, computes how long each set lasts, captures the humidity
 *  reading at swap time, and analyzes which size/quantity configuration
 *  lasts longest per humidor.
 *
 *  Author : J.R. Farrar with Claude
 *  Version: 1.0.0
 *
 *  ---------------------------------------------------------------------------
 *  Storage model -> state.humidors, keyed by device ID:
 *    [ <deviceId> : [
 *        current : [ size:<g>, qty:<n>, installDate:<epoch ms>, installRh:<rh> ],
 *        history : [ [ size, qty, installDate, removeDate, lifespanDays,
 *                      installRh, removeRh ], ... ]
 *    ] ]
 *  ---------------------------------------------------------------------------
 */

definition(
    name          : "Humidor Pack Tracker",
    namespace     : "jrfarrar",
    author        : "J.R. Farrar",
    description   : "Track humidity pack replacements, lifespan, and best size/qty per humidor",
    category      : "Convenience",
    iconUrl       : "",
    iconX2Url     : "",
    singleInstance: true,
    importUrl: "https://raw.githubusercontent.com/jrfarrar/hubitat/master/Apps/HumidorPackTracker.groovy"
)

preferences {
    page(name: "mainPage")
    page(name: "humidorPage")
    page(name: "historyPage")
}

/* =========================================================================
 *  PAGES
 * ========================================================================= */

def mainPage() {
    // Leaving these pages clears any stale "armed reset" / message state.
    state.resetArmed = null
    state.lastMsg = null

    dynamicPage(name: "mainPage", title: "Humidor Pack Tracker", install: true, uninstall: true) {
        section("Humidors") {
            input "sensors", "capability.relativeHumidityMeasurement",
                title: "Select the humidity sensor inside each humidor",
                multiple: true, required: false, submitOnChange: true
            paragraph "Each selected sensor's name becomes the humidor name. Add a sensor any time to start tracking another humidor."
        }
        if (sensors) {
            cleanupHumidors()
            section("Current Status") {
                sensors.sort(false) { it.displayName }.each { dev ->
                    def h = getHumidor(dev.id)
                    def rh = currentRh(dev)
                    def line
                    if (h?.current) {
                        def days = daysSince(h.current.installDate)
                        line = "${h.current.qty} x ${h.current.size}g - installed ${days} day${days == 1 ? '' : 's'} ago"
                    } else {
                        line = "No packs logged yet"
                    }
                    href name: "h_${dev.id}", page: "humidorPage", params: [deviceId: dev.id],
                        title: "${dev.displayName}",
                        description: "${line}\nHumidity now: ${rh != null ? rh + '%' : 'n/a'}"
                }
            }
        }
        section("Options") {
            input "debugEnable", "bool", title: "Enable debug logging", defaultValue: false
        }
    }
}

def humidorPage(params) {
    def devId = params?.deviceId ?: state.selectedHumidor
    state.selectedHumidor = devId
    state.resetArmed = null   // not on the history page anymore

    def dev = sensors?.find { it.id == devId }
    if (!dev) {
        return dynamicPage(name: "humidorPage", title: "Humidor") {
            section { paragraph "Sensor not found - go back and reselect." }
        }
    }

    def h = getHumidor(devId)

    dynamicPage(name: "humidorPage", title: "${dev.displayName}") {
        section("Current Pack Set") {
            if (h?.current) {
                def days = daysSince(h.current.installDate)
                paragraph "Installed: ${formatDate(h.current.installDate)} (${days} day${days == 1 ? '' : 's'} ago)\n" +
                          "Configuration: ${h.current.qty} x ${h.current.size}g\n" +
                          "Humidity when installed: ${h.current.installRh != null ? h.current.installRh + '%' : 'n/a'}"
            } else {
                paragraph "No pack set currently logged for this humidor."
            }
            paragraph "Humidity right now: ${currentRh(dev) != null ? currentRh(dev) + '%' : 'n/a'}"
        }

        section("Log a Replacement (effective today)") {
            input "logSize", "number", title: "Pack size in grams (e.g. 60)", required: false, submitOnChange: true
            input "logQty",  "number", title: "Number of packs", required: false, submitOnChange: true
            input "btnLog", "button", title: "Log Replacement"
            if (state.lastMsg) paragraph state.lastMsg
        }

        if (h?.current) {
            section("Correction") {
                input "btnUndo", "button", title: "Undo Last Replacement"
                paragraph "Reverts the most recent replacement you logged for this humidor (one level)."
            }
        }

        section("History & Analysis") {
            href name: "hist_${devId}", page: "historyPage", params: [deviceId: devId],
                title: "View history & best configuration",
                description: "${h?.history ? h.history.size() : 0} past set${(h?.history?.size() ?: 0) == 1 ? '' : 's'} recorded"
        }
    }
}

def historyPage(params) {
    def devId = params?.deviceId ?: state.selectedHumidor
    state.selectedHumidor = devId

    def dev = sensors?.find { it.id == devId }
    def h = getHumidor(devId)

    dynamicPage(name: "historyPage", title: "${dev?.displayName ?: 'Humidor'} - History") {
        section("Best Configuration") {
            paragraph bestConfigText(h)
        }
        section("Average Lifespan by Configuration") {
            paragraph configBreakdown(h)
        }
        section("Replacement History (most recent first)") {
            if (h?.history) {
                def txt = h.history.sort(false) { -it.removeDate }.collect { r ->
                    "${formatDate(r.installDate)} -> ${formatDate(r.removeDate)}  |  " +
                    "${r.qty} x ${r.size}g  |  lasted ${r.lifespanDays} day${r.lifespanDays == 1 ? '' : 's'}" +
                    (r.removeRh != null ? "  |  RH at swap: ${r.removeRh}%" : "")
                }.join("\n")
                paragraph txt
            } else {
                paragraph "No history yet. Lifespan is recorded when you replace a set, so data appears here after your second replacement."
            }
        }
        section("Danger Zone") {
            input "btnResetThis", "button", title: "Reset ALL data for this humidor"
            if (state.resetArmed == devId) {
                paragraph "WARNING: press the button again to permanently delete this humidor's current set and history."
            }
            if (state.lastMsg) paragraph state.lastMsg
        }
    }
}

/* =========================================================================
 *  LIFECYCLE
 * ========================================================================= */

def installed() { initialize() }
def updated()   { initialize() }

def initialize() {
    if (state.humidors == null) state.humidors = [:]
    cleanupHumidors()
    updateLabel()
    logDebug "initialize complete - tracking ${sensors?.size() ?: 0} humidor(s)"
}

// Ensure a storage slot exists for every selected sensor.
// NOTE: deselecting a sensor does NOT delete its data, so months of
// hand-entered history can never be lost by an accidental deselect.
// Use the per-humidor "Reset" button to intentionally clear data.
def cleanupHumidors() {
    if (state.humidors == null) state.humidors = [:]
    sensors?.each { dev ->
        if (state.humidors[dev.id] == null) {
            state.humidors[dev.id] = [current: null, history: []]
        }
    }
}

/* =========================================================================
 *  BUTTON HANDLING
 * ========================================================================= */

def appButtonHandler(String btn) {
    switch (btn) {
        case "btnLog":       doLogReplacement(); break
        case "btnUndo":      doUndo();           break
        case "btnResetThis": doReset();          break
        default:             logDebug "Unhandled button: ${btn}"
    }
}

def doLogReplacement() {
    def devId = state.selectedHumidor
    def dev = sensors?.find { it.id == devId }
    if (!dev) { state.lastMsg = "Error: humidor not found."; return }

    def size = settings.logSize
    def qty  = settings.logQty
    if (size == null || qty == null || size <= 0 || qty <= 0) {
        state.lastMsg = "Enter a pack size (grams) and a quantity greater than zero before logging."
        return
    }
    size = (size as BigDecimal)
    qty  = (qty as Integer)

    def h = getHumidor(devId)
    if (h == null) { h = [current: null, history: []]; state.humidors[devId] = h }

    Long nowMs = now()
    def rhNow = currentRh(dev)

    // Close out the existing set (if any) into history.
    def prevCurrent = h.current
    def closedRecord = null
    if (prevCurrent) {
        closedRecord = [
            size        : prevCurrent.size,
            qty         : prevCurrent.qty,
            installDate : prevCurrent.installDate,
            removeDate  : nowMs,
            lifespanDays: (Integer) Math.round((nowMs - prevCurrent.installDate) / 86400000.0d),
            installRh   : prevCurrent.installRh,
            removeRh    : rhNow
        ]
        h.history << closedRecord
        logDebug "Closed set for ${dev.displayName}: ${closedRecord}"
    }

    // Install the new set as current.
    h.current = [size: size, qty: qty, installDate: nowMs, installRh: rhNow]
    state.humidors[devId] = h

    // Remember enough to undo this exact action once.
    state.lastUndo = [devId: devId, prevCurrent: prevCurrent, closedRecord: closedRecord]

    // Clear the entry fields for next time.
    app.removeSetting("logSize")
    app.removeSetting("logQty")

    state.lastMsg = "Logged ${qty} x ${size}g in ${dev.displayName} on ${formatDate(nowMs)}."
    updateLabel()
}

def doUndo() {
    def devId = state.selectedHumidor
    def u = state.lastUndo
    if (!u || u.devId != devId) {
        state.lastMsg = "Nothing to undo for this humidor."
        return
    }
    def h = getHumidor(devId)
    if (!h) { state.lastMsg = "Nothing to undo."; return }

    // Remove the history record this action created (match install+remove dates).
    if (u.closedRecord) {
        h.history.removeAll {
            it.removeDate == u.closedRecord.removeDate && it.installDate == u.closedRecord.installDate
        }
    }
    // Restore the set that was current before the logged replacement.
    h.current = u.prevCurrent
    state.humidors[devId] = h
    state.lastUndo = null
    state.lastMsg = "Last replacement undone."
    updateLabel()
}

def doReset() {
    def devId = state.selectedHumidor
    if (state.resetArmed != devId) {
        state.resetArmed = devId
        state.lastMsg = "Reset armed - press the reset button again to confirm."
        return
    }
    state.humidors[devId] = [current: null, history: []]
    state.resetArmed = null
    state.lastUndo = null
    state.lastMsg = "Data for this humidor has been reset."
    updateLabel()
}

/* =========================================================================
 *  ANALYSIS
 * ========================================================================= */

String configKey(rec) { return "${rec.qty} x ${rec.size}g" }

// key -> [count, total, min, max, avg]
Map configStats(h) {
    def groups = [:]
    h?.history?.each { r ->
        def k = configKey(r)
        if (groups[k] == null) groups[k] = [count: 0, total: 0, min: null, max: null]
        def g = groups[k]
        g.count += 1
        g.total += r.lifespanDays
        g.min = (g.min == null) ? r.lifespanDays : Math.min(g.min, r.lifespanDays)
        g.max = (g.max == null) ? r.lifespanDays : Math.max(g.max, r.lifespanDays)
    }
    groups.each { k, g -> g.avg = (Integer) Math.round(g.total / (double) g.count) }
    return groups
}

String configBreakdown(h) {
    def groups = configStats(h)
    if (!groups) {
        return "No completed sets yet. A set's lifespan is calculated when you replace it, so figures appear after your second replacement of a given configuration."
    }
    return groups.sort(false) { -it.value.avg }.collect { k, g ->
        "${k}: avg ${g.avg} days over ${g.count} set${g.count == 1 ? '' : 's'} (range ${g.min}-${g.max} days)"
    }.join("\n")
}

String bestConfigText(h) {
    def groups = configStats(h)
    if (!groups) {
        return "Not enough data yet - log at least two replacements of the same size/quantity to compare lifespans."
    }
    def reliable = groups.findAll { it.value.count >= 2 }
    def pool = reliable ?: groups
    def best = pool.max { it.value.avg }
    def g = best.value
    def note = (g.count >= 2) ? "" : " (only ${g.count} data point so far - preliminary)"
    return "Longest-lasting so far: ${best.key} at an average of ${g.avg} days${note}."
}

/* =========================================================================
 *  HELPERS
 * ========================================================================= */

def getHumidor(devId) {
    if (state.humidors == null) state.humidors = [:]
    return state.humidors[devId]
}

def currentRh(dev) {
    try {
        def v = dev?.currentValue("humidity")
        return (v != null) ? (v as BigDecimal) : null
    } catch (e) {
        return null
    }
}

Integer daysSince(Long epochMs) {
    if (!epochMs) return 0
    return (Integer) Math.round((now() - epochMs) / 86400000.0d)
}

String formatDate(Long epochMs) {
    if (!epochMs) return "n/a"
    return new Date(epochMs).format("yyyy-MM-dd", location.timeZone)
}

def updateLabel() {
    def n = sensors?.size() ?: 0
    int total = 0
    state.humidors?.each { k, v -> total += (v?.history?.size() ?: 0) }
    app.updateLabel("Humidor Pack Tracker (${n} humidor${n == 1 ? '' : 's'}, ${total} logged)")
}

def logDebug(msg) {
    if (settings.debugEnable) log.debug "HumidorPackTracker: ${msg}"
}
