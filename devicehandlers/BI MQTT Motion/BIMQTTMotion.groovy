/**
 *  BI MQTT Motion Sensor Driver
 *
 *  J.R. Farrar (jrfarrar)
 *
 * 1.1.0 - 08/20/22 - Initial Release
 * 1.2.0 - 11/22/22 - Added active,inactive commands
 * 1.3.0 - 11/12/25 - Cleaned up code, removed legacy Garadget references
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
        
        command "active"
        command "inactive"

        
    preferences {
        section("Settings for connection from HE to MQTT Broker") {
            input name: "topicName", type: "text", title: "Topic Name (Camera/Sensor Name)", required: true
            input name: "ipAddr", type: "text", title: "IP Address of MQTT broker", required: true
            input name: "ipPort", type: "text", title: "Port # of MQTT broker", defaultValue: "1883", required: true
            input name: "username", type: "text", title: "MQTT Username:", description: "(blank if none)", required: false
            input name: "password", type: "password", title: "MQTT Password:", description: "(blank if none)", required: false
            input name: "retryTime", type: "number", title: "Number of seconds between retries to connect if broker goes down", defaultValue: 300, required: true
            input name: "watchDogSched", type: "bool", title: "Check for connection to MQTT broker on a schedule?", defaultValue: false, required: true
            input name: "watchDogTime", type: "number", title: "This number of minutes to check for connection to MQTT broker", defaultValue: 15, range: "1..59", required: true
            input name: "logLevel",title: "IDE logging level",multiple: false,required: true,type: "enum", options: getLogLevels(), submitOnChange : false, defaultValue : "1"
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
    infolog "Refreshed - MQTT connection status: ${interfaces.mqtt.isConnected()}"
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
}

void uninstalled() {
    infolog "Disconnecting from MQTT..."
    interfaces.mqtt.disconnect()
    unschedule()
}

void initialize() {
    infolog "Initializing MQTT connection..."
    try {
        // Open connection
        def mqttInt = interfaces.mqtt
        mqttbroker = "tcp://" + ipAddr + ":" + ipPort
        mqttclientname = "Hubitat_BI_Motion_" + topicName
        mqttInt.connect(mqttbroker, mqttclientname, username, password)
        
        // Give it a chance to start
        pauseExecution(1000)
        infolog "Connection established to ${mqttbroker}"
        
        // Subscribe to motion and type topics
        mqttInt.subscribe("${topicName}/motion")
        mqttInt.subscribe("${topicName}/type")
        infolog "Subscribed to topics: ${topicName}/motion and ${topicName}/type"
        
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

def watchDog() {
    debuglog "Checking MQTT connection status"    
    // If not connected, re-initialize
    if(!interfaces.mqtt.isConnected()) { 
        log.warn "MQTT not connected - attempting to reconnect..."
        initialize()
    } else {
        debuglog "MQTT connection is healthy"
    }
}

void mqttClientStatus(String message) {
    log.warn "${device.label?device.label:device.name}: **** Received status message: ${message} ****"
    if (message.contains("Connection lost")) {
        connectionLost()
    }
}

// If connection is dropped, try to reconnect every (retryTime) seconds until the connection is back
void connectionLost(){
    delayTime = retryTime * 1000
    while(!interfaces.mqtt.isConnected()) {
        log.warn "Connection lost - attempting to reconnect in ${retryTime} seconds..."
        initialize()
        pauseExecution(delayTime)
    }
    infolog "Connection restored!"
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
