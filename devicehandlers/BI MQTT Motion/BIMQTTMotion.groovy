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
            input name: "watchDogTime", type: "number", title: "Minutes between health checks", defaultValue: 15, range: "1..59", required: true
        }
        section("<b>Logging</b>") {
            input name: "logLevel", title: "IDE logging level", multiple: false, required: true, type: "enum", options: getLogLevels(), submitOnChange: false, defaultValue: "1"
        }
    }
  }
}

def setVersion(){
    state.name = "BI MQTT Motion"
    state.version = "1.3.0"   
}

void installed() {
    log.warn "installed..."
    setVersion()
    state.totalReconnects = 0
    sendEvent(name: "reconnectCount", value: 0)
    sendEvent(name: "connectionStatus", value: "Not Connected")
}

// Parse incoming device messages to generate events
void parse(String description) {
    topicFull = interfaces.mqtt.parseMessage(description).topic
    def topic = topicFull.split('/')
    def topicType = topic[1]  // Gets the second part of topic (motion or type)
    def message = interfaces.mqtt.parseMessage(description).payload
    
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
    
    // Schedule the watchdog to run in case the broker restarts
    if (watchDogSched) {
        debuglog "Setting schedule to check for MQTT broker connection every ${watchDogTime} minutes"
        schedule("44 7/${watchDogTime} * ? * *", watchDog)
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
    
    if (!doorName) {
        log.error "Topic Name not configured! Please configure the device settings."
        return
    }
    
    try {
        // Disconnect first if already connected (cleanup)
        if (interfaces.mqtt.isConnected()) {
            debuglog "Disconnecting existing connection before reconnect..."
            interfaces.mqtt.disconnect()
            pauseExecution(500)
        }
        
        // Open connection
        def mqttInt = interfaces.mqtt
        mqttbroker = "tcp://" + ipAddr + ":" + ipPort
        mqttclientname = "Hubitat_BI_Motion_" + doorName
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
        log.warn "${device.label?device.label:device.name}: MQTT initialize error: ${e.message}"
    }
    
    // If logs are in "Need Help" turn down to "Running" after an hour
    logL = logLevel.toInteger()
    if (logL == 2) runIn(3600, logsOff)
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
    
    // Disconnect and reinitialize
    try {
        interfaces.mqtt.disconnect()
        pauseExecution(2000)
    } catch(e) {
        debuglog "Disconnect error (may not have been connected): ${e.message}"
    }
    
    initialize()
    infolog "Connection reset complete"
}

def watchDog() {
    debuglog "Checking MQTT connection status"    
    
    // If not connected, re-initialize (but only if not already reconnecting)
    if (!interfaces.mqtt.isConnected()) {
        if (state.reconnecting) {
            debuglog "Reconnection already in progress, skipping watchdog reconnect"
            return
        }
        log.warn "MQTT not connected - watchdog triggering reconnection..."
        connectionLost()
    } else {
        debuglog "MQTT connection is healthy"
    }
}

void mqttClientStatus(String message) {
    log.warn "${device.label?device.label:device.name}: Status message: ${message}"
    
    // Track connection state changes
    def currentState = device.currentValue("motion")
    def timestamp = new Date().format("HH:mm:ss")
    state.lastStatusMessage = message
    state.lastStatusTime = timestamp
    
    if (message.contains("Connection lost")) {
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
        log.error "RECONNECTION LOOP DETECTED! Stopping automatic reconnection. Please manually click 'Initialize' to reconnect."
        state.reconnecting = false
        state.loopDetected = true
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
        return
    }
    
    if (interfaces.mqtt.isConnected()) {
        infolog "Already reconnected!"
        state.reconnecting = false
        return
    }
    
    infolog "Attempting to reconnect..."
    try {
        initialize()
        pauseExecution(2000)
        
        if (interfaces.mqtt.isConnected()) {
            infolog "Reconnection successful!"
            state.reconnecting = false
        } else {
            log.warn "Reconnection failed, will retry..."
            state.reconnecting = false
            // Schedule another attempt
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
