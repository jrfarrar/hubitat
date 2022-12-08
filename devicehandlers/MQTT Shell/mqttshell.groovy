/**
 *   MQTT Device Handler
 *
 *  J.R. Farrar (jrfarrar)
 *
 */

metadata {
    definition (name: "BI MQTT Motion", 
                namespace: "jrfarrar", 
                author: "J.R. Farrar"){
                
        capability "Initialize"
        capability "Refresh"
        capability "Configuration"
        //capability "Sensor"
        //capability "Motion Sensor"
        
        attribute "type", "string"
        
        //command "active"
        //command "inactive"


        
preferences {
    section("Settings for connection from HE to Broker") {
        input name: "doorName", type: "text", title: "Topic Name(Topic name)", required: true
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
    //state.name = "Garadget MQTT"
	//state.version = "1.4.2 - Garadget MQTT Device Handler version"   
}

void installed() {
    log.warn "installed..."
}

// Parse incoming device messages to generate events
void parse(String description) {
    topicFull=interfaces.mqtt.parseMessage(description).topic
    debuglog "TOPIC FULL: " + topicFull
	def topic=topicFull.split('/')
    debuglog "TOPIC: " + topic
    def topicType=interfaces.mqtt.parseMessage(description).topic.split('/')
    debuglog "topicType: " + topicType[2]
    def myTopic = topicType[2]
    debuglog "myTopic: " + myTopic
    def message=interfaces.mqtt.parseMessage(description).payload
    debuglog "MESSAGE: " + message
 
}
//Handle status update topic
void getStatus(status) {
    if (status.status != device.currentValue("door")) { 
        infolog "Next Line Status"
        infolog status.status 
        if (status.status == "active") {
            sendEvent(name: "motion", value: "active")
        } else if (status.status == "inactive") {
            sendEvent(name: "motion", value: "inactive")
        } else {
            infolog "unknown status event"
        }
    }   

    //log
    debuglog "motion: " + status.motion
    debuglog "type: " + status.type
}
//Handle config update topic
void getConfig(config) {

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
    //interfaces.mqtt.publish("garadget/${doorName}/command", "get-config")
    //pauseExecution(1000)
    //Garadget requires sending a command to force it to update the status topic (unless there is a door event)
    //interfaces.mqtt.publish("garadget/${doorName}/command", "get-status")
}
void updated() {
    infolog "updated..."
    //write the configuration
    configure()
    //set schedules   
    unschedule()
    pauseExecution(1000)
    //schedule the watchdog to run in case the broker restarts
    if (watchDogSched) {
        debuglog "setting schedule to check for MQTT broker connection every ${watchDogTime} minutes"
        schedule("44 7/${watchDogTime} * ? * *", watchDog)
    }
    //If refresh set to true then set the schedule
    if (refreshStats) {
        debuglog "setting schedule to refresh every ${refreshTime} minutes"
        schedule("22 3/${refreshTime} * ? * *", requestStatus) 
    }
}
void uninstalled() {
    infolog "disconnecting from mqtt..."
    interfaces.mqtt.disconnect()
    unschedule()
}

void initialize() {
    infolog "initialize..."
    try {
        //open connection
        def mqttInt = interfaces.mqtt
        mqttbroker = "tcp://" + ipAddr + ":" + ipPort
        mqttclientname = "Hubitat MQTT " + doorName
        mqttInt.connect(mqttbroker, mqttclientname, username,password)
        //give it a chance to start
        pauseExecution(1000)
        infolog "connection established..."
        //subscribe to status and config topics
//----------------------------------------------------------------
//        
// Below here is where you need to define the topic to connect to    
//----------------------------------------------------------------
        mqttInt.subscribe("${doorName}/motion")
        mqttInt.subscribe("${doorName}/type")
    } catch(e) {
        log.warn "${device.label?device.label:device.name}: MQTT initialize error: ${e.message}"
    }
    //if logs are in "Need Help" turn down to "Running" after an hour
    logL = logLevel.toInteger()
    if (logL == 2) runIn(3600,logsOff)
}

void configure(){
    infolog "configure..."
    watchDog()
}

def watchDog() {
    debuglog "Checking MQTT status"    
    //if not connnected, re-initialize
    if(!interfaces.mqtt.isConnected()) { 
        debuglog "MQTT Connected: (${interfaces.mqtt.isConnected()})"
        initialize()
    }
}
void mqttClientStatus(String message) {
	log.warn "${device.label?device.label:device.name}: **** Received status message: ${message} ****"
    if (message.contains ("Connection lost")) {
        connectionLost()
    }
}
//if connection is dropped, try to reconnect every (retryTime) seconds until the connection is back
void connectionLost(){
    //convert to milliseconds
    delayTime = retryTime * 1000
    while(!interfaces.mqtt.isConnected()) {
        infolog "connection lost attempting to reconnect..."
        initialize()
        pauseExecution(delayTime)
    }
}
    
//Logging below here
def logsOff(){
    log.warn "debug logging disabled"
    device.updateSetting("logLevel", [value: "1", type: "enum"])
}
def debuglog(statement)
{   
	def logL = 0
    if (logLevel) logL = logLevel.toInteger()
    if (logL == 0) {return}//bail
    else if (logL >= 2)
	{
		log.debug("${device.label?device.label:device.name}: " + statement)
	}
}
def infolog(statement)
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
