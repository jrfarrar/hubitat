/**
 *  Sinope TH1300ZB Device Driver for Hubitat
 *
 *  Code derived from kris2k2
 *  Source: https://github.com/kris2k2/hubitat/drivers/kris2k2-Sinope-TH112XZB.groovy
 * 
 *  Code derived from Sinope's SmartThing TH1300ZB Thermostat
 *  Source: https://raw.githubusercontent.com/Sinopetech/Smartthings/master/Sinope%20Technologies%20TH1300ZB%20V.1.0.5.txt
 *
 *
 */

metadata {

    definition(name: "Sinope TH1300ZB Thermostat", namespace: "erilaj", author: "Eric Lajoie") {
        // https://docs.hubitat.com/index.php?title=Driver_Capability_List#Thermostat
        //
        capability "Configuration"
        capability "TemperatureMeasurement"
        capability "Thermostat"
        capability "Refresh"
        
        attribute "outdoorTemp", "string"
        attribute "gfciStatus", "enum", ["OK", "error"]
        attribute "floorLimitStatus", "enum", ["OK", "floorLimitLowReached", "floorLimitMaxReached", "floorAirLimitLowReached", "floorAirLimitMaxReached"]
        // Receiving temperature notifications via RuleEngine
        capability "Notification"
        
        command "eco"
        command "displayOn"
        command "displayOff"
        command "setClockTime"
        
        preferences {
            input name: "prefDisplayOutdoorTemp", type: "bool", title: "Enable display of outdoor temperature", defaultValue: true
            input name: "prefTimeFormatParam", type: "enum", title: "Time Format", options:[["1":"24h"], ["2":"12h AM/PM"]], defaultValue: "1", multiple: false, required: true
	    input name: "prefBacklightMode", type: "enum", title: "Backlight Mode", multiple: false, options: [["1":"Always ON"],["2":"On Demand"], ["3":"Custom Command"]], defaultValue: "1", submitOnChange:true, required: true
            input name: "prefAirFloorModeParam", type: "enum", title: "Control mode (Floor or Ambient temperature)", options: ["Ambient", "Floor"], defaultValue: "Floor", multiple: false, required: false
            input name: "prefFloorSensorTypeParam", type: "enum", title: "Probe type (Default: 10k)", options: ["10k", "12k"], defaultValue: "10k", multiple: false, required: false
            input name: "prefKeyLock", type: "bool", title: "Enable keylock", defaultValue: false
            input name: "prefLogging", type: "bool", title: "Enable logging", defaultValue: false
        }        

       fingerprint endpoint: "1",
			profileId: "0104",
			inClusters: "0000,0003,0004,0005,0201,0204,0402,0B04,0B05,FF01",
			outClusters: "0003", manufacturer: "Sinope Technologies", model: "TH1300ZB"
    }
}

//-- Installation ----------------------------------------------------------------------------------------

def installed() {
    if(prefLogging) log.info "installed() : scheduling configure() every 3 hours"
    runEvery3Hours(configure)
}

def updated() {
    if(prefLogging) log.info "updated() : re-scheduling configure() every 3 hours, and once within a minute."
    try {
        unschedule()
    } catch (e) {
        if(prefLogging) log.error "updated(): Error unschedule() - ${errMsg}"
    }
    runIn(1,configure)
    runEvery3Hours(configure)  
    try{
        state.remove("displayClock")
        state.remove("hideClock")
    }catch(errMsg){
    	if(prefLogging) log.error "${errMsg}"
    }
}

def uninstalled() {
    if(prefLogging) log.info "uninstalled() : unscheduling configure()"
    try {    
        unschedule()
    } catch (errMsg) {
        if(prefLogging) log.error "uninstalled(): Error unschedule() - ${errMsg}"
    }
}


//-- Parsing ---------------------------------------------------------------------------------------------

// parse events into attributes
def parse(String description) {
    def result = []
    def scale = getTemperatureScale()
    state?.scale = scale
    def cluster = zigbee.parse(description)
    if (description?.startsWith("read attr -")) {
        // log.info description
        def descMap = zigbee.parseDescriptionAsMap(description)
        result += createCustomMap(descMap)
        if(descMap.additionalAttrs){
               def mapAdditionnalAttrs = descMap.additionalAttrs
            mapAdditionnalAttrs.each{add ->
                add.cluster = descMap.cluster
                result += createCustomMap(add)
            }
        }
    }
    
    return result
}

private createCustomMap(descMap){
    def result = null
    def map = [: ]
        if (descMap.cluster == "0201" && descMap.attrId == "0000") {
            map.name = "temperature"
            if(map.value > 158)
            {
            	map.value = "Sensor Error"
            }else{
            map.value = getTemperature(descMap.value)
            }
            map.unit = "°${location.temperatureScale}"
            
        } else if (descMap.cluster == "0201" && descMap.attrId == "0008") {
            map.name = "thermostatOperatingState"
            map.value = getHeatingDemand(descMap.value)
            map.value = (map.value.toInteger() < 10) ? "idle" : "heating"
        
        } else if (descMap.cluster == "0201" && descMap.attrId == "0012") {
            map.name = "heatingSetpoint"
            map.value = getTemperature(descMap.value)
            map.unit = "°${location.temperatureScale}"
            sendEvent("name": "thermostatSetpoint", "value": getTemperature(descMap.value), "unit": "°${location.temperatureScale}")
            if(prefLogging) log.info "heatingSetpoint: ${map.value}"
            
        } else if (descMap.cluster == "0201" && descMap.attrId == "0015") {
            map.name = "heatingSetpointRangeLow"
            map.value = getTemperature(descMap.value)

        } else if (descMap.cluster == "0201" && descMap.attrId == "0016") {
            map.name = "heatingSetpointRangeHigh"
            map.value = getTemperature(descMap.value)
            
        } else if (descMap.cluster == "0201" && descMap.attrId == "001C") {
            map.name = "thermostatMode"
            map.value = getModeMap()[descMap.value]
            
        } else if (descMap.cluster == "0204" && descMap.attrId == "0001") {
            map.name = "thermostatLock"
            map.value = getLockMap()[descMap.value]
            
        } else if (descMap.cluster == "FF01" && descMap.attrId == "010c") {
			map.name = "floorLimitStatus"
			if(descMap.value.toInteger() == 0){
            	map.value = "OK"
            }else if(descMap.value.toInteger() == 1){
            	map.value = "floorLimitLowReached"
            }else if(descMap.value.toInteger() == 2){
            	map.value = "floorLimitMaxReached"
            }else if(descMap.value.toInteger() == 3){
            	map.value = "floorAirLimitMaxReached"
            }else{
            	map.value = "floorAirLimitMaxReached"
            }
        } else if (descMap.cluster == "FF01" && descMap.attrId == "0115") {
			map.name = "gfciStatus"
			if(descMap.value.toInteger() == 0){
            	map.value = "OK"
            }else if(descMap.value.toInteger() == 1){
            	map.value = "error"
            }
		}
        
    if (map) {
        def isChange = isStateChange(device, map.name, map.value.toString())
        map.displayed = isChange
        if ((map.name.toLowerCase().contains("temp")) || (map.name.toLowerCase().contains("setpoint"))) {
            map.scale = scale
        }
        // log.info "map: ${map}"
        result = createEvent(map)
    }
    return result
}

//-- Capabilities

def refresh() {
    if(prefLogging) log.info "refresh()"
    def cmds = []    
    cmds += zigbee.readAttribute(0x0204, 0x0000)	// Rd thermostat display mode       
    cmds += zigbee.readAttribute(0x0201, 0x0000)	// Rd thermostat Local temperature
    cmds += zigbee.readAttribute(0x0201, 0x0012)	// Rd thermostat Occupied heating setpoint
    cmds += zigbee.readAttribute(0x0201, 0x0008)	// Rd thermostat PI heating demand
    cmds += zigbee.readAttribute(0x0201, 0x001C)	// Rd thermostat System Mode
    cmds += zigbee.readAttribute(0x0204, 0x0001) 	// Rd thermostat Keypad lockout
    cmds += zigbee.readAttribute(0x0201, 0x0015)	// Rd thermostat Minimum heating setpoint
    cmds += zigbee.readAttribute(0x0201, 0x0016)	// Rd thermostat Maximum heating setpoint
    cmds += zigbee.readAttribute(0xFF01, 0x0105)	// Rd thermostat Control mode
  
    // Submit zigbee commands
    sendZigbeeCommands(cmds)
    setClockTime()
}   

def configure(){    
    if(prefLogging) log.info "configure()"
        
    // Set unused default values
    sendEvent(name: "coolingSetpoint", value:getTemperature("0BB8")) // 0x0BB8 =  30 Celsius
    sendEvent(name: "thermostatFanMode", value:"auto") // We dont have a fan, so auto is is
    updateDataValue("lastRunningMode", "heat") // heat is the only compatible mode for this device

    // Prepare our zigbee commands
    def cmds = []

    // Configure Reporting
    cmds += zigbee.configureReporting(0x0201, 0x0000, 0x29, 19, 300, 25) 	// local temperature
    cmds += zigbee.configureReporting(0x0201, 0x0008, 0x0020, 11, 301, 10) 	// heating demand
    cmds += zigbee.configureReporting(0x0201, 0x0012, 0x0029, 8, 302, 40) 	// occupied heating setpoint
    cmds += zigbee.configureReporting(0xFF01, 0x0115, 0x30, 10, 3600, 1) 	// report gfci status each hours
    cmds += zigbee.configureReporting(0xFF01, 0x010C, 0x30, 10, 3600, 1) 	// floor limit status each hours
    
    // Configure displayed scale
    if (getTemperatureScale() == 'C') {
        cmds += zigbee.writeAttribute(0x0204, 0x0000, 0x30, 0)    // Wr °C on thermostat display
    } else {
        cmds += zigbee.writeAttribute(0x0204, 0x0000, 0x30, 1)    // Wr °F on thermostat display 
    }

    // Configure keylock
    if (prefKeyLock) {
        cmds+= zigbee.writeAttribute(0x204, 0x01, 0x30, 0x01) // Lock Keys
    } else {
        cmds+= zigbee.writeAttribute(0x204, 0x01, 0x30, 0x00) // Unlock Keys
    }

    // Configure Outdoor Weather
    if (prefDisplayOutdoorTemp) {
        cmds += zigbee.writeAttribute(0xFF01, 0x0011, 0x21, 10800)  //set the outdoor temperature timeout to 3 hours
    } else {
        cmds += zigbee.writeAttribute(0xFF01, 0x0011, 0x21, 0)  //set the outdoor temperature timeout immediately
    }     
        
    // Configure Screen Baclight
    if(prefBacklightMode == "1"){
         cmds += zigbee.writeAttribute(0x0201, 0x0402, 0x30, 0x0001) // set display brigtness to explicitly on
    }else if(prefBacklightMode == "2"){
        cmds += zigbee.writeAttribute(0x0201, 0x0402, 0x30, 0x0000) // set display brightness to ambient lighting
    }

    //Configure Clock Format
    if(prefTimeFormatParam == "2"){//12h AM/PM
       if(prefLogging) log.info "Set to 12h AM/PM"
        cmds += zigbee.writeAttribute(0xFF01, 0x0114, 0x30, 0x0001)
    }
    else{//24h
        if(prefLogging) log.info "Set to 24h"
        cmds += zigbee.writeAttribute(0xFF01, 0x0114, 0x30, 0x0000)
    }

    if(prefAirFloorModeParam == "Ambient"){//Air mode
        if(prefLogging) log.info "Set to Ambient mode"
         cmds += zigbee.writeAttribute(0xFF01, 0x0105, 0x30, 0x0001)
    }
    else{//Floor mode
        if(prefLogging) log.info "Set to Floor mode"
        cmds += zigbee.writeAttribute(0xFF01, 0x0105, 0x30, 0x0002)
    }

    if(prefFloorSensorTypeParam == "12k"){//sensor type = 12k
         if(prefLogging) log.info "Sensor type is 12k"
        cmds += zigbee.writeAttribute(0xFF01, 0x010B, 0x30, 0x0001)
    }
    else{//sensor type = 10k
         if(prefLogging) log.info "Sensor type is 10k"
        cmds += zigbee.writeAttribute(0xFF01, 0x010B, 0x30, 0x0000)
    }

    // Submit zigbee commands
    sendZigbeeCommands(cmds)    
    // Submit refresh
    refresh()   
    // Return
    return
}

def auto() {
    if(prefLogging) log.info "auto(): mode is not available for this device. => Defaulting to heat mode instead."
    heat()
}

def cool() {
    if(prefLogging) log.info "cool(): mode is not available for this device. => Defaulting to eco mode instead."
    eco()
}

def emergencyHeat() {
    if(prefLogging) log.info "emergencyHeat(): mode is not available for this device. => Defaulting to heat mode instead."
    heat()
}

def fanAuto() {
    if(prefLogging) log.info "fanAuto(): mode is not available for this device"
}

def fanCirculate() {
    if(prefLogging) log.info "fanCirculate(): mode is not available for this device"
}

def fanOn() {
    if(prefLogging) log.info "fanOn(): mode is not available for this device"
}

def heat() {
    if(prefLogging) log.info "heat(): mode set"
    
    def cmds = []
    cmds += zigbee.writeAttribute(0x0201, 0x001C, 0x30, 04, [:], 1000) // MODE
    cmds += zigbee.writeAttribute(0x0201, 0x401C, 0x30, 04, [mfgCode: "0x1185"]) // SETPOINT MODE
    sendEvent("name": "thermostatMode", "value": "heat")
    // Submit zigbee commands
    sendZigbeeCommands(cmds)
}

def off() {
    if(prefLogging) log.info "off(): mode set"
    
    def cmds = []
    cmds += zigbee.writeAttribute(0x0201, 0x001C, 0x30, 0)
     sendEvent("name": "thermostatMode", "value": "off")
    // Submit zigbee commands
    sendZigbeeCommands(cmds)    
}

def setCoolingSetpoint(degrees) {
    if(prefLogging) log.info "setCoolingSetpoint(${degrees}): is not available for this device"
}

def setHeatingSetpoint(preciseDegrees) {
    if(prefLogging) log.info "setHeatingSetpoint(${preciseDegrees})"
    if (preciseDegrees != null) {
        def temperatureScale = getTemperatureScale()
        def degrees = new BigDecimal(preciseDegrees).setScale(1, BigDecimal.ROUND_HALF_UP)
        def cmds = []        
        
        if(prefLogging) log.info "setHeatingSetpoint(${degrees}:${temperatureScale})"
        
        def celsius = (temperatureScale == "C") ? degrees as Float : (fahrenheitToCelsius(degrees) as Float).round(2)
        int celsius100 = Math.round(celsius * 100)
      
        cmds += zigbee.writeAttribute(0x0201, 0x0012, 0x29, celsius100) //Write Heat Setpoint

        // Submit zigbee commands
        sendZigbeeCommands(cmds)         
    } 
}

def setSchedule(JSON_OBJECT){
    if(prefLogging) log.info "setSchedule(JSON_OBJECT): is not available for this device"
}

def setThermostatFanMode(fanmode){
    if(prefLogging) log.info "setThermostatFanMode(${fanmode}): is not available for this device"
}

def setThermostatMode(String value) {
    if(prefLogging) log.info "setThermostatMode(${value})"
    def currentMode = device.currentState("thermostatMode")?.value
    def lastTriedMode = state.lastTriedMode ?: currentMode ?: "heat"
    def modeNumber;
    Integer setpointModeNumber;
    def modeToSendInString;
    switch (value) {
        case "heat":
        case "emergency heat":
        case "auto":
            return heat()
        
        case "eco":
        case "cool":
            return eco()
        
        default:
            return off()
    }
}

def eco() {
    if(prefLogging) log.info "eco()"
    
    def cmds = []
    cmds += zigbee.writeAttribute(0x0201, 0x001C, 0x30, 04, [:], 1000) // MODE
    cmds += zigbee.writeAttribute(0x0201, 0x401C, 0x30, 05, [mfgCode: "0x1185"]) // SETPOINT MODE    
    
    // Submit zigbee commands
    sendZigbeeCommands(cmds)   
}

def deviceNotification(text) {
   
    double outdoorTemp = text.toDouble()
    def cmds = []

    if (prefDisplayOutdoorTemp) {
        
        if(prefLogging){
             log.info "deviceNotification() : Received outdoor weather : ${text} : ${outdoorTemp}"
        }
            sendEvent( name: "outdoorTemp", value: outdoorTemp, unit: state?.scale)
            //the value sent to the thermostat must be in C
            if (getTemperatureScale() == 'F') {    
                outdoorTemp = fahrenheitToCelsius(outdoorTemp).toDouble().round()
            }        
            int outdoorTempDevice = outdoorTemp*100
            cmds += zigbee.writeAttribute(0xFF01, 0x0011, 0x21, 10800)   //set the outdoor temperature timeout to 3 hours
            cmds += zigbee.writeAttribute(0xFF01, 0x0010, 0x29, outdoorTempDevice, [mfgCode: "0x119C"]) //set the outdoor temperature as integer
    
            // Submit zigbee commands    
            sendZigbeeCommands(cmds)
    } else {
            if(prefLogging) log.info "deviceNotification() : Not setting any outdoor weather, since feature is disabled."  
    }
}
def displayOn(){
    if(prefLogging) log.info "displayOn() command send"
    def cmds = []
    cmds += zigbee.writeAttribute(0x0201, 0x0402, 0x30, 0x0001) // set display brigtness to explicitly on 
    // Submit zigbee commands    
    sendZigbeeCommands(cmds)
}
def displayOff(){
    if(prefLogging) log.info "displayOff() command send"
    def cmds = []
     cmds += zigbee.writeAttribute(0x0201, 0x0402, 0x30, 0x0000) // set display brightnes to ambient lighting
     // Submit zigbee commands    
     sendZigbeeCommands(cmds)
}

def setClockTime() {
     if(prefLogging) log.info "setClockTime() command send"
      def cmds=[]    
      // Time
      def thermostatDate = new Date();
      def thermostatTimeSec = thermostatDate.getTime() / 1000;
      def thermostatTimezoneOffsetSec = thermostatDate.getTimezoneOffset() * 60;
      def currentTimeToDisplay = Math.round(thermostatTimeSec - thermostatTimezoneOffsetSec - 946684800);
      cmds += zigbee.writeAttribute(0xFF01, 0x0020, DataType.UINT32, zigbee.convertHexToInt(hex(currentTimeToDisplay)), [mfgCode: "0x119C"])
    
	sendZigbeeCommands(cmds)
}
//-- Private functions -----------------------------------------------------------------------------------
private void sendZigbeeCommands(cmds) {
    cmds.removeAll { it.startsWith("delay") }
    def hubAction = new hubitat.device.HubMultiAction(cmds, hubitat.device.Protocol.ZIGBEE)
    sendHubCommand(hubAction)
}

private getTemperature(value) {
    if (value != null) {
        def celsius = Integer.parseInt(value, 16) / 100
        if (getTemperatureScale() == "C") {
            return celsius
        }
        else {
            return Math.round(celsiusToFahrenheit(celsius))
        }
    }
}

private getModeMap() {
  [
    "00": "off",
    "04": "heat"
  ]
}

private getLockMap() {
  [
    "00": "unlocked ",
    "01": "locked ",
  ]
}

private getTemperatureScale() {
    return "${location.temperatureScale}"
}

private getHeatingDemand(value) {
    if (value != null) {
        def demand = Integer.parseInt(value, 16)
        return demand.toString()
    }
}

private hex(value) {

	String hex=new BigInteger(Math.round(value).toString()).toString(16)
	return hex
}

private String swapEndianHex(String hex) {
	 reverseArray(hex.decodeHex()).encodeHex()
}

private byte[] reverseArray(byte[] array) {
	int i = 0;
	int j = array.length - 1;
	byte tmp;

	while (j > i) {
		tmp = array[j];
		array[j] = array[i];
		array[i] = tmp;
		j--;
		i++;
	}

	return array
}
