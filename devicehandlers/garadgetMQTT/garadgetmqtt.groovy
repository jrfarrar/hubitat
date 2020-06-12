
/**
 *  Garadget MQTT Device Handler
 *
 *  J.R. Farrar (jrfarrar)
 *
 * 2020/06/12 V1.0 First release
 */

metadata {
    definition (name: "Garadget MQTT", 
                namespace: "jrfarrar", 
                author: "J.R. Farrar",
               importUrl: "https://raw.githubusercontent.com/jrfarrar/hubitat/master/devicehandlers/garadgetMQTT/garadgetmqtt.groovy") {
        capability "Initialize"
        capability "Garage Door Control"
        capability "Contact Sensor"
        capability "Switch"
        capability "Refresh"
        capability "Illuminance Measurement"
        capability "Configuration"
        
        attribute "status", "string"
        attribute "time", "string"
        attribute "sensor", "number"
        attribute "signal", "number"
        attribute "bright", "number"

        //command "get-status", ["String"]
        //command "getstatus"
preferences {
        //paragraph "Press Configuration button after changing preferences."
    section("Settings for connection from HE to Broker") {
        input (name: "doorName", type: "text", title: "Garadget Door Name(Topic name)")
        input (name: "ipAddr", type: "text", title: "IP Address and port: ex: 192.168.0.1:1833 this is for this DH to connect to")
        }
    section("Settings for Garadget"){
        // put configuration here
        input ( "rdt", "number", title: "sensor scan interval in mS (200-60,000, default 1,000)", defaultValue: 1000,range: "200..60000", required: false)
        input ( "mtt", "number", title: "door moving time in mS from completely opened to completely closed (1,000 - 120,000, default 10,000)", defaultValue: 10000,range: "1000..120000", required: false)
        input ( "rlt", "number", title: "button press time mS, time for relay to keep contacts closed (10-2,000, default 300)", defaultValue: 300,range: "10..2000", required: false)
        input ( "rlp", "number", title: "delay between consecutive button presses in mS (10-5,000 default 1,000)", defaultValue: 1000,range: "10..5000", required: false)
        input ( "srt", "number", title: "reflection threshold below which the door is considered open (1-80, default 25)", defaultValue: 25,range: "1..80", required: false)
        //input ( "nme", "text", title: "device name to be used in MQTT topic. If cloud connection enabled, at reboot this value will be overwritten with the one saved in cloud via the apps", required: false)
        //input ( "mqtt", "text", title: "bitmap 0x01 - cloud enabled, 0x02 - mqtt enabled, 0x03 - cloud and mqtt enabled", defaultValue: "0x03", required: false)
        //input ( "mqip", "text", title: "MQTT broker IP address(IP for Garadget to connect to)", required: false)
        //input ( "mqpt", "number", title: "MQTT broker port number(port for Garadget to connect to)", required: false)
        //input ( "mqus", "text", title: "MQTT user", required: false)
        input ( "mqto", "number", title: "MQTT timeout (keep alive) in seconds", defaultValue: 15, required: false)
        }
    section("Logging"){
        //logging
        input(name: "logLevel",title: "IDE logging level",multiple: false,required: true,type: "enum",options: getLogLevels(),submitOnChange : false,defaultValue : "1")
        }
     }
  }
}


void installed() {
    log.warn "installed..."
}

// Parse incoming device messages to generate events
void parse(String description) {
    topicFull=interfaces.mqtt.parseMessage(description).topic
	def topic=topicFull.split('/')
	//def topicCount=topic.size()
	//def payload=interfaces.mqtt.parseMessage(description).payload.split(',')
    //log.debug "Desc.payload: " + interfaces.mqtt.parseMessage(description).payload
    //if (payload[0].startsWith ('{')) json="true" else json="false"
    //log.debug "json= " + json
    //log.debug "topic0: " + topic[0]
    //log.debug "topic1: " + topic[1]
    log.debug "topic2: " + topic[2]
    //top=interfaces.mqtt.parseMessage(description).topic

    def message=interfaces.mqtt.parseMessage(description).payload
    jsonVal=parseJson(message)
    if (topic[2] == "status") {
        getStatus(jsonVal)
    } else if (topic[2] == "config") {
        getConfig(jsonVal)
    }
    

}

void getStatus(status) {
    debuglog "status: " + status.status
    debuglog "bright: " + status.bright
    debuglog "sensor: " + status.sensor
    debuglog "signal: " + status.signal
    debuglog "time: " + status.time
    if (status.status == "closed") {
        sendEvent(name: "contact", value: "closed")
        sendEvent(name: "door", value: "closed")
    } else if (status.status == "open") {
        sendEvent(name: "contact", value: "open")
        sendEvent(name: "door", value: "open")
    } else if (status.status == "stopped") {
        sendEvent(name: "door", value: "stopped")
        sendEvent(name: "door", value: "open")
    } else if (status.status == "opening") {
        sendEvent(name: "door", value: "opening")
        sendEvent(name: "contact", value: "open")
    } else if (status.status == "closing") {
        sendEvent(name: "door", value: "closing")
    } else {
        infolog "unknown event"
    }
    sendEvent(name: "sensor", value: status.sensor)
    sendEvent(name: "signal", value: status.signal)
    sendEvent(name: "bright", value: status.bright)
    sendEvent(name: "time", value: status.time)
    sendEvent(name: "illuminance", value: status.bright)
}
void getConfig(config) {
    debuglog "rdt: " + config.rdt + " - sensor scan interval in mS (200-60,000, default 1,000)"
    debuglog "mtt: " + config.mtt + " - door moving time in mS from completely opened to completely closed (1,000 - 120,000, default 10,000)"
    debuglog "rlt: " + config.rlt + " - button press time mS, time for relay to keep contacts closed (10-2,000, default 300)"
    debuglog "rlp: " + config.rlp + " - delay between consecutive button presses in mS (10-5,000 default 1,000)"
    debuglog "srt: " + config.srt + " - reflection threshold below which the door is considered open (1-80, default 25)"
    debuglog "nme: " + config.nme + " - device name to be used in MQTT topic. If cloud connection enabled, at reboot this value will be overwritten with the one saved in cloud via the apps"
    debuglog "mqtt: " + config.mqtt + " - bitmap 0x01 - cloud enabled, 0x02 - mqtt enabled, 0x03 - cloud and mqtt enabled"
    debuglog "mqip: " + config.mqip + " - MQTT broker IP address"
    debuglog "mqpt: " + config.mqpt + " - MQTT broker port number"
    debuglog "mqus: " + config.mqus + " - MQTT user"
    debuglog "mqto: " + config.mqto + " - MQTT timeout (keep alive) in seconds"
    rdt = config.rdt
    mtt = config.mtt
    rlt = config.rlt
    rlp = config.rlp
    srt = config.srt
    //nme = config.nme
    //mqtt = config.mqtt
    //mqip = config.mqip
    //mqpt = config.mqpt
    //mqus = config.mqus
    mqto = config.mqto

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
    interfaces.mqtt.publish("garadget/${doorName}/command", "get-config")
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
        mqttInt.subscribe("garadget/${doorName}/config")
        //mqttInt.subscribe("test/hubitat")
    } catch(e) {
        debuglog "initialize error: ${e.message}"
    }
    //configure()
}

void configure(){
    infolog "Configure..."
    
    def options = [
        'rdt': rdt,
        'mtt': mtt,
        'rlt': rlt,
        'rlp': rlp,
        'srt': srt,
        'mqto': mqto,
        ]
    
    def json = new groovy.json.JsonOutput().toJson(options)
    debuglog json
    interfaces.mqtt.publish("garadget/${doorName}/set-config", json)
    interfaces.mqtt.publish("garadget/${doorName}/command", "get-config")
}

void mqttClientStatus(String message) {
	infolog "Received status message ${message}"
}
void on() {
    debuglog "Sending on event to open door"
	open()
}
void off() {
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
