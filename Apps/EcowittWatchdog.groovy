/**
 *  Ecowitt Watchdog
 *
 *  https://raw.githubusercontent.com/jrfarrar/hubitat/master/Apps/EcowittWatchdog.groovy
 *
 *  Watches an Ecowitt gateway and its RF sensors and tells you when something
 *  has actually stopped reporting -- as opposed to merely looking quiet.
 *
 *  Why this exists:
 *    Hubitat's "last activity" only moves when an attribute VALUE CHANGES.
 *    A steady-state sensor (a dry leak detector, a stable battery) produces no
 *    events for months while working perfectly, so activity-based watchdogs
 *    report it dead. Conversely, if the gateway itself dies, every child simply
 *    goes silent and no per-sensor flag ever fires.
 *
 *  So this app uses the right signal for each failure mode:
 *    SENSOR RF LOST -> the driver's `orphaned` attribute, which the gateway
 *                      driver recomputes on every push and which fires a real
 *                      event when it changes.
 *    GATEWAY DEAD   -> the parent's `lastUpdate` attribute, which genuinely
 *                      changes on every push, used as a heartbeat.
 *
 *  Running this on the logic hub also covers the source hub dying or hub mesh
 *  breaking -- all three present as "no heartbeat", which is what you want.
 *
 *  Both thresholds are generous by design. A weather station being down for an
 *  hour is not an event worth a notification.
 *
 *  Copyright 2026 J.R. Farrar
 *  Licensed under the Apache License, Version 2.0
 */

definition(
    name: "Ecowitt Watchdog",
    namespace: "jrfarrar",
    author: "J.R. Farrar",
    description: "Alerts when an Ecowitt gateway stops reporting, or when an individual RF sensor goes orphaned, using debounced thresholds.",
    category: "Convenience",
    iconUrl: "",
    iconX2Url: "",
    iconX3Url: "",
    importUrl: "https://raw.githubusercontent.com/jrfarrar/hubitat/master/Apps/EcowittWatchdog.groovy"
)

preferences {
    page(name: "mainPage")
}

def mainPage() {
    dynamicPage(name: "mainPage", title: "", install: true, uninstall: true) {

        section("<b>Gateway heartbeat</b>") {
            paragraph "<small>The gateway parent device publishes <code>lastUpdate</code> on every push. " +
                      "If that stops, the gateway, its hub, or hub mesh has failed.</small>"
            input name: "gatewayDevice", type: "capability.*", title: "Ecowitt gateway parent device",
                  required: true, multiple: false
            input name: "gatewayTimeoutMinutes", type: "number",
                  title: "Alert if no heartbeat for this many minutes", defaultValue: 60, required: true
        }

        section("<b>Sensor orphan detection</b>") {
            paragraph "<small>Select the gateway's child devices. The driver sets <code>orphaned</code> " +
                      "true when the gateway received nothing from that sensor in a push cycle. " +
                      "The confirm delay below suppresses brief blips.</small>"
            input name: "sensors", type: "capability.*", title: "Ecowitt sensor devices to watch",
                  required: false, multiple: true
            input name: "orphanConfirmMinutes", type: "number",
                  title: "Only alert if a sensor stays orphaned this many minutes", defaultValue: 120, required: true
        }

        section("<b>Notification</b>") {
            input name: "notifyDevices", type: "capability.notification",
                  title: "Notify these devices", required: false, multiple: true
            input name: "notifyRecovery", type: "bool",
                  title: "Also notify when something recovers", defaultValue: true
            input name: "reminderHours", type: "number",
                  title: "Re-send a reminder every N hours while still down (0 = never)", defaultValue: 0, required: false
        }

        section("<b>Status</b>") {
            paragraph statusHtml()
            input name: "testNotify", type: "button", title: "Send test notification"
        }

        section("<b>Options</b>") {
            input name: "checkMinutes", type: "enum", title: "How often to evaluate",
                  options: ["5":"Every 5 minutes","10":"Every 10 minutes","15":"Every 15 minutes"],
                  defaultValue: "10", required: true
            input name: "pauseApp", type: "bool", title: "Pause this app", defaultValue: false
            input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: false
            label title: "Name this app", required: false
        }
    }
}

// ------------------------------------------------------------ lifecycle

def installed() { initialize() }
def updated()   { initialize() }

def initialize() {
    unsubscribe()
    unschedule()

    if (state.orphans == null) state.orphans = [:]
    if (state.gwAlerted == null) state.gwAlerted = false
    state.lastHeartbeat = now()
    state.gwAlerted = false

    if (pauseApp) {
        log.warn "Ecowitt Watchdog is PAUSED"
        return
    }

    subscribe(gatewayDevice, "lastUpdate", heartbeatHandler)
    sensors?.each { subscribe(it, "orphaned", orphanHandler) }

    // Seed from current state so a sensor already orphaned at install is caught.
    sensors?.each { dev ->
        if ("${dev.currentValue('orphaned')}" == "true") {
            recordOrphan(dev.id?.toString(), dev.displayName)
        }
    }

    def mins = (checkMinutes ?: "10") as Integer
    if (mins == 5)       runEvery5Minutes("checkStatus")
    else if (mins == 15) runEvery15Minutes("checkStatus")
    else                 runEvery10Minutes("checkStatus")

    log.info "Ecowitt Watchdog: scheduled checkStatus every ${mins} minutes"
    logDebug "initialized: heartbeat on ${gatewayDevice?.displayName}, ${sensors?.size() ?: 0} sensors, " +
             "gateway timeout ${gatewayTimeoutMinutes}m, orphan confirm ${orphanConfirmMinutes}m"
}

// ------------------------------------------------------------ events

def heartbeatHandler(evt) {
    state.lastHeartbeat = now()
    if (state.gwAlerted) {
        state.gwAlerted = false
        state.gwAlertedAt = null
        sendNotify("Ecowitt gateway is reporting again (${gatewayDevice?.displayName}).", true)
    }
    logDebug "heartbeat"
}

def orphanHandler(evt) {
    def id = evt.deviceId?.toString()
    if (id == null) return

    if ("${evt.value}" == "true") {
        recordOrphan(id, evt.displayName)
        logDebug "${evt.displayName} went orphaned"
    } else {
        def orphans = state.orphans ?: [:]
        def rec = orphans.remove(id)
        state.orphans = orphans
        if (rec?.alerted) {
            def downFor = friendlyDuration(now() - (rec.since as Long))
            sendNotify("${evt.displayName} is reporting again (was orphaned for ${downFor}).", true)
        }
        logDebug "${evt.displayName} recovered"
    }
}

private recordOrphan(String id, String name) {
    def orphans = state.orphans ?: [:]
    if (!orphans.containsKey(id)) {
        orphans[id] = [since: now(), alerted: false, name: name]
        state.orphans = orphans
    }
}

// ------------------------------------------------------------ periodic evaluation

def checkStatus() {
    if (pauseApp) return

    def nowMs = now()

    // --- gateway heartbeat ---
    def gwAgeMin = (nowMs - (state.lastHeartbeat as Long)) / 60000L
    def gwLimit  = (gatewayTimeoutMinutes ?: 60) as Integer
    if (gwAgeMin >= gwLimit) {
        if (!state.gwAlerted) {
            state.gwAlerted = true
            state.gwAlertedAt = nowMs
            sendNotify("Ecowitt gateway has not reported for ${friendlyDuration(nowMs - (state.lastHeartbeat as Long))}. " +
                   "Check the gateway, its hub, and hub mesh.", false)
        } else {
            maybeRemind("gateway", nowMs)
        }
    }

    // --- sensor orphans ---
    def orphans = state.orphans ?: [:]
    def limit = (orphanConfirmMinutes ?: 120) as Integer
    orphans.each { id, rec ->
        def ageMin = (nowMs - (rec.since as Long)) / 60000L
        if (!rec.alerted && ageMin >= limit) {
            rec.alerted = true
            rec.alertedAt = nowMs
            sendNotify("${rec.name} has been orphaned for ${friendlyDuration(nowMs - (rec.since as Long))} " +
                   "- the gateway is not receiving it.", false)
        } else if (rec.alerted) {
            maybeRemindSensor(rec, nowMs)
        }
    }
    state.orphans = orphans

    logDebug "checkStatus: gateway ${Math.round(gwAgeMin)}m since heartbeat, ${orphans.size()} orphaned"
}

private maybeRemind(String key, Long nowMs) {
    def hrs = (reminderHours ?: 0) as Integer
    if (hrs <= 0) return
    def last = state.gwAlertedAt as Long
    if (last && (nowMs - last) >= hrs * 3600000L) {
        state.gwAlertedAt = nowMs
        sendNotify("Still no Ecowitt gateway heartbeat - down ${friendlyDuration(nowMs - (state.lastHeartbeat as Long))}.", false)
    }
}

private maybeRemindSensor(Map rec, Long nowMs) {
    def hrs = (reminderHours ?: 0) as Integer
    if (hrs <= 0) return
    def last = rec.alertedAt as Long
    if (last && (nowMs - last) >= hrs * 3600000L) {
        rec.alertedAt = nowMs
        sendNotify("${rec.name} still orphaned - down ${friendlyDuration(nowMs - (rec.since as Long))}.", false)
    }
}

// ------------------------------------------------------------ helpers

private sendNotify(String msg, Boolean isRecovery) {
    if (isRecovery && !notifyRecovery) return
    log.info "Ecowitt Watchdog: ${msg}"
    notifyDevices?.each { it.deviceNotification(msg) }
}

private String friendlyDuration(Long ms) {
    if (ms == null) return "unknown"
    def mins = Math.round(ms / 60000L)
    if (mins < 60) return "${mins} min"
    def hrs = mins / 60.0
    if (hrs < 48) return "${String.format('%.1f', hrs)} hours"
    return "${Math.round(hrs / 24)} days"
}

private String statusHtml() {
    def sb = new StringBuilder()
    def gwAge = state.lastHeartbeat ? friendlyDuration(now() - (state.lastHeartbeat as Long)) : "never"
    sb << "<b>Gateway:</b> last heartbeat ${gwAge} ago"
    sb << (state.gwAlerted ? " &mdash; <span style='color:red'>ALERTED</span>" : " &mdash; ok")
    sb << "<br>"
    def orphans = state.orphans ?: [:]
    if (orphans.isEmpty()) {
        sb << "<b>Sensors:</b> none orphaned"
    } else {
        sb << "<b>Orphaned:</b><br>"
        orphans.each { id, rec ->
            sb << "&nbsp;&nbsp;${rec.name} &mdash; ${friendlyDuration(now() - (rec.since as Long))}"
            sb << (rec.alerted ? " <span style='color:red'>(alerted)</span>" : " (within confirm window)")
            sb << "<br>"
        }
    }
    return sb.toString()
}

def appButtonHandler(String btn) {
    if (btn == "testNotify") {
        sendNotify("Ecowitt Watchdog test notification.", false)
    }
}

private logDebug(String msg) { if (logEnable) log.debug "Ecowitt Watchdog: ${msg}" }
