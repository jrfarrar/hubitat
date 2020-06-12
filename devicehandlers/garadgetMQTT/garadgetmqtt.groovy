metadata {
    definition (name: "Test Mqtt", namespace: "test", author: "Test") {
        capability "Initialize"
        capability "Garage Door Control"
        capability "Contact Sensor"
        capability "Switch"
        capability "Refresh"
        capability "Illuminance Measurement"
        
        attribute "status", "string"
        attribute "time", "string"
        attribute "sensor", "number"
        attribute "signal", "number"
        attribute "bright", "number"

        //command "get-status", ["String"]
        //command "getstatus"
}

    preferences {
        input (name: "doorName", type: "text", title: "Garadget Door Name(Topic name)")
        input (name: "ipAddr", type: "text", title: "IP Address and port: ex: 192.168.0.1:1833")
        // put configuration here
        input(name: "logLevel",title: "IDE logging level",multiple: false,required: true,type: "enum",options: getLogLevels(),submitOnChange : false,defaultValue : "1")
        
    }

}

void installed() {
    log.warn "installed..."
}

// Parse incoming device messages to generate events
void parse(String description) {
    //topicFull=interfaces.mqtt.parseMessage(description).topic
	//def topic=topicFull.split('/')
	//def topicCount=topic.size()
	//def payload=interfaces.mqtt.parseMessage(description).payload.split(',')
    //log.debug "Desc.payload: " + interfaces.mqtt.parseMessage(description).payload
    //if (payload[0].startsWith ('{')) json="true" else json="false"
    //log.debug "json= " + json
    //log.debug "topic0: " + topic[0]
    //log.debug "topic1: " + topic[1]
    //log.debug "topic2: " + topic[2]
    //top=interfaces.mqtt.parseMessage(description).topic

    def message=interfaces.mqtt.parseMessage(description).payload
    jsonVal=parseJson(message)

    
    debuglog "status: " + jsonVal.status
    debuglog "bright: " + jsonVal.bright
    debuglog "sensor: " + jsonVal.sensor
    debuglog "signal: " + jsonVal.signal
    debuglog "time: " + jsonVal.time
    if (jsonVal.status == "closed") {
        sendEvent(name: "contact", value: "closed")
        sendEvent(name: "door", value: "closed")
    } else if (jsonVal.status == "open") {
        sendEvent(name: "contact", value: "open")
        sendEvent(name: "door", value: "open")
    } else if (jsonVal.status == "stopped") {
        sendEvent(name: "door", value: "stopped")
        sendEvent(name: "door", value: "open")
    } else if (jsonVal.status == "opening") {
        sendEvent(name: "door", value: "opening")
    } else if (jsonVal.status == "closing") {
        sendEvent(name: "door", value: "closing")
    } else {
        infolog "unknown event"
    }
    sendEvent(name: "sensor", value: jsonVal.sensor)
    sendEvent(name: "signal", value: jsonVal.signal)
    sendEvent(name: "bright", value: jsonVal.bright)
    sendEvent(name: "time", value: jsonVal.time)
    sendEvent(name:"illuminance", value: jsonVal.bright)
    
    
/*    
    def message
    message = interfaces.mqtt.parseMessage(description)
    log.debug description
    log.debug message
    
    payload = message["payload"]
    payload = payload.replace("\u007B","")
    payload = payload.replace("\u007D","")
    //payload = payload.replaceAll("\"", "")

    //payload = payload.replace("\"status\"", "status")
    //payload = payload.replace("\"time\"", "time")
    //payload = payload.replace("\"sensor\"", "sensor")
    //payload = payload.replace("\"bright\"", "bright")
    //payload = payload.replace("\"signal\"", "signal")
    
    payload = payload.replace(",", ", ")
    payload = payload.replace("s,", ",")
    
    log.debug "Payload: " + payload
    
    def map = [payload]
    log.debug "Map: " + map
    
    //log.debug "status: " + map.status
    
    //map.each{entry -> log.debug "$entry.key: $entry.value"}
    
    //sensor = map.sensor
    //log.debug sensor
    
    def newMap = ["status":"closed", "time":"0s", "sensor":97, "bright":21, "signal":-52]
    log.debug "newMap: " + newMap
    log.debug "newMap status: " + newMap.status
    
    */
    //sendEvent(name: "contact", value: "open")
    //sendEvent(name: "door", value: "open")
}

//void publishMsg(String s) {
//    interfaces.mqtt.publish("test/hubitat", s)
//}

void open() {
    debuglog "Opening command sent: garadget/${doorName}/command"
    interfaces.mqtt.publish("garadget/${doorName}/command", "open")
}
void close() {
    debuglog "Close command sent"
    interfaces.mqtt.publish("garadget/${doorName}/command", "close")
}
void refresh(){
    getstatus()
}

void getstatus() {
    debuglog "getstatus command sent"
    interfaces.mqtt.publish("garadget/${doorName}/command", "get-status")
}

void updated() {
    infolog "updated..."
    initialize()
}

void uninstalled() {
    infolog "disconnecting from mqtt"
    interfaces.mqtt.disconnect()
}

void initialize() {
    try {
        def mqttInt = interfaces.mqtt
        //open connection
        mqttInt.connect("tcp://${ipAddr}", "HEgaradget ${doorName}", null, null)
        //give it a chance to start
        pauseExecution(1000)
        infolog "connection established"
        mqttInt.subscribe("garadget/${doorName}/status")
        //mqttInt.subscribe("test/hubitat")
    } catch(e) {
        debuglog "initialize error: ${e.message}"
    }
}

void mqttClientStatus(String message) {
	infolog "Received status message ${message}"
}

def on() {
    debuglog "Sending on event to open door"
	open()
}

def off() {
    debuglog "Sending off event to close door"  
	close()
}

def debuglog(statement)
{   
	def logL = 0
    if (logLevel) logL = logLevel.toInteger()
    if (logL == 0) {return}//bail
    else if (logL >= 2)
	{
		log.debug(statement)
	}
}
def infolog(statement)
{       
	def logL = 0
    if (logLevel) logL = logLevel.toInteger()
    if (logL == 0) {return}//bail
    else if (logL >= 1)
	{
		log.info(statement)
	}
}
def getLogLevels(){
    return [["0":"None"],["1":"Running"],["2":"NeedHelp"]]
}
