/**
 * Shelly Gateway Status MQTT Device Handler
 *
 * Modified from BI MQTT Motion by J.R. Farrar
 * Tracks Shelly Gateway online/offline status
 *
 * 1.0.0 - 11/08/25 - Initial Release for Gateway Status Tracking
 * 1.1.0 - 11/24/25 - Added throttling for lastSeen updates to reduce DB noise (max 1/min)
 * 1.2.0 - 01/07/26 - Added system status monitoring (uptime, RAM, filesystem, updates, dynamic script tracking)
 * 1.3.0 - 01/07/26 - Added MQTT command to request immediate status updates, WiFi monitoring, fixed script tracking for deleted scripts
 * 1.3.1 - 02/04/26 - Fixed connection recovery: watchdog survives reboots, detects stale connections,
 *                      non-blocking reconnect, explicit disconnect before reconnect, fixed cron for >59 min
 */

metadata {
    definition (name: "Shelly Gateway Status", 
                namespace: "jrfarrar", 
                author: "J.R. Farrar",
                importUrl: "") {
        capability "Initialize"
        capability "Refresh"
        capability "Configuration"
        capability "Sensor"
        capability "PresenceSensor"
        
        command "requestStatusUpdate"
        
        attribute "online", "enum", ["true", "false"]
        attribute "presence", "enum", ["present", "not present"]
        attribute "lastSeen", "string"
        attribute "uptime", "string"
        attribute "uptimeSeconds", "number"
        attribute "ramFreePercent", "number"
        attribute "fsFreePercent", "number"
        attribute "restartRequired", "enum", ["true", "false"]
        attribute "updateAvailable", "string"
        attribute "scriptsTotal", "number"
        attribute "scriptsRunning", "number"
        attribute "allScriptsRunning", "enum", ["true", "false"]
        attribute "scriptsSummary", "string"
        attribute "wifiRssi", "number"
        attribute "wifiIp", "string"
        attribute "wifiSsid", "string"

    preferences {
        section("Settings for connection from HE to Broker") {
            input name: "gatewayName", type: "text", title: "Gateway Name/ID (e.g., shellyblugw-08d1f90435e4)", required: true, description: "The gateway device ID"
            input name: "ipAddr", type: "text", title: "IP Address of MQTT broker", required: true
            input name: "ipPort", type: "text", title: "Port # of MQTT broker", defaultValue: "1883", required: true
            input name: "username", type: "text", title: "MQTT Username:", description: "(blank if none)", required: false
            input name: "password", type: "password", title: "MQTT Password:", description: "(blank if none)", required: false
            input name: "retryTime", type: "number", title: "Number of seconds between retries to connect if broker goes down", defaultValue: 300, required: true
            input name: "watchDogSched", type: "bool", title: "Check for connection to MQTT broker on a schedule?", defaultValue: false, required: true
            input name: "watchDogTime", type: "number", title: "Check MQTT broker connection interval", defaultValue: 15, range: "1..240", required: true, description: "1-59 = every X minutes, 60-240 = converted to hourly (e.g., 120 = every 2 hours)"
            input name: "offlineTimeout", type: "number", title: "Minutes without update before marking offline", defaultValue: 10, range: "1..60", required: true
            input name: "logLevel",title: "IDE logging level",multiple: false,required: true,type: "enum", options: getLogLevels(), submitOnChange : false, defaultValue : "1"
        }
     }
  }
}

def setVersion(){
    state.name = "Shelly Gateway Status MQTT"
    state.version = "1.3.1 - Shelly Gateway Status MQTT Device Handler"   
}

void installed() {
    log.warn "installed..."
    setVersion()
    sendEvent(name: "online", value: "false")
    sendEvent(name: "presence", value: "not present")
    state.lastSeenMillis = 0
    state.reconnectAttempts = 0
    state.scripts = [:]  // Map to track all scripts: [id: [running: bool, mem_used: int]]
}

// Parse incoming device messages to generate events
void parse(String description) {
    topicFull = interfaces.mqtt.parseMessage(description).topic
    debuglog "TOPIC FULL: " + topicFull
    
    def topic = topicFull.split('/')
    def topicType = topic[-1] // Get last part of topic
    
    def message = interfaces.mqtt.parseMessage(description).payload
    debuglog "MESSAGE: " + message
    
    if (!message) {
        debuglog "Empty payload"
        return
    }
    
    // Reset reconnect attempts on successful message receipt
    state.reconnectAttempts = 0
    
    // Update last seen timestamp (Throttled inside the function)
    updateLastSeen()
    
    // Handle the online topic (true/false)
    if (topicType == "online") {
        setOnlineStatus(message)
        return
    }
    
    // Handle events/rpc topic (system heartbeat messages)
    if (topicType == "rpc") {
        try {
            def jsonVal = parseJson(message)
            debuglog "Parsed JSON: " + jsonVal
            
            // If we receive any event from the gateway, it's online
            if (jsonVal.method == "NotifyStatus" || jsonVal.src) {
                debuglog "Received heartbeat from gateway"
                setOnlineStatus("true")
            }
            
        } catch(e) {
            log.warn "Error parsing JSON: ${e.message}"
        }
    }
    
    // Handle status topic (full status response from status_update command)
    if (topicType == "status" && topic.size() == 2) {
        // This is the full status payload from <gateway>/status (not <gateway>/status/sys)
        try {
            def jsonVal = parseJson(message)
            debuglog "Parsed full status JSON: " + jsonVal
            setOnlineStatus("true")
            
            // Parse sys data if present
            if (jsonVal.sys) {
                parseSysStatus(jsonVal.sys)
            }
            
            // Parse wifi data if present
            if (jsonVal.wifi) {
                parseWifiStatus(jsonVal.wifi)
            }
            
            // Reset scripts map and rebuild from fresh data (handles deleted scripts)
            state.scripts = [:]
            
            // Parse all script:N entries
            jsonVal.each { key, value ->
                if (key.startsWith("script:")) {
                    def scriptId = key.split(":")[1].toInteger()
                    parseScriptStatus(scriptId, value)
                }
            }
            
            // Update summary after processing all scripts
            updateScriptsSummary()
            
        } catch(e) {
            debuglog "Status message not JSON or error: ${e.message}"
        }
        return
    }
    
    // Handle status/sys topic (system status)
    if (topicType == "sys") {
        try {
            def jsonVal = parseJson(message)
            debuglog "Parsed sys status: " + jsonVal
            parseSysStatus(jsonVal)
            setOnlineStatus("true")
        } catch(e) {
            log.warn "Error parsing sys status: ${e.message}"
        }
    }
    
    // Handle status/wifi topic (wifi status)
    if (topicType == "wifi") {
        try {
            def jsonVal = parseJson(message)
            debuglog "Parsed wifi status: " + jsonVal
            parseWifiStatus(jsonVal)
            setOnlineStatus("true")
        } catch(e) {
            log.warn "Error parsing wifi status: ${e.message}"
        }
    }
    
    // Handle status/script:N topic (script status)
    if (topicType.startsWith("script:")) {
        try {
            def scriptId = topicType.split(":")[1].toInteger()
            def jsonVal = parseJson(message)
            debuglog "Parsed script:${scriptId} status: " + jsonVal
            parseScriptStatus(scriptId, jsonVal)
            setOnlineStatus("true")
        } catch(e) {
            log.warn "Error parsing script status: ${e.message}"
        }
    }
}

void setOnlineStatus(status) {
    def isOnline = (status == "true" || status == true)
    def onlineValue = isOnline ? "true" : "false"
    def presenceValue = isOnline ? "present" : "not present"
    
    // Only send event and log if status actually changes
    if (device.currentValue("online") != onlineValue) {
        infolog "Gateway status changed to: ${onlineValue}"
        sendEvent(name: "online", value: onlineValue)
        sendEvent(name: "presence", value: presenceValue)
    } else {
        debuglog "Gateway reported ${onlineValue} (no change)"
    }
}

void parseSysStatus(sysData) {
    // Parse uptime
    if (sysData.uptime != null) {
        def uptimeSecs = sysData.uptime.toLong()
        def days = Math.floor(uptimeSecs / 86400).toInteger()
        def hours = Math.floor((uptimeSecs % 86400) / 3600).toInteger()
        def mins = Math.floor((uptimeSecs % 3600) / 60).toInteger()
        def uptimeStr = "${days}d ${hours}h ${mins}m"
        
        if (device.currentValue("uptime") != uptimeStr) {
            sendEvent(name: "uptime", value: uptimeStr)
            sendEvent(name: "uptimeSeconds", value: uptimeSecs)
            debuglog "Uptime: ${uptimeStr}"
        }
    }
    
    // Parse RAM usage
    if (sysData.ram_size != null && sysData.ram_free != null) {
        def ramFreePercent = Math.round((sysData.ram_free / sysData.ram_size) * 100).toInteger()
        if (device.currentValue("ramFreePercent") != ramFreePercent) {
            sendEvent(name: "ramFreePercent", value: ramFreePercent, unit: "%")
            debuglog "RAM Free: ${ramFreePercent}% (${sysData.ram_free} / ${sysData.ram_size} bytes)"
        }
    }
    
    // Parse filesystem usage
    if (sysData.fs_size != null && sysData.fs_free != null) {
        def fsFreePercent = Math.round((sysData.fs_free / sysData.fs_size) * 100).toInteger()
        if (device.currentValue("fsFreePercent") != fsFreePercent) {
            sendEvent(name: "fsFreePercent", value: fsFreePercent, unit: "%")
            debuglog "Filesystem Free: ${fsFreePercent}% (${sysData.fs_free} / ${sysData.fs_size} bytes)"
        }
    }
    
    // Parse restart required
    if (sysData.restart_required != null) {
        def restartVal = sysData.restart_required ? "true" : "false"
        if (device.currentValue("restartRequired") != restartVal) {
            sendEvent(name: "restartRequired", value: restartVal)
            if (sysData.restart_required) {
                log.warn "${device.displayName}: Gateway restart required!"
            }
        }
    }
    
    // Parse available updates
    if (sysData.available_updates != null) {
        def updateStr = "none"
        if (sysData.available_updates.stable?.version) {
            updateStr = "stable: ${sysData.available_updates.stable.version}"
        } else if (sysData.available_updates.beta?.version) {
            updateStr = "beta: ${sysData.available_updates.beta.version}"
        }
        if (device.currentValue("updateAvailable") != updateStr) {
            sendEvent(name: "updateAvailable", value: updateStr)
            if (updateStr != "none") {
                infolog "Firmware update available: ${updateStr}"
            }
        }
    }
}

void parseWifiStatus(wifiData) {
    // Parse WiFi RSSI
    if (wifiData.rssi != null) {
        def rssi = wifiData.rssi.toInteger()
        if (device.currentValue("wifiRssi") != rssi) {
            sendEvent(name: "wifiRssi", value: rssi, unit: "dBm")
            debuglog "WiFi RSSI: ${rssi} dBm"
        }
    }
    
    // Parse WiFi IP
    if (wifiData.sta_ip != null) {
        if (device.currentValue("wifiIp") != wifiData.sta_ip) {
            sendEvent(name: "wifiIp", value: wifiData.sta_ip)
            debuglog "WiFi IP: ${wifiData.sta_ip}"
        }
    }
    
    // Parse WiFi SSID
    if (wifiData.ssid != null) {
        if (device.currentValue("wifiSsid") != wifiData.ssid) {
            sendEvent(name: "wifiSsid", value: wifiData.ssid)
            debuglog "WiFi SSID: ${wifiData.ssid}"
        }
    }
}

void parseScriptStatus(scriptId, scriptData) {
    // Initialize scripts map if needed
    if (state.scripts == null) state.scripts = [:]
    
    // Get previous state for this script
    def prevState = state.scripts[scriptId.toString()]
    def wasRunning = prevState?.running
    
    // Update script state
    state.scripts[scriptId.toString()] = [
        running: scriptData.running ?: false,
        mem_used: scriptData.mem_used ?: 0
    ]
    
    // Log state changes
    if (wasRunning != null && wasRunning != scriptData.running) {
        if (scriptData.running) {
            infolog "Script:${scriptId} started running"
        } else {
            log.warn "${device.displayName}: Script:${scriptId} stopped running!"
        }
    } else if (wasRunning == null) {
        debuglog "Script:${scriptId} discovered (running: ${scriptData.running}, mem: ${scriptData.mem_used} bytes)"
    }
    
    // Update summary attributes
    updateScriptsSummary()
}

void updateScriptsSummary() {
    if (state.scripts == null || state.scripts.size() == 0) return
    
    def total = state.scripts.size()
    def running = state.scripts.count { k, v -> v.running == true }
    def allRunning = (running == total)
    
    // Build summary string
    def summary = "${running}/${total} running"
    if (!allRunning) {
        def stopped = state.scripts.findAll { k, v -> v.running != true }.collect { k, v -> "script:${k}" }
        summary += " (stopped: ${stopped.join(', ')})"
    }
    
    // Update attributes if changed
    if (device.currentValue("scriptsTotal") != total) {
        sendEvent(name: "scriptsTotal", value: total)
    }
    if (device.currentValue("scriptsRunning") != running) {
        sendEvent(name: "scriptsRunning", value: running)
    }
    def allRunningVal = allRunning ? "true" : "false"
    if (device.currentValue("allScriptsRunning") != allRunningVal) {
        sendEvent(name: "allScriptsRunning", value: allRunningVal)
        if (!allRunning) {
            log.warn "${device.displayName}: Not all scripts running! ${summary}"
        }
    }
    if (device.currentValue("scriptsSummary") != summary) {
        sendEvent(name: "scriptsSummary", value: summary)
        debuglog "Scripts summary: ${summary}"
    }
}

void updateLastSeen() {
    // THROTTLING LOGIC:
    // Only update the DB and reset the timer if it's been > 60 seconds
    // or if this is the very first run.
    
    def now = now()
    def lastUpdate = state.lastSeenMillis ?: 0
    
    if (now - lastUpdate > 60000) {
        def timestamp = new Date().format("yyyy-MM-dd HH:mm:ss", location.timeZone)
        sendEvent(name: "lastSeen", value: timestamp)
        state.lastSeenMillis = now
        
        // Reset the offline check timer
        // We only need to do this when we update the timestamp
        unschedule(checkOffline)
        runIn(offlineTimeout * 60, checkOffline)
        debuglog "Updated lastSeen and reset offline timer"
    }
}

void checkOffline() {
    // Double check logic: ensure we really haven't seen it in a while
    // (in case a reboot cleared the schedule but state persisted)
    def timeoutMillis = offlineTimeout * 60 * 1000
    def lastSeen = state.lastSeenMillis ?: 0
    def currentTime = now()
    
    if (currentTime - lastSeen > timeoutMillis) {
        def minsSince = Math.round((currentTime - lastSeen) / 60000)
        log.warn "No updates received for ${minsSince} minutes - marking gateway offline"
        setOnlineStatus("false")
        
        // Connection may be stale even if isConnected() says true - force reconnect
        infolog "No data in ${minsSince} minutes - forcing MQTT reconnect"
        reconnectMqtt()
    } else {
        // This is a safety catch. If we are here, but time hasn't elapsed, 
        // it means we got an update recently. Reschedule the check.
        def timeRemaining = ((lastSeen + timeoutMillis) - currentTime) / 1000
        if (timeRemaining > 0) runIn(timeRemaining.toInteger(), checkOffline)
    }
}

void refresh(){
    watchDog()
    setVersion()
    requestStatusUpdate()
}

/**
 * Request immediate status update from Shelly gateway
 * Publishes 'status_update' to the gateway's command topic
 */
void requestStatusUpdate() {
    if (!gatewayName) {
        log.warn "Gateway name not configured - cannot request status update"
        return
    }
    
    if (!interfaces.mqtt.isConnected()) {
        log.warn "MQTT not connected - cannot request status update"
        return
    }
    
    def commandTopic = "${gatewayName}/command"
    infolog "Requesting status update from gateway (publishing to ${commandTopic})"
    
    try {
        interfaces.mqtt.publish(commandTopic, "status_update")
        debuglog "Published 'status_update' to ${commandTopic}"
    } catch (e) {
        log.warn "Failed to publish status update request: ${e.message}"
    }
}

void updated() {
    infolog "updated..."
    
    // Reset reconnect counter
    state.reconnectAttempts = 0
    
    configure()
    unschedule()
    pauseExecution(1000)
    
    // Set up all schedules (watchdog, offline check)
    setupSchedules()
}

/**
 * Central schedule setup - called from both updated() and initialize()
 * Ensures watchdog and offline check survive hub reboots
 */
void setupSchedules() {
    // Schedule the watchdog to run in case the broker restarts
    if (watchDogSched) {
        if (watchDogTime <= 59) {
            // Minute-based scheduling for intervals under 60 minutes
            debuglog "Setting schedule to check MQTT broker connection every ${watchDogTime} minute(s)"
            schedule("44 0/${watchDogTime} * ? * *", watchDog)
        } else {
            // Hour-based scheduling for intervals 60 minutes or greater
            def hours = Math.round(watchDogTime / 60.0).toInteger()
            if (hours < 1) hours = 1
            if (hours > 23) hours = 23
            debuglog "Setting schedule to check MQTT broker connection every ${hours} hour(s)"
            schedule("44 7 0/${hours} ? * *", watchDog)
        }
    }
    
    // Re-establish offline check timer based on last known data
    // Critical after hub reboot: without this, checkOffline never fires
    def lastSeen = state.lastSeenMillis ?: 0
    if (lastSeen > 0) {
        def timeoutMillis = offlineTimeout * 60 * 1000
        def elapsed = now() - lastSeen
        if (elapsed > timeoutMillis) {
            // Already stale - check immediately
            runIn(5, checkOffline)
            debuglog "Last seen was ${Math.round(elapsed / 60000)} min ago - scheduling immediate offline check"
        } else {
            // Schedule check for when timeout would expire
            def remainingSecs = ((timeoutMillis - elapsed) / 1000).toInteger()
            if (remainingSecs < 1) remainingSecs = 1
            runIn(remainingSecs, checkOffline)
            debuglog "Scheduling offline check in ${remainingSecs} seconds"
        }
    } else {
        // Never received data - schedule check at offlineTimeout
        runIn(offlineTimeout * 60, checkOffline)
        debuglog "No previous data - scheduling offline check in ${offlineTimeout} minutes"
    }
}

void uninstalled() {
    infolog "Disconnecting from mqtt..."
    interfaces.mqtt.disconnect()
    unschedule()
}

void initialize() {
    infolog "initialize..."
    
    // Initialize state variables
    if (state.lastSeenMillis == null) state.lastSeenMillis = 0
    if (state.reconnectAttempts == null) state.reconnectAttempts = 0
    if (state.scripts == null) state.scripts = [:]
    
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
 */
void connectMqtt() {
    try {
        // Open connection
        def mqttInt = interfaces.mqtt
        mqttbroker = "tcp://" + ipAddr + ":" + ipPort
        mqttclientname = "Hubitat MQTT Gateway Status " + device.deviceNetworkId
        mqttInt.connect(mqttbroker, mqttclientname, username, password)
        
        // Give it a chance to start
        pauseExecution(1000)
        infolog "Connection established..."
        
        // Subscribe to gateway status topics
        mqttInt.subscribe("${gatewayName}/online")
        mqttInt.subscribe("${gatewayName}/events/rpc")
        mqttInt.subscribe("${gatewayName}/status/#")  // Wildcard to catch status/sys, status/script:N, etc.
        
        infolog "Subscribed to topics: ${gatewayName}/online, ${gatewayName}/events/rpc, ${gatewayName}/status/#"
        
        // Request immediate status update from gateway
        pauseExecution(500)
        requestStatusUpdate()
        
    } catch(e) {
        log.warn "${device.label?device.label:device.name}: MQTT connect error: ${e.message}"
    }
}

/**
 * Force disconnect and reconnect to MQTT broker
 * Used when connection is suspected stale (isConnected() lies about half-open TCP connections)
 */
void reconnectMqtt() {
    state.reconnectAttempts = (state.reconnectAttempts ?: 0) + 1
    infolog "Reconnect attempt #${state.reconnectAttempts} - disconnecting first..."
    
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
    infolog "configure..."
    watchDog()
}

/**
 * Watchdog - checks MQTT connection health
 * Now checks both isConnected() AND whether we've received data recently
 */
def watchDog() {
    debuglog "Watchdog: Checking MQTT status"
    
    def connected = interfaces.mqtt.isConnected()
    def lastSeen = state.lastSeenMillis ?: 0
    def elapsed = lastSeen > 0 ? now() - lastSeen : -1
    def staleThreshold = (offlineTimeout ?: 10) * 60 * 1000
    
    if (!connected) {
        // Definitely disconnected - reconnect
        log.warn "Watchdog: MQTT disconnected - reconnecting"
        reconnectMqtt()
    } else if (lastSeen > 0 && elapsed > staleThreshold) {
        // Connected but no data - likely a ghost/stale connection
        def minsSince = Math.round(elapsed / 60000)
        log.warn "Watchdog: MQTT reports connected but no data in ${minsSince} minutes - forcing reconnect"
        reconnectMqtt()
    } else {
        debuglog "Watchdog: MQTT OK (connected: ${connected}, last data: ${elapsed > 0 ? Math.round(elapsed / 60000) + ' min ago' : 'never'})"
    }
}

void mqttClientStatus(String message) {
    log.warn "${device.label?device.label:device.name}: **** MQTT status: ${message} ****"
    if (message.contains("Connection lost") || message.contains("Client is not connected")) {
        // Schedule a reconnect attempt instead of blocking
        infolog "Connection lost detected - scheduling reconnect in ${retryTime} seconds"
        runIn(retryTime ?: 300, reconnectMqtt)
    }
}
    
// Logging below here
def logsOff(){
    log.warn "Debug logging disabled"
    device.updateSetting("logLevel", [value: "1", type: "enum"])
}

def debuglog(statement) {   
    def logL = 0
    if (logLevel) logL = logLevel.toInteger()
    if (logL == 0) {return} // bail
    else if (logL >= 2) {
        log.debug("${device.label?device.label:device.name}: " + statement)
    }
}

def infolog(statement) {        
    def logL = 0
    if (logLevel) logL = logLevel.toInteger()
    if (logL == 0) {return} // bail
    else if (logL >= 1) {
        log.info("${device.label?device.label:device.name}: " + statement)
    }
}

def getLogLevels(){
    return [["0":"None"],["1":"Running"],["2":"NeedHelp"]]
}
