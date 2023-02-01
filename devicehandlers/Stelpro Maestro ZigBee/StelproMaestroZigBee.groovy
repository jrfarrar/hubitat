/**
 *  Copyright 2020 Philippe Charette
 *  Copyright 2018 Stelpro
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 *
 *  Stelpro Maestro Thermostat Hubitat Driver
 *
 *  Notice: This file is a modified version of the SmartThings Device Hander, found in this repository:
 *           https://github.com/stelpro/maestro-thermostat
 *
 *  Author: Philippe Charette
 *  Author: Stelpro
 *
 *  Date: 2020-04-16
 *
 *  2023-02-01 - added fix for proper modes and fanmodes
 */

metadata {
    definition (name: "Stelpro Maestro ZigBee Thermostat", namespace: "PhilC", author: "PhilC") {
        capability "Actuator"
        capability "Configuration"
        capability "TemperatureMeasurement"
        capability "Thermostat"
        capability "ThermostatHeatingSetpoint"
        capability "ThermostatCoolingSetpoint"
        capability "ThermostatSetpoint"
        capability "Refresh"
        capability "RelativeHumidityMeasurement"
        
        attribute "supportedThermostatFanModes", "JSON_OBJECT"
		attribute "supportedThermostatModes", "JSON_OBJECT"
       
        command "switchMode"
        command "tempUp"
        command "tempDown"

        fingerprint profileId: "0104", endpointId: "19", inClusters: " 0000,0003,0201,0204,0405", outClusters: "0402"
    }
    
    preferences {
        input("lock", "enum", title: "Do you want to lock your thermostat's physical keypad?", options: ["No", "Yes"], defaultValue: "No", required: false, displayDuringSetup: false)
        input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: true
    }
}
import groovy.json.JsonOutput    

def parse(String description) {
    logDebug "Parse description $description"
    def descMap = zigbee.parseDescriptionAsMap(description)
    logDebug "Desc Map: $descMap"
    def map = [:]
    if (description?.startsWith("read attr -")) {
        if (descMap.cluster == "0201" && descMap.attrId == "0000")
        {
            map.name = "temperature"
            map.value = getTemperature(descMap.value)
            if (descMap.value == "7FFD") {       //0x7FFD
                map.value = "low"
            }
            else if (descMap.value == "7FFF") {  //0x7FFF
                map.value = "high"
            }
            else if (descMap.value == "8000") {  //0x8000
                map.value = "--"
            }
            
            else if (descMap.value > "8000") {
                map.value = -(Math.round(2*(655.36 - map.value))/2)
            }
                        
            sendEvent(name:"temperature", value:map.value)
        }
        else if (descMap.cluster == "0201" && descMap.attrId == "0012") {
            logDebug "HEATING SETPOINT"
            map.name = "heatingSetpoint"
            map.value = getTemperature(descMap.value)
            if (descMap.value == "8000") {        //0x8000
                map.value = getTemperature("01F4")  // 5 Celsius (minimum setpoint)
            }
            sendEvent(name:"heatingSetpoint", value:map.value)
            sendEvent(name:"thermostatSetpoint", value:map.value)
        }
        else if (descMap.cluster == "0201" && descMap.attrId == "0008") {
            logDebug "HEAT DEMAND"
            map.name = "thermostatOperatingState"
            map.value = getModeMap()[descMap.value]
            if (descMap.value < "10") {
                map.value = "idle"
            }
            else {
                map.value = "heating"
            }
            sendEvent(name:"thermostatOperatingState", value:map.value)
            sendEvent(name:"thermostatMode", value:"heat")
        }
        else if (descMap.cluster == "0405" && descMap.attrId == "0000") {
            logDebug "DEVICE HUMIDITY"
            map.name = "humidity"
            map.value = Integer.parseInt(descMap.value, 16) / 100
            sendEvent(name:"humidity", value:map.value)
        }
    }

    def result = null
    if (map) {
        result = createEvent(map)
    }
    logDebug "Parse returned $map"
    return result
}

def logsOff(){
    log.warn "debug logging disabled..."
    device.updateSetting("logEnable",[value:"false",type:"bool"])
}

def refresh() {
    logDebug "refresh"
    def cmds = []
    
    cmds += zigbee.readAttribute(0x201, 0x0000) //Read Local Temperature
    cmds += zigbee.readAttribute(0x201, 0x0008) //Read PI Heating State  
    cmds += zigbee.readAttribute(0x201, 0x0012) //Read Heat Setpoint
    cmds += zigbee.readAttribute(0x405, 0x0000) //Read Local Humidity
    
    cmds += zigbee.readAttribute(0x204, 0x0000) //Read Temperature Display Mode
    cmds += zigbee.readAttribute(0x204, 0x0001) //Read Keypad Lockout
    
    return cmds
}    

def logDebug(value){
    if (logEnable) log.debug(value)
}

def configure(){    
    log.warn "configure..."
    runIn(1800,logsOff)    
    logDebug "binding to Thermostat cluster"

    
    // Set unused default values (for Google Home Integration)
    sendEvent(name: "coolingSetpoint", value:getTemperature("0BB8")) // 0x0BB8 =  30 Celsius
    sendEvent(name: "thermostatFanMode", value:"auto")
    
    //old way 
    //sendEvent(name: "supportedThermostatModes", value: "auto, heat, off")
    //sendEvent(name: "supportedThermostatFanModes", value: "on, auto")
    
    //new way
    setSupportedThermostatFanModes(JsonOutput.toJson(["auto","on"]))
    setSupportedThermostatModes(JsonOutput.toJson(["auto", "emergency heat", "heat", "off"]))

    updateDataValue("lastRunningMode", "heat") // heat is the only compatible mode for this device
    
    def cmds = [
    //bindings
        "zdo bind 0x${device.deviceNetworkId} 1 0x019 0x201 {${device.zigbeeId}} {}", "delay 200"
        ]
    
    //reporting
    cmds += zigbee.configureReporting(0x201, 0x0000, 0x29, 10, 60, 50)   //Attribute ID 0x0000 = local temperature, Data Type: S16BIT
    cmds += zigbee.configureReporting(0x201, 0x0008, 0x20, 10, 900, 5)   //Attribute ID 0x0008 = pi heating demand, Data Type: U8BIT
    cmds += zigbee.configureReporting(0x201, 0x0012, 0x29, 1, 0, 50)     //Attribute ID 0x0012 = occupied heat setpoint, Data Type: S16BIT
    cmds += zigbee.configureReporting(0x405, 0x0000, 0x21, 10, 300, 1)   //Attribute ID 0x0000 = local humidity, Data Type: U16BIT
    
    cmds += zigbee.configureReporting(0x204, 0x0000, 0x30, 1, 0)         //Attribute ID 0x0000 = temperature display mode, Data Type: 8 bits enum
    cmds += zigbee.configureReporting(0x204, 0x0001, 0x30, 1, 0)         //Attribute ID 0x0001 = keypad lockout, Data Type: 8 bits enum
    
    return cmds + refresh()
}

def getModeMap() { [
    "00":"off",
    "04":"heat",
    "05":"eco"
]}

def getTemperature(value) {
    if (value != null) {
        logDebug("getTemperature: value $value")
        def celsius = Integer.parseInt(value, 16) / 100
        if (getTemperatureScale() == "C") {
            return celsius
        }
        else {
            return Math.round(celsiusToFahrenheit(celsius))
        }
    }
}

def getTemperatureScale() {
    return "${location.temperatureScale}"
}

def off() {
    logDebug "off"
    zigbee.writeAttribute(0x201, 0x001C, 0x30, 0)
}

def heat() {
    logDebug "heat"
    
    def cmds = []
    cmds += zigbee.writeAttribute(0x201, 0x001C, 0x30, 04, [:], 1000) // MODE    
    return cmds
}

def cool() {
    log.info "cool mode is not available for this device. => Defaulting to off instead."
    off()
}

def auto() {
    log.info "auto mode is not available for this device. => Defaulting to heat mode instead."
    heat()
}

def emergencyHeat() {
    log.info "emergencyHeat mode is not available for this device. => Defaulting to heat mode instead."
    heat()
}

def fanAuto() {
    log.info "fanAuto mode is not available for this device"
}

def fanCirculate(){
    log.info "fanCirculate mode is not available for this device"
}

def fanOn(){
    log.info "fanOn mode is not available for this device"
}

def setSchedule(JSON_OBJECT){
    log.info "setSchedule is not available for this device"
}

def setThermostatFanMode(fanmode){
    log.info "setThermostatFanMode is not available for this device"
}

def setHeatingSetpoint(preciseDegrees) {
    if (preciseDegrees != null) {
        def temperatureScale = getTemperatureScale()
        def degrees = new BigDecimal(preciseDegrees).setScale(1, BigDecimal.ROUND_HALF_UP)
        
        logDebug "setHeatingSetpoint(${degrees} ${temperatureScale})"
        
        def celsius = (temperatureScale == "C") ? degrees as Float : (fahrenheitToCelsius(degrees) as Float).round(2)
        int celsius100 = Math.round(celsius * 100)
        
        zigbee.writeAttribute(0x201, 0x0012, 0x29, celsius100) //Write Heat Setpoint      
    }
}

def setCoolingSetpoint(degrees) {
    log.info "setCoolingSetpoint is not available for this device"
}

def setThermostatMode(String value) {
    logDebug "setThermostatMode({$value})"
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
        
        case "cool":        
        default:
            return off()
    }
}


def updated() {
    parameterSetting()
}

def parameterSetting() {
    def lockmode = null
    def valid_lock = 0

    log.info "lock : $settings.lock"
    if (settings.lock == "Yes") {
        lockmode = 0x01
        valid_lock = 1
    }
    else if (settings.lock == "No") {
        lockmode = 0x00
        valid_lock = 1
    }
    
    if (valid_lock == 1)
    {
        log.info "lock valid"
        def cmds = []
        
        cmds+= zigbee.writeAttribute(0x204, 0x01, 0x30, lockmode)    //Write Lock Mode
        cmds+= refresh()
        return cmds
    }
    else {
        log.info "nothing valid"
    }
}

def setSupportedThermostatFanModes(fanModes) {
    sendEvent(name: "supportedThermostatFanModes", value: fanModes)
}

def setSupportedThermostatModes(modes) {
    sendEvent(name: "supportedThermostatModes", value: modes)
}

/*
def relativeAdjustment(Integer sign) {
  def locationScale = getTemperatureScale()
  def heatingSetpoint = device.currentValue("heatingSetpoint")
  def adjustTo = heatingSetpoint

  if (locationScale == "C") {
    adjustTo += 0.5 * sign
  }
  else {
    adjustTo += 1 * sign
  }

  log.info "Adjust heating set point to ${adjustTo}${locationScale}"
  setHeatingSetpoint(adjustTo)
}

def tempUp() {
  relativeAdjustment(1)
}

def tempDown() {
  relativeAdjustment(-1)
}
*/
