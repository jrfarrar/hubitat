/**
 *  HomeSeer HS-FLS100+
 *
 *  Copyright 2018 HomeSeer
 *
 *  
 *
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
 *	Author: HomeSeer
 *	Date: 7/24/2018
 *
 *	Changelog:
 *
 *	1.2     Modified for Hubitat and Lux to 0 - jrf
 *	1.0	Initial Version
 *
 *
 *
 */
 
metadata {
	definition (name: "FLS100+ Motion Sensor(outdoor)(homeseer)", namespace: "homeseer", author: "support@homeseer.com") {
		capability "Switch"
		capability "Motion Sensor"
		capability "Sensor"
		capability "Polling"
		capability "Refresh"
        capability "Configuration"
        capability "Illuminance Measurement"
        
		command "manual"
        command "auto"
        
		attribute "operatingMode", "enum", ["Manual", "Auto"]
        attribute "defaultLevel", "number"        
        
        fingerprint mfr: "000C", prod: "0201", model: "000B"
}

//	simulator {
//		status "on":  "command: 2003, payload: FF"
//		status "off": "command: 2003, payload: 00"		
//
//		// reply messages
//		reply "2001FF,delay 5000,2602": "command: 2603, payload: FF"
//		reply "200100,delay 5000,2602": "command: 2603, payload: 00"		
//	}

    preferences {            
       input ( "onTime", "number", title: "Press Configuration button after changing preferences\n\n   On Time: Duration (8-720 seconds) [default: 15]", defaultValue: 15,range: "8..720", required: false)
       input ( "luxDisableValue", "number", title: "Lux Value to Disable Sensor: (0-200 lux)(0 Disables lux from motion) [default: 50]", defaultValue: 50, range: "0..200", required: false)       
       input ( "luxReportInterval", "number", title: "Lux Report Interval: (0-1440 minutes) [default 10]", defaultValue: 10, range: "0..1440", required: false)
       input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: true
       input name: "txtEnable", type: "bool", title: "Enable descriptionText logging", defaultValue: true
    }

}

def parse(String description) {
	def result = null
    if (logEnable) log.debug (description)
    if (description != "updated") {
	    def cmd = zwave.parse(description, [0x20: 1, 0x26: 1, 0x70: 1])	
        if (cmd) {
		    result = zwaveEvent(cmd)
	    }
    }
    if (!result){
        if (logEnable) log.debug "Parse returned ${result} for command ${cmd}"
    }
    else {
		if (logEnable) log.debug "Parse returned ${result}"
    }   
	return result
}


// Creates motion events.
def zwaveEvent(hubitat.zwave.commands.notificationv3.NotificationReport cmd) {
	if (logEnable) log.debug "NotificationReport: ${cmd}"
	
	if (cmd.notificationType == 0x07) {
		switch (cmd.event) {
			case 0:
				if (txtEnable) log.info "NO MOTION"	
                createEvent(name:"motion", value: "inactive", isStateChange: true)
				break
			case 8:
				if (txtEnable) log.info "MOTION"
				createEvent(name:"motion", value: "active", isStateChange: true)
				break
			default:
				if (txtEnable) logDebug "Sensor is ${cmd.event}"
		}
	}	
}

def zwaveEvent(hubitat.zwave.commands.sensormultilevelv5.SensorMultilevelReport cmd) {
	if (logEnable) log.debug("sensor multilevel report")
    if (logEnable) log.debug "cmd:  ${cmd}"
    
    def lval = cmd.scaledSensorValue
    
    createEvent(name:"illuminance", value: lval)
}

def zwaveEvent(hubitat.zwave.commands.manufacturerspecificv2.ManufacturerSpecificReport cmd) {
	if (logEnable) log.debug "manufacturerId:   ${cmd.manufacturerId}"
	if (logEnable) log.debug "manufacturerName: ${cmd.manufacturerName}"
    state.manufacturer=cmd.manufacturerName
	if (logEnable) log.debug "productId:        ${cmd.productId}"
	if (logEnable) log.debug "productTypeId:    ${cmd.productTypeId}"
	def msr = String.format("%04X-%04X-%04X", cmd.manufacturerId, cmd.productTypeId, cmd.productId)
	updateDataValue("MSR", msr)	
    setFirmwareVersion()
    createEvent([descriptionText: "$device.displayName MSR: $msr", isStateChange: false])
}

def zwaveEvent(hubitat.zwave.commands.versionv1.VersionReport cmd) {	
    //updateDataValue("applicationVersion", "${cmd.applicationVersion}")
    if (logEnable) log.debug ("received Version Report")
    if (logEnable) log.debug "applicationVersion:      ${cmd.applicationVersion}"
    if (logEnable) log.debug "applicationSubVersion:   ${cmd.applicationSubVersion}"
    state.firmwareVersion=cmd.applicationVersion+'.'+cmd.applicationSubVersion
    if (logEnable) log.debug "zWaveLibraryType:        ${cmd.zWaveLibraryType}"
    if (logEnable) log.debug "zWaveProtocolVersion:    ${cmd.zWaveProtocolVersion}"
    if (logEnable) log.debug "zWaveProtocolSubVersion: ${cmd.zWaveProtocolSubVersion}"
    setFirmwareVersion()
    createEvent([descriptionText: "Firmware V"+state.firmwareVersion, isStateChange: false])
}

def zwaveEvent(hubitat.zwave.commands.firmwareupdatemdv2.FirmwareMdReport cmd) { 
    if (logEnable) log.debug ("received Firmware Report")
    if (logEnable) log.debug "checksum:       ${cmd.checksum}"
    if (logEnable) log.debug "firmwareId:     ${cmd.firmwareId}"
    if (logEnable) log.debug "manufacturerId: ${cmd.manufacturerId}"
    [:]
}

def zwaveEvent(hubitat.zwave.commands.switchbinaryv1.SwitchBinaryReport cmd) { 
	if (logEnable) log.debug ("received switch binary Report")
    createEvent(name:"switch", value: cmd.value ? "on" : "off")
}

def zwaveEvent(hubitat.zwave.Command cmd) {
	// Handles all Z-Wave commands we aren't interested in
	[:]
}

def on() {
	if (txtEnable) log.info "On"
	delayBetween([
			zwave.basicV1.basicSet(value: 0xFF).format(),
			zwave.switchMultilevelV1.switchMultilevelGet().format()
	],5000)
}

def off() {
	if (txtEnable) log.info "Off"
	delayBetween([
			zwave.basicV1.basicSet(value: 0x00).format(),
			zwave.switchMultilevelV1.switchMultilevelGet().format()
	],5000)
}



def poll() {
/*
zwave.commands.switchbinaryv1.SwitchBinaryGet
	zwave.switchMultilevelV1.switchMultilevelGet().format()
*/
}

def refresh() {
	log.info "refresh() called"
	createEvent(name:"motion", value: "inactive", isStateChange: true)
    configure()
}

//
//THIS IS NOT WORKING PROPERLY YET
//
def zwaveEvent(hubitat.zwave.commands.associationv2.AssociationReport cmd) {
	if (logEnable) log.debug "---CONFIGURATION REPORT V1--- ${device.displayName} sent ${cmd}"
	def config = cmd.scaledConfigurationValue
	//config = zwave.configurationV1.configurationGet(parameterNumber: 2)
	def result = []
	if (cmd.parameterNumber == 1) {
		def value = config
		result << sendEvent([name:"TimeoutDuration", value: value, displayed:true])
    } else if (cmd.parameterNumber == 2) {
		if (config == 0 ) {
        	result << sendEvent([name:"operatingMode", value: "Manual", displayed:true])
		} else {
			result << sendEvent([name:"operatingMode", value: "Auto", displayed:true])
		}	
	return result
	}
}

def setFirmwareVersion() {
   def versionInfo = ''
   if (state.manufacturer)
   {
      versionInfo=state.manufacturer+' '
   }
   if (state.firmwareVersion)
   {
      versionInfo=versionInfo+"Firmware V"+state.firmwareVersion
   }
   else 
   {
     versionInfo=versionInfo+"Firmware unknown"
   }   
   sendEvent(name: "firmwareVersion",  value: versionInfo, isStateChange: true, displayed: false)
}

def configure() {
	if (logEnable) log.info "On"
	if (logEnable) log.debug ("configure() called")

//sendEvent(name: "numberOfButtons", value: 12, displayed: false)
def cmds = []
//cmds << setPrefs()
cmds = setPrefs()
cmds << zwave.manufacturerSpecificV1.manufacturerSpecificGet().format()
cmds << zwave.versionV1.versionGet().format()
if (logEnable) log.info "On"
if (logEnable) log.debug ("cmds: " + cmds)
delayBetween(cmds,500)
}

def setPrefs() 
{
   if (logEnable) log.debug ("set prefs")
   def cmds = []

	if (onTime)
	{
    	def onTime = Math.max(Math.min(onTime, 720), 8)
		cmds << zwave.configurationV1.configurationSet(parameterNumber:1, size:2, scaledConfigurationValue: onTime ).format()
	}
    if (luxDisableValue)
	{
    	def luxDisableValue = Math.max(Math.min(luxDisableValue, 200), 0)
		cmds << zwave.configurationV1.configurationSet(parameterNumber:2, size:2, scaledConfigurationValue: luxDisableValue ).format()
	}
    if (luxReportInterval)
	{
    	def luxReportInterval = Math.max(Math.min(luxReportInterval, 1440), 0)
		cmds << zwave.configurationV1.configurationSet(parameterNumber:3, size:2, scaledConfigurationValue: luxReportInterval).format()
	}
   
   
   
   //Enable the following configuration gets to verify configuration in the logs
   //cmds << zwave.configurationV1.configurationGet(parameterNumber: 7).format()
   //cmds << zwave.configurationV1.configurationGet(parameterNumber: 8).format()
   //cmds << zwave.configurationV1.configurationGet(parameterNumber: 9).format()
   //cmds << zwave.configurationV1.configurationGet(parameterNumber: 10).format()
   
   return cmds
}
 
def updated()
{
def cmds= []
cmds = setPrefs()
cmds << zwave.manufacturerSpecificV1.manufacturerSpecificGet().format()
cmds << zwave.versionV1.versionGet().format()

delayBetween(cmds, 500)

}


def manual() {
	if (logEnable) log.debug "Setting operating mode to: Manual"
	def cmds = []
	cmds << zwave.configurationV1.configurationSet(parameterNumber:2, size:2, scaledConfigurationValue: 0 ).format()
	sendEvent(name:"operatingMode", value: "manual")
	return cmds
}

def auto() {
	if (logEnable) log.debug "Setting operating mode to: Auto"
	def cmds = []
	def luxDisableValue = Math.max(Math.min(luxDisableValue, 200), 0)
	cmds << zwave.configurationV1.configurationSet(parameterNumber:2, size:2, scaledConfigurationValue: luxDisableValue ).format()
	sendEvent(name:"operatingMode", value: "auto")
	return cmds
}

