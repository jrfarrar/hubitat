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
 *  - Subscribes to: {topicName}/type (camera type identifier)
 *
 *  Usage:
 *  1. Configure Blue Iris to publish motion events to MQTT broker
 *  2. Set up topic name to match BI camera short name
 *  3. Driver will create motion sensor events for Hubitat rules/automations
 *
 *  Connection Management:
 *  - Automatic reconnection with exponential backoff and jitter
 *  - Emergency brake prevents reconnection loops
 *  - Manual reset available via "Reset Connection" command
 *  - Optional scheduled watchdog for connection health monitoring
 *  - Staleness detection: force reconnect if no data received within configured time
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
 */

metadata {
    definition (name: "BI MQTT Motion", 
                namespace: "jrfarrar", 
                author: "J.R. Farrar",
                importUrl: "https://github.com/jrfarrar/hubitat/blob/master/devicehandlers/BI%20MQTT%20Motion/BIMQTTMotion.groovy") {
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
        
        command "active"
        command "inactive"
        command "resetConnection"

        
    preferences {
        section("<b>MQTT Connection Settings</b>") {
            input name: "doorName", type: "text", title: "Topic Name (Camera/Sensor Name)", required: true
            input name: "ipAddr", type: "text", title: "IP Address of MQTT broker", required: true
            input name: "ipPort", type: "text", title: "Port # of MQTT broker", defaultValue: "1883", required: true
            input name: "username", type: "text", title: "MQTT Username", description: "(blank if none)", required: false
            input name: "password", type: "password", title: "MQTT Password", description: "(blank if none)", required: false
        }
        section("<b>Connection Management</b>") {
            input name: "retryTime", type: "number", title: "Seconds between reconnection attempts", defaultValue: 300, range: "60..3600", required: true
            input name: "watchDogSched", type: "bool", title: "Enable scheduled connection health checks?", defaultValue: false, required: true
            input name: "watchDogTime", type: "number", title: "Minutes between health checks", defaultValue: 15, range: "1..240", required: true, description: "1-59 = every X minutes, 60-240 = converted to hourly (e.g., 120 = every 2 hours)"
            input name: "staleMinutes", type: "number", title: "Minutes without data before forcing reconnect", defaultValue: 60, range: "5..1440", required: true, description: "If no MQTT messages received within this time, force reconnect. Set higher for low-traffic cameras."
        }
        section("<b>Logging</b>") {
            input name: "logLevel", title: "IDE logging level", multiple: false, required: true, type: "enum", options: getLogLevels(), submitOnChange: false, defaultValue: "1"
        }
    }
  }
}

def setVersion(){
    state.name = "BI MQTT Motion"
    state.version = "1.3.1"   
}

void installed() {
    log.warn "installed..."
    setVersion()
    state.totalReconnects = 0
    state.lastMessageReceived = 0
    sendEvent(name: "reconnectCount", value: 0)
    sendEvent(name: "connectionStatus", value: "Not Connected")
}

// Parse incoming device messages to generate events
void parse(String description) {
    topicFull = interfaces.mqtt.parseMessage(description).topic
    def topic = topicFull.split('/')
    def topicType = topic[1]  // Gets the second part of topic (motion or type)
    def message = interfaces.mqtt.parseMessage(description).payload
    
    // Track message receipt for staleness detection
    state.lastMessageReceived = now()
    
    debuglog "Topic: ${topicType}, Message: ${message}"

    if (topicType) {
        if (topicType == "motion") {
            if (message) {
                if (message == "active") {
                    infolog "Motion detected - active"
                    sendEvent(name: "motion", value: "active")
                    def timestamp = new Date().format("yyyy-MM-dd HH:mm:ss", location.timeZone)
                    sendEvent(name: "lastMotionTime", value: timestamp)
                    state.lastMotion = now()
                } else if (message == "inactive") {
                    infolog "Motion cleared - inactive"
                    sendEvent(name: "motion", value: "inactive")
                } else {
                    debuglog "Unknown motion message: ${message}"
                }
            } 
        } else if (topicType == "type") {
            infolog "Type: ${message}"
            sendEvent(name: "type", value: message) 
        }
    }
}

void refresh(){
    watchDog()
    setVersion()
    updateConnectionUptime()
    infolog "Refreshed - MQTT connection status: ${interfaces.mqtt.isConnected()}"
}

def updateConnectionUptime() {
    if (state.connectionEstablished) {
        def uptime = now() - state.connectionEstablished
        def uptimeStr = formatUptime(uptime)
        sendEvent(name: "connectionUptime", value: uptimeStr)
        debuglog "Connection uptime: ${uptimeStr}"
    } else {
        sendEvent(name: "connectionUptime", value: "Unknown")
    }
}

def formatUptime(milliseconds) {
    def seconds = Math.round(milliseconds / 1000)
    def minutes = Math.round(seconds / 60)
    def hours = Math.round(minutes / 60)
    def days = Math.round(hours / 24)
    
    if (days > 0) {
        return "${days}d ${hours % 24}h"
    } else if (hours > 0) {
        return "${hours}h ${minutes % 60}m"
    } else if (minutes > 0) {
        return "${minutes}m"
    } else {
        return "${seconds}s"
    }
}

void updated() {
    infolog "updated..."
    configure()
    unschedule()
    pauseExecution(1000)
    
    // Set up all schedules (watchdog, uptime tracker)
    setupSchedules()
}

/**
 * Central schedule setup - called from both updated() and initialize()
 * Ensures watchdog and uptime tracker survive hub reboots
 */
void setupSchedules() {
    // Schedule the watchdog to run in case the broker restarts
    if (watchDogSched) {
        if (watchDogTime <= 59) {
            debuglog "Setting schedule to check MQTT broker connection every ${watchDogTime} minute(s)"
            schedule("44 0/${watchDogTime} * ? * *", watchDog)
        } else {
            def hours = Math.round(watchDogTime / 60.0).toInteger()
            if (hours < 1) hours = 1
            if (hours > 23) hours = 23
            debuglog "Setting schedule to check MQTT broker connection every ${hours} hour(s)"
            schedule("44 7 0/${hours} ? * *", watchDog)
        }
    }
    
    // Schedule uptime updates every 5 minutes
    schedule("13 */5 * ? * *", updateConnectionUptime)
}

void uninstalled() {
    infolog "Disconnecting from MQTT..."
    interfaces.mqtt.disconnect()
    unschedule()
}

void initialize() {
    infolog "Initializing MQTT connection..."
    
    // Clear any reconnection loop states
    unschedule(attemptReconnect)
    state.reconnecting = false
    state.loopDetected = false
    state.reconnectHistory = []
    
    // Initialize state variables
    if (state.lastMessageReceived == null) state.lastMessageReceived = 0
    
    if (!doorName) {
        log.error "Topic Name not configured! Please configure the device settings."
        return
    }
    
    // Connect to MQTT broker
    connectMqtt()
    
    // Set up schedules - critical for surviving hub reboots
    // unschedule first to avoid duplicates since initialize() can be called multiple times
    unschedule()
    pauseExecution(500)
    setupSchedules()
    
    // If logs are in "Need Help" turn down to "Running" after an hour
    logL = logLevel.toInteger()
    if (logL == 2) runIn(3600, logsOff)
}

/**
 * Connect to MQTT broker and subscribe to topics
 * Separated from initialize() so it can be called independently for reconnection
 * Includes disconnect-first logic to clear stale connections
 */
void connectMqtt() {
    try {
        // Disconnect first if already connected (cleanup stale connections)
        if (interfaces.mqtt.isConnected()) {
            debuglog "Disconnecting existing connection before reconnect..."
            interfaces.mqtt.disconnect()
            pauseExecution(500)
        }
        
        // Open connection
        def mqttInt = interfaces.mqtt
        mqttbroker = "tcp://" + ipAddr + ":" + ipPort
        mqttclientname = "Hubitat_BI_Motion_" + doorName + "_" + device.deviceNetworkId
        mqttInt.connect(mqttbroker, mqttclientname, username, password)
        
        // Give it a chance to start
        pauseExecution(1000)
        infolog "Connection established to ${mqttbroker}"
        
        // Clear reconnecting flag on successful connection
        state.reconnecting = false
        state.connectionEstablished = now()
        sendEvent(name: "connectionStatus", value: "Connected")
        updateConnectionUptime()
        
        // Subscribe to motion and type topics
        mqttInt.subscribe("${doorName}/motion")
        mqttInt.subscribe("${doorName}/type")
        infolog "Subscribed to topics: ${doorName}/motion and ${doorName}/type"
        
    } catch(e) {
        log.warn "${device.label?device.label:device.name}: MQTT connect error: ${e.message}"
        sendEvent(name: "connectionStatus", value: "Error: ${e.message}")
    }
}

/**
 * Force disconnect and reconnect to MQTT broker
 * Used when connection is suspected stale (isConnected() lies about half-open TCP connections)
 * Preserves emergency brake and loop detection logic
 */
void reconnectMqtt() {
    infolog "Forcing MQTT reconnect - disconnecting first..."
    sendEvent(name: "connectionStatus", value: "Reconnecting")
    
    try {
        interfaces.mqtt.disconnect()
    } catch(e) {
        debuglog "Disconnect error (expected if already disconnected): ${e.message}"
    }
    
    pauseExecution(2000)
    
    infolog "Reconnecting to MQTT broker..."
    connectMqtt()
}

void configure(){
    infolog "Configuring..."
    watchDog()
}

void active(){
    infolog "Manual active command called"
    sendEvent(name: "motion", value: "active")
}

void inactive(){
    infolog "Manual inactive command called"
    sendEvent(name: "motion", value: "inactive")
}

void resetConnection(){
    log.warn "Manual connection reset - clearing all reconnection states and scheduled tasks"
    unschedule(attemptReconnect)
    state.reconnecting = false
    state.loopDetected = false
    state.reconnectHistory = []
    state.totalReconnects = 0
    sendEvent(name: "reconnectCount", value: 0)
    
    // Use reconnectMqtt for clean disconnect-then-connect
    reconnectMqtt()
    
    infolog "Connection reset complete"
}

/**
 * Watchdog - checks MQTT connection health
 * Now checks both isConnected() AND whether we've received data recently
 */
def watchDog() {
    debuglog "Watchdog: Checking MQTT status"
    
    // If loop was previously detected, don't auto-reconnect
    if (state.loopDetected) {
        log.warn "Watchdog: Reconnection loop was previously detected. Click 'Reset Connection' to try again."
        return
    }
    
    // If already reconnecting, don't pile on
    if (state.reconnecting) {
        debuglog "Watchdog: Reconnection already in progress, skipping"
        return
    }
    
    def connected = interfaces.mqtt.isConnected()
    def lastMsg = state.lastMessageReceived ?: 0
    def elapsed = lastMsg > 0 ? now() - lastMsg : -1
    def staleThreshold = (staleMinutes ?: 60) * 60 * 1000
    
    if (!connected) {
        // Definitely disconnected - use connectionLost for proper tracking
        log.warn "Watchdog: MQTT disconnected - triggering reconnection"
        connectionLost()
    } else if (lastMsg > 0 && elapsed > staleThreshold) {
        // Connected but no data - likely a ghost/stale connection
        def minsSince = Math.round(elapsed / 60000)
        log.warn "Watchdog: MQTT reports connected but no data in ${minsSince} minutes - forcing reconnect"
        sendEvent(name: "connectionStatus", value: "Stale - reconnecting")
        reconnectMqtt()
    } else {
        debuglog "Watchdog: MQTT OK (connected: ${connected}, last data: ${elapsed > 0 ? Math.round(elapsed / 60000) + ' min ago' : 'never'})"
    }
}

void mqttClientStatus(String message) {
    log.warn "${device.label?device.label:device.name}: Status message: ${message}"
    
    // Track connection state changes
    def currentState = device.currentValue("motion")
    def timestamp = new Date().format("HH:mm:ss")
    state.lastStatusMessage = message
    state.lastStatusTime = timestamp
    
    if (message.contains("Connection lost") || message.contains("Client is not connected")) {
        connectionLost()
    } else if (message.contains("succeeded")) {
        debuglog "Connection succeeded notification received"
        state.reconnecting = false
    }
}

// If connection is dropped, schedule a single reconnection attempt (prevents reconnection storms)
void connectionLost(){
    // Update connection status
    sendEvent(name: "connectionStatus", value: "Disconnected")
    
    // Track reconnection count
    def reconnectCount = (state.totalReconnects ?: 0) + 1
    state.totalReconnects = reconnectCount
    sendEvent(name: "reconnectCount", value: reconnectCount)
    
    // Emergency brake - if we've reconnected more than 3 times in the last minute, stop trying
    def now = now()
    if (!state.reconnectHistory) state.reconnectHistory = []
    
    // Clean old entries (older than 1 minute)
    state.reconnectHistory.removeAll { it < (now - 60000) }
    
    // Check if we're in a reconnection loop
    if (state.reconnectHistory.size() >= 3) {
        log.error "RECONNECTION LOOP DETECTED! Stopping automatic reconnection. Please click 'Reset Connection' to reconnect."
        state.reconnecting = false
        state.loopDetected = true
        sendEvent(name: "connectionStatus", value: "Loop detected - manual reset required")
        return
    }
    
    // Add this attempt to history
    state.reconnectHistory << now
    
    // Check if we're already trying to reconnect
    if (state.reconnecting) {
        debuglog "Reconnection already scheduled, skipping duplicate attempt"
        return
    }
    
    state.reconnecting = true
    
    // Add some jitter (0-30 seconds) to prevent all cameras from reconnecting simultaneously
    def jitter = new Random().nextInt(30)
    def retryDelay = retryTime + jitter
    
    log.warn "Connection lost - scheduling reconnection attempt in ${retryDelay} seconds..."
    runIn(retryDelay, attemptReconnect)
}

void attemptReconnect() {
    // Check if loop was detected and we're locked out
    if (state.loopDetected) {
        log.error "Reconnection loop detected previously. Click 'Reset Connection' command to try again."
        sendEvent(name: "connectionStatus", value: "Loop detected - manual reset required")
        return
    }
    
    if (interfaces.mqtt.isConnected()) {
        infolog "Already reconnected!"
        state.reconnecting = false
        sendEvent(name: "connectionStatus", value: "Connected")
        return
    }
    
    infolog "Attempting to reconnect..."
    try {
        reconnectMqtt()
        pauseExecution(2000)
        
        if (interfaces.mqtt.isConnected()) {
            infolog "Reconnection successful!"
            state.reconnecting = false
        } else {
            log.warn "Reconnection failed, will retry..."
            state.reconnecting = false
            // Schedule another attempt (goes through connectionLost for loop detection)
            connectionLost()
        }
    } catch(e) {
        log.error "Reconnection error: ${e.message}"
        state.reconnecting = false
        // Schedule another attempt
        connectionLost()
    }
}
    
// Logging functions
def logsOff(){
    log.warn "Debug logging disabled"
    device.updateSetting("logLevel", [value: "1", type: "enum"])
}

def debuglog(statement) {   
    def logL = 0
    if (logLevel) logL = logLevel.toInteger()
    if (logL == 0) {return}
    else if (logL >= 2) {
        log.debug("${device.label?device.label:device.name}: " + statement)
    }
}

def infolog(statement) {       
    def logL = 0
    if (logLevel) logL = logLevel.toInteger()
    if (logL == 0) {return}
    else if (logL >= 1) {
        log.info("${device.label?device.label:device.name}: " + statement)
    }
}

def getLogLevels(){
    return [["0":"None"],["1":"Running"],["2":"NeedHelp"]]
}
