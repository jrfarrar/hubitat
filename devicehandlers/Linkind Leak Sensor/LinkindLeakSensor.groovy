/*
 * Linkind Leak Sensor
 *
 * To pair this device, press and hold the button for 5+ seconds.  
 * While holding, the red light will blink indicating its ready to pair.
 *
 * Pressing the button appears to do nothing.
 * 
 * Details:
 * Leak sensor only.  No temperature or humidity. 
 * Uses 2 AAA sized batteries
 * Has a 90db built in siren when a alarm condition is detected
 * Long poll checkins are set to every 1200 qtr-seconds (5 minutes).
 * It will flash the LED with Identify (cluster 0x0003) commads 
 * Water events:
 *   Registers as an IAS device.    Water events are sent as an IAS zone status update for alarm 1.
 *
 * Battery updates:
 *    The device allows for setting a report frequency for both battery voltage and battery percentage.  By default
 *    the reporting is disabled (no unsolicited battery reports).
 *
 * Update history:
 * 12/01/2021 - V0.9   - Initial version
 * 12/07/2021 - JRF added option to change amount of time for battery reporting
 * 12/16/2021 - JRF fixed debug/info log, added update to run configure for parameters if changed
 *
 * Get updates from:
 * 
 *
 */

// For examples:
// https://github.com/hubitat/HubitatPublic/tree/master/examples/drivers

import hubitat.zigbee.clusters.iaszone.ZoneStatus
import com.hubitat.zigbee.DataType

metadata {
        definition(name: "Linkind Leak Sensor", namespace: "csstup", author: "coreystup@gmail.com") {
                capability "Battery"
                capability "Water Sensor"
                capability "Sensor"
                capability "Configuration"
        
                attribute "batteryVoltage", "String"

                fingerprint endpointId: "01", profileId:"0104", inClusters: "0000,0001,0003,0020,0500,0B05", outClusters: "0019", model: "A001082", manufacturer: "LK", deviceJoinName: "Linkind Leak Sensor" 
                
        }
 
	    preferences {
            input name: "batteryReportTime", type: "number", title: "Number of seconds to report battery", defaultValue: 3600, required: true
            input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: true
            input name: "txtEnable", type: "bool", title: "Enable descriptionText logging", defaultValue: true
	    }
}

// Build a list of maps with parsed attributes
private List<Map> collectAttributes(Map descMap) {
    List<Map> descMaps = new ArrayList<Map>()

    descMaps.add(descMap)

    if (descMap.additionalAttrs) {
        descMaps.addAll(descMap.additionalAttrs)
    }

    return  descMaps
}


def getATTRIBUTE_IAS_ZONE_STATUS() { 0x0002 }  

def getBATTERY_VOLTAGE_ATTR() { 0x0020 }
def getBATTERY_PERCENT_ATTR() { 0x0021 }

def parse(String description) {
        logDebug "parse() description: $description"
       
        // Determine current time and date in the user-selected date format and clock style
        def now = formatDate()    

        def result = []  // Create an empty result list
        def eventList = []  // A list of events, to be processed by createEvent
    
        if (description?.startsWith('zone status')) {
            eventList += parseIasMessage(description)
        } else {
            // parseDescriptionAsMap() can return additional attributes into the additionalAttrs map member
            Map descMap = zigbee.parseDescriptionAsMap(description)
            logDebug "parse() descMap: ${descMap}"

            if (descMap?.clusterInt == 0x0000 && descMap.value) {  // Basic cluster responses
                switch (descMap?.attrInt) {
                    case 0x0006:
                        updateDataValue("datecode",descMap.value ?: "unknown")
                        break
                    case 0x4000:
                        updateDataValue("softwareBuild",descMap.value ?: "unknown")
                        break
                }
            }
                
                if (descMap?.clusterInt == zigbee.POWER_CONFIGURATION_CLUSTER && descMap.commandInt != 0x07 && descMap.value) {
                        // Multiple attributes are encoded in descMap, pull them apart
                        List<Map> descMaps = collectAttributes(descMap)
            
                        def battMap = descMaps.find { it.attrInt == BATTERY_VOLTAGE_ATTR }  // Attribute 0x0020
                        if (battMap) {
                                eventList += getBatteryResult(Integer.parseInt(battMap.value, 16))
                        }
            
                        battMap = descMaps.find { it.attrInt == BATTERY_PERCENT_ATTR }  // Attribute 0x0021
                        if (battMap) {
                                eventList += getBatteryPercentageResult(Integer.parseInt(battMap.value, 16))
                        }

                        } else if (descMap?.clusterInt == zigbee.IAS_ZONE_CLUSTER && descMap.attrInt == ATTRIBUTE_IAS_ZONE_STATUS && descMap?.value) {
                                eventList += translateZoneStatus(new ZoneStatus(Integer.parseInt(descMap?.value,16)))
                        }
                }

            // For each item in the event list, create an actual event and append it to the result list.
            eventList.each {
                result += createEvent(it)
        }

        if (description?.startsWith('enroll request')) {
                logInfo "Sending IAS enroll response..."
                def enrollResponseCmds = zigbee.enrollResponse() + zigbee.readAttribute(0x0500, 0x0000)
                logDebug "enroll response: ${enrollResponseCmds}"
                return enrollResponseCmds
        }
    
        logDebug "parse() returning ${result}"
        return result
}

private Map parseIasMessage(String description) {
        ZoneStatus zs = zigbee.parseZoneStatus(description)
        translateZoneStatus(zs)
}

private Map translateZoneStatus(ZoneStatus zs) {
        return zs.isAlarm1Set() ? getMoistureResult('wet') : getMoistureResult('dry')
}

private Map getBatteryResult(rawValue) {
        // Passed as units of 100mv (27 = 2700mv = 2.7V)
        def rawVolts = String.format("%2.1f", rawValue / 10.0)
        
        def descText = "${device.displayName} battery level is ${rawVolts}V"
        logInfo(descText)
    
        def result = [:]
        result.name          = "batteryVoltage"
        result.value         = "${rawVolts}"
        result.unit          = 'V'
        result.displayed     = true
        result.isStateChange = true
        result.descriptionText = descText

        return result
}

private Map getBatteryPercentageResult(rawValue) {
        def result = [:]

        if (0 <= rawValue && rawValue <= 200) {
                def percentage = Math.round(rawValue / 2)
                
                def descText = "${device.displayName} battery is ${percentage}%"
                logInfo(descText)
               
                result.name = 'battery'
                result.translatable = true
                result.value = percentage
                result.unit  = '%'
                result.isStateChange = true
                result.descriptionText = descText
        }

        return result
}

private Map getMoistureResult(value) {
        def descriptionText
        if (value == "wet")
                descriptionText = "${device.displayName} is wet"
        else
                descriptionText = "${device.displayName} is dry"
        logInfo(descriptionText)
        return [
                        name           : 'water',
                        value          : value,
                        descriptionText: descriptionText,
                        translatable   : true
        ]
}

// installed() runs just after a sensor is paired

def getRefreshCmds() {
    def refreshCmds = []

    refreshCmds += zigbee.readAttribute(zigbee.POWER_CONFIGURATION_CLUSTER, BATTERY_PERCENT_ATTR)
    refreshCmds += zigbee.readAttribute(zigbee.POWER_CONFIGURATION_CLUSTER, BATTERY_VOLTAGE_ATTR)

    refreshCmds += 
        zigbee.readAttribute(zigbee.IAS_ZONE_CLUSTER, ATTRIBUTE_IAS_ZONE_STATUS) +  // zigbee.ATTRIBUTE_IAS_ZONE_STATUS
        zigbee.enrollResponse()

    return refreshCmds
}

void updated() {
    logInfo "updated..."
    //write the configuration
    configure()
}

// configure() runs after installed() when a sensor is paired or reconnected
def configure() {
    logInfo "configure..."

    def cmds = []
    
    // Query some of the basic values
    cmds += zigbee.readAttribute(0x0000, 0x0006) // date code
    cmds += zigbee.readAttribute(0x0000, 0x4000) // SWbuild
     
    int BatRT = batteryReportTime.toInteger()
    // By default, there is no scheduled battery report.   Configure one.
    cmds += zigbee.configureReporting(0x0001, 0x0020, DataType.UINT8, BatRT, BatRT, 1, [:], 200)   // Configure Battery Voltage - Report once per Xhrs or if a change of 1% detected
    cmds += zigbee.configureReporting(0x0001, 0x0021, DataType.UINT8, BatRT, BatRT, 1, [:], 200)   // Configure Battery Percentage - Report once per Xhrs or if a change of 1% detected
    
    return cmds + getRefreshCmds() // send refresh cmds as part of config
}

def formatDate(batteryReset) {
        def correctedTimezone = ""
        def timeString = clockformat ? "HH:mm:ss" : "h:mm:ss aa"

        // If user's hub timezone is not set, display error messages in log and events log, and set timezone to GMT to avoid errors
        if (!(location.timeZone)) {
                correctedTimezone = TimeZone.getTimeZone("GMT")
                log.error "${device.displayName}: Time Zone not set, so GMT was used. Please set up your location in the SmartThings mobile app."
                sendEvent(name: "error", value: "", descriptionText: "ERROR: Time Zone not set, so GMT was used. Please set up your location in the SmartThings mobile app.")
        } else {
                correctedTimezone = location.timeZone
        }
        
        if (dateformat == "US" || dateformat == "" || dateformat == null) {
                if (batteryReset)
                        return new Date().format("MMM dd yyyy", correctedTimezone)
                else
                        return new Date().format("EEE MMM dd yyyy ${timeString}", correctedTimezone)
        } else if (dateformat == "UK") {
                if (batteryReset)
                        return new Date().format("dd MMM yyyy", correctedTimezone)
                else
                        return new Date().format("EEE dd MMM yyyy ${timeString}", correctedTimezone)
        } else {
                if (batteryReset)
                        return new Date().format("yyyy MMM dd", correctedTimezone)
                else
                        return new Date().format("EEE yyyy MMM dd ${timeString}", correctedTimezone)
    }
}

private def logDebug(message) {
    if (logEnable) {
        log.debug "${message}"
    }
}

private def logInfo(message) {
    if (txtEnable) {
		log.info "${message}"
    }
}
