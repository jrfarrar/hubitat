
/**
 *  Garadget MQTT Device Handler
 *
 *  J.R. Farrar (jrfarrar)
 *
 * 1.1.1 - 06/12/10 - logging fixes
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

preferences {
    section("Settings for connection from HE to Broker") {
        input (name: "doorName", type: "text", title: "Garadget Door Name(Topic name)")
        input (name: "ipAddr", type: "text", title: "IP Address and port of MQTT broker - EXAMPLE: 192.168.0.1:1833 ")
        }
    section("Settings for Garadget"){
        // put configuration here
        if (doorName) input ( "rdt", "number", title: "sensor scan interval in mS (200-60,000, default 1,000)", defaultValue: 1000,range: "200..60000", required: false)
        if (doorName) input ( "mtt", "number", title: "door moving time in mS from completely opened to completely closed (1,000 - 120,000, default 10,000)", defaultValue: 10000,range: "1000..120000", required: false)
        if (doorName) input ( "rlt", "number", title: "button press time mS, time for relay to keep contacts closed (10-2,000, default 300)", defaultValue: 300,range: "10..2000", required: false)
        if (doorName) input ( "rlp", "number", title: "delay between consecutive button presses in mS (10-5,000 default 1,000)", defaultValue: 1000,range: "10..5000", required: false)
        if (doorName) input ( "srt", "number", title: "reflection threshold below which the door is considered open (1-80, default 25)", defaultValue: srt,range: "1..80", required: false)
        //leaving the next few commented out. Do not think there should be a need to set these via this driver
        //if (doorName) input ( "nme", "text", title: "device name to be used in MQTT topic. If cloud connection enabled, at reboot this value will be overwritten with the one saved in cloud via the app", required: false)
        //if (doorName) input ( "mqtt", "text", title: "bitmap 0x01 - cloud enabled, 0x02 - mqtt enabled, 0x03 - cloud and mqtt enabled", defaultValue: "0x03", required: false)
        //if (doorName) input ( "mqip", "text", title: "MQTT broker IP address(IP for Garadget to connect to)", required: false)
        //if (doorName) input ( "mqpt", "number", title: "MQTT broker port number(port for Garadget to connect to)", required: false)
        //if (doorName) input ( "mqus", "text", title: "MQTT user", required: false)
        if (doorName) input ( "mqto", "number", title: "MQTT timeout (keep alive) in seconds", defaultValue: 15, required: false)
        }
    section("Logging"){
        //logging
        input(name: "logLevel",title: "IDE logging level",multiple: false,required: true,type: "enum",options: getLogLevels(),submitOnChange : false,defaultValue : "1")
        }
     }
  }
}

def setVersion(){
    //state.name = "Garadget MQTT"
	state.version = "1.1.1 - This DH"   
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
    debuglog "topic2: " + topic[2]
    //top=interfaces.mqtt.parseMessage(description).topic

    def message=interfaces.mqtt.parseMessage(description).payload
    jsonVal=parseJson(message)
    if (topic[2] == "status") {
        getStatus(jsonVal)
    } else if (topic[2] == "config") {
        getConfig(jsonVal)
    } else {
        debuglog "Unhandled topic..."
    }
    

}

void getStatus(status) {
    infolog "status: " + status.status
    debuglog "bright: " + status.bright
    debuglog "sensor: " + status.sensor
    debuglog "signal: " + status.signal
    debuglog "time: " + status.time
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
        sendEvent(name: "door", value: "open")
        sendEvent(name: "stopped", value: "true")
    } else if (status.status == "opening") {
        sendEvent(name: "door", value: "opening")
        sendEvent(name: "contact", value: "open")
        sendEvent(name: "stopped", value: "false")
    } else if (status.status == "closing") {
        sendEvent(name: "door", value: "closing")
        sendEvent(name: "stopped", value: "false")
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
    debuglog "sys: " + config.sys + " - Particle Firmware Version"
    state.sys = config.sys + " - Particle Firmware Version"
    debuglog "ver: " + config.ver + " - Garadget firmware version"
    state.ver = config.ver + " - Garadget firmware version"
    debuglog "id: "  + config.id  + " - Garadget/Particle device ID"
    state.id = config.id  + " - Garadget/Particle device ID"
    debuglog "ssid: "+ config.ssid + " - WiFi SSID name"
    state.ssid = config.ssid + " - WiFi SSID name"
    debuglog "rdt: " + config.rdt + " - sensor scan interval in mS (200-60,000, default 1,000)"
    debuglog "mtt: " + config.mtt + " - door moving time in mS from completely opened to completely closed (1,000 - 120,000, default 10,000)"
    debuglog "rlt: " + config.rlt + " - button press time mS, time for relay to keep contacts closed (10-2,000, default 300)"
    debuglog "rlp: " + config.rlp + " - delay between consecutive button presses in mS (10-5,000 default 1,000)"
    debuglog "srt: " + config.srt + " - reflection threshold below which the door is considered open (1-80, default 25)"
    debuglog "nme: " + config.nme + " - device name to be used in MQTT topic."
    debuglog "mqtt: " + config.mqtt + " - bitmap 0x01 - cloud enabled, 0x02 - mqtt enabled, 0x03 - cloud and mqtt enabled"
    debuglog "mqip: " + config.mqip + " - MQTT broker IP address"
    debuglog "mqpt: " + config.mqpt + " - MQTT broker port number"
    debuglog "mqus: " + config.mqus + " - MQTT user"
    debuglog "mqto: " + config.mqto + " - MQTT timeout (keep alive) in seconds"
    rdt = config.rdt
    device.updateSetting("rdt", [value: "${rdt}", type: "number"])
    mtt = config.mtt
    device.updateSetting("mtt", [value: "${mtt}", type: "number"])
    rlt = config.rlt
    device.updateSetting("rlt", [value: "${rlt}", type: "number"])
    rlp = config.rlp
    device.updateSetting("rlp", [value: "${rlp}", type: "number"])
    srt = config.srt
    device.updateSetting("srt", [value: "${srt}", type: "number"])
    //nme = config.nme
    //mqtt = config.mqtt
    //mqip = config.mqip
    //mqpt = config.mqpt
    //mqus = config.mqus
    mqto = config.mqto
    device.updateSetting("mqto", [value: "${mqto}", type: "number"])

}

void open() {
    infolog "Open command sent..."
    interfaces.mqtt.publish("garadget/${doorName}/command", "open")
}
void close() {
    infolog "Close command sent..."
    interfaces.mqtt.publish("garadget/${doorName}/command", "close")
}
void refresh(){
    getstatus()
    setVersion()
}
void getstatus() {
    infolog "Getting status and config..."
    interfaces.mqtt.publish("garadget/${doorName}/command", "get-status")
    interfaces.mqtt.publish("garadget/${doorName}/command", "get-config")
}
void updated() {
    infolog "updated..."
    initialize()
}
void uninstalled() {
    infolog "disconnecting from mqtt..."
    interfaces.mqtt.disconnect()
}

void initialize() {
    try {
        def mqttInt = interfaces.mqtt
        //open connection
        mqttInt.connect("tcp://${ipAddr}", "HEgaradget ${doorName}", null, null)
        //give it a chance to start
        pauseExecution(1000)
        infolog "connection established..."
        mqttInt.subscribe("garadget/${doorName}/status")
        mqttInt.subscribe("garadget/${doorName}/config")
    } catch(e) {
        debuglog "initialize error: ${e.message}"
    }
    interfaces.mqtt.publish("garadget/${doorName}/command", "get-config")
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
/*
void on() {
    debuglog "On, open door..."
	open()
}
void off() {
    debuglog "Off, close door..."  
	close()
}
*/
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
