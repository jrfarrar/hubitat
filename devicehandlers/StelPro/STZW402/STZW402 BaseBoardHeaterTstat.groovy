/**
 *  Modified version of the SmartThings Z-Wave Thermostat device handler for Stelpro STZW402+
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
 */


metadata {
  definition (name: "Stelpro STZW402+", namespace: "JoshConley", author: "Josh Conley") {
    capability "Actuator"
    capability "Temperature Measurement"
    capability "Thermostat"
    capability "Configuration"
    capability "Polling"
    capability "Sensor"
    capability "Refresh"      

    command "switchMode"
    command "tempUp"
    command "tempDown"
//NEW BELOW
    fingerprint deviceId: "0x0806", inClusters: "0x5E,0x86,0x72,0x40,0x43,0x31,0x85,0x59,0x5A,0x73,0x20,0x42"
//  THis was the old
//    fingerprint deviceId: "0x08"
//    fingerprint inClusters: "0x43,0x40,0x44,0x31"
  }
  
  preferences {
        input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: true
        input name: "txtEnable", type: "bool", title: "Enable detailed state logging", defaultValue: true
  }    

}

def parse(String description)
{
  // def map = createEvent(zwaveEvent(zwave.parse(description, [0x42:1, 0x43:2, 0x31: 3])))
  def map = createEvent(zwaveEvent(zwave.parse(description, [0x40:2, 0x43:2, 0x31:3, 0x42:1, 0x20:1, 0x85: 2])))  
  if (!map) {
    return null
  }

  def result = [map]
  if (map.isStateChange && map.name in ["heatingSetpoint","thermostatMode"]) {
    def map2 = [
      name: "thermostatSetpoint",
      unit: getTemperatureScale()
    ]
    if (map.name == "thermostatMode") {
      state.lastTriedMode = map.value

      map2.value = device.latestValue("heatingSetpoint")
      log.info "THERMOSTAT, latest heating setpoint = ${map2.value}"
    }
    else {
      def mode = device.latestValue("thermostatMode")
      log.info "THERMOSTAT, latest mode = ${mode}"

      if (map.name == "heatingSetpoint") {
        map2.value = map.value
        map2.unit = map.unit
      }
    }
    if (map2.value != null) {
      if (logEnable) log.debug "THERMOSTAT, adding setpoint event: $map"
      result << createEvent(map2)
    }
  }

  if (map.isStateChange && map.name == "thermostatOperatingState") {
    map.displayed = false
  }

  if (logEnable) log.debug "Parse returned $result"
  result
}

// Event Generation
def zwaveEvent(hubitat.zwave.commands.thermostatsetpointv2.ThermostatSetpointReport cmd)
{
  def cmdScale = cmd.scale == 1 ? "F" : "C"
  def map = [:]
  map.value = convertTemperatureIfNeeded(cmd.scaledValue, cmdScale, cmd.precision)
  map.unit = getTemperatureScale()
  map.displayed = false
  switch (cmd.setpointType) {
    case 1:
      map.name = "heatingSetpoint"
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
  if (logEnable) log.debug "Multilevel report: ${cmd.sensorType}"

  def map = [:]
  if (cmd.sensorType == 1) {
    map.value = convertTemperatureIfNeeded(cmd.scaledSensorValue, cmd.scale == 1 ? "F" : "C", cmd.precision)
    map.unit = getTemperatureScale()
    map.name = "temperature"

    if (logEnable) log.debug "Temperature ${map.value}"
  }
  map
}

def zwaveEvent(hubitat.zwave.commands.thermostatoperatingstatev1.ThermostatOperatingStateReport cmd)
{
 def map = [:]
  switch (cmd.operatingState) {
    case hubitat.zwave.commands.thermostatoperatingstatev1.ThermostatOperatingStateReport.OPERATING_STATE_IDLE:
      map.value = "idle"
      if (txtEnable) log.info "idle"
      break
    case hubitat.zwave.commands.thermostatoperatingstatev1.ThermostatOperatingStateReport.OPERATING_STATE_HEATING:
      map.value = "heating"
      if (txtEnable) log.info "heating"
      break
    default:
      log.warn "Unknown operating state: ${cmd.operatingState}"
  }
  map.name = "thermostatOperatingState"
  //map.displayed = false
  map
}

def zwaveEvent(hubitat.zwave.commands.thermostatmodev2.ThermostatModeReport cmd) {
  def map = [:]
  switch (cmd.mode) {
    // Heat mode
    case hubitat.zwave.commands.thermostatmodev2.ThermostatModeReport.MODE_HEAT:
      map.value = "heat"
      break

    // ECO mode
    case '11':
      map.value = "eco"
      break
  }

  map.name = "thermostatMode"
  map
}

def zwaveEvent(hubitat.zwave.commands.basicv1.BasicReport cmd) {
  if (logEnable) log.debug "Zwave event received: $cmd"
}

def zwaveEvent(hubitat.zwave.Command cmd) {
  log.warn "Unexpected zwave command $cmd"
}

// Command Implementations
def poll() {
  delayBetween([
    zwave.sensorMultilevelV3.sensorMultilevelGet().format(), // current temperature
    zwave.thermostatSetpointV1.thermostatSetpointGet(setpointType: 1).format(), // heating setpoint
    zwave.thermostatModeV2.thermostatModeGet().format(), // heat or eco
    zwave.thermostatOperatingStateV1.thermostatOperatingStateGet().format() // idle or heating
  ], 2300)
}

def quickSetHeat(degrees) {
  setHeatingSetpoint(degrees, 1000)
}

def setHeatingSetpoint(preciseDegrees, Integer delay = 30000) {
  def degrees = new BigDecimal(preciseDegrees).setScale(1, BigDecimal.ROUND_HALF_UP)
  log.info "setHeatingSetpoint($degrees, $delay)"
  def deviceScale = state.scale ?: 1
  def deviceScaleString = deviceScale == 2 ? "C" : "F"
  def locationScale = getTemperatureScale()
  def p = (state.precision == null) ? 1 : state.precision
  def roundedDegrees = degrees

  def convertedDegrees
  if (locationScale == "C" && deviceScaleString == "F") {
    convertedDegrees = celsiusToFahrenheit(degrees)
  } else if (locationScale == "F" && deviceScaleString == "C") {
    convertedDegrees = fahrenheitToCelsius(degrees)
  } else {
    convertedDegrees = degrees
  }

  sendEvent(name: "heatingSetpoint", value: degrees, unit: locationScale)

  delayBetween([
    zwave.thermostatSetpointV1.thermostatSetpointSet(setpointType: 1, scale: deviceScale, precision: p, scaledValue: convertedDegrees).format(),
    zwave.thermostatSetpointV1.thermostatSetpointGet(setpointType: 1).format()
  ], delay)
}

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
  quickSetHeat(adjustTo)
}

def refresh() {
    //added below line just so it has a coolingsetpoint to make it compatible with Google Home-assistant
    sendEvent(name:"coolingSetpoint",value:90) 
    
    poll()
}

def configure() {
  delayBetween([
    zwave.thermostatModeV2.thermostatModeSupportedGet().format(),
    zwave.associationV1.associationSet(groupingIdentifier:1, nodeId:[zwaveHubNodeId]).format()
  ], 2300)
}

def modes() {
  ["heat", "eco"]
}

def switchMode() {
  def currentMode = device.currentState("thermostatMode")?.value
  def lastTriedMode = state.lastTriedMode ?: currentMode ?: "heat"
  def modeOrder = modes()
  def next = { modeOrder[modeOrder.indexOf(it) + 1] ?: modeOrder[0] }
  def nextMode = next(lastTriedMode)
  state.lastTriedMode = nextMode
  delayBetween([
    zwave.thermostatModeV2.thermostatModeSet(mode: modeMap[nextMode]).format(),
    zwave.thermostatModeV2.thermostatModeGet().format()
  ], 1000)
}

def switchToMode(nextMode) {
  if (nextMode in modes()) {
    state.lastTriedMode = nextMode
    "$nextMode"()
  } else {
    if (logEnable) log.debug("no mode method '$nextMode'")
  }
}

def getDataByName(String name) {
  state[name] ?: device.getDataValue(name)
}

def getModeMap() { [
  "heat": 1,
  "eco": 11
]}

def setThermostatMode(String value) {
  delayBetween([
    zwave.thermostatModeV2.thermostatModeSet(mode: modeMap[value]).format(),
    zwave.thermostatModeV2.thermostatModeGet().format()
  ], standardDelay)
}

def heat() {
  delayBetween([
    zwave.thermostatModeV2.thermostatModeSet(mode: 1).format(),
    zwave.thermostatModeV2.thermostatModeGet().format()
  ], standardDelay)
}

def eco() {
  delayBetween([
    zwave.thermostatModeV2.thermostatModeSet(mode: 11).format(),
    zwave.thermostatModeV2.thermostatModeGet().format()
  ], standardDelay)
}

def tempUp() {
  relativeAdjustment(1)
}

def tempDown() {
  relativeAdjustment(-1)
}

private getStandardDelay() {
  1000
}