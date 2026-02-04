/**
 *  Garadget MQTT Device Handler
 *
 *  J.R. Farrar (jrfarrar)
 *
 * 1.4.4 - 02/04/26 - Fixed connection recovery: watchdog survives reboots, detects stale connections,
 *                      explicit disconnect before reconnect, fixed cron for >59 min, added lastMessageReceived tracking
 * 1.4.3 - 11/12/25 - Code cleanup: fixed syntax error, blocking loop, null safety, variable declarations
 * 1.4.2 - 11/15/21 - settings update(show/hide) fixed retryTime (should have been seconds not minutes)
 * 1.4.1 - 06/18/20 - log tweaking and code efficiency for scheduled refreshes
 * 1.4.0 - 06/15/20 - added scheduling for refresh and reconnect, log streamlining
 * 1.3.3 - 06/15/20 - minor logging fix
 * 1.3.2 - 06/14/20 - Default bug, auto-reconnection if broker drops
 * 1.3.0 - 06/14/20 - impletmented Garadget IP and Port changing and validation of config data, documentation, other small stuff
 * 1.2.0 - 06/14/20 - added: stop command, watchdog, MQTT username/pass, separated IP addr & port
 * 1.1.1 - 06/12/20 - logging fixes
 * 1.1.0 - 06/12/20 - Initial Release
 */

metadata {
    definition (name: "Garadget MQTT",
                namespace: "jrfarrar",
                author: "J.R. Farrar",
                importUrl: "https://raw.githubusercontent.com/jrfarrar/hubitat/master/devicehandlers/garadgetMQTT/garadgetmqtt.groovy") {
        capability "Initialize"
        capability "Garage Door Control"
        capability "Contact Sensor"
        //capability "Switch"
        capability "Refresh"
        capability "Illuminance Measurement"
        capability "Configuration"

        attribute "status", "string"
        attribute "time", "string"
        attribute "sensor", "number"
        attribute "signal", "number"
        attribute "bright", "number"
        attribute "stopped", "enum", ["true","false"]

        command "stop"
    }

    preferences {
        section("Settings for connection from HE to Broker") {
            input name: "doorName", type: "text", title: "Garadget Door Name(Topic name)", required: true
            input name: "ipAddr", type: "text", title: "IP Address of MQTT broker", required: true
            input name: "ipPort", type: "text", title: "Port # of MQTT broker", defaultValue: "1883", required: true
            input name: "username", type: "text", title: "MQTT Username:", description: "(blank if none)", required: false
            input name: "password", type: "password", title: "MQTT Password:", description: "(blank if none)", required: false
            input name: "retryTime", type: "number", title: "Number of seconds between retries to connect if broker goes down", defaultValue: 300, required: true
            input name: "refreshStats", type: "bool", title: "Refresh Garadget stats on a schedule?", defaultValue: false, required: true
            input name: "refreshTime", type: "number", title: "If using refresh, refresh this number of minutes", defaultValue: 5, range: "1..240", required: true, description: "1-59 = every X minutes, 60-240 = converted to hourly"
            input name: "watchDogSched", type: "bool", title: "Check for connection to MQTT broker on a schedule?", defaultValue: false, required: true
            input name: "watchDogTime", type: "number", title: "Check MQTT broker connection interval", defaultValue: 15, range: "1..240", required: true, description: "1-59 = every X minutes, 60-240 = converted to hourly (e.g., 120 = every 2 hours)"
            input name: "staleMinutes", type: "number", title: "Minutes without data before forcing reconnect", defaultValue: 30, range: "5..120", required: true, description: "If no MQTT messages received within this time, force reconnect even if isConnected() says true"
            input name: "logLevel",title: "IDE logging level",multiple: false,required: true,type: "enum", options: getLogLevels(), submitOnChange : false, defaultValue : "1"
            input name: "ShowAllPreferences", type: "bool", title: "<b>Show Garadget device settings?</b>These change settings on the device itself through MQTT", defaultValue: false
        }
        if( ShowAllPreferences ){
            section("Settings for Garadget"){
                // put configuration here
                if (doorName && ipAddr && ipPort) input ( "rdt", "number", title: "<b>GARADGET: sensor scan interval in mS</b> (200-60,000, default 1,000)", defaultValue: 1000,range: "200..60000", required: false)
                if (doorName && ipAddr && ipPort) input ( "mtt", "number", title: "<b>GARADGET: door moving time in mS from completely opened to completely closed</b> (1,000 - 120,000, default 10,000)", defaultValue: 10000,range: "1000..120000", required: false)
                if (doorName && ipAddr && ipPort) input ( "rlt", "number", title: "<b>GARADGET: button press time mS, time for relay to keep contacts closed</b> (10-2,000, default 300)", defaultValue: 300,range: "10..2000", required: false)
                if (doorName && ipAddr && ipPort) input ( "rlp", "number", title: "<b>GARADGET: delay between consecutive button presses in mS</b> (10-5,000 default 1,000)", defaultValue: 1000,range: "10..5000", required: false)
                if (doorName && ipAddr && ipPort) input ( "srt", "number", title: "<b>GARADGET: reflection threshold below which the door is considered open</b> (1-80, default 25)", defaultValue: 25,range: "1..80", required: false)
                //Topic name is broken in current version(1.20) of Garadget firmware. It uses the Door Name
                //if (doorName) input ( "nme", "text", title: "device name to be used in MQTT topic. If cloud connection enabled, at reboot this value will be overwritten with the one saved in cloud via the app", required: false)
                //Not positive of how to cast this bitmap in HE so leaving this commented out for now
                //if (doorName) input ( "mqtt", "text", title: "bitmap 0x01 - cloud enabled, 0x02 - mqtt enabled, 0x03 - cloud and mqtt enabled", defaultValue: "0x03", required: false)
                if (doorName && ipAddr && ipPort) input ( "mqip", "text", title: "<b>GARADGET: *CAUTION: This can break your connection*\n MQTT broker IP address(IP for Garadget to connect to)</b>", required: false)
                if (doorName && ipAddr && ipPort) input ( "mqpt", "number", title: "<b>GARADGET: *CAUTION: This can break your connection*\n MQTT broker port number(port for Garadget to connect to)</b>", required: false)
                //Leaving username commented out as no need to change it since you can't change password via MQTT
                //if (doorName) input ( "mqus", "text", title: "MQTT user", required: false)
                if (doorName && ipAddr && ipPort) input ( "mqto", "number", title: "<b>GARADGET: MQTT timeout (keep alive) in seconds</b>", defaultValue: 15, required: false)
            }
        }
        /*
        section("Logging"){
            //logging
            input(name: "logLevel",title: "IDE logging level",multiple: false,required: true,type: "enum", options: getLogLevels(), submitOnChange : false, defaultValue : "1")
        }
        */
    }
}

void setVersion(){
    //state.name = "Garadget MQTT"
    state.version = "1.4.4 - Garadget MQTT Device Handler version"
}

void installed() {
    log.warn "installed..."
    state.lastMessageReceived = 0
    state.reconnectAttempts = 0
}

// Parse incoming device messages to generate events
void parse(String description) {
    def topicFull = interfaces.mqtt.parseMessage(description).topic
    def topic = topicFull.split('/')

    def message = interfaces.mqtt.parseMessage(description).payload
    
    // Track message receipt for staleness detection
    state.lastMessageReceived = now()
    state.reconnectAttempts = 0
    
    if (message) {
        def jsonVal = parseJson(message)
        if (topic[2] == "status") {
            getStatus(jsonVal)
        } else if (topic[2] == "config") {
            getConfig(jsonVal)
        } else {
            debuglog "Unhandled topic..." + topic[2]
        }
    } else {
        debuglog "Empty payload"
    }
}
//Handle status update topic
void getStatus(status) {
    if (status.status != device.currentValue("door")) {
        infolog status.status
        if (status.status == "closed") {
            sendEvent(name: "contact", value: "closed")
            sendEvent(name: "door", value: "closed")
            sendEvent(name: "stopped", value: "false")
        } else if (status.status == "open") {
            sendEvent(name: "contact", value: "open")
            sendEvent(name: "door", value: "open")
            sendEvent(name: "stopped", value: "false")
        } else if (status.status == "stopped") {
            sendEvent(name: "door", value: "stopped")
            sendEvent(name: "contact", value: "open")
            sendEvent(name: "stopped", value: "true")
        } else if (status.status == "opening") {
            sendEvent(name: "door", value: "opening")
            sendEvent(name: "contact", value: "open")
            sendEvent(name: "stopped", value: "false")
        } else if (status.status == "closing") {
            sendEvent(name: "door", value: "closing")
            sendEvent(name: "stopped", value: "false")
        } else {
            infolog "unknown status event"
        }
    }
    //update device status if it has changed
    if (status.sensor != device.currentValue("sensor")) { sendEvent(name: "sensor", value: status.sensor) }
    if (status.signal != device.currentValue("signal")) { sendEvent(name: "signal", value: status.signal) }
    if (status.bright != device.currentValue("bright")) { sendEvent(name: "bright", value: status.bright) }
    if (status.time != device.currentValue("time")) { sendEvent(name: "time", value: status.time) }
    // Fixed: changed from status.illuminance to status.bright for consistency
    if (status.bright != device.currentValue("illuminance")) { sendEvent(name: "illuminance", value: status.bright) }
    //log
    debuglog "status: " + status.status
    debuglog "bright: " + status.bright
    debuglog "sensor: " + status.sensor
    debuglog "signal: " + status.signal
    debuglog "time: " + status.time
}
//Handle config update topic
void getConfig(config) {
    //
    //Set some states for Garadget/Particle Info
    //
    debuglog "sys: " + config.sys + " - Particle Firmware Version"
    state.sys = config.sys + " - Particle Firmware Version"
    debuglog "ver: " + config.ver + " - Garadget firmware version"
    state.ver = config.ver + " - Garadget firmware version"
    debuglog "id: "  + config.id  + " - Garadget/Particle device ID"
    state.id = config.id  + " - Garadget/Particle device ID"
    debuglog "ssid: "+ config.ssid + " - WiFi SSID name"
    state.ssid = config.ssid + " - WiFi SSID name"
    //
    //refresh and update configuration values
    //
    debuglog "rdt: " + config.rdt + " - sensor scan interval in mS (200-60,000, default 1,000)"
    def rdt = config.rdt
    device.updateSetting("rdt", [value: "${rdt}", type: "number"])
    sendEvent(name: "rdt", value: rdt)
    //
    debuglog "mtt: " + config.mtt + " - door moving time in mS from completely opened to completely closed (1,000 - 120,000, default 10,000)"
    def mtt = config.mtt
    device.updateSetting("mtt", [value: "${mtt}", type: "number"])
    sendEvent(name: "mtt", value: mtt)
    //
    debuglog "rlt: " + config.rlt + " - button press time mS, time for relay to keep contacts closed (10-2,000, default 300)"
    def rlt = config.rlt
    device.updateSetting("rlt", [value: "${rlt}", type: "number"])
    sendEvent(name: "rlt", value: rlt)
    //
    debuglog "rlp: " + config.rlp + " - delay between consecutive button presses in mS (10-5,000 default 1,000)"
    def rlp = config.rlp
    device.updateSetting("rlp", [value: "${rlp}", type: "number"])
    sendEvent(name: "rlp", value: rlp)
    //
    debuglog "srt: " + config.srt + " - reflection threshold below which the door is considered open (1-80, default 25)"
    def srt = config.srt
    device.updateSetting("srt", [value: "${srt}", type: "number"])
    sendEvent(name: "srt", value: srt)
    //
    //nme is currently broken in Garadget firmware 1.2 - it does not honor it. It uses default device name.
    debuglog "nme: " + config.nme + " - device name to be used in MQTT topic."
    //def nme = config.nme
    //device.updateSetting("nme", [value: "${nme}", type: "text"])
    //sendEvent(name: "nme", value: nme)
    //
    //Not tested setting the bitmap from HE - needs to be tested
    debuglog "mqtt: " + config.mqtt + " - bitmap 0x01 - cloud enabled, 0x02 - mqtt enabled, 0x03 - cloud and mqtt enabled"
    //def mqtt = config.mqtt
    //device.updateSetting("mqtt", [value: "${mqtt}", type: "text"])
    //sendEvent(name: "mqtt", value: mqtt)
    //
    debuglog "mqip: " + config.mqip + " - MQTT broker IP address"
    def mqip = config.mqip
    device.updateSetting("mqip", [value: "${mqip}", type: "text"])
    sendEvent(name: "mqip", value: mqip)
    //
    debuglog "mqpt: " + config.mqpt + " - MQTT broker port number"
    def mqpt = config.mqpt
    device.updateSetting("mqpt", [value: "${mqpt}", type: "number"])
    sendEvent(name: "mqpt", value: mqpt)
    //
    //See no need to implement changing the username as you can't change the password via the MQTT interface
    debuglog "mqus: " + config.mqus + " - MQTT user"
    //def mqus = config.mqus
    //
    debuglog "mqto: " + config.mqto + " - MQTT timeout (keep alive) in seconds"
    def mqto = config.mqto
    device.updateSetting("mqto", [value: "${mqto}", type: "number"])
    sendEvent(name: "mqto", value: mqto)
}

void refresh(){
    requestStatus()
    setVersion()
}
//refresh data from status and config topics
void requestStatus() {
    watchDog()
    debuglog "Getting status and config..."
    //Garadget requires sending a command to force it to update the config topic
    interfaces.mqtt.publish("garadget/${doorName}/command", "get-config")
    pauseExecution(1000)
    //Garadget requires sending a command to force it to update the status topic (unless there is a door event)
    interfaces.mqtt.publish("garadget/${doorName}/command", "get-status")
}
void updated() {
    infolog "updated..."
    //write the configuration
    configure()
    
    // Reset reconnect counter
    state.reconnectAttempts = 0
    
    //set schedules
    unschedule()
    pauseExecution(1000)
    
    // Set up all schedules (watchdog, refresh)
    setupSchedules()
}

/**
 * Central schedule setup - called from both updated() and initialize()
 * Ensures watchdog and refresh survive hub reboots
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
    
    // If refresh set to true then set the schedule
    if (refreshStats) {
        if (refreshTime <= 59) {
            debuglog "Setting schedule to refresh every ${refreshTime} minute(s)"
            schedule("22 0/${refreshTime} * ? * *", requestStatus)
        } else {
            def hours = Math.round(refreshTime / 60.0).toInteger()
            if (hours < 1) hours = 1
            if (hours > 23) hours = 23
            debuglog "Setting schedule to refresh every ${hours} hour(s)"
            schedule("22 3 0/${hours} ? * *", requestStatus)
        }
    }
}

void uninstalled() {
    infolog "disconnecting from mqtt..."
    interfaces.mqtt.disconnect()
    unschedule()
}

void initialize() {
    infolog "initialize..."
    
    // Initialize state variables
    if (state.lastMessageReceived == null) state.lastMessageReceived = 0
    if (state.reconnectAttempts == null) state.reconnectAttempts = 0
    
    // Connect to MQTT broker
    connectMqtt()
    
    // Set up schedules - critical for surviving hub reboots
    // unschedule first to avoid duplicates since initialize() can be called multiple times
    unschedule()
    pauseExecution(500)
    setupSchedules()
    
    //if logs are in "Need Help" turn down to "Running" after an hour
    def logL = logLevel ? logLevel.toInteger() : 1
    if (logL == 2) runIn(3600, logsOff)
}

/**
 * Connect to MQTT broker and subscribe to topics
 * Separated from initialize() so it can be called independently for reconnection
 */
void connectMqtt() {
    try {
        //open connection
        def mqttInt = interfaces.mqtt
        def mqttbroker = "tcp://" + ipAddr + ":" + ipPort
        def mqttclientname = "Hubitat MQTT " + doorName + " " + device.deviceNetworkId
        mqttInt.connect(mqttbroker, mqttclientname, username, password)
        //give it a chance to start
        pauseExecution(1000)
        infolog "connection established..."
        //subscribe to status and config topics
        mqttInt.subscribe("garadget/${doorName}/status")
        mqttInt.subscribe("garadget/${doorName}/config")
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

    //Build Option Map based on preferences
    def options = [:]
    if (rdt) options.rdt = rdt
    if (mtt) options.mtt = mtt
    if (rlt) options.rlt = rlt
    if (rlp) options.rlp = rlp
    if (srt) options.srt = srt
    if (mqip) options.mqip = mqip
    if (mqpt) options.mqpt = mqpt
    if (mqto) options.mqto = mqto
    //create json from option map
    def json = new groovy.json.JsonOutput().toJson(options)
    debuglog json
    //write configuration to MQTT broker
    interfaces.mqtt.publish("garadget/${doorName}/set-config", json)
    //refresh config from broker
    interfaces.mqtt.publish("garadget/${doorName}/command", "get-config")
}

void open() {
    infolog "Open command sent..."
    watchDog()
    interfaces.mqtt.publish("garadget/${doorName}/command", "open")
}
void close() {
    infolog "Close command sent..."
    watchDog()
    interfaces.mqtt.publish("garadget/${doorName}/command", "close")
}
void stop(){
    infolog "Stop command sent..."
    watchDog()
    interfaces.mqtt.publish("garadget/${doorName}/command", "stop")
}

/**
 * Watchdog - checks MQTT connection health
 * Now checks both isConnected() AND whether we've received data recently
 */
void watchDog() {
    debuglog "Watchdog: Checking MQTT status"
    
    def connected = interfaces.mqtt.isConnected()
    def lastMsg = state.lastMessageReceived ?: 0
    def elapsed = lastMsg > 0 ? now() - lastMsg : -1
    def staleThreshold = (staleMinutes ?: 30) * 60 * 1000
    
    if (!connected) {
        // Definitely disconnected - reconnect
        log.warn "Watchdog: MQTT disconnected - reconnecting"
        reconnectMqtt()
    } else if (lastMsg > 0 && elapsed > staleThreshold) {
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

//Logging below here
void logsOff(){
    log.warn "debug logging disabled"
    device.updateSetting("logLevel", [value: "1", type: "enum"])
}
void debuglog(statement)
{
    def logL = 0
    if (logLevel) logL = logLevel.toInteger()
    if (logL == 0) {return}//bail
    else if (logL >= 2)
    {
        log.debug("${device.label?device.label:device.name}: " + statement)
    }
}
void infolog(statement)
{
    def logL = 0
    if (logLevel) logL = logLevel.toInteger()
    if (logL == 0) {return}//bail
    else if (logL >= 1)
    {
        log.info("${device.label?device.label:device.name}: " + statement)
    }
}
def getLogLevels(){
    return [["0":"None"],["1":"Running"],["2":"NeedHelp"]]
}
