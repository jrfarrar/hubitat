metadata {
    // Automatically generated. Make future change here.
    definition (name: "Z-Wave thermostat with RH", author: "minollo@minollo.com", namespace: "minollo") {
        capability "Actuator"
        capability "Temperature Measurement"
        capability "Relative Humidity Measurement"
        capability "Thermostat"
        capability "Configuration"
        capability "Polling"
        capability "Refresh"
        capability "Sensor"
        capability "Battery"
       
        attribute "thermostatFanState", "string"
        attribute "responsive", "string"
 
        command "tempDown"
        command "tempUp"
        command "switchMode"
        command "switchFanMode"
        command "quickSetCool"
        command "quickSetHeat"
 
        fingerprint deviceId: "0x08"
        fingerprint inClusters: "0x43,0x40,0x44,0x31"
    }
    preferences {
        input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: false
        input name: "txtEnable", type: "bool", title: "Enable descriptionText logging", defaultValue: true
    }
}
 
def pollDevice() {
    //log.debug "pollDevice(); ignoring it"
	poll()
}
 
def tempUp() {
    def mode = device.currentValue("thermostatMode")
    if (mode == "heat") {
        log.trace "Heating setpoint UP"
        def value = device.currentValue("heatingSetpoint") + 1
        sendEvent(name: "heatingSetpoint", value: value)
        sendEvent(name: "thermostatSetpoint", value: value)
        setHeatingSetpoint(value)
    } else if (mode == "cool") {
        log.trace "Cooling setpoint UP"
        def value = device.currentValue("coolingSetpoint") + 1
        sendEvent(name: "coolingSetpoint", value: value)
        sendEvent(name: "thermostatSetpoint", value: value)
        setCoolingSetpoint(value)
    }
}
 
def tempDown() {
    def mode = device.currentValue("thermostatMode")
    if (mode == "heat") {
        log.trace "Heating setpoint DOWN"
        def value = device.currentValue("heatingSetpoint") - 1
        sendEvent(name: "heatingSetpoint", value: value)
        sendEvent(name: "thermostatSetpoint", value: value)
        setHeatingSetpoint(value)
    } else if (mode == "cool") {
        log.trace "Cooling setpoint DOWN"
        def value = device.currentValue("coolingSetpoint") - 1
        sendEvent(name: "coolingSetpoint", value: value)
        sendEvent(name: "thermostatSetpoint", value: value)
        setCoolingSetpoint(value)
    }
}
 
 
def parse(String description)
{
    def map = createEvent(zwaveEvent(zwave.parse(description, [0x42:1, 0x43:2, 0x31:3, 0x80:1])))
    if (!map) {
        return null
    }
    updateResponsiveness()
    def result = [map]
    if (map.name in ["heatingSetpoint","coolingSetpoint","thermostatMode"]) {
        def map2 = [
            name: "thermostatSetpoint",
            unit: getTemperatureScale()
        ]
        if (map.name == "thermostatMode") {
            state.lastTriedMode = map.value
            if (map.value == "cool") {
                map2.value = device.latestValue("coolingSetpoint")
                //log.info "THERMOSTAT, latest cooling setpoint = ${map2.value}"
            }
            else {
                map2.value = device.latestValue("heatingSetpoint")
                //log.info "THERMOSTAT, latest heating setpoint = ${map2.value}"
            }
        }
        else {
            def mode = device.latestValue("thermostatMode")
            //log.info "THERMOSTAT, latest mode = ${mode}"
            if ((map.name == "heatingSetpoint" && (mode == "heat" || mode == "emergencyHeat")) || (map.name == "coolingSetpoint" && mode == "cool")) {
                map2.value = map.value
                map2.unit = map.unit
            }
        }
        if (map2.value != null) {
            //log.debug "THERMOSTAT, adding setpoint event: $map"
            result << createEvent(map2)
        }
    } else if (map.name == "thermostatFanMode") {
        state.lastTriedFanMode = map.value
    }
    if (logEnable) log.debug "ParseEx returned $result"
    result
}
 
// Event Generation
 
// MultiChannelCmdEncap and MultiChannelCmdEncap are ways that devices can indicate that a message
// is coming from one of multiple subdevices or "endpoints" that would otherwise be indistinguishable
def zwaveEvent(hubitat.zwave.commands.multichannelv3.MultiChannelCmdEncap cmd) {
    def encapsulatedCommand = cmd.encapsulatedCommand([0x30: 3, 0x31: 3]) // can specify command class versions here like in zwave.parse
    if (encapsulatedCommand) {
        return zwaveEvent(encapsulatedCommand)
    }
}
 
def zwaveEvent(hubitat.zwave.commands.batteryv1.BatteryReport cmd) {
    if (cmd.batteryLevel <= 100) {
        def nowTime = new Date().time
        state.lastBatteryGet = nowTime
        def map = [ name: "battery", unit: "%" ]
        if (cmd.batteryLevel == 0xFF || cmd.batteryLevel == 0) {
            map.value = 1
            map.descriptionText = "$device.displayName battery is low!"
        } else {
            map.value = cmd.batteryLevel
        }
        map
    } else {
        log.warn "Bad battery value returned: ${cmd.batteryLevel}"
        [:]
    }
}
 
def zwaveEvent(hubitat.zwave.commands.thermostatsetpointv2.ThermostatSetpointReport cmd)
{
    def map = [:]
    map.value = cmd.scaledValue.toString()
    map.unit = cmd.scale == 1 ? "F" : "C"
    map.displayed = false
    switch (cmd.setpointType) {
        case 1:
            map.name = "heatingSetpoint"
            break;
        case 2:
            map.name = "coolingSetpoint"
            break;
        default:
            return [:]
    }
    // So we can respond with same format
    state.size = cmd.size
    state.scale = cmd.scale
    state.precision = cmd.precision
    map
}
 
def zwaveEvent(hubitat.zwave.commands.sensormultilevelv3.SensorMultilevelReport cmd)
{
    //  log.debug "SensorMultilevelReport ${cmd}"
    def map = [:]
    switch (cmd.sensorType) {
        case 1:
            // temperature
            map.value = cmd.scaledSensorValue.toString()
            map.unit = cmd.scale == 1 ? "F" : "C"
            map.name = "temperature"
            break;
        case 5:
            // humidity
            map.value = cmd.scaledSensorValue.toInteger().toString()
            map.unit = "%"
            map.name = "humidity"
            break;
    }
    map
}
 
def zwaveEvent(hubitat.zwave.commands.thermostatoperatingstatev1.ThermostatOperatingStateReport cmd)
{
    def map = [:]
    switch (cmd.operatingState) {
        case hubitat.zwave.commands.thermostatoperatingstatev1.ThermostatOperatingStateReport.OPERATING_STATE_IDLE:
            map.value = "idle"
            break
        case hubitat.zwave.commands.thermostatoperatingstatev1.ThermostatOperatingStateReport.OPERATING_STATE_HEATING:
            map.value = "heating"
            break
        case hubitat.zwave.commands.thermostatoperatingstatev1.ThermostatOperatingStateReport.OPERATING_STATE_COOLING:
            map.value = "cooling"
            break
        case hubitat.zwave.commands.thermostatoperatingstatev1.ThermostatOperatingStateReport.OPERATING_STATE_FAN_ONLY:
            map.value = "fan only"
            break
        case hubitat.zwave.commands.thermostatoperatingstatev1.ThermostatOperatingStateReport.OPERATING_STATE_PENDING_HEAT:
            map.value = "pending heat"
            break
        case hubitat.zwave.commands.thermostatoperatingstatev1.ThermostatOperatingStateReport.OPERATING_STATE_PENDING_COOL:
            map.value = "pending cool"
            break
        case hubitat.zwave.commands.thermostatoperatingstatev1.ThermostatOperatingStateReport.OPERATING_STATE_VENT_ECONOMIZER:
            map.value = "vent economizer"
            break
    }
    if (map.value != null)
        map.name = "thermostatOperatingState"
    else
        log.warn "Bad ThermostatOperatingStateReport command: ${cmd}"
    map
}
 
def zwaveEvent(hubitat.zwave.commands.thermostatfanstatev1.ThermostatFanStateReport cmd) {
    def map = [name: "thermostatFanState", unit: ""]
    switch (cmd.fanOperatingState) {
        case 0:
            map.value = "idle"
            break
        case 1:
            map.value = "running"
            break
        case 2:
            map.value = "running high"
            break
    }
    map
}
 
def zwaveEvent(hubitat.zwave.commands.thermostatmodev2.ThermostatModeReport cmd) {
    def nowTime = new Date().time
    state.lastMoreInfoGet = nowTime
    def map = [:]
    switch (cmd.mode) {
        case hubitat.zwave.commands.thermostatmodev2.ThermostatModeReport.MODE_OFF:
            map.value = "off"
            break
        case hubitat.zwave.commands.thermostatmodev2.ThermostatModeReport.MODE_HEAT:
            map.value = "heat"
            break
        case hubitat.zwave.commands.thermostatmodev2.ThermostatModeReport.MODE_AUXILIARY_HEAT:
            map.value = "emergencyHeat"
            break
        case hubitat.zwave.commands.thermostatmodev2.ThermostatModeReport.MODE_COOL:
            map.value = "cool"
            break
        case hubitat.zwave.commands.thermostatmodev2.ThermostatModeReport.MODE_AUTO:
            map.value = "auto"
            break
    }
    map.name = "thermostatMode"
    map
}
 
def zwaveEvent(hubitat.zwave.commands.thermostatfanmodev3.ThermostatFanModeReport cmd) {
    def map = [:]
    switch (cmd.fanMode) {
        case hubitat.zwave.commands.thermostatfanmodev3.ThermostatFanModeReport.FAN_MODE_AUTO_LOW:
            map.value = "fanAuto"
            break
        case hubitat.zwave.commands.thermostatfanmodev3.ThermostatFanModeReport.FAN_MODE_LOW:
            map.value = "fanOn"
            break
        case hubitat.zwave.commands.thermostatfanmodev3.ThermostatFanModeReport.FAN_MODE_CIRCULATION:
            map.value = "fanCirculate"
            break
    }
    map.name = "thermostatFanMode"
    map.displayed = false
    map
}
 
def zwaveEvent(hubitat.zwave.commands.thermostatmodev2.ThermostatModeSupportedReport cmd) {
    if (logEnable) log.debug "Processing ThermostatModeSupportedReport ${cmd}"
    def supportedModes = ""
    if(cmd.off) { supportedModes += "off " }
    if(cmd.heat) { supportedModes += "heat " }
    if(cmd.auxiliaryemergencyHeat) { supportedModes += "emergencyHeat " }
    if(cmd.cool) { supportedModes += "cool " }
    if(cmd.auto) { supportedModes += "auto " }
 
    updateState("supportedModes", supportedModes)
}
 
def zwaveEvent(hubitat.zwave.commands.thermostatfanmodev3.ThermostatFanModeSupportedReport cmd) {
    if (logEnable) log.debug "Processing FanModeSupportedReport ${cmd}"
    def supportedFanModes = ""
    if(cmd.auto) { supportedFanModes += "fanAuto " }
    if(cmd.low) { supportedFanModes += "fanOn " }
    if(cmd.circulation) { supportedFanModes += "fanCirculate " }
 
    updateState("supportedFanModes", supportedFanModes)
}
 
def updateState(String name, String value) {
    state[name] = value
    device.updateDataValue(name, value)
}
 
def zwaveEvent(hubitat.zwave.commands.basicv1.BasicReport cmd) {
    if (logEnable) log.debug "Zwave event received: $cmd"
}
 
def zwaveEvent(hubitat.zwave.Command cmd) {
    log.warn "Unexpected zwave command $cmd"
}
 
// Command Implementations
def refresh() {
    poll()
}
 
def poll() {
    checkResponsiveness()
    def commands = [
        zwave.sensorMultilevelV3.sensorMultilevelGet().format(), // current temperature
        zwave.thermostatOperatingStateV1.thermostatOperatingStateGet().format(),
        zwave.multiChannelV3.multiChannelCmdEncap(destinationEndPoint: 2).encapsulate(zwave.sensorMultilevelV3.sensorMultilevelGet()).format(), //current RH
        zwave.thermostatSetpointV1.thermostatSetpointGet(setpointType: 1).format(),
        zwave.thermostatSetpointV1.thermostatSetpointGet(setpointType: 2).format()
    ]
    def batteryCmd = getBattery()
    if (batteryCmd != null) commands.add(batteryCmd)
    def moreInfoCmds = getMoreInfo()
    if (moreInfoCmds != null) commands.addAll(moreInfoCmds)
    def setClockCmd = setClock()
    if (setClockCmd != null) commands.add(setClockCmd)
    delayBetween(commands, getStandardDelay())
}
 
private getMoreInfo() { //once every 1 hour
    def nowTime = new Date().time
    def ageInMinutes = state.lastMoreInfoGet ? (nowTime - state.lastMoreInfoGet)/60000 : 60
    def batteryValue = device.currentValue("battery")
    if (logEnable) log.debug "More info report age: ${ageInMinutes} minutes, battery charge is ${batteryValue}%"
    if (batteryValue == 100 || ageInMinutes >= 60) {
        if (logEnable) log.debug "Fetching fresh more info values"
        [zwave.thermostatModeV2.thermostatModeGet().format(),
         zwave.thermostatFanModeV3.thermostatFanModeGet().format()
        ]
    } else null
}
 
private getBattery() {  //once every 10 hours
    def nowTime = new Date().time
    def ageInMinutes = state.lastBatteryGet ? (nowTime - state.lastBatteryGet)/60000 : 600
    if (logEnable) log.debug "Battery report age: ${ageInMinutes} minutes"
    if (ageInMinutes >= 600) {
        if (logEnable) log.debug "Fetching fresh battery value"
         zwave.batteryV1.batteryGet().format()
    } else null
}
 
private setClock() {    //once a day
 
    def nowTime = new Date().time
    def ageInMinutes = state.lastClockSet ? (nowTime - state.lastClockSet)/60000 : 1440
    if (logEnable) log.debug "Clock set age: ${ageInMinutes} minutes"
    if (ageInMinutes >= 1440) {
        state.lastClockSet = nowTime
        def nowCal = Calendar.getInstance(location.timeZone);
        def clockSetCmd = zwave.clockV1.clockSet(hour: nowCal.get(Calendar.HOUR_OF_DAY), minute: nowCal.get(Calendar.MINUTE), weekday: nowCal.get(Calendar.DAY_OF_WEEK)).format()
        if (txtEnable) log.info "Setting clock: ${clockSetCmd}"
        clockSetCmd
    } else null
}
 
def setHeatingSetpoint(degreesF) {
    setHeatingSetpoint(degreesF.toDouble())
}
 
def setHeatingSetpoint(Double degreesF) {
    if (txtEnable) log.info "setHeatingSetpoint(${degreesF})"
    def p = (state.precision == null) ? 1 : state.precision
    delayBetween([
        zwave.thermostatSetpointV1.thermostatSetpointSet(setpointType: 1, scale: 1, precision: p, scaledValue: degreesF).format(),
        zwave.thermostatSetpointV1.thermostatSetpointGet(setpointType: 1).format(),
        "delay 2400"
    ])
}
 
def setCoolingSetpoint(degreesF) {
    setCoolingSetpoint(degreesF.toDouble())
}
 
def setCoolingSetpoint(Double degreesF) {
    if (txtEnable) log.info "setCoolingSetpoint(${degreesF})"
    def p = (state.precision == null) ? 1 : state.precision
    delayBetween([
        "delay 9600",
        zwave.thermostatSetpointV1.thermostatSetpointSet(setpointType: 2, scale: 1, precision: p,  scaledValue: degreesF).format(),
        zwave.thermostatSetpointV1.thermostatSetpointGet(setpointType: 2).format(),
        "delay 2400"
    ])
}
 
def configure() {
    if (txtEnable) log.info "Configuring..."
    delayBetween([
        zwave.thermostatModeV2.thermostatModeSupportedGet().format(),
        zwave.thermostatFanModeV3.thermostatFanModeSupportedGet().format(),
        zwave.associationV1.associationSet(groupingIdentifier:1, nodeId:[zwaveHubNodeId]).format()
    ], 2300)
}
 
def modes() {
    ["off", "heat", "cool", "auto", "emergencyHeat"]
}
 
def switchMode() {
    def currentMode = device.currentState("thermostatMode")?.value
    def lastTriedMode = state.lastTriedMode ?: currentMode ?: "off"
    def supportedModes = getDataByName("supportedModes")
    def modeOrder = modes()
    def next = { modeOrder[modeOrder.indexOf(it) + 1] ?: modeOrder[0] }
    def nextMode = next(lastTriedMode)
    if (supportedModes?.contains(currentMode)) {
        while (!supportedModes.contains(nextMode) && nextMode != "off") {
            nextMode = next(nextMode)
        }
    }
    state.lastTriedMode = nextMode
    delayBetween([
        zwave.thermostatModeV2.thermostatModeSet(mode: modeMap[nextMode]).format(),
        zwave.thermostatModeV2.thermostatModeGet().format()
    ], 1000)
}
 
def switchToMode(nextMode) {
    def supportedModes = getDataByName("supportedModes")
    if(supportedModes && !supportedModes.contains(nextMode)) log.warn "thermostat mode '$nextMode' is not supported"
    if (nextMode in modes()) {
        state.lastTriedMode = nextMode
        "$nextMode"()
    } else {
        if (logEnable) log.debug("no mode method '$nextMode'")
    }
}
 
def switchFanMode() {
    def currentMode = device.currentState("thermostatFanMode")?.value
    def lastTriedMode = state.lastTriedFanMode ?: currentMode ?: "off"
    def supportedModes = getDataByName("supportedFanModes") ?: "fanAuto fanOn"
    def modeOrder = ["fanAuto", "fanCirculate", "fanOn"]
    def next = { modeOrder[modeOrder.indexOf(it) + 1] ?: modeOrder[0] }
    def nextMode = next(lastTriedMode)
    while (!supportedModes?.contains(nextMode) && nextMode != "fanAuto") {
        nextMode = next(nextMode)
    }
    switchToFanMode(nextMode)
}
 
def switchToFanMode(nextMode) {
    def supportedFanModes = getDataByName("supportedFanModes")
    if(supportedFanModes && !supportedFanModes.contains(nextMode)) log.warn "thermostat mode '$nextMode' is not supported"
 
    def returnCommand
    if (nextMode == "fanAuto") {
        returnCommand = fanAuto()
    } else if (nextMode == "fanOn") {
        returnCommand = fanOn()
    } else if (nextMode == "fanCirculate") {
        returnCommand = fanCirculate()
    } else {
        if (logEnable) log.debug("no fan mode '$nextMode'")
    }
    if(returnCommand) state.lastTriedFanMode = nextMode
    returnCommand
}
 
def getDataByName(String name) {
    state[name] ?: device.getDataValue(name)
}
 
def getModeMap() { [
    "off": 0,
    "heat": 1,
    "cool": 2,
    "auto": 3,
    "emergencyHeat": 4
]}
 
def setThermostatMode(String value) {
    delayBetween([
        zwave.thermostatModeV2.thermostatModeSet(mode: modeMap[value]).format(),
        zwave.thermostatModeV2.thermostatModeGet().format()
    ], standardDelay)
}
 
def getFanModeMap() { [
    "auto": 0,
    "on": 1,
    "circulate": 6
]}
 
def setThermostatFanMode(String value) {
    delayBetween([
        zwave.thermostatFanModeV3.thermostatFanModeSet(fanMode: fanModeMap[value]).format(),
        zwave.thermostatFanModeV3.thermostatFanModeGet().format()
    ], standardDelay)
}
 
def off() {
    delayBetween([
        zwave.thermostatModeV2.thermostatModeSet(mode: 0).format(),
        zwave.thermostatModeV2.thermostatModeGet().format(),
        "delay 2400"
    ])
}
 
def heat() {
    delayBetween([
        zwave.thermostatModeV2.thermostatModeSet(mode: 1).format(),
        zwave.thermostatModeV2.thermostatModeGet().format(),
        "delay 2400"
    ])
}
 
def emergencyHeat() {
    delayBetween([
        zwave.thermostatModeV2.thermostatModeSet(mode: 4).format(),
        zwave.thermostatModeV2.thermostatModeGet().format(),
        "delay 2400"
    ])
}
 
def cool() {
    delayBetween([
        zwave.thermostatModeV2.thermostatModeSet(mode: 2).format(),
        zwave.thermostatModeV2.thermostatModeGet().format(),
        "delay 2400"
    ])
}
 
def auto() {
    delayBetween([
        zwave.thermostatModeV2.thermostatModeSet(mode: 3).format(),
        zwave.thermostatModeV2.thermostatModeGet().format(),
        "delay 2400"
    ])
}
 
def fanOn() {
    delayBetween([
        zwave.thermostatFanModeV3.thermostatFanModeSet(fanMode: 1).format(),
        zwave.thermostatFanModeV3.thermostatFanModeGet().format(),
        "delay 2400"
    ])
}
 
def fanAuto() {
    delayBetween([
        zwave.thermostatFanModeV3.thermostatFanModeSet(fanMode: 0).format(),
        zwave.thermostatFanModeV3.thermostatFanModeGet().format(),
        "delay 2400"
    ])
}
 
def fanCirculate() {
    delayBetween([
        zwave.thermostatFanModeV3.thermostatFanModeSet(fanMode: 6).format(),
        zwave.thermostatFanModeV3.thermostatFanModeGet().format(),
        "delay 2400"
    ])
}
 
private updateResponsiveness() {
    def nowTime = new Date().time
    state.lastResponse = nowTime
    if (device.currentValue("responsive") == null || device.currentValue("responsive") == "false") {
        if (txtEnable) log.info "Updating responsive attribute to true"
        sendEvent(name: "responsive", value: "true")
    }
    null
}
 
private checkResponsiveness() {
    def nowTime = new Date().time
    if (state.lastResponse) {
        def lastResponseAge = (nowTime - state.lastResponse) / 60000
        if (logEnable) log.debug "Last response from device was received ${lastResponseAge} minutes ago"
        if (lastResponseAge >= 1440) {  //if no response was received in the last 24 hourse, set responsive to false
            if (device.currentValue("responsive") == "true") {
                log.info "Updating responsive attribute to false"
                sendEvent(name: "responsive", value: "false")
            }
        }
    }
    null
}
 
private getStandardDelay() {
    1800
}
