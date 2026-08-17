/*
 *  Blue Iris Status (MQTT)
 *
 *  Author: J.R. Farrar
 *
 *  Reads Blue Iris state from MQTT (BlueIris/status and BlueIris/app) and can set
 *  the Blue Iris profile by publishing to BlueIris/admin. Designed to be driven by
 *  a companion app that decides which profile Blue Iris should be in.
 *
 *  Blue Iris profile map (this install): 1 = Away, 2 = Day, 3 = Night
 *
 *  Notes on the "lock" value used when setting a profile (from Blue Iris help):
 *      0 = run  (Blue Iris follows its own schedule)
 *      1 = temp (temporary override)
 *      2 = hold (hold the profile)
 *  I am not 100% certain of the exact temp-vs-hold behavior in your BI version, so
 *  it is exposed as a preference below rather than hard-coded. Default is 1 (temp),
 *  which matches the lock value your status topic is currently reporting.
 */

metadata {
    definition(
        name: "Blue Iris Status (MQTT)",
        namespace: "jrfarrar",
        author: "J.R. Farrar",
        importUrl: "https://raw.githubusercontent.com/jrfarrar/hubitat/master/devicehandlers/BlueIrisStatus/BlueIrisStatus.groovy"
    ) {
        capability "Initialize"
        capability "Refresh"

        attribute "profile", "number"       // current BI profile number
        attribute "profileName", "string"   // friendly name for the above
        attribute "signal", "number"        // BI traffic signal (0 red / 1 green / 2 yellow)
        attribute "lock", "number"          // current lock state reported by BI
        attribute "schedule", "string"      // BI schedule name
        attribute "appStatus", "string"     // running / closing / unexpected stop, etc.
        attribute "connection", "string"    // connected / disconnected / stale
        attribute "lastUpdate", "string"    // human-readable time of last status message

        command "setProfile", [[name: "Profile*", type: "NUMBER", description: "1=Away, 2=Day, 3=Night"]]
        command "connect"
        command "disconnect"
    }

    preferences {
        input name: "brokerIp",     type: "text",   title: "MQTT broker IP",   required: true,  defaultValue: "192.168.13.4"
        input name: "brokerPort",   type: "text",   title: "MQTT broker port", required: true,  defaultValue: "1883"
        input name: "mqttUser",     type: "text",   title: "MQTT username (blank if none)", required: false
        input name: "mqttPass",     type: "password", title: "MQTT password (blank if none)", required: false
        input name: "baseTopic",    type: "text",   title: "Blue Iris base topic", required: true, defaultValue: "BlueIris"
        input name: "setLock",      type: "enum",   title: "Lock value to use when setting a profile",
              options: ["0": "0 - run (follow BI schedule)", "1": "1 - temp", "2": "2 - hold"], defaultValue: "1", required: true
        input name: "staleMinutes", type: "number", title: "Mark connection 'stale' after N minutes with no status", defaultValue: 10, required: true
        input name: "logEnable",    type: "bool",   title: "Enable debug logging", defaultValue: true
    }
}

// ---------- lifecycle ----------

def installed() {
    log.info "Blue Iris Status installed"
    initialize()
}

def updated() {
    log.info "Blue Iris Status updated"
    initialize()
}

def initialize() {
    unschedule()
    connect()
    // periodic staleness check
    runEvery5Minutes("checkStale")
}

def refresh() {
    debug "refresh requested"
    if (!interfaces.mqtt.isConnected()) connect()
}

// ---------- MQTT connection ----------

def connect() {
    // explicit disconnect first (avoids duplicate/half-open sessions)
    try { interfaces.mqtt.disconnect() } catch (ignored) {}

    try {
        def clientId = "hubitat-biStatus-${device.deviceNetworkId}"
        def uri = "tcp://${settings.brokerIp}:${(settings.brokerPort ?: 1883) as int}"
        debug "connecting to ${uri} as ${clientId}"
        sendEvent(name: "connection", value: "connecting")
        interfaces.mqtt.connect(uri, clientId, settings.mqttUser ?: null, settings.mqttPass ?: null)
        pauseExecution(800)
        subscribeTopics()
    } catch (e) {
        log.error "MQTT connect failed: ${e.message}"
        sendEvent(name: "connection", value: "disconnected")
        runIn(60, "connect")
    }
}

def disconnect() {
    try { interfaces.mqtt.disconnect() } catch (ignored) {}
    sendEvent(name: "connection", value: "disconnected")
}

private subscribeTopics() {
    def base = settings.baseTopic ?: "BlueIris"
    try {
        interfaces.mqtt.subscribe("${base}/status")
        interfaces.mqtt.subscribe("${base}/app")
        debug "subscribed to ${base}/status and ${base}/app"
    } catch (e) {
        log.error "subscribe failed: ${e.message}"
    }
}

def mqttClientStatus(String status) {
    if (status?.toLowerCase()?.contains("succeeded")) {
        debug "MQTT status: ${status}"
        sendEvent(name: "connection", value: "connected")
        subscribeTopics()   // idempotent; safe on reconnect
    } else {
        log.warn "MQTT status: ${status}"
        sendEvent(name: "connection", value: "disconnected")
        runIn(30, "connect")
    }
}

// ---------- incoming messages ----------

def parse(String description) {
    def msg = interfaces.mqtt.parseMessage(description)
    def topic = msg?.topic ?: ""
    def payload = msg?.payload ?: ""
    debug "rx [${topic}] ${payload}"

    if (topic.endsWith("/status")) {
        parseStatus(payload)
    } else if (topic.endsWith("/app")) {
        sendEvent(name: "appStatus", value: payload.trim())
    }
}

private parseStatus(String payload) {
    // Example: time=1782913189938 signal=1 profile=2 lock=1 schedule=Default
    def profile  = extractInt(payload, /profile=(\d+)/)
    def signal   = extractInt(payload, /signal=(\d+)/)
    def lockVal  = extractInt(payload, /lock=(\d+)/)
    def schedule = extractStr(payload, /schedule=(\S+)/)

    if (profile != null) {
        sendEvent(name: "profile", value: profile)
        sendEvent(name: "profileName", value: profileName(profile))
    }
    if (signal   != null) sendEvent(name: "signal",   value: signal)
    if (lockVal  != null) sendEvent(name: "lock",     value: lockVal)
    if (schedule != null) sendEvent(name: "schedule", value: schedule)

    state.lastStatusEpoch = now()
    sendEvent(name: "lastUpdate", value: new Date().format("yyyy-MM-dd HH:mm:ss", location.timeZone))
    sendEvent(name: "connection", value: "connected")
}

private Integer extractInt(String s, def pattern) {
    def m = (s =~ pattern)
    return m ? (m[0][1] as Integer) : null
}

private String extractStr(String s, def pattern) {
    def m = (s =~ pattern)
    return m ? m[0][1].toString() : null
}

private String profileName(Integer p) {
    // This install: 1=Away, 2=Day, 3=Night. Adjust here if your BI profiles change.
    switch (p) {
        case 1: return "Away"
        case 2: return "Day"
        case 3: return "Night"
        default: return "Profile ${p}"
    }
}

// ---------- setting the profile ----------

def setProfile(profile) {
    // NUMBER commands arrive as BigDecimal; use toInteger() (not .round())
    Integer p = (profile as BigDecimal).toInteger()
    def lock = (settings.setLock ?: "1")
    def topic = "${settings.baseTopic ?: 'BlueIris'}/admin"
    def payload = "profile=${p}&lock=${lock}"

    if (interfaces.mqtt.isConnected()) {
        interfaces.mqtt.publish(topic, payload)
        log.info "setProfile -> ${topic}  ${payload}"
    } else {
        log.warn "setProfile requested but MQTT not connected; reconnecting"
        connect()
    }
}

// ---------- staleness ----------

def checkStale() {
    def stale = (settings.staleMinutes ?: 10) as int
    if (!state.lastStatusEpoch) return
    def ageMin = (now() - state.lastStatusEpoch) / 60000
    if (ageMin > stale) {
        log.warn "No Blue Iris status for ${ageMin as int} min (threshold ${stale}); marking stale"
        sendEvent(name: "connection", value: "stale")
        if (!interfaces.mqtt.isConnected()) connect()
    }
}

// ---------- logging ----------

private debug(msg) {
    if (settings.logEnable) log.debug "BI Status: ${msg}"
}