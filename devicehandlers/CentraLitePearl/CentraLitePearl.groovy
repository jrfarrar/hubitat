/*
 *  Centralite Pearl Thermostat
 */
preferences {
    input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: true
}

metadata {
    definition (name: "CentraLite Pearl Thermostat", namespace: "jrfarrar", author: "dagrider") {
        capability "Actuator"
        capability "Temperature Measurement"
        capability "Thermostat"
        capability "Configuration"
        capability "Refresh"
        capability "Sensor"
        capability "Polling"
        capability "Battery"
                                
        // Custom commands 
        command "raiseHeatLevel"
        command "lowerHeatLevel"
        command "raiseCoolLevel"
        command "lowerCoolLevel"
        //command "setTemperature"
        command "setThermostatHoldMode"
        //command "getPowerSource"
 
        // thermostat capability commands
        // setHeatingSetpoint(number)
        // setCoolingSetpoint(number)
        // off()
        // heat()
        // emergencyHeat()
        // cool()
        // setThermostatMode(enum)
        // fanOn()
        // fanAuto()
        // fanCirculate()
        // setThermostatFanMode(enum)
        // auto()
 
        // thermostat capability attributes
        // temperature
        // heatingSetpoint
        // coolingSetpoint
        // thermostatSetpoint
        // thermostatMode (auto, emergency heat, heat, off, cool)
        // thermostatFanMode (auto, on, circulate)
        // thermostatOperatingState (heating, idle, pending cool, vent economizer, cooling, pending heat, fan only)
 
        attribute "thermostatHoldMode", "string"
        attribute "powerSource", "string"
                                
        fingerprint profileId: "0104", inClusters: "0000,0001,0003,0020,0201,0202,0204,0B05", outClusters: "000A, 0019"
    }
}
 
def installed() {
    log.debug "installed"
    configure()
}
 
def updated() {
    log.debug "updated"
    configure()
}
 
def configure() {
    log.debug "configure"
    def cmds = 
        [
            //"zdo bind 0x${device.deviceNetworkId} 1 1 0x0001 {${device.zigbeeId}} {}", "delay 500",
            //"zcl global send-me-a-report 0x0001 0x20 0x20 3600 86400 {01}", "delay 500", //battery report request
            //"send 0x${device.deviceNetworkId} 1 1",
            "zdo bind 0x${device.deviceNetworkId} 1 1 0x0001 {${device.zigbeeId}} {}", "delay 500",
            "zcl global send-me-a-report 0x0001 0x20 0x20 3600 86400 {}", "delay 500", //battery report request
            "send 0x${device.deviceNetworkId} 1 1",
            "zdo bind 0x${device.deviceNetworkId} 1 1 0x0201 {${device.zigbeeId}} {}", "delay 500",
            "zcl global send-me-a-report 0x0201 0x0000 0x29 5 300 {0A00}", "delay 500", // report temperature changes over 0.5°C (0x3200 in little endian)
            "send 0x${device.deviceNetworkId} 1 1",
            "zcl global send-me-a-report 0x0201 0x0011 0x29 5 300 {3200}", "delay 500", // report cooling setpoint delta: 0.5°C
            "send 0x${device.deviceNetworkId} 1 1",
            "zcl global send-me-a-report 0x0201 0x0012 0x29 5 300 {3200}", "delay 500", // report heating setpoint delta: 0.5°C
            "send 0x${device.deviceNetworkId} 1 1",
            "zcl global send-me-a-report 0x0201 0x001C 0x30 5 300 {}", "delay 500",     // report system mode
            "send 0x${device.deviceNetworkId} 1 1",
            "zcl global send-me-a-report 0x0201 0x0029 0x19 5 300 {}", "delay 500",    // report running state
            "send 0x${device.deviceNetworkId} 1 1",
           "zcl global send-me-a-report 0x0201 0x0023 0x30 5 300 {}", "delay 500",     // report hold mode
            "send 0x${device.deviceNetworkId} 1 1", 
            "zdo bind 0x${device.deviceNetworkId} 1 1 0x0202 {${device.zigbeeId}} {}", "delay 500",
            "zcl global send-me-a-report 0x0202 0 0x30 5 300 {}","delay 500",          // report fan mode
            "send 0x${device.deviceNetworkId} 1 1", 
        ]
    
    //cmds += zigbee.batteryConfig()
    
}
 
def refresh() {
    if (logEnable) log.debug "refresh called"
    // 0000 07 power source
    // 0201 00 temperature
    // 0201 11 cooling setpoint
    // 0201 12 heating setpoint
    // 0201 1C thermostat mode 
    // 0201 1E run mode
    // 0201 23 hold mode
    // 0001 20 battery
    // 0202 00 fan mode
 
    def cmds = zigbee.readAttribute(0x0000, 0x0007) +
               zigbee.readAttribute(0x0201, 0x0000) +
               zigbee.readAttribute(0x0201, 0x0011) +
               zigbee.readAttribute(0x0201, 0x0012) +
               zigbee.readAttribute(0x0201, 0x001C) +
               zigbee.readAttribute(0x0201, 0x001E) +
               zigbee.readAttribute(0x0201, 0x0023) +
               zigbee.readAttribute(0x0201, 0x0029) +
               zigbee.readAttribute(0x0001, 0x0020) +
               zigbee.readAttribute(0x0202, 0x0000)
    
    return cmds
}
 
def raiseHeatLevel(){
    if (isHoldOn()) return
    
    def currentLevel = device.currentValue("heatingSetpoint")
    int nextLevel = currentLevel.toInteger() + 1
    log.debug "raiseHeatLevel: calling setHeatingSetpoint with ${nextLevel}"
    setHeatingSetpoint(nextLevel)
}
 
def lowerHeatLevel(){
    if (isHoldOn()) return
    
    def currentLevel = device.currentValue("heatingSetpoint")
    int nextLevel = currentLevel.toInteger() - 1
    log.debug "lowerHeatLevel: calling setHeatingSetpoint with ${nextLevel}"
    setHeatingSetpoint(nextLevel)
}
 
def raiseCoolLevel(){
    if (isHoldOn()) return
    
    def currentLevel = device.currentValue("coolingSetpoint")
    int nextLevel = currentLevel.toInteger() + 1
    log.debug "raiseCoolLevel: calling setCoolingSetpoint with ${nextLevel}"
    setCoolingSetpoint(nextLevel)
}
 
def lowerCoolLevel(){
    if (isHoldOn()) return
    
    def currentLevel = device.currentValue("coolingSetpoint")
    int nextLevel = currentLevel.toInteger() - 1
    log.debug "lowerCoolLevel: calling setCoolingSetpoint with ${nextLevel}"
    setCoolingSetpoint(nextLevel)
}
 
def parse(String description) {
    if (logEnable) log.debug "Parse description $description"
    def map = [:]
 
    if (description?.startsWith("read attr -")) {
        def descMap = zigbee.parseDescriptionAsMap(description)
        
        if (descMap.cluster == "0201" && descMap.attrId == "0000") {
            if (logEnable) log.debug "TEMPERATURE"
            map.name = "temperature"
            map.unit = getTemperatureScale()
            map.value = getTemperature(descMap.value, map.name)
        } else if (descMap.cluster == "0201" && descMap.attrId == "0011") {
            if (logEnable) log.debug "COOLING SETPOINT"
            map.name = "coolingSetpoint"
            map.unit = getTemperatureScale()
            map.value = getTemperature(descMap.value, map.name)
        } else if (descMap.cluster == "0201" && descMap.attrId == "0012") {
            if (logEnable) log.debug "HEATING SETPOINT"
            map.name = "heatingSetpoint"
            map.unit = getTemperatureScale()
            map.value = getTemperature(descMap.value, map.name)
        } else if (descMap.cluster == "0201" && descMap.attrId == "001C") {
            if (logEnable) log.debug "MODE"
            map.name = "thermostatMode"
            map.value = getModeMap()[descMap.value]
        } else if (descMap.cluster == "0202" && descMap.attrId == "0000") {
            if (logEnable) log.debug "FAN MODE"
            map.name = "thermostatFanMode"
            map.value = getFanModeMap()[descMap.value]
        } else if (descMap.cluster == "0001" && descMap.attrId == "0020") {
            if (logEnable) log.debug "BATTERY"
            map.name = "battery"
            map.value = getBatteryLevel(descMap.value)
        } else if (descMap.cluster == "0201" && descMap.attrId == "001E") {
            if (logEnable) log.debug "RUN MODE"
            map.name = "thermostatRunMode"
            map.value = getModeMap()[descMap.value]
        } else if (descMap.cluster == "0201" && descMap.attrId == "0023") {
            if (logEnable) log.debug "HOLD MODE"
            map.name = "thermostatHoldMode"
            map.value = getHoldModeMap()[descMap.value]  
        } else if (descMap.cluster == "0201" && descMap.attrId == "0029") {
            if (logEnable) log.debug "OPERATING MODE"
            map.name = "thermostatOperatingState"
            map.value = getThermostatOperatingStateMap()[descMap.value]  
        } else if (descMap.cluster == "0000" && descMap.attrId == "0007") {
            if (logEnable) log.debug "POWER SOURCE"
            map.name = "powerSource"
            map.value = getPowerSource()[descMap.value]                                  
        }
    }
 
    def result = null
 
    if (map) {
        result = createEvent(map)
    }
 
    if (logEnable) log.debug "Parse returned $map"
    return result
}
 
def getModeMap() { 
    [
        "00":"off",
        "01":"auto",
        "03":"cool",
        "04":"heat",
        "05":"emergency heat",
        "06":"precooling",
        "07":"fan only",
        "08":"dry",
        "09":"sleep"
    ]
}
 
def modes() {
    ["off", "cool", "heat", "emergencyHeat"]
}
 
def getHoldModeMap() { 
    [
        "00":"holdOff",
        "01":"holdOn",
    ]
}
 
def getPowerSource() { 
    [
        "01":"24VAC",
        "03":"Battery",
        "81":"24VAC"
    ]
}
 
def getFanModeMap() { 
    [
        "04":"fanOn",
        "05":"fanAuto"
    ]
}

def getThermostatOperatingStateMap() {
    /**  Bit Number
    //  0 Heat State
    //  1 Cool State
    //  2 Fan State
    //  3 Heat 2nd Stage State
    //  4 Cool 2nd Stage State
    //  5 Fan 2nd Stage State
    //  6 Fan 3rd Stage Stage
    **/
    [
        "0000":"idle",
        "0001":"heating",
        "0002":"cooling",
        "0004":"fan only",
        "0005":"heating",
        "0006":"cooling",
        "0008":"heating",
        "0009":"heating",
        "000A":"heating",
        "000D":"heating",
        "0010":"cooling",
        "0012":"cooling",
        "0014":"cooling",
        "0015":"cooling"
    ]
}
 
def getTemperature(value, name) {
    if (value != null) {
        def temp = new BigInteger(value, 16) & 0xFFFF
        def celsius = temp / 100
        float fahrenheit = celsiusToFahrenheit(celsius)
        fahrenheit = fahrenheit.round(2)

        if (getTemperatureScale() == "C") {
            return celsius
        } else {
            if (name != "temperature") {
                fahrenheit = Math.round(fahrenheit)
            }
            return fahrenheit
        }
    }
}
 
def setThermostatHoldMode() {
    log.debug "setThermostatHoldMode"
    def currentHoldMode = device.currentState("thermostatHoldMode")?.value
    def returnCommand
 
    if (!currentHoldMode) { 
        log.debug "Unable to determine thermostat hold mode, setting to hold off"
        returnCommand = holdOff() 
    } else {
        log.debug "Switching thermostat from current mode: $currentHoldMode"
 
        switch (currentHoldMode) {
            case "holdOff":
                returnCommand = holdOn()
                break
            case "holdOn":
                returnCommand = holdOff()
                break
        }
    }
 
    returnCommand
}
 
def setThermostatMode(String value) {
    log.debug "setThermostatMode to {$value}"
    if (value == "emergency heat") {
        emergencyHeat()
    } else {
        "$value"()
    }
}
 
def setThermostatFanMode(String value) {
    log.debug "setThermostatFanMode({$value})"
    if (value == "auto") {
        fanAuto()
    } else {
        "$value"()
    }
}
 
def setThermostatHoldMode(String value) {
    log.debug "setThermostatHoldMode({$value})"
    "$value"()
}
 
def off() {
    log.debug "off"
    sendEvent("name":"thermostatMode", "value":"off")
    zigbee.writeAttribute(0x0201, 0x1C, 0x30, 0)
}
 
def cool() {
    log.debug "cool"
    sendEvent("name":"thermostatMode", "value":"cool")
    zigbee.writeAttribute(0x0201, 0x1C, 0x30, 3)
}
 
def heat() {
    log.debug "heat"
    sendEvent("name":"thermostatMode", "value":"heat")
    zigbee.writeAttribute(0x0201, 0x1C, 0x30, 4)
}
 
def emergencyHeat() {
    log.debug "emergencyHeat"
    sendEvent("name":"thermostatMode", "value":"emergencyHeat")
    zigbee.writeAttribute(0x0201, 0x1C, 0x30, 5)
}
 
def on() {
    log.debug "on"
    fanOn()
}
 
def fanOn() {
    log.debug "fanOn"
    sendEvent("name":"thermostatFanMode", "value":"fanOn")
    zigbee.writeAttribute(0x0202, 0x00, 0x30, 4)
}
 
def auto() {
    log.debug "auto"
    // thermostat doesn't support auto
}
 
def fanAuto() {
    log.debug "fanAuto"
    sendEvent("name":"thermostatFanMode", "value":"auto")
    zigbee.writeAttribute(0x0202, 0x00, 0x30, 5)
}
 
def holdOn() {
    log.debug "holdOn"
    sendEvent("name":"thermostatHoldMode", "value":"holdOn")
    zigbee.writeAttribute(0x0201, 0x23, 0x30, 1)
}
 
def holdOff() {
    log.debug "Set Hold Off for thermostat"
    sendEvent("name":"thermostatHoldMode", "value":"holdOff")
    zigbee.writeAttribute(0x0201, 0x23, 0x30, 0)
}
 
// Commment out below if no C-wire since it will kill the batteries.
def poll() {
//            log.debug "Executing 'poll'"
//            refresh()
}
 
private getBatteryLevel(rawValue) {
    def intValue = Integer.parseInt(rawValue,16)
    def min = 2.1
    def max = 3.0
    def vBatt = intValue / 10
    return ((vBatt - min) / (max - min) * 100) as int
}
 
private cvtTemp(value) {
    new BigInteger(Math.round(value))
}

private isHoldOn() {
    if (device.currentState("thermostatHoldMode")?.value == "holdOn") return true
    return false
}
 
def setHeatingSetpoint(degrees) {
    log.debug "setHeatingSetpoint to $degrees"
    
    if (isHoldOn()) return
 
    if (degrees != null) {
        def temperatureScale = getTemperatureScale()
        def degreesInteger = Math.round(degrees)
        def maxTemp
        def minTemp
 
        if (temperatureScale == "C") {
            maxTemp = 44 
            minTemp = 7 
            log.debug "Location is in Celsius, maxTemp: $maxTemp, minTemp: $minTemp"
        } else {
            maxTemp = 86 
            minTemp = 30 
            log.debug "Location is in Farenheit, maxTemp: $maxTemp, minTemp: $minTemp"
        }
 
        if (degreesInteger > maxTemp) degreesInteger = maxTemp
        if (degreesInteger < minTemp) degreesInteger = minTemp
 
        log.debug "setHeatingSetpoint degrees $degreesInteger $temperatureScale"
        def celsius = (temperatureScale == "C") ? degreesInteger : (fahrenheitToCelsius(degreesInteger) as Double).round(2)
        zigbee.writeAttribute(0x0201, 0x12, 0x29, cvtTemp(celsius * 100))
    }
}
 
def setCoolingSetpoint(degrees) {
    log.debug "setCoolingSetpoint to $degrees"
    
    if (isHoldOn()) return
 
    if (degrees != null) {
        def temperatureScale = getTemperatureScale()
        def degreesInteger = Math.round(degrees)
        def maxTemp
        def minTemp
 
        if (temperatureScale == "C") {
            maxTemp = 44 
            minTemp = 7 
            log.debug "Location is in Celsius, maxTemp: $maxTemp, minTemp: $minTemp"
        } else {
            maxTemp = 86 
            minTemp = 30 
            log.debug "Location is in Farenheit, maxTemp: $maxTemp, minTemp: $minTemp"
        }
 
        if (degreesInteger > maxTemp) degreesInteger = maxTemp
        if (degreesInteger < minTemp) degreesInteger = minTemp
 
        log.debug "setCoolingSetpoint degrees $degreesInteger $temperatureScale"
        def celsius = (temperatureScale == "C") ? degreesInteger : (fahrenheitToCelsius(degreesInteger) as Double).round(2)
        zigbee.writeAttribute(0x0201, 0x11, 0x29, cvtTemp(celsius * 100))
    }
}
