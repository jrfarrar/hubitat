/**
 *  BI MQTT Motion Sensor Driver
 *
 *  Integrates Blue Iris camera motion detection into Hubitat via MQTT.
 *  Creates a motion sensor device that responds to MQTT messages published
 *  by Blue Iris when camera motion is detected.
 *
 *  Author: J.R. Farrar (jrfarrar)
 *
 *  MQTT Topic Structure:
 *  - Subscribes to: {topicName}/motion (expects "active" or "inactive")
 *  - Subscribes to: {topicName}/type (what triggered the alert)
 *  - Subscribes to: {topicName}/ai (optional - Blue Iris &MEMO, the AI findings, e.g. "person:81%")
 *  - {topicName} may have any number of levels, e.g. "BlueIris/CAM6" or "CAM6".
 *    The LAST level of the received topic decides what the message is.
 *
 *  Usage:
 *  1. Configure Blue Iris to publish motion events to MQTT broker
 *  2. Set the topic name to the prefix Blue Iris publishes under (e.g. BlueIris/CAM6)
 *  3. Driver will create motion sensor events for Hubitat rules/automations
 *
 *  Connection Management:
 *  - Automatic reconnection after a fixed delay plus jitter (retried until it succeeds)
 *  - Emergency brake prevents reconnection loops
 *  - Manual reset available via "Reset Connection" command
 *  - Optional scheduled watchdog for connection health monitoring
 *  - Quiet-connection check: after the configured quiet time, probe the link (publish to an own topic and
 *    wait for the echo); reconnect only if the probe does not come back
 *
 *  Version History:
 *  1.1.0 - 08/20/22 - Initial Release
 *  1.2.0 - 11/22/22 - Added active/inactive commands
 *  1.3.0 - 11/15/25 - Major cleanup and improvements:
 *                     - Removed legacy Garadget code
 *                     - Fixed reconnection storm issues
 *                     - Added emergency brake for reconnection loops
 *                     - Added connection uptime tracking
 *                     - Added last motion timestamp
 *                     - Better connection state management
 *                     - Improved logging and diagnostics
 *  1.3.1 - 02/04/26 - Fixed connection recovery: watchdog survives reboots, detects stale connections,
 *                      fixed cron for >59 min, added lastMessageReceived tracking
 *  1.4.0 - 11/14/26 - FIX: parse() read the 2nd topic level, so with a prefix such as "BlueIris/CAM6"
 *                      every motion/type message was dropped (since 1.3.0). Now reads the last level.
 *                     - Warns once per connection when a message arrives that the driver cannot use
 *                     - singleThreaded: no more lost state writes between parse() and reconnects
 *                     - A failed connect (at boot, on save, on reset) now schedules a retry
 *                     - Saving preferences reconnects, so a changed topic/broker takes effect
 *                     - Auto-inactive timeout (default 5 min, 0 = off) if Blue Iris never sends "inactive"
 *                     - New optional topic {topicName}/ai carrying Blue Iris &MEMO -> aiLabel, aiConfidence, aiSummary
 *                     - Uptime arithmetic fixed (36 h showed as "2d 12h"); uptime event hourly, not every 5 min
 *                     - Duplicate "connection lost" messages no longer count toward the emergency brake
 *  1.4.1 - 11/14/26 - A retained 'active' that the broker replays when the driver (re)subscribes no longer raises
 *                      motion: a stale value left at the broker fired rules at every reconnect. Hubitat does not
 *                      expose the MQTT retained flag, so anything arriving within 5 s of subscribing counts as a
 *                      replay. 'inactive', type and ai are still taken from the replay.
 *                     - Blue Iris sends the literal "(empty)" when an alert has no AI finding -> aiLabel "none"
 *  1.4.2 - 11/15/26 - One motion event per episode: Blue Iris publishes 'active' for every alert (2-4 within
 *                      seconds); only the first one of an episode is posted, the rest just restart the timeout.
 *                      A repeated 'inactive' is not posted either.
 *                     - Health check: silence is not an outage. After the quiet time the driver publishes a probe
 *                      to its own topic and reconnects only if the probe does not come back within 30 s
 *                      (was: reconnect, with a warning, every time a camera had been quiet).
 *                     - "nothing found" on /ai -> aiLabel "none"
 */

import groovy.transform.Field

@Field static final long REPLAY_WINDOW_MS = 5000   // see isReplay()
@Field static final int PROBE_WAIT_SECONDS = 30    // see probeLink()

metadata {
    definition (name: "BI MQTT Motion",
                namespace: "jrfarrar",
                author: "J.R. Farrar",
                singleThreaded: true,
                importUrl: "https://raw.githubusercontent.com/jrfarrar/hubitat/master/devicehandlers/BI%20MQTT%20Motion/BIMQTTMotion.groovy") {
        capability "Initialize"
        capability "Refresh"
        capability "Configuration"
        capability "Sensor"
        capability "Motion Sensor"

        attribute "type", "string"
        attribute "connectionStatus", "string"
        attribute "lastMotionTime", "string"
        attribute "connectionUptime", "string"
        attribute "reconnectCount", "number"
        attribute "aiLabel", "string"        // most confident AI finding of the last alert, e.g. person
        attribute "aiConfidence", "number"   // its confidence in percent
        attribute "aiSummary", "string"      // the AI findings exactly as Blue Iris sent them

        command "active"
        command "inactive"
        command "resetConnection"
    }

    preferences {
        section("<b>MQTT Connection Settings</b>") {
            // The setting is still called doorName (Garadget heritage): renaming it would blank it on every installed device
            input name: "doorName", type: "text", title: "Topic prefix Blue Iris publishes under", description: "e.g. BlueIris/CAM6 - the driver listens on &lt;prefix&gt;/motion, &lt;prefix&gt;/type and &lt;prefix&gt;/ai", required: true
            input name: "ipAddr", type: "text", title: "IP Address of MQTT broker", required: true
            input name: "ipPort", type: "text", title: "Port # of MQTT broker", defaultValue: "1883", required: true
            input name: "username", type: "text", title: "MQTT Username", description: "(blank if none)", required: false
            input name: "password", type: "password", title: "MQTT Password", description: "(blank if none)", required: false
        }
        section("<b>Motion</b>") {
            input name: "autoInactiveMinutes", type: "number", title: "Force inactive after this many minutes without an 'inactive' from Blue Iris", defaultValue: 5, range: "0..1440", required: false, description: "Default 5. 0 = never. A safety net for a missed or never-sent 'inactive'; every new 'active' restarts the timer."
        }
        section("<b>Connection Management</b>") {
            input name: "retryTime", type: "number", title: "Seconds between reconnection attempts", defaultValue: 300, range: "60..3600", required: true
            input name: "watchDogSched", type: "bool", title: "Enable scheduled connection health checks?", defaultValue: false, required: true
            input name: "watchDogTime", type: "number", title: "Minutes between health checks", defaultValue: 15, range: "1..240", required: true, description: "1-59 = every X minutes, 60-240 = converted to hourly (e.g., 120 = every 2 hours)"
            input name: "staleMinutes", type: "number", title: "Minutes without data before the health check probes the link", defaultValue: 60, range: "5..1440", required: true, description: "After this much silence the health check publishes a probe to its own topic. It reconnects only if the probe does not come back within 30 seconds - a quiet camera is left alone."
        }
        section("<b>Logging</b>") {
            input name: "logLevel", title: "IDE logging level", multiple: false, required: true, type: "enum", options: getLogLevels(), submitOnChange: false, defaultValue: "1"
        }
    }
}

void setVersion() {
    state.name = "BI MQTT Motion"
    state.version = "1.4.2"
}

// ---------------------------------------------------------------- lifecycle

void installed() {
    log.warn "installed..."
    setVersion()
    state.totalReconnects = 0
    state.lastMessageReceived = 0
    sendEvent(name: "reconnectCount", value: 0)
    sendEvent(name: "connectionStatus", value: "Not Connected")
}

void updated() {
    infolog "updated..."
    // Reconnect, so a changed topic or broker takes effect and the schedules are rebuilt
    initialize()
}

void uninstalled() {
    infolog "Disconnecting from MQTT..."
    unschedule()
    try { interfaces.mqtt.disconnect() } catch (e) { }
}

void initialize() {
    infolog "Initializing MQTT connection..."
    setVersion()

    // Start from a clean slate: no pending retries, no loop lock-out
    unschedule()
    state.reconnecting = false
    state.loopDetected = false
    state.reconnectHistory = []
    if (state.lastMessageReceived == null) state.lastMessageReceived = 0

    // Schedules first - they must exist even if the connect below fails (hub boot before the broker)
    setupSchedules()

    if (!doorName || !ipAddr) {
        log.error "${dName()}: Topic prefix or broker address not configured! Please configure the device settings."
        sendEvent(name: "connectionStatus", value: "Not configured")
        return
    }

    if (!connectMqtt()) connectionLost()

    // unschedule() above also dropped a running motion timeout - put it back if motion is active
    Integer mins = autoInactiveMins()
    if (mins > 0 && device.currentValue("motion", true) == "active") runIn(mins * 60, autoInactive)

    // If logs are in "Need Help" turn down to "Running" after an hour
    if (currentLogLevel() == 2) runIn(3600, logsOff)
}

void configure() {
    infolog "Configuring..."
    watchDog()
}

void refresh() {
    watchDog()
    setVersion()
    updateConnectionUptime()
    infolog "Refreshed - MQTT connection status: ${interfaces.mqtt.isConnected()}"
}

/**
 * Central schedule setup - called from initialize() (hub boot, Save Preferences, Initialize)
 */
void setupSchedules() {
    if (watchDogSched) {
        Integer mins = (watchDogTime ?: 15) as Integer
        if (mins <= 59) {
            debuglog "Setting schedule to check MQTT broker connection every ${mins} minute(s)"
            schedule("44 0/${mins} * ? * *", watchDog)
        } else {
            Integer hours = Math.round(mins / 60.0) as Integer
            if (hours < 1) hours = 1
            if (hours > 23) hours = 23
            debuglog "Setting schedule to check MQTT broker connection every ${hours} hour(s)"
            schedule("44 7 0/${hours} ? * *", watchDog)
        }
    }

    // Uptime attribute once an hour (was every 5 minutes: 288 events a day per camera)
    schedule("13 7 * ? * *", updateConnectionUptime)
}

// ---------------------------------------------------------------- messages

// Parse incoming MQTT messages to generate events
void parse(String description) {
    def msg = interfaces.mqtt.parseMessage(description)
    String topic = msg?.topic ?: ""
    String payload = msg?.payload?.toString()?.trim()

    // Track message receipt for the quiet-connection check
    state.lastMessageReceived = now()

    // The LAST level says what this is: <prefix>/motion or <prefix>/type, whatever the prefix depth
    List levels = topic.tokenize('/')
    String kind = levels ? levels.last() : ""
    debuglog "Topic: ${topic}, Message: ${payload}"

    if (topic == probeTopic()) {
        // Our own probe coming back: the link is alive. (A stray or old probe just counts as traffic.)
        if (state.probeNonce && payload == state.probeNonce) {
            state.probeNonce = null
            state.probeAnsweredAt = now()
            debuglog "Probe echoed - link is alive"
        }
    } else if (kind == "motion") {
        handleMotion(payload, isReplay())
    } else if (kind == "type") {
        if (payload) {
            infolog "Type: ${payload}"
            sendEvent(name: "type", value: payload)
        }
    } else if (kind == "ai") {
        handleAi(payload)
    } else {
        warnUnusable("topic '${topic}' (expected ${doorName}/motion, /type or /ai)")
    }
}

// True for the first seconds after subscribing: what arrives then is the broker replaying retained values,
// not something that just happened. (interfaces.mqtt.parseMessage gives topic and payload only - no retained flag.)
private boolean isReplay() {
    if (!state.subscribedAt) return false
    return (now() - (state.subscribedAt as long)) < REPLAY_WINDOW_MS
}

private void handleMotion(String payload, boolean replay = false) {
    if (payload == "active" && replay) {
        // Only a live 'active' is motion. A replayed one may be hours old (Blue Iris missed its 'inactive').
        infolog "Ignoring retained 'active' replayed at connect - motion stays ${device.currentValue('motion')}"
    } else if (payload == "active") {
        // Blue Iris publishes 'active' for every alert of a trigger. One event per episode: post the first,
        // let the others only restart the timeout.
        if (device.currentValue("motion", true) != "active") {
            infolog "Motion detected - active"
            sendEvent(name: "motion", value: "active", descriptionText: "${dName()} motion is active")
            sendEvent(name: "lastMotionTime", value: new Date().format("yyyy-MM-dd HH:mm:ss", location.timeZone))
        } else {
            debuglog "Another 'active' in the same episode - timeout restarted"
        }
        state.lastMotion = now()
        Integer mins = autoInactiveMins()
        if (mins > 0) runIn(mins * 60, autoInactive)      // every 'active' restarts the timer
    } else if (payload == "inactive") {
        unschedule(autoInactive)
        if (device.currentValue("motion", true) != "inactive") {
            infolog "Motion cleared - inactive"
            sendEvent(name: "motion", value: "inactive", descriptionText: "${dName()} motion is inactive")
        } else {
            debuglog "'inactive' while already inactive - nothing to post"
        }
    } else {
        warnUnusable("motion payload '${payload}' (expected active or inactive)")
    }
}

/**
 * Blue Iris &MEMO = the alert's AI findings, e.g. "person:81%" (manual, macro list).
 * Several findings are expected comma-separated; the most confident one becomes aiLabel/aiConfidence.
 * Publish this BEFORE the motion message in Blue Iris so a rule fired by motion sees the new label.
 */
private void handleAi(String payload) {
    if (!payload) return
    infolog "AI: ${payload}"
    sendEvent(name: "aiSummary", value: payload)

    // Blue Iris sends the literal text "(empty)" when the alert carries no AI result, and "nothing found"
    // when the AI ran and found nothing
    if (payload.equalsIgnoreCase("(empty)") || payload.equalsIgnoreCase("nothing found")) {
        sendEvent(name: "aiLabel", value: "none")
        sendEvent(name: "aiConfidence", value: 0, unit: "%")
        return
    }

    String bestLabel = null
    Integer bestConf = -1
    payload.split(/[,;]/).each { String part ->
        def m = (part =~ /^\s*([^:]+?)\s*:\s*(\d{1,3})\s*%?\s*$/)
        if (m.matches()) {
            Integer conf = m.group(2) as Integer
            if (conf > bestConf) { bestConf = conf; bestLabel = m.group(1).toLowerCase() }
        }
    }

    if (bestLabel != null) {
        sendEvent(name: "aiLabel", value: bestLabel)
        sendEvent(name: "aiConfidence", value: bestConf, unit: "%")
    } else {
        // Not label:NN% - keep what was sent as the label, claim no confidence
        sendEvent(name: "aiLabel", value: payload.toLowerCase())
        sendEvent(name: "aiConfidence", value: 0, unit: "%")
    }
}

// Unset = 5 minutes; an explicit 0 turns the timeout off
private Integer autoInactiveMins() {
    return autoInactiveMinutes == null ? 5 : (autoInactiveMinutes as Integer)
}

// Safety net: Blue Iris never sent 'inactive'
void autoInactive() {
    if (device.currentValue("motion") == "active") {
        log.warn "${dName()}: no 'inactive' from Blue Iris within ${autoInactiveMins()} min - setting motion inactive"
        sendEvent(name: "motion", value: "inactive", descriptionText: "${dName()} motion is inactive (timeout)")
    }
}

// A message the driver cannot use must be loud, not silent - but only once per connection
private void warnUnusable(String what) {
    if (state.unusableWarned) {
        debuglog "Ignored ${what}"
    } else {
        state.unusableWarned = true
        log.warn "${dName()}: ignoring ${what} - check the topic prefix setting. (Further ones are logged at debug level only.)"
    }
}

void active() {
    infolog "Manual active command called"
    sendEvent(name: "motion", value: "active")
}

void inactive() {
    infolog "Manual inactive command called"
    unschedule(autoInactive)
    sendEvent(name: "motion", value: "inactive")
}

// ---------------------------------------------------------------- connection

/**
 * Connect to the broker and subscribe. Disconnects first to clear a stale connection.
 * Returns true only when the client reports connected and the subscriptions were made.
 * It never schedules a retry itself - the caller decides (connectionLost()).
 */
boolean connectMqtt() {
    try {
        if (interfaces.mqtt.isConnected()) {
            debuglog "Disconnecting existing connection before reconnect..."
            interfaces.mqtt.disconnect()
            pauseExecution(500)
        }

        String broker = "tcp://${ipAddr}:${ipPort}"
        String clientId = "Hubitat_BI_Motion_${doorName}_${device.deviceNetworkId}"
        interfaces.mqtt.connect(broker, clientId, username, password)

        // Give it a chance to start
        pauseExecution(1000)
        if (!interfaces.mqtt.isConnected()) {
            log.warn "${dName()}: MQTT connect to ${broker} did not come up"
            sendEvent(name: "connectionStatus", value: "Error: not connected")
            return false
        }

        state.subscribedAt = now()       // start of the replay window - see isReplay()
        interfaces.mqtt.subscribe("${doorName}/motion")
        interfaces.mqtt.subscribe("${doorName}/type")
        interfaces.mqtt.subscribe("${doorName}/ai")
        interfaces.mqtt.subscribe(probeTopic())
        state.probeNonce = null
        infolog "Connected to ${broker}, subscribed to ${doorName}/motion, /type and /ai"

        state.reconnecting = false
        state.unusableWarned = false
        state.connectionEstablished = now()
        sendEvent(name: "connectionStatus", value: "Connected")
        updateConnectionUptime()
        return true
    } catch (e) {
        log.warn "${dName()}: MQTT connect error: ${e.message}"
        sendEvent(name: "connectionStatus", value: "Error: ${e.message}")
        return false
    }
}

/**
 * Force disconnect and reconnect. Used when the connection is suspected stale
 * (isConnected() can stay true on a half-open TCP connection). Returns connectMqtt()'s result.
 */
boolean reconnectMqtt() {
    infolog "Forcing MQTT reconnect - disconnecting first..."
    sendEvent(name: "connectionStatus", value: "Reconnecting")

    try {
        interfaces.mqtt.disconnect()
    } catch (e) {
        debuglog "Disconnect error (expected if already disconnected): ${e.message}"
    }

    pauseExecution(2000)
    return connectMqtt()
}

void resetConnection() {
    log.warn "${dName()}: Manual connection reset - clearing all reconnection states and scheduled retries"
    unschedule(attemptReconnect)
    state.reconnecting = false
    state.loopDetected = false
    state.reconnectHistory = []
    state.totalReconnects = 0
    sendEvent(name: "reconnectCount", value: 0)

    if (reconnectMqtt()) {
        infolog "Connection reset complete"
    } else {
        connectionLost()
    }
}

/**
 * Watchdog - checks MQTT connection health: isConnected() AND whether data arrived recently.
 * Runs on Refresh/Configure, and on a schedule when enabled.
 */
void watchDog() {
    debuglog "Watchdog: Checking MQTT status"

    if (state.loopDetected) {
        log.warn "${dName()}: Watchdog: Reconnection loop was previously detected. Click 'Reset Connection' to try again."
        return
    }
    if (state.reconnecting) {
        debuglog "Watchdog: Reconnection already in progress, skipping"
        return
    }

    boolean connected = interfaces.mqtt.isConnected()
    // Silence is counted from the last message, or from the connect if nothing has arrived since
    long lastSeen = Math.max((state.lastMessageReceived ?: 0) as long, (state.connectionEstablished ?: 0) as long)
    long elapsed = lastSeen > 0 ? now() - lastSeen : -1
    long quietThreshold = ((staleMinutes ?: 60) as long) * 60 * 1000

    if (!connected) {
        log.warn "${dName()}: Watchdog: MQTT disconnected - triggering reconnection"
        connectionLost()
    } else if (state.probeNonce) {
        debuglog "Watchdog: a probe is already out, waiting for it"
    } else if (elapsed > quietThreshold) {
        // Connected but silent: a quiet camera or a dead link. Ask the link itself before touching it.
        debuglog "Watchdog: no data in ${elapsed.intdiv(60000)} minutes - probing the link"
        probeLink()
    } else {
        debuglog "Watchdog: MQTT OK (connected: ${connected}, last data: ${elapsed >= 0 ? elapsed.intdiv(60000) + ' min ago' : 'never'})"
    }
}

// The driver's own echo topic. The device network id keeps devices apart; the payload is a one-time value,
// so another device or hub using the same topic cannot answer for this one.
private String probeTopic() {
    return "hubitat/bimqttmotion/${device.deviceNetworkId.toString().replaceAll(/[^A-Za-z0-9_-]/, '_')}/probe"
}

/**
 * Publish a one-time value to our own topic. If the link is alive the broker sends it straight back
 * (parse() clears state.probeNonce); verifyProbe() looks PROBE_WAIT_SECONDS later.
 */
private void probeLink() {
    String nonce = "${now()}-${new Random().nextInt(1000000)}"
    state.probeNonce = nonce
    state.probeSentAt = now()
    try {
        interfaces.mqtt.publish(probeTopic(), nonce)
        runIn(PROBE_WAIT_SECONDS, verifyProbe)
    } catch (e) {
        state.probeNonce = null
        log.warn "${dName()}: Watchdog: probe could not be published (${e.message}) - reconnecting"
        sendEvent(name: "connectionStatus", value: "Stale - reconnecting")
        if (!reconnectMqtt()) connectionLost()
    }
}

void verifyProbe() {
    if (!state.probeNonce) {
        debuglog "Probe was answered - nothing to do"
        return
    }
    state.probeNonce = null
    log.warn "${dName()}: Watchdog: probe not echoed within ${PROBE_WAIT_SECONDS} s - the link is dead, reconnecting"
    sendEvent(name: "connectionStatus", value: "Stale - reconnecting")
    if (!reconnectMqtt()) connectionLost()
}

void mqttClientStatus(String message) {
    state.lastStatusMessage = message
    state.lastStatusTime = new Date().format("HH:mm:ss", location.timeZone)

    if (message.contains("Connection lost") || message.contains("Client is not connected")) {
        log.warn "${dName()}: MQTT status: ${message}"
        connectionLost()
    } else if (message.contains("succeeded")) {
        debuglog "MQTT status: ${message}"
    } else {
        log.warn "${dName()}: MQTT status: ${message}"
    }
}

// Connection dropped or could not be made: schedule ONE reconnection attempt (prevents reconnection storms)
void connectionLost() {
    // A queued 'lost' message that arrives after we are already back is old news
    if (interfaces.mqtt.isConnected()) {
        debuglog "connectionLost called but the client is connected - ignoring"
        return
    }

    sendEvent(name: "connectionStatus", value: "Disconnected")

    // A retry is already pending: nothing more to do, and do not count this toward the brake
    if (state.reconnecting) {
        debuglog "Reconnection already scheduled, skipping duplicate"
        return
    }

    Integer total = ((state.totalReconnects ?: 0) as Integer) + 1
    state.totalReconnects = total
    sendEvent(name: "reconnectCount", value: total)

    // Emergency brake - 3 scheduled attempts inside one minute means something is looping
    long nowMs = now()
    List history = (state.reconnectHistory ?: []).findAll { it >= (nowMs - 60000) }
    if (history.size() >= 3) {
        log.error "${dName()}: RECONNECTION LOOP DETECTED! Stopping automatic reconnection. Please click 'Reset Connection' to reconnect."
        state.reconnectHistory = history
        state.loopDetected = true
        sendEvent(name: "connectionStatus", value: "Loop detected - manual reset required")
        return
    }
    history << nowMs
    state.reconnectHistory = history
    state.reconnecting = true

    // Jitter (0-30 seconds) so all cameras do not reconnect at the same instant
    Integer retryDelay = ((retryTime ?: 300) as Integer) + new Random().nextInt(30)
    log.warn "${dName()}: Connection lost - scheduling reconnection attempt in ${retryDelay} seconds..."
    runIn(retryDelay, attemptReconnect)
}

void attemptReconnect() {
    if (state.loopDetected) {
        log.error "${dName()}: Reconnection loop detected previously. Click 'Reset Connection' command to try again."
        sendEvent(name: "connectionStatus", value: "Loop detected - manual reset required")
        return
    }

    state.reconnecting = false

    if (interfaces.mqtt.isConnected()) {
        infolog "Already reconnected!"
        sendEvent(name: "connectionStatus", value: "Connected")
        return
    }

    infolog "Attempting to reconnect..."
    if (reconnectMqtt()) {
        infolog "Reconnection successful!"
    } else {
        log.warn "${dName()}: Reconnection failed, will retry..."
        connectionLost()      // schedules the next attempt, with loop detection
    }
}

// ---------------------------------------------------------------- uptime

void updateConnectionUptime() {
    if (state.connectionEstablished && interfaces.mqtt.isConnected()) {
        String uptimeStr = formatUptime(now() - (state.connectionEstablished as long))
        sendEvent(name: "connectionUptime", value: uptimeStr)
        debuglog "Connection uptime: ${uptimeStr}"
    } else {
        sendEvent(name: "connectionUptime", value: "Not connected")
    }
}

// Whole units only - no rounding up (36 h is "1d 12h", not "2d 12h")
String formatUptime(Long milliseconds) {
    long seconds = milliseconds.intdiv(1000)
    long minutes = seconds.intdiv(60)
    long hours = minutes.intdiv(60)
    long days = hours.intdiv(24)

    if (days > 0) return "${days}d ${hours % 24}h"
    if (hours > 0) return "${hours}h ${minutes % 60}m"
    if (minutes > 0) return "${minutes}m"
    return "${seconds}s"
}

// ---------------------------------------------------------------- logging

private String dName() { return device.label ?: device.name }

private Integer currentLogLevel() { return logLevel ? logLevel.toInteger() : 1 }

void logsOff() {
    log.warn "${dName()}: Debug logging disabled"
    device.updateSetting("logLevel", [value: "1", type: "enum"])
}

void debuglog(statement) {
    if (currentLogLevel() >= 2) log.debug("${dName()}: ${statement}")
}

void infolog(statement) {
    if (currentLogLevel() >= 1) log.info("${dName()}: ${statement}")
}

def getLogLevels() {
    return [["0":"None"],["1":"Running"],["2":"NeedHelp"]]
}