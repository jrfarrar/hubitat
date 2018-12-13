/**
 *  GE Motion Switch 26931 - FOR HUBITAT
 *
 *	Author: Matt lebaugh (@mlebaugh)
 *
 *     Copyright (C) Matt LeBaugh
 *
 *  Version 1.1.0 12/4/18 - bug fixes and refresh update
 *  Version 1.1.0 11/11/18 - Modified for Hubitat - jrf
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
	definition (name: "GE Motion Switch 26931", namespace: "jrfarrar", author: "jrfarrar") {
	capability "Motion Sensor"
    capability "Actuator"
 	capability "Switch"
	capability "Polling"
	capability "Refresh"
	capability "Sensor"
	capability "Health Check"
	capability "Light"
	capability "PushableButton"
        command "Occupancy"
        command "Vacancy"
        command "Manual"
        command "LightSenseOn"
        command "LightSenseOff"
        command "TimeOut1"
        command "TimeOut5"
        command "TimeOut15"
        command "TimeOut30"
        command "MotionSenseHigh"
        command "MotionSenseMedium"
        command "MotionSenseLow"
        command "MotionSenseEnable"
        command "MotionSenseDisable"

        attribute "operatingMode", "enum", ["Manual", "Vacancy", "Occupancy"]

		fingerprint mfr:"0063", prod:"494D", model: "3032", deviceJoinName: "GE Z-Wave Plus Motion Wall Switch"
	}


	preferences {
        	input title: "", description: "Select your prefrences here, they will be sent to the device once updated.\n\nTo verify the current settings of the device, they will be shown in the 'recently' page once any setting is updated", displayDuringSetup: false, type: "paragraph", element: "paragraph"
			input (
                name: "operationmode",
                title: "Operating Mode",
                description: "Occupancy: Automatically turn on and off the light with motion\nVacancy: Manually turn on, automatically turn off light with no motion.",
                type: "enum",
                options: [
                    "1" : "Manual",
                    "2" : "Vacancy (auto-off)",
                    "3" : "Occupancy (auto-on/off)",
                ],
                required: false
            )
            input (
                name: "timeoutduration",
                title: "Timeout Duration",
                description: "Length of time after no motion for the light to shut off in Occupancy/Vacancy modes",
                type: "enum",
                options: [
                    "0" : "Test (5s)",
                    "1" : "1 minute",
                    "5" : "5 minutes (default)",
                    "15" : "15 minutes",
                    "30" : "30 minutes",
                    "255" : "disabled"
                ],
                required: false
            )
			input (
                name: "motionsensitivity",
                title: "Motion Sensitivity",
                description: "Motion Sensitivity",
                type: "enum",
                options: [
                    "1" : "High",
                    "2" : "Medium (default)",
                    "3" : "Low"
                ],
                required: false
            )
			input (
                name: "lightsense",
                title: "Light Sensing",
                description: "If enabled, Occupancy mode will only turn light on if it is dark",
                type: "enum",
                options: [
                    "0" : "Disabled",
                    "1" : "Enabled",
                ],
                required: false
            )
			
			input (
                name: "motion",
                title: "Motion Sensor",
                description: "Enable/Disable Motion Sensor.",
                type: "enum",
                options: [
                    "0" : "Disable",
                    "1" : "Enable",
                ],
                required: false
            )
//            input (
//                name: "invertSwitch",
//                title: "Switch Orientation",
//                type: "enum",
//                options: [
//                    "0" : "Normal",
//                    "1" : "Inverted",
//                ],
//                required: false
//            )
            input (
                name: "resetcycle",
                title: "Reset Cycle",
                type: "enum",
                options: [
                    "0" : "Disabled",
                    "1" : "10 sec",
                    "2" : "20 sec (default)",
                    "3" : "30 sec",
                    "4" : "45 sec",
                    "110" : "27 mins",
                ],
                required: false
            )
            input (
            type: "paragraph",
            element: "paragraph",
            title: "Configure Association Groups:",
            description: "Devices in association group 2 will receive Basic Set commands directly from the switch when it is turned on or off. Use this to control another device as if it was connected to this switch.\n\n" +
                         "Devices in association group 3 will receive Basic Set commands directly from the switch when it is double tapped up or down.\n\n" +
                         "Devices are entered as a comma delimited list of IDs in hexadecimal format."
        	)

        	input (
            	name: "requestedGroup2",
            	title: "Association Group 2 Members (Max of 5):",
            	type: "text",
            	required: false
        	)

        	input (
            	name: "requestedGroup3",
            	title: "Association Group 3 Members (Max of 4):",
            	type: "text",
            	required: false
        	)
           
          input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: false
          input name: "txtEnable", type: "bool", title: "Enable descriptionText logging", defaultValue: true
    }
}

def parse(String description) {
    def result = null
	if (description != "updated") {
		if (logEnable) log.debug "parse() >> zwave.parse($description)"
		def cmd = zwave.parse(description, [0x20: 1, 0x25: 1, 0x56: 1, 0x70: 2, 0x72: 2, 0x85: 2, 0x71: 3])
		if (cmd) {
			if (logEnable) log.debug("'$description' parsed to $result")
			if (txtEnable) log.info("${device.displayName} ${result}")
			result = zwaveEvent(cmd)
        }
	}
    if (!result) { log.warn "Parse returned ${result} for $description" }
    else {if (logEnable) log.debug "Parse returned ${result}"}
	return result
	if (result?.name == 'hail' && hubFirmwareLessThan("000.011.00602")) {
		result = [result, response(zwave.basicV1.basicGet())]
		if (logEnable) log.debug "Was hailed: requesting state update"
	}
	return result
}

def zwaveEvent(hubitat.zwave.commands.crc16encapv1.Crc16Encap cmd) {
	if (logEnable) log.debug("zwaveEvent(): CRC-16 Encapsulation Command received: ${cmd}")
	def encapsulatedCommand = zwave.commandClass(cmd.commandClass)?.command(cmd.command)?.parse(cmd.data)
	if (!encapsulatedCommand) {
		if (logEnable) log.debug("zwaveEvent(): Could not extract command from ${cmd}")
	} else {
		return zwaveEvent(encapsulatedCommand)
	}
}

def zwaveEvent(hubitat.zwave.commands.basicv1.BasicReport cmd) {
	def sstate = cmd.value ? "on" : "off"
	//if (txtEnable) log.info "Switch is: ${sstate} physical"
	
	[name: "switch", value: cmd.value ? "on" : "off", type: "physical"]
}

def zwaveEvent(hubitat.zwave.commands.basicv1.BasicSet cmd) {
    if (logEnable) log.debug "---BASIC SET V1--- ${device.displayName} sent ${cmd}"
	def result = []
    result << createEvent([name: "switch", value: cmd.value ? "on" : "off", type: "physical"])
    if (cmd.value == 255) {
    	result << createEvent([name: "button", value: "pushed", data: [buttonNumber: "1"], descriptionText: "On/Up on (button 1) $device.displayName was pushed", isStateChange: true, type: "physical"])
    }
	else if (cmd.value == 0) {
    	result << createEvent([name: "button", value: "pushed", data: [buttonNumber: "2"], descriptionText: "Off/Down (button 2) on $device.displayName was pushed", isStateChange: true, type: "physical"])
    }
    return result
}

def zwaveEvent(hubitat.zwave.commands.associationv2.AssociationReport cmd) {
	if (logEnable) log.debug "---ASSOCIATION REPORT V2--- ${device.displayName} sent groupingIdentifier: ${cmd.groupingIdentifier} maxNodesSupported: ${cmd.maxNodesSupported} nodeId: ${cmd.nodeId} reportsToFollow: ${cmd.reportsToFollow}"
    state.group3 = "1,2"
    if (cmd.groupingIdentifier == 3) {
    	if (cmd.nodeId.contains(zwaveHubNodeId)) {
        	sendEvent(name: "numberOfButtons", value: 2, displayed: false)
        }
        else {
        	sendEvent(name: "numberOfButtons", value: 0, displayed: false)
			sendHubCommand(new hubitat.device.HubAction(zwave.associationV2.associationSet(groupingIdentifier: 3, nodeId: zwaveHubNodeId).format()))
			sendHubCommand(new hubitat.device.HubAction(zwave.associationV2.associationGet(groupingIdentifier: 3).format()))
        }
    }
}

def zwaveEvent(hubitat.zwave.commands.configurationv1.ConfigurationReport cmd) {
	if (logEnable) log.debug "---CONFIGURATION REPORT V1--- ${device.displayName} sent ${cmd}"
    def config = cmd.scaledConfigurationValue
    def result = []
    if (cmd.parameterNumber == 1) {
		def value = config == 0 ? "Test 5s" : config == 1 ? "1 minute" : config == 5 ? "5 minute" : config == 15 ? "15 minute" : config == 30 ? "30 minute" : "255 minute"
    	result << createEvent([name:"TimeoutDuration", value: value, displayed:true, isStateChange:true])
    } else if (cmd.parameterNumber == 13) {
		def value = config == 1 ? "High" : config == 2 ? "Medium" : "Low"
    	result << createEvent([name:"MotionSensitivity", value: value, displayed:true, isStateChange:true])
	} else if (cmd.parameterNumber == 14) {
		def value = config == 1 ? "Enabled" : "Disabled"
    	result << createEvent([name:"LightSense", value: value, displayed:true, isStateChange:true])
    } else if (cmd.parameterNumber == 15) {
    	def value = config == 0 ? "Disabled" : config == 1 ? "10 sec" : config == 2 ? "20 sec" : config == 3 ? "30 sec" : config == 4 ? "45 sec" : "27 minute"
    	result << createEvent([name:"ResetCycle", value: value, displayed:true, isStateChange:true])
    } else if (cmd.parameterNumber == 3) {
    	if (config == 1 ) {
        	result << createEvent([name:"operatingMode", value: "Manual", displayed:true, isStateChange:true])
         } else if (config == 2 ) {
        	result << createEvent([name:"operatingMode", value: "Vacancy", displayed:true, isStateChange:true])
        } else if (config == 3 ) {
        	result << createEvent([name:"operatingMode", value: "Occupancy", displayed:true, isStateChange:true])
        }
    } else if (cmd.parameterNumber == 6) {
    	def value = config == 0 ? "Disabled" : "Enabled"
    	result << createEvent([name:"MotionSensor", value: value, displayed:true, isStateChange:true])
    } else if (cmd.parameterNumber == 5) {
    	def value = config == 1 ? "Inverted" : "Normal"
    	result << createEvent([name:"SwitchOrientation", value: value, displayed:true, isStateChange:true])
    }
   return result
}

def zwaveEvent(hubitat.zwave.commands.switchbinaryv1.SwitchBinaryReport cmd) {
	def sstate = cmd.value ? "on" : "off"
    if (logEnable) log.debug "---BINARY SWITCH REPORT V1--- ${device.displayName} sent ${cmd}"
    //if (txtEnable) log.info "Switch is: ${sstate} physical"
    createEvent([name: "switch", value: cmd.value ? "on" : "off", type: "digital"])
}

def zwaveEvent(hubitat.zwave.commands.manufacturerspecificv2.ManufacturerSpecificReport cmd) {
    if (logEnable) log.debug "---MANUFACTURER SPECIFIC REPORT V2--- ${device.displayName} sent ${cmd}"
	if (logEnable) log.debug "manufacturerId:   ${cmd.manufacturerId}"
	if (logEnable) log.debug "manufacturerName: ${cmd.manufacturerName}"
    state.manufacturer=cmd.manufacturerName
	if (logEnable) log.debug "productId:        ${cmd.productId}"
	if (logEnable) log.debug "productTypeId:    ${cmd.productTypeId}"
	def msr = String.format("%04X-%04X-%04X", cmd.manufacturerId, cmd.productTypeId, cmd.productId)
	updateDataValue("MSR", msr)
    sendEvent([descriptionText: "$device.displayName MSR: $msr", isStateChange: false])
}

def zwaveEvent(hubitat.zwave.commands.versionv1.VersionReport cmd) {
	def fw = "${cmd.applicationVersion}.${cmd.applicationSubVersion}"
	updateDataValue("fw", fw)
	if (logEnable) log.debug "---VERSION REPORT V1--- ${device.displayName} is running firmware version: $fw, Z-Wave version: ${cmd.zWaveProtocolVersion}.${cmd.zWaveProtocolSubVersion}"
}

def zwaveEvent(hubitat.zwave.commands.hailv1.Hail cmd) {
	[name: "hail", value: "hail", descriptionText: "Switch button was pressed", displayed: false]
}

def zwaveEvent(hubitat.zwave.commands.notificationv3.NotificationReport cmd) {
	//if (logEnable) log.debug "---NOTIFICATION REPORT V3--- ${device.displayName} sent ${cmd}"
	def result = []
	if (cmd.notificationType == 0x07) {
		if ((cmd.event == 0x00)) {
			result << createEvent(name: "motion", value: "inactive", descriptionText: "$device.displayName motion has stopped")
            //if (txtEnable) log.info "Motion Inactive"
		} else if (cmd.event == 0x08) {
			result << createEvent(name: "motion", value: "active", descriptionText: "$device.displayName detected motion")
            //if (txtEnable) log.info "Motion Active"
		}
	}
	result
}

def zwaveEvent(hubitat.zwave.Command cmd) {
    log.warn "${device.displayName} received unhandled command: ${cmd}"
}

def on() {
	//sendEvent(name: "switch", value: "on")
	delayBetween([
		zwave.basicV1.basicSet(value: 0xFF).format(),
		zwave.switchBinaryV1.switchBinaryGet().format()
	],100)
}

def off() {
	//sendEvent(name: "switch", value: "off")
	delayBetween([
		zwave.basicV1.basicSet(value: 0x00).format(),
		zwave.switchBinaryV1.switchBinaryGet().format()
	],100)
}

def poll() {
	delayBetween([
		zwave.switchBinaryV1.switchBinaryGet().format(),
		zwave.manufacturerSpecificV1.manufacturerSpecificGet().format()
	],100)
}

/**
  * PING is used by Device-Watch in attempt to reach the Device
**/
def ping() {
		refresh()
}

def refresh() {
	log.info "refresh() is called"
    def cmds = []
	return delayBetween([
	 secureCmd(zwave.configurationV1.configurationGet(parameterNumber: 1)),
	 secureCmd(zwave.configurationV1.configurationGet(parameterNumber: 3)),
	 secureCmd(zwave.configurationV1.configurationGet(parameterNumber: 5)),
	 secureCmd(zwave.configurationV1.configurationGet(parameterNumber: 6)),
	 secureCmd(zwave.configurationV1.configurationGet(parameterNumber: 13)),
	 secureCmd(zwave.configurationV1.configurationGet(parameterNumber: 14)),
	 secureCmd(zwave.configurationV1.configurationGet(parameterNumber: 15)),
	 secureCmd(zwave.switchBinaryV1.switchBinaryGet()),
     secureCmd(zwave.switchMultilevelV1.switchMultilevelGet()),
	 secureCmd(zwave.notificationV3.notificationGet(notificationType: 7)),
	 secureCmd(zwave.switchMultilevelV3.switchMultilevelGet())
    ],1000)
}

def SetModeNumber(value) {
	if (logEnable) log.debug("Setting mode by number: ${value}")
	return delayBetween([
		secureCmd(zwave.configurationV1.configurationSet(scaledConfigurationValue: value , parameterNumber: 3, size: 1)),
  		secureCmd(zwave.configurationV1.configurationGet(parameterNumber: 3))
	],500)
}

def Occupancy() {
    if (logEnable) log.debug("Occupancy")
	return delayBetween([
	    secureCmd(zwave.configurationV1.configurationSet(scaledConfigurationValue: 3, parameterNumber: 3, size: 1)),
	    secureCmd(zwave.configurationV1.configurationGet(parameterNumber: 3))
	],500)
}

def Vacancy() {
    if (logEnable) log.debug("Vacancy")
	return delayBetween([
	    secureCmd(zwave.configurationV1.configurationSet(scaledConfigurationValue: 2, parameterNumber: 3, size: 1)),
	    secureCmd(zwave.configurationV1.configurationGet(parameterNumber: 3))
	],500)
}

def Manual() {
    if (logEnable) log.debug("Manual")
	return delayBetween([
	    secureCmd(zwave.configurationV1.configurationSet(scaledConfigurationValue: 1, parameterNumber: 3, size: 1)),
	    secureCmd(zwave.configurationV1.configurationGet(parameterNumber: 3))
	],500)
}

def LightSenseOn() {
	if (logEnable) log.debug("Setting Light Sense On")
	return delayBetween([
	    secureCmd(zwave.configurationV1.configurationSet(scaledConfigurationValue: 1, parameterNumber: 14, size: 1)),
	    secureCmd(zwave.configurationV1.configurationGet(parameterNumber: 14))
	],500)
}

def LightSenseOff() {
	if (logEnable) log.debug("Setting Light Sense Off")
	return delayBetween([
	    secureCmd(zwave.configurationV1.configurationSet(scaledConfigurationValue: 0, parameterNumber: 14, size: 1)),
	    secureCmd(zwave.configurationV1.configurationGet(parameterNumber: 14))
	],500)
}

//TimeOut1
def TimeOut1() {
	if (logEnable) log.debug("Setting Light Timeout Delay 1 min")
	return delayBetween([
	    secureCmd(zwave.configurationV1.configurationSet(scaledConfigurationValue: 1, parameterNumber: 1, size: 1)),
	    secureCmd(zwave.configurationV1.configurationGet(parameterNumber: 1))
	],500)
}
def TimeOut5() {
	if (logEnable) log.debug("Setting Light Timeout Delay 5 min")
	return delayBetween([
	    secureCmd(zwave.configurationV1.configurationSet(scaledConfigurationValue: 5, parameterNumber: 1, size: 1)),
	    secureCmd(zwave.configurationV1.configurationGet(parameterNumber: 1))
	],500)
}
def TimeOut15() {
	if (logEnable) log.debug("Setting Light Timeout Delay 15 min")
	return delayBetween([
	    secureCmd(zwave.configurationV1.configurationSet(scaledConfigurationValue: 15, parameterNumber: 1, size: 1)),
	    secureCmd(zwave.configurationV1.configurationGet(parameterNumber: 1))
	],500)
}
def TimeOut30() {
	if (logEnable) log.debug("Setting Light Timeout Delay 30 min")
	return delayBetween([
	    secureCmd(zwave.configurationV1.configurationSet(scaledConfigurationValue: 30, parameterNumber: 1, size: 1)),
	    secureCmd(zwave.configurationV1.configurationGet(parameterNumber: 1))
	],500)
}
//MotionSenseHigh
def MotionSenseHigh() {
	if (logEnable) log.debug("Setting Motion Sensitivity High")
	return delayBetween([
	    secureCmd(zwave.configurationV1.configurationSet(scaledConfigurationValue: 1, parameterNumber: 13, size: 1)),
	    secureCmd(zwave.configurationV1.configurationGet(parameterNumber: 13))
	],500)
}
def MotionSenseMedium() {
	if (logEnable) log.debug("Setting Motion Sensitivity Medium")
	return delayBetween([
	    secureCmd(zwave.configurationV1.configurationSet(scaledConfigurationValue: 2, parameterNumber: 13, size: 1)),
	    secureCmd(zwave.configurationV1.configurationGet(parameterNumber: 13))
	],500)
}
def MotionSenseLow() {
	if (logEnable) log.debug("Setting Motion Sensitivity Low")
	return delayBetween([
	    secureCmd(zwave.configurationV1.configurationSet(scaledConfigurationValue: 3, parameterNumber: 13, size: 1)),
	    secureCmd(zwave.configurationV1.configurationGet(parameterNumber: 13))
	],500)
}
//MotionSenseEnable
def MotionSenseEnable() {
	if (logEnable) log.debug("Setting Motion Sensor Enabled")
	return delayBetween([
	    secureCmd(zwave.configurationV1.configurationSet(scaledConfigurationValue: 1, parameterNumber: 6, size: 1)),
	    secureCmd(zwave.configurationV1.configurationGet(parameterNumber: 6))
	],500)
}
def MotionSenseDisable() {
	if (logEnable) log.debug("Setting Motion Sensor Disabled")
	return delayBetween([
	    secureCmd(zwave.configurationV1.configurationSet(scaledConfigurationValue: 0, parameterNumber: 6, size: 1)),
	    secureCmd(zwave.configurationV1.configurationGet(parameterNumber: 6))
	],500)
}

def installed() {
	// Device-Watch simply pings if no device events received for 32min(checkInterval)
	//sendEvent(name: "checkInterval", value: 2 * 15 * 60 + 2 * 60, displayed: false, data: [protocol: "zwave", hubHardwareId: //device.hub.hardwareID])
}

def updated() {
	//sendEvent(name: "checkInterval", value: 2 * 15 * 60 + 2 * 60, displayed: false, data: [protocol: "zwave", hubHardwareId: device.hub.hardwareID])
    if (state.lastUpdated && now() <= state.lastUpdated + 3000) return
    state.lastUpdated = now()

	def cmds = []
	//switch and dimmer settings
        if (settings.timeoutduration) {cmds << zwave.configurationV1.configurationSet(configurationValue: [settings.timeoutduration.toInteger()], parameterNumber: 1, size: 1)}
        cmds << secureCmd(zwave.configurationV1.configurationGet(parameterNumber: 1))
        if (settings.motionsensitivity) {cmds << zwave.configurationV1.configurationSet(configurationValue: [settings.motionsensitivity.toInteger()], parameterNumber: 13, size: 1)}
        cmds << secureCmd(zwave.configurationV1.configurationGet(parameterNumber: 13))
        if (settings.lightsense) {cmds << zwave.configurationV1.configurationSet(configurationValue: [settings.lightsense.toInteger()], parameterNumber: 14, size: 1)}
        cmds << secureCmd(zwave.configurationV1.configurationGet(parameterNumber: 14))
        if (settings.resetcycle) {cmds << zwave.configurationV1.configurationSet(configurationValue: [settings.resetcycle.toInteger()], parameterNumber: 15, size: 1)}
        cmds << secureCmd(zwave.configurationV1.configurationGet(parameterNumber: 15))
        if (settings.operationmode) {cmds << zwave.configurationV1.configurationSet(configurationValue: [settings.operationmode.toInteger()], parameterNumber: 3, size: 1)}
        cmds << secureCmd(zwave.configurationV1.configurationGet(parameterNumber: 3))
        if (settings.motion) {cmds << zwave.configurationV1.configurationSet(configurationValue: [settings.motion.toInteger()], parameterNumber: 6, size: 1)}
        cmds << secureCmd(zwave.configurationV1.configurationGet(parameterNumber: 6))
        if (settings.invertSwitch) {cmds << zwave.configurationV1.configurationSet(scaledConfigurationValue: invertSwitch, parameterNumber: 5, size: 1)}
        cmds << secureCmd(zwave.configurationV1.configurationGet(parameterNumber: 5))
		
        // Make sure lifeline is associated - was missing on a dimmer:
		cmds << secureCmd(zwave.associationV1.associationSet(groupingIdentifier:0, nodeId:zwaveHubNodeId))
        cmds << secureCmd(zwave.associationV1.associationSet(groupingIdentifier:1, nodeId:zwaveHubNodeId))
		cmds << secureCmd(zwave.associationV1.associationSet(groupingIdentifier:2, nodeId:zwaveHubNodeId))
		cmds << secureCmd(zwave.associationV1.associationSet(groupingIdentifier:3, nodeId:zwaveHubNodeId))
        
        //association groups
		def nodes = []
		if (settings.requestedGroup2 != state.currentGroup2) {
    	    nodes = parseAssocGroupList(settings.requestedGroup2, 2)
        	cmds << secureCmd(zwave.associationV2.associationRemove(groupingIdentifier: 2, nodeId: []))
        	cmds << secureCmd(zwave.associationV2.associationSet(groupingIdentifier: 2, nodeId: nodes))
        	cmds << secureCmd(zwave.associationV2.associationGet(groupingIdentifier: 2))
        	state.currentGroup2 = settings.requestedGroup2
    	}

    	if (settings.requestedGroup3 != state.currentGroup3) {
        	nodes = parseAssocGroupList(settings.requestedGroup3, 3)
        	cmds << secureCmd(zwave.associationV2.associationRemove(groupingIdentifier: 3, nodeId: []))
        	cmds << secureCmd(zwave.associationV2.associationSet(groupingIdentifier: 3, nodeId: nodes))
        	cmds << secureCmd(zwave.associationV2.associationGet(groupingIdentifier: 3))
        	state.currentGroup3 = settings.requestedGroup3
    	}
        
        //sendHubCommand(cmds.collect{ new hubitat.device.HubAction(it.format()) }, 500)
        return cmds
}

def Up() {
	sendEvent(name: "button", value: "pushed", data: [buttonNumber: "1"], descriptionText: "On/Up (button 1) on $device.displayName was pushed", isStateChange: true, type: "digital")
    on()
}

def Down() {
	sendEvent(name: "button", value: "pushed", data: [buttonNumber: "2"], descriptionText: "Off/Down (button 2) on $device.displayName was pushed", isStateChange: true, type: "digital")
    off()
}

def configure() {
        def cmds = []
		// Make sure lifeline is associated - was missing on a dimmer:
		cmds << zwave.associationV1.associationSet(groupingIdentifier:0, nodeId:zwaveHubNodeId)
        cmds << zwave.associationV1.associationSet(groupingIdentifier:1, nodeId:zwaveHubNodeId)
		cmds << zwave.associationV1.associationSet(groupingIdentifier:2, nodeId:zwaveHubNodeId)
		cmds << zwave.associationV1.associationSet(groupingIdentifier:3, nodeId:zwaveHubNodeId)
        //sendHubCommand(cmds.collect{ new hubitat.device.HubAction(it.format()) }, 1000)
        return delayBetween([cmds],500)
}

private secureCmd(cmd) {
    if (getDataValue("zwaveSecurePairingComplete") == "true") {
	return zwave.securityV1.securityMessageEncapsulation().encapsulate(cmd).format()
    } else {
	return cmd.format()
    }
}

private parseAssocGroupList(list, group) {
    def nodes = group == 2 ? [] : [zwaveHubNodeId]
    if (list) {
        def nodeList = list.split(',')
        def max = group == 2 ? 5 : 4
        def count = 0

        nodeList.each { node ->
            node = node.trim()
            if ( count >= max) {
                log.warn "Association Group ${group}: Number of members is greater than ${max}! The following member was discarded: ${node}"
            }
            else if (node.matches("\\p{XDigit}+")) {
                def nodeId = Integer.parseInt(node,16)
                if (nodeId == zwaveHubNodeId) {
                	log.warn "Association Group ${group}: Adding the hub as an association is not allowed (it would break double-tap)."
                }
                else if ( (nodeId > 0) & (nodeId < 256) ) {
                    nodes << nodeId
                    count++
                }
                else {
                    log.warn "Association Group ${group}: Invalid member: ${node}"
                }
            }
            else {
                log.warn "Association Group ${group}: Invalid member: ${node}"
            }
        }
    }
    
    return nodes
}
