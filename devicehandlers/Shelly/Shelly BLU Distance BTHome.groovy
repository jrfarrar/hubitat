/**
 *  Shelly BLU Distance (BTHome) - Hubitat native Bluetooth driver
 *  ----------------------------------------------------------------------------
 *  For the Hubitat C-8 Pro "Bluetooth Integration (beta)" app.
 *
 *  HOW THIS WORKS (verified on a live device, July 2026):
 *   - The beta integration decodes the Shelly BLU Distance BTHome v2 advertisement
 *     for you and stores a clean JSON object in the device's "rawData" data field:
 *
 *       {"address":"F8:44:77:1C:E3:08",
 *        "sensors":[{"unit":"%","device":"battery","value":96},
 *                   {"unit":"mm","device":"distance","value":235},
 *                   {"unit":"dBm","device":"signal_strength","value":-60}],
 *        "model":"BTHome sensor",
 *        "binary_values":[{"device":"vibration","value":false}],
 *        "advertised_name":"SBDI-003E","events":[],"manufacturer":null}
 *
 *   - It calls this driver's parse() on each update, BUT passes a Groovy-stringified
 *     map (unquoted keys, '=' separators) which is not valid JSON. So instead of
 *     trying to parse that argument, we read the clean JSON from the "rawData"
 *     device-data field. (If a future build passes a real Map or JSON string,
 *     normalizeToMap() will use the argument directly.)
 *
 *   - "events" is empty at idle and briefly carries a button entry on a press. A
 *     single press is broadcast across ~2 consecutive advertisements, so we de-dup
 *     pushes within a short window.
 *
 *  Reports: distance (in/cm/mm), battery %, rssi, vibration (+acceleration), button.
 *
 *  Author: J.R. Farrar
 *  Version: 1.5  (adds an upper validity limit to drop out-of-range far-echo readings)
 */

import groovy.json.JsonSlurper
import groovy.transform.Field

@Field static final String DRIVER_VERSION = "1.5"

metadata {
    definition(
        name: "Shelly BLU Distance (BTHome)",
        namespace: "jrfarrar",
        author: "J.R. Farrar",
        importUrl: "https://raw.githubusercontent.com/jrfarrar/hubitat/master/devicehandlers/Shelly/Shelly%20BLU%20Distance%20BTHome.groovy"
    ) {
        capability "Sensor"
        capability "Battery"
        capability "SignalStrength"          // rssi
        capability "AccelerationSensor"       // mirrors the vibration flag
        capability "PushableButton"           // sensor button
        capability "Switch"                   // threshold-driven switch (e.g. low-salt alert)
        capability "PresenceSensor"           // present / not present (has it gone silent?)
        capability "Refresh"

        attribute "distance", "number"        // smoothed, in the unit chosen below
        attribute "distanceUnit", "string"    // "in", "cm", or "mm"
        attribute "distanceMm", "number"      // smoothed distance in millimetres (canonical)
        attribute "rawDistance", "number"     // unsmoothed raw reading (mm)
        attribute "smoothingStatus", "string" // smoothing state text
        attribute "vibration", "enum", ["detected", "clear"]
        attribute "lastUpdate", "string"
        attribute "healthStatus", "string"    // online / offline / unknown
        attribute "thresholdTimer", "string"  // "idle" or delay countdown status

        command "resetThresholdTimer"
    }

    preferences {
        input name: "distUnit", type: "enum", title: "Distance unit",
              options: ["in": "Inches", "cm": "Centimeters", "mm": "Millimeters"],
              defaultValue: "in", required: true
        input name: "decimals", type: "enum", title: "Decimal places",
              options: ["0", "1", "2", "3"], defaultValue: "1", required: true
        input name: "buttonDebounce", type: "enum", title: "Button de-dup window (seconds)",
              options: ["1", "2", "3", "5"], defaultValue: "2", required: true

        input name: "enableThreshold", type: "bool", title: "Enable threshold switch?", defaultValue: false
        input name: "triggerType", type: "enum", title: "Switch turns ON when distance is...",
              options: ["over": "OVER the threshold (e.g. salt is low)", "under": "UNDER the threshold"],
              defaultValue: "over"
        input name: "thresholdValue", type: "decimal", title: "Threshold value", defaultValue: 50
        input name: "thresholdUnit", type: "enum", title: "Threshold unit",
              options: ["mm": "mm", "cm": "cm", "in": "inches"], defaultValue: "cm"
        input name: "enableTimeDelay", type: "bool", title: "Require the condition to hold before switching ON?", defaultValue: false
        input name: "timeDelayMinutes", type: "decimal", title: "Minutes the condition must hold", defaultValue: 5

        input name: "minValidMm", type: "number", title: "Ignore readings below this (mm) - the sensor reports 0 on a bad read; its real floor is 200 mm",
              defaultValue: 100, range: "0..2000", required: true
        input name: "maxValidMm", type: "number", title: "Ignore readings above this (mm) - far-echo garbage; set a bit above your deepest real reading (empty tank)",
              defaultValue: 2000, range: "200..6000", required: true

        input name: "smoothingMode", type: "enum", title: "Distance smoothing",
              options: ["none": "None (instant updates)",
                        "stable": "Stable Reading (require consecutive similar readings)",
                        "average": "Time-Based Average (average over a window)",
                        "outlier": "Outlier Rejection (ignore readings far from trend)"],
              defaultValue: "none", required: true
        input name: "stableReadingsRequired", type: "number", title: "Stable mode: consecutive readings required", defaultValue: 3
        input name: "stableTolerance", type: "number", title: "Stable mode: tolerance (mm)", defaultValue: 10
        input name: "averageWindowHours", type: "number", title: "Average mode: window (hours)", defaultValue: 24
        input name: "averageUpdateInterval", type: "enum", title: "Average mode: update interval (hours)",
              options: ["1": "1", "6": "6", "12": "12", "24": "24"], defaultValue: "24"
        input name: "outlierThreshold", type: "number", title: "Outlier mode: reject if more than X mm from trend", defaultValue: 50
        input name: "outlierBufferSize", type: "number", title: "Outlier mode: trend buffer size", defaultValue: 5

        input name: "batteryHysteresis", type: "number", title: "Only report battery when it changes by more than (%)", defaultValue: 10
        input name: "rssiHysteresis", type: "number", title: "Only report rssi when it changes by more than (dBm)", defaultValue: 5

        input name: "staleMinutes", type: "number", title: "Minutes of silence before marking the sensor offline",
              defaultValue: 15, range: "5..240", required: true

        input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: false
        input name: "txtEnable", type: "bool", title: "Enable descriptionText logging", defaultValue: true
    }
}

/* ------------------------------------------------------------------ *
 *  Lifecycle
 * ------------------------------------------------------------------ */

void installed() {
    log.info "Shelly BLU Distance (BTHome) v${DRIVER_VERSION} installed"
    sendEvent(name: "numberOfButtons", value: 1)
    sendEvent(name: "healthStatus", value: "unknown")
    sendEvent(name: "presence", value: "not present")
    runEvery5Minutes("checkSensorHealth")
}

void updated() {
    log.info "Preferences saved (unit=${settings.distUnit}, decimals=${settings.decimals}, " +
             "buttonDebounce=${settings.buttonDebounce}s, smoothing=${settings.smoothingMode}, " +
             "staleMinutes=${settings.staleMinutes}, debug=${logEnable})"
    sendEvent(name: "numberOfButtons", value: 1)
    if (logEnable) runIn(1800, "logsOff")
    // A pref change may switch smoothing modes; clear buffers for a clean start.
    resetSmoothingState()
    // (Re)schedule the silence check.
    runEvery5Minutes("checkSensorHealth")
    // Re-render distance in the newly chosen unit from the last smoothed value.
    def mm = device.currentValue("distanceMm")
    if (mm != null) updateDistance(mm as BigDecimal)
}

void logsOff() {
    log.warn "Debug logging disabled"
    device.updateSetting("logEnable", [value: "false", type: "bool"])
}

void refresh() {
    // Re-process whatever the integration last stored in the rawData field.
    def data = normalizeToMap(device.getDataValue("rawData"))
    if (data != null) processBthome(data)
    else log.warn "refresh(): no 'rawData' field to read yet."
}

/* ------------------------------------------------------------------ *
 *  Data entry
 * ------------------------------------------------------------------ */

def parse(description) {
    if (logEnable) log.debug "parse() trigger; arg type=${typeOf(description)}"

    // Prefer a usable Map/JSON from the argument; otherwise read the clean JSON
    // the integration stores in the 'rawData' device-data field.
    Map data = normalizeToMap(description)
    if (data == null) data = normalizeToMap(device.getDataValue("rawData"))
    if (data == null) {
        if (logEnable) log.warn "parse(): no usable BTHome data from argument or 'rawData'."
        return
    }
    processBthome(data)
}

/* ------------------------------------------------------------------ *
 *  Processing
 * ------------------------------------------------------------------ */

private void processBthome(Map data) {
    if (logEnable) log.debug "processBthome(): ${data}"

    markReported()   // any advertisement means the sensor is alive

    data.sensors?.each { s ->
        String dev = (s.device ?: "").toString().toLowerCase()
        def val = s.value
        if (val == null) return
        switch (dev) {
            case "distance":                       // millimetres from BTHome
                handleDistanceReading(val as BigDecimal)
                break
            case "battery":
                handleBattery(val as Integer)
                break
            case "signal_strength":
            case "rssi":
                handleRssi(val as Integer)
                break
            default:
                if (logEnable) log.debug "Unhandled sensor '${dev}' = ${val} ${s.unit ?: ''}"
        }
    }

    data.binary_values?.each { b ->
        String dev = (b.device ?: "").toString().toLowerCase()
        boolean on = (b.value == true || "${b.value}".toLowerCase() == "true")
        if (dev == "vibration") {
            sendAttr("vibration", on ? "detected" : "clear", null, "vibration ${on ? 'detected' : 'clear'}")
            sendAttr("acceleration", on ? "active" : "inactive", null, "acceleration ${on ? 'active' : 'inactive'}")
        } else if (logEnable) {
            log.debug "Unhandled binary '${dev}' = ${b.value}"
        }
    }

    data.events?.each { ev ->
        if (logEnable) log.debug "event: ${ev}"
        String dev = (ev?.device ?: ev?.type ?: "").toString().toLowerCase()
        if (dev.contains("button") || dev.contains("press")) handleButtonPush()
    }

    sendEvent(name: "lastUpdate", value: new Date().format("yyyy-MM-dd HH:mm:ss", location.timeZone))
}

// A single physical press is broadcast across ~2 advertisements; ignore repeats
// that arrive within the configured window so one press = one 'pushed' event.
private void handleButtonPush() {
    long nowMs = now()
    long last = (state.lastPushAt ?: 0L) as long
    long windowMs = ((settings.buttonDebounce ?: "2") as int) * 1000L
    if (nowMs - last < windowMs) {
        if (logEnable) log.debug "Ignoring duplicate button push (${nowMs - last} ms < ${windowMs} ms)"
        return
    }
    state.lastPushAt = nowMs
    sendEvent(name: "pushed", value: 1, isStateChange: true,
              descriptionText: "${device.displayName} button pushed")
    if (txtEnable) log.info "${device.displayName} button pushed"
}

/* ------------------------------------------------------------------ *
 *  Threshold switch
 *
 *  Drives the standard on/off switch from the distance reading. For a salt
 *  tank, distance grows as the level drops, so "ON when OVER threshold" acts
 *  as a low-salt alert. An optional time-delay requires the condition to hold
 *  before switching ON, so a momentary spike won't trip it.
 * ------------------------------------------------------------------ */

private BigDecimal mmToThresholdUnit(BigDecimal mm) {
    switch (settings.thresholdUnit ?: "cm") {
        case "in": return mm / 25.4
        case "mm": return mm
        default:   return mm / 10.0   // cm
    }
}

private boolean thresholdMet(BigDecimal mm) {
    BigDecimal cur = mmToThresholdUnit(mm)
    BigDecimal thr = (settings.thresholdValue ?: 50) as BigDecimal
    return ((settings.triggerType ?: "over") == "over") ? (cur > thr) : (cur < thr)
}

private void evaluateThreshold(BigDecimal mm) {
    if (!enableThreshold) return
    boolean met = thresholdMet(mm)
    if (logEnable) {
        log.debug "threshold: ${mmToThresholdUnit(mm)} ${settings.thresholdUnit ?: 'cm'} vs " +
                  "${settings.thresholdValue} (${settings.triggerType}) -> met=${met}, switch=${device.currentValue('switch')}"
    }

    if (met) {
        if (device.currentValue("switch") == "on") return
        if (settings.enableTimeDelay && ((settings.timeDelayMinutes ?: 0) as BigDecimal) > 0) {
            if (state.pendingOn) return                     // delay already running
            state.pendingOn = true
            int secs = (((settings.timeDelayMinutes as BigDecimal)) * 60).intValue()
            runIn(secs, "thresholdDelayElapsed", [overwrite: true])
            sendEvent(name: "thresholdTimer", value: "ON in ${settings.timeDelayMinutes} min if still over")
            if (logEnable) log.debug "threshold met; scheduling ON in ${secs}s"
        } else {
            setThresholdSwitch("on")
        }
    } else {
        if (state.pendingOn) { unschedule("thresholdDelayElapsed"); state.pendingOn = false }
        sendEvent(name: "thresholdTimer", value: "idle")
        setThresholdSwitch("off")
    }
}

void thresholdDelayElapsed() {
    state.pendingOn = false
    def mm = device.currentValue("distanceMm")
    if (mm != null && thresholdMet(mm as BigDecimal)) {
        setThresholdSwitch("on")
    } else {
        sendEvent(name: "thresholdTimer", value: "idle")
    }
}

private void setThresholdSwitch(String val) {
    sendEvent(name: "thresholdTimer", value: "idle")
    if (device.currentValue("switch") != val) {
        sendEvent(name: "switch", value: val,
                  descriptionText: "${device.displayName} switch ${val} (threshold)")
        if (txtEnable) log.info "${device.displayName} switch ${val} (threshold)"
    }
}

// Manual overrides (Switch capability). Automatic threshold evaluation resumes
// on the next reading.
void on()  { sendEvent(name: "switch", value: "on",  descriptionText: "${device.displayName} switch on (manual)") }
void off() { sendEvent(name: "switch", value: "off", descriptionText: "${device.displayName} switch off (manual)") }

void resetThresholdTimer() {
    unschedule("thresholdDelayElapsed")
    state.pendingOn = false
    sendEvent(name: "thresholdTimer", value: "idle")
    if (txtEnable) log.info "${device.displayName} threshold timer reset"
}

/* ------------------------------------------------------------------ *
 *  Distance smoothing (ported from the MQTT driver)
 *
 *  applySmoothing() returns a processed millimetre value to publish, or null
 *  to withhold this cycle (still collecting / rejected outlier). rawDistance
 *  always shows the unsmoothed reading; distance/distanceMm show the result.
 * ------------------------------------------------------------------ */

private void handleDistanceReading(BigDecimal rawMm) {
    sendEvent(name: "rawDistance", value: rawMm, unit: "mm")

    // Validity window: the sensor emits 0 mm on a bad read (no echo) and, when it
    // loses the salt surface, catches far echoes well beyond the tank (several
    // metres). Both are out of range - drop them before they reach smoothing,
    // the average buffer, or the threshold switch, so distance holds its last
    // good value through a bad streak instead of spiking.
    BigDecimal floor = (settings.minValidMm != null ? settings.minValidMm : 100) as BigDecimal
    BigDecimal ceil  = (settings.maxValidMm != null ? settings.maxValidMm : 2000) as BigDecimal
    if (rawMm < floor || rawMm > ceil) {
        sendEvent(name: "smoothingStatus", value: "ignored out-of-range ${rawMm} mm (valid ${floor}-${ceil} mm)")
        if (logEnable) log.debug "Ignoring out-of-range distance ${rawMm} mm (valid ${floor}-${ceil} mm)"
        return
    }

    def processed = applySmoothing(rawMm)
    if (processed != null) updateDistance(processed as BigDecimal)
    else if (logEnable) log.debug "smoothing withheld update for raw ${rawMm} mm"
}

private def applySmoothing(BigDecimal rawMm) {
    switch (settings.smoothingMode ?: "none") {
        case "stable":  return applySmoothingStable(rawMm)
        case "average": return applySmoothingAverage(rawMm)
        case "outlier": return applySmoothingOutlier(rawMm)
        default:
            sendEvent(name: "smoothingStatus", value: "none")
            return rawMm
    }
}

private Integer toMm(BigDecimal v) { return v.setScale(0, BigDecimal.ROUND_HALF_UP).intValue() }

private def applySmoothingStable(BigDecimal rawMm) {
    int required = (settings.stableReadingsRequired ?: 3) as int
    int tolerance = (settings.stableTolerance ?: 10) as int
    int r = toMm(rawMm)

    if (state.smoothingBuffer == null) state.smoothingBuffer = []
    def buf = state.smoothingBuffer

    if (buf.isEmpty()) {
        buf.add(r); state.smoothingBuffer = buf
        sendEvent(name: "smoothingStatus", value: "initializing (1/${required})")
        return r   // publish the first reading so distance isn't blank
    }
    int lastCandidate = buf[-1] as int
    if (Math.abs(r - lastCandidate) <= tolerance) {
        buf.add(r)
        while (buf.size() > required) buf.remove(0)
        state.smoothingBuffer = buf
        if (buf.size() >= required) {
            int avg = Math.round((buf.sum() / buf.size()) as double) as int
            sendEvent(name: "smoothingStatus", value: "stable (${required} readings)")
            return avg
        }
        sendEvent(name: "smoothingStatus", value: "collecting (${buf.size()}/${required})")
        return null
    } else {
        state.smoothingBuffer = [r]
        sendEvent(name: "smoothingStatus", value: "reset - new candidate ${r} mm")
        return null
    }
}

private def applySmoothingAverage(BigDecimal rawMm) {
    int windowHours = (settings.averageWindowHours ?: 24) as int
    int updIntervalHours = (settings.averageUpdateInterval ?: "24") as int
    long windowMs = windowHours * 3600000L
    long updIntervalMs = updIntervalHours * 3600000L
    long currentTime = now()
    int r = toMm(rawMm)

    if (state.averageReadings == null) state.averageReadings = []
    if (state.lastAverageUpdate == null) state.lastAverageUpdate = 0L

    def readings = state.averageReadings
    readings.add([v: r, t: currentTime])
    long cutoff = currentTime - windowMs
    readings = readings.findAll { (it.t as long) >= cutoff }
    state.averageReadings = readings

    long lastUpd = state.lastAverageUpdate as long
    if (lastUpd == 0L) {
        state.lastAverageUpdate = currentTime
        sendEvent(name: "smoothingStatus", value: "averaging (${readings.size()} readings)")
        return r
    }
    if (currentTime - lastUpd >= updIntervalMs && readings.size() > 0) {
        def sum = readings.sum { it.v as int }
        int avg = Math.round((sum / readings.size()) as double) as int
        state.lastAverageUpdate = currentTime
        sendEvent(name: "smoothingStatus", value: "averaged ${readings.size()} readings")
        return avg
    }
    sendEvent(name: "smoothingStatus", value: "collecting (${readings.size()} readings)")
    return null
}

private def applySmoothingOutlier(BigDecimal rawMm) {
    int threshold = (settings.outlierThreshold ?: 50) as int
    int bufferSize = (settings.outlierBufferSize ?: 5) as int
    int r = toMm(rawMm)

    if (state.smoothingBuffer == null) state.smoothingBuffer = []
    def buf = state.smoothingBuffer

    if (buf.size() < 2) {
        buf.add(r); state.smoothingBuffer = buf
        sendEvent(name: "smoothingStatus", value: "building trend (${buf.size()}/${bufferSize})")
        return r
    }
    int trendAvg = Math.round((buf.sum() / buf.size()) as double) as int
    if (Math.abs(r - trendAvg) > threshold) {
        sendEvent(name: "smoothingStatus", value: "rejected outlier ${r} mm (trend ${trendAvg} mm)")
        return null
    }
    buf.add(r)
    while (buf.size() > bufferSize) buf.remove(0)
    state.smoothingBuffer = buf
    sendEvent(name: "smoothingStatus", value: "accepted (trend ${trendAvg} mm)")
    return r
}

private void resetSmoothingState() {
    state.remove("smoothingBuffer")
    state.remove("averageReadings")
    state.remove("lastAverageUpdate")
    sendEvent(name: "smoothingStatus",
              value: (!settings.smoothingMode || settings.smoothingMode == "none") ? "none" : "reset")
}

/* ------------------------------------------------------------------ *
 *  Battery / RSSI hysteresis - only report when the change is large
 *  enough, to keep the event log tidy.
 * ------------------------------------------------------------------ */

private void handleBattery(Integer cur) {
    int last = (state.lastBattery != null) ? (state.lastBattery as int) : -1000
    int thr = (settings.batteryHysteresis != null) ? (settings.batteryHysteresis as int) : 10
    if (Math.abs(cur - last) > thr) {
        state.lastBattery = cur
        sendAttr("battery", cur, "%", "battery ${cur}%")
    } else if (logEnable) {
        log.debug "battery ${cur}% within ${thr}% hysteresis; skipped"
    }
}

private void handleRssi(Integer cur) {
    int last = (state.lastRssi != null) ? (state.lastRssi as int) : -1000
    int thr = (settings.rssiHysteresis != null) ? (settings.rssiHysteresis as int) : 5
    if (Math.abs(cur - last) > thr) {
        state.lastRssi = cur
        sendAttr("rssi", cur, "dBm", "rssi ${cur} dBm")
    } else if (logEnable) {
        log.debug "rssi ${cur} dBm within ${thr} dBm hysteresis; skipped"
    }
}

/* ------------------------------------------------------------------ *
 *  Silence / presence detection
 *
 *  Unlike the MQTT driver there is no connection to reconnect - the beta
 *  integration owns the radio. We simply track the time of the last received
 *  advertisement; a scheduled check (every 5 min) flips the device to
 *  offline / not-present if nothing has arrived within staleMinutes, and back
 *  to online / present as soon as a fresh advertisement lands.
 * ------------------------------------------------------------------ */

private void markReported() {
    state.lastReportAt = now()
    if (device.currentValue("healthStatus") != "online") {
        sendEvent(name: "healthStatus", value: "online")
        sendEvent(name: "presence", value: "present")
        if (txtEnable) log.info "${device.displayName} is reporting - online / present"
    }
}

void checkSensorHealth() {
    long last = (state.lastReportAt ?: 0L) as long
    if (last == 0L) {
        // Nothing received yet since install/reboot - stay 'unknown', don't false-alarm.
        if (device.currentValue("healthStatus") == null) sendEvent(name: "healthStatus", value: "unknown")
        return
    }
    long staleMs = ((settings.staleMinutes ?: 15) as long) * 60000L
    long elapsed = now() - last
    if (elapsed > staleMs) {
        if (device.currentValue("healthStatus") != "offline") {
            log.warn "${device.displayName} has not reported in ${Math.round(elapsed / 60000)} min - marking offline"
            sendEvent(name: "healthStatus", value: "offline")
            sendEvent(name: "presence", value: "not present")
        }
    } else if (device.currentValue("healthStatus") != "online") {
        sendEvent(name: "healthStatus", value: "online")
        sendEvent(name: "presence", value: "present")
    }
}

/* ------------------------------------------------------------------ *
 *  Helpers
 * ------------------------------------------------------------------ */

// Sandbox-safe type description (Hubitat blocks getClass()/.class).
private String typeOf(o) {
    if (o == null) return "null"
    if (o instanceof String) return "String"
    if (o instanceof Map) return "Map"
    if (o instanceof List) return "List"
    if (o instanceof Number) return "Number"
    if (o instanceof Boolean) return "Boolean"
    return "other"
}

private Map normalizeToMap(input) {
    try {
        if (input == null) return null
        if (input instanceof Map) return input
        if (input instanceof List) return input.find { it instanceof Map } as Map
        if (input instanceof String) {
            String s = input.trim()
            if (!s.startsWith("{") && !s.startsWith("[")) return null
            def parsed = new JsonSlurper().parseText(s)
            if (parsed instanceof Map) return parsed
            if (parsed instanceof List) return parsed.find { it instanceof Map } as Map
        }
    } catch (e) {
        // Expected when parse()'s argument is a Groovy-stringified map (not JSON);
        // the caller falls back to the clean 'rawData' JSON field.
        if (logEnable) log.debug "normalizeToMap(): input not JSON (${e.message}); falling back."
    }
    return null
}

private void updateDistance(BigDecimal mm) {
    sendEvent(name: "distanceMm", value: mm, unit: "mm")

    String unit = settings.distUnit ?: "in"
    int dp = (settings.decimals ?: "1") as int
    BigDecimal converted
    switch (unit) {
        case "in": converted = mm / 25.4;  break
        case "cm": converted = mm / 10.0;  break
        default:   unit = "mm"; converted = mm; break
    }
    def rounded = converted.setScale(dp, BigDecimal.ROUND_HALF_UP)
    sendEvent(name: "distance", value: rounded, unit: unit,
              descriptionText: "${device.displayName} distance is ${rounded} ${unit}")
    sendEvent(name: "distanceUnit", value: unit)
    if (txtEnable) log.info "${device.displayName} distance is ${rounded} ${unit} (${mm} mm raw)"

    evaluateThreshold(mm)
}

private void sendAttr(String name, def value, String unit, String text) {
    def evt = [name: name, value: value]
    if (unit) evt.unit = unit
    if (text) evt.descriptionText = "${device.displayName} ${text}"
    sendEvent(evt)
    if (txtEnable && text) log.info "${device.displayName} ${text}"
}