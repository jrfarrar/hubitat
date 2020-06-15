
/**
 *  Garadget MQTT Device Handler
 *
 *  J.R. Farrar (jrfarrar)
 *
 * 1.4.0 - 06/15/20 - added scheduling for refresh and reconnect, log streamlining
 * 1.3.3 - 06/15/20 - minor logging fix
 * 1.3.2 - 06/14/20 - Default bug, auto-reconnection if broker drops
 * 1.3.0 - 06/14/20 - impletmented Garadget IP and Port changing and validation of config data, documentation, other small stuff
 * 1.2.0 - 06/14/20 - added: stop command, watchdog, MQTT username/pass, separated IP addr & port
 * 1.1.1 - 06/12/20 - logging fixes
 * 1.1.0 - 06/12/20 - Initial Release
 */

metadata {
    definition (name: "Garadget MQTT - 1.4.0", 
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

preferences {
    section("Settings for connection from HE to Broker") {
        input name: "doorName", type: "text", title: "Garadget Door Name(Topic name)", required: true
        input name: "ipAddr", type: "text", title: "IP Address of MQTT broker", required: true
        input name: "ipPort", type: "text", title: "Port # of MQTT broker", defaultValue: "1883", required: true
        input name: "username", type: "text", title: "MQTT Username:", description: "(blank if none)", required: false
	    input name: "password", type: "password", title: "MQTT Password:", description: "(blank if none)", required: false
        input name: "retryTime", type: "number", title: "Number of minutes between retries to connect if broker goes down", defaultValue: 5, required: true
        input name: "refreshStats", type: "bool", title: "Refresh Garadget stats on a schedule?", defaultValue: false, required: true
        input name: "refreshTime", type: "number", title: "If using refresh, refresh this number of minutes", defaultValue: 5, required: true
        input name: "watchDogSched", type: "bool", title: "Check for connection to MQTT broker on a schedule?", defaultValue: false, required: true
        input name: "watchDogTime", type: "number", title: "This number of minutes to check for connection to MQTT broker", defaultValue: 15, required: true
        }
    section("Settings for Garadget"){
        // put configuration here
        if (doorName && ipAddr && ipPort) input ( "rdt", "number", title: "sensor scan interval in mS (200-60,000, default 1,000)", defaultValue: 1000,range: "200..60000", required: false)
        if (doorName && ipAddr && ipPort) input ( "mtt", "number", title: "door moving time in mS from completely opened to completely closed (1,000 - 120,000, default 10,000)", defaultValue: 10000,range: "1000..120000", required: false)
        if (doorName && ipAddr && ipPort) input ( "rlt", "number", title: "button press time mS, time for relay to keep contacts closed (10-2,000, default 300)", defaultValue: 300,range: "10..2000", required: false)
        if (doorName && ipAddr && ipPort) input ( "rlp", "number", title: "delay between consecutive button presses in mS (10-5,000 default 1,000)", defaultValue: 1000,range: "10..5000", required: false)
        if (doorName && ipAddr && ipPort) input ( "srt", "number", title: "reflection threshold below which the door is considered open (1-80, default 25)", defaultValue: 25,range: "1..80", required: false)
        //Topic name is broken in current version(1.20) of Garadget firmware. It uses the Door Name
        //if (doorName) input ( "nme", "text", title: "device name to be used in MQTT topic. If cloud connection enabled, at reboot this value will be overwritten with the one saved in cloud via the app", required: false)
        //Not positive of how to cast this bitmap in HE so leaving this commented out for now
        //if (doorName) input ( "mqtt", "text", title: "bitmap 0x01 - cloud enabled, 0x02 - mqtt enabled, 0x03 - cloud and mqtt enabled", defaultValue: "0x03", required: false)
        if (doorName && ipAddr && ipPort) input ( "mqip", "text", title: "*CAUTION: This can break your connection*\n MQTT broker IP address(IP for Garadget to connect to)", required: false)
        if (doorName && ipAddr && ipPort) input ( "mqpt", "number", title: "*CAUTION: This can break your connection*\n MQTT broker port number(port for Garadget to connect to)", required: false)
        //Leaving username commented out as no need to change it since you can't change password via MQTT
        //if (doorName) input ( "mqus", "text", title: "MQTT user", required: false)
        if (doorName && ipAddr && ipPort) input ( "mqto", "number", title: "MQTT timeout (keep alive) in seconds", defaultValue: 15, required: false)
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
	state.version = "1.4.0 - This Device Handler version"   
}

void installed() {
    log.warn "installed..."
}

// Parse incoming device messages to generate events
void parse(String description) {
    topicFull=interfaces.mqtt.parseMessage(description).topic
	def topic=topicFull.split('/')
    /*
	//def topicCount=topic.size()
	//def payload=interfaces.mqtt.parseMessage(description).payload.split(',')
    //log.debug "Desc.payload: " + interfaces.mqtt.parseMessage(description).payload
    //if (payload[0].startsWith ('{')) json="true" else json="false"
    //log.debug "json= " + json
    //log.debug "topic0: " + topic[0]
    //log.debug "topic1: " + topic[1]
    //debuglog "topic2: " + topic[2]
    //top=interfaces.mqtt.parseMessage(description).topic
    */
    
    def message=interfaces.mqtt.parseMessage(description).payload
    if (message) {
    jsonVal=parseJson(message)
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
    debuglog "status: " + status.status
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
        infolog "unknown status event"
    }
    sendEvent(name: "sensor", value: status.sensor)
    sendEvent(name: "signal", value: status.signal)
    sendEvent(name: "bright", value: status.bright)
    sendEvent(name: "time", value: status.time)
    sendEvent(name: "illuminance", value: status.bright)
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
    rdt = config.rdt
    device.updateSetting("rdt", [value: "${rdt}", type: "number"])
    sendEvent(name: "rdt", value: rdt)
    //
    debuglog "mtt: " + config.mtt + " - door moving time in mS from completely opened to completely closed (1,000 - 120,000, default 10,000)"
    mtt = config.mtt
    device.updateSetting("mtt", [value: "${mtt}", type: "number"])
    sendEvent(name: "mtt", value: mtt)
    //
    debuglog "rlt: " + config.rlt + " - button press time mS, time for relay to keep contacts closed (10-2,000, default 300)"
    rlt = config.rlt
    device.updateSetting("rlt", [value: "${rlt}", type: "number"])
    sendEvent(name: "rlt", value: rlt)
    //
    debuglog "rlp: " + config.rlp + " - delay between consecutive button presses in mS (10-5,000 default 1,000)"
    rlp = config.rlp
    device.updateSetting("rlp", [value: "${rlp}", type: "number"])
    sendEvent(name: "rlp", value: rlp)
    //
    debuglog "srt: " + config.srt + " - reflection threshold below which the door is considered open (1-80, default 25)"
    srt = config.srt
    device.updateSetting("srt", [value: "${srt}", type: "number"])
    sendEvent(name: "srt", value: srt)  
    //
    //nme is currently broken in Garadget firmware 1.2 - it does not honor it. It uses default device name.
    debuglog "nme: " + config.nme + " - device name to be used in MQTT topic."
    //nme = config.nme
    //device.updateSetting("nme", [value: "${nme}", type: "text"])
    //sendEvent(name: "nme", value: nme")
    //
    //Not tested setting the bitmap from HE - needs to be tested
    debuglog "mqtt: " + config.mqtt + " - bitmap 0x01 - cloud enabled, 0x02 - mqtt enabled, 0x03 - cloud and mqtt enabled"
    //mqtt = config.mqtt
    //device.updateSetting("mqtt", [value: "${mqtt}", type: "text"])
    //sendEvent(name: "mqtt", value: mqtt")
    //
    debuglog "mqip: " + config.mqip + " - MQTT broker IP address"    
    mqip = config.mqip
    device.updateSetting("mqip", [value: "${mqip}", type: "text"])
    sendEvent(name: "mqip", value: mqip)
    //
    debuglog "mqpt: " + config.mqpt + " - MQTT broker port number"
    mqpt = config.mqpt
    device.updateSetting("mqpt", [value: "${mqpt}", type: "number"])
    sendEvent(name: "mqpt", value: mqpt)
    //
    //See no need to implement changing the username as you can't change the password via the MQTT interface
    debuglog "mqus: " + config.mqus + " - MQTT user"
    //mqus = config.mqus
    //
    debuglog "mqto: " + config.mqto + " - MQTT timeout (keep alive) in seconds"
    mqto = config.mqto
    device.updateSetting("mqto", [value: "${mqto}", type: "number"])
    sendEvent(name: "mqto", value: mqto)
}

void refresh(){
    getstatus()
    setVersion()
}
//refresh data from status and config topics
void getstatus() {
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
        schedule("22 3/${refreshTime} * ? * *", getstatus) 
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
        mqttInt.subscribe("garadget/${doorName}/status")
        mqttInt.subscribe("garadget/${doorName}/config")
    } catch(e) {
        log.warn "${device.label?device.label:device.name}: MQTT initialize error: ${e.message}"
    }
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
    infolog "Open..."
    watchDog()
    interfaces.mqtt.publish("garadget/${doorName}/command", "open")
}
void close() {
    infolog "Close..."
    watchDog()
    interfaces.mqtt.publish("garadget/${doorName}/command", "close")
}
def stop(){
	infolog "Stop..."
    watchDog()
    interfaces.mqtt.publish("garadget/${doorName}/command", "stop")
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
//if connection is dropped, try to reconnect every (retryTime) minutes until the connection is back
void connectionLost(){
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
    device.updateSetting("logLevel",[value:"1",type:"number"])
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
