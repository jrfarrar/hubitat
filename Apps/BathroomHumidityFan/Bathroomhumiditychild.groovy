/**
*  Bathroom Humidity Fan
*
*  Turns on a fan when you start taking a shower... turns it back off when you are done.
*    -Uses humidity change rate for rapid response
*    -Timeout option when manaully controled (for stench mitigation)
*    -Child/Parent with pause/resume (Thanks to Lewis.Heidrick!)
*
*  Copyright 2018 Craig Romei
*  GNU General Public License v2 (https://www.gnu.org/licenses/gpl-2.0.txt)
*
*  CORRECTED VERSION - Issues fixed by code review
*/
import groovy.transform.Field
import hubitat.helper.RMUtils

def setVersion() {
    state.version = "1.1.46" // Race condition fix for turnOffFan state checking
    state.InternalName = "BathroomHumidityFan"   // this is the name used in the JSON file for this app
}

definition(
    name: "Bathroom Humidity Fan Child",
    namespace: "Craig.Romei",
    author: "Craig Romei",
    description: "Control a fan (switch) based on relative humidity.",
    category: "Convenience",
    parent: "Craig.Romei:Bathroom Humidity Fan",
    iconUrl: "",
    iconX2Url: "",
    iconX3Url: "",
    importUrl: "https://raw.githubusercontent.com/napalmcsr/Hubitat_Napalmcsr/master/Apps/BathroomHumidityFan/BathroomHumidityChild.src")

preferences {
    page(name: "mainPage")
    page(name: "timeIntervalInput", title: "Only during a certain time") {
		section {
			input "starting", "time", title: "Starting", required: false
			input "ending", "time", title: "Ending", required: false
       }
    }
}

def mainPage() {    
    dynamicPage(name: "", title: "", install: true, uninstall: true, refreshInterval:0) {
    ifTrace("mainPage")
    setPauseButtonName()
    setCreateSmartSwitchButtonName()
        
    section("") {
        input name: "Pause", type: "button", title: state.pauseButtonName, submitOnChange:true
        input name: "detailedInstructions", type: "bool", title: "Enable detailed instructions", defaultValue: false, submitOnChange: true
        input name: "On", type: "button", title: "On", submitOnChange:true
        input name: "Off", type: "button", title: "Off", submitOnChange:true
    }
    section("") {
        if ((state.thisName == null) || (state.thisName == "null <span style=color:white> </span>")) {state.thisName = "Enter a name for this app."}
        input name: "thisName", type: "text", title: "", required:true, submitOnChange:true, defaultValue: "Enter a name for this app."
        state.thisName = thisName
        updateLabel()
    }
	section("") {
        input "refresh", "bool", title: "Click here to refresh the device status", submitOnChange:true
        if (detailedInstructions == true) {paragraph "This is the device that is triggered when conditions are met."}
        input "fanSwitch", "capability.switch", title: "${state.fanSwitchStatus}", required: true, submitOnChange:true
        if (detailedInstructions == true) {paragraph "This humidity sensor is used to trigger any of the response methods."}
        input "humiditySensor", "capability.relativeHumidityMeasurement", title: "${state.humiditySensorStatus}  ${state.humiditySensorBatteryStatus}", required: true, submitOnChange:true
        paragraph "NOTE: The humidity sensor you select will need to report about 5 min or less."
        if (detailedInstructions == true) {paragraph "Rate of change: Triggers when the humidity sensors humidity value increases by more than the humidity increase rate."}
        if (detailedInstructions == true) {paragraph "Humidity over fixed threshold: Triggers when the humidity sensors humidity value goes over the humidity threshold."}
        if (detailedInstructions == true) {paragraph "Rate of change and humidity over comparison sensor: Triggers when the humidity is greater than the comparison humidity sensor + comparison offset trigger and the rate of change is greater than the humidity increase rate."}
        if (detailedInstructions == true) {paragraph "Humidity over comparison senor: Triggers when the humidity sensors humidity value is greater than the comparison sensors humidity value + comparison offset trigger."}
        input "humidityResponseMethod", "enum", title: "Humidity Response Method", options: humidityResponseMethodOptions, defaultValue: 1, required: true, multiple: true, submitOnChange:true
        app.updateSetting("refresh",[value:"false",type:"bool"])
    }    
    if (settings.humidityResponseMethod?.contains("3") || settings.humidityResponseMethod?.contains("4")) {
        section("") {
            if (detailedInstructions == true) {paragraph "Comparison sensor is used as a dynamic method of setting a humidity threshold. Combining multiple humidity sensors is a good way of providing a stable baseline humidity value that will adjust over the seasons. Take the comparison sensors humidity plus comparison offset trigger to get your target humidity that you want the fan to come on."}
            input "compareHumiditySensor", "capability.relativeHumidityMeasurement", title: "${state.compareHumiditySensorStatus}  ${state.compareHumiditySensorBatteryStatus}", required: true, submitOnChange:true
            if (compareHumiditySensor) {compareHumiditySensor = (compareHumiditySensor.currentValue("humidity"))}
            if (detailedInstructions == true) {paragraph "Comparison offset trigger is used to increase the comparison humidity by a fixed value. This is added to the comparison sensors humidity value to provide a threshold value to trigger the fan. How much deviation from the comparison sensor do you want to trigger the fan? This will set the comparison sensor to be the threshold plus this offset."}
            input "compareHumiditySensorOffset", "decimal", title: "Comparison Offset Trigger, Range: 0-100, Default Value:10", required: true, submitOnChange:true, defaultValue: 10
        }
    }
	section("<b><u>Fan Activation</u></b>"){
        if (detailedInstructions == true) {paragraph "Humidity increase rate: This checks the change between humidity samplings.  The sampling rate is device dependent, room size also plays a large part in how fast the humidity will increase. Typical values are around 3 to 6."}
        if (settings.humidityResponseMethod?.contains("1") || settings.humidityResponseMethod?.contains("3")) {input "humidityIncreaseRate", "decimal", title: "Humidity Increase Rate, Range: 1-20, Default value: 3", required: true, defaultValue: 3}
        if (detailedInstructions == true) {paragraph "Humidity threshold: This is the trigger point when humidity goes above or below this value."}
        if (settings.humidityResponseMethod?.contains("2")) {input "humidityThreshold", "decimal", title: "Humidity Threshold (%), Range 1-100, Default Value: 65", required: false, defaultValue: 65}
        if (detailedInstructions == true) {paragraph "Fan on delay: When a trigger tries to turn on the fan, it will wait for this delay before kicking on."}
        input "fanOnDelay", "number", title: "Delay turning fan on (Minutes), Default Value: 0", required: false, defaultValue: 0
    }
    section("<b><u>Fan Deactivation</b></u>") {
        input "humidityDropTimeout", "number", title: "How long after the humidity returns to normal should the fan turn off (minutes)? Default Value: 10", required: true, defaultValue:  10
        if (humidityDropTimeout > 0) {input "humidityDropLimit", "decimal", title: "What percentage above the starting humidity before triggering the turn off delay? Default Value: 25", required: true, defaultValue: 25} else {humidityDropLimit = 0}
        input "maxRunTime", "number", title: "Maximum time (minutes) for Fan to run when automatically turned on. Default Value: 120", required: false, defaultValue: 120
    }
    section("<b><u>Manual Activation</b></u>") {
        input "manualControlMode", "enum", title: "When should the fan turn off when turned on manually?", submitOnChange:true, required: true, options: manualControlModeOptions, defaultValue: 2
        if (detailedInstructions == true) { paragraph "When the fan is manually turned on, wait this delay before turning off."}
        if (settings.manualControlMode?.contains("2")) {input "manualOffMinutes", "number", title: "How many minutes until the fan is auto-turned-off? Default Value: 20", submitOnChange:true, required: true, defaultValue: 20}
    }
    section(title: "Additional Features:", hideable: true, hidden: hideAdditionalFeaturesSection()) {
        input "deviceActivation", "capability.switch", title: "Switches to turn on and off the fan immediately.", submitOnChange:true, required:false, multiple:true
        paragraph ""
        input name: "CreateSmartSwitch", type: "button", title: state.createSmartSwitchButtonName, submitOnChange:true, width: 5
        paragraph "Create a virtual switch to keep lights on while the fan is running to use in other apps or rules."
        paragraph "Note: You can use an existing switch. The app will turn on and off this switch in sync with the fan."
        input "smartSwitch", "capability.switch", title: "${state.smartSwitchStatus}", required: false, submitOnChange:true
    }
    section(title: "Only Run When:", hideable: true, hidden: hideOptionsSection()) {
        def timeLabel = timeIntervalLabel()
        href "timeIntervalInput", title: "Only during a certain time", description: timeLabel ?: "Tap to set", state: timeLabel ? "complete" : null
        input "days", "enum", title: "Only on certain days of the week", multiple: true, required: false, options: daysOptions
        input "modes", "mode", title: "Only when mode is", multiple: true, required: false
        input "disabledSwitch", "capability.switch", title: "Switch to Enable and Disable this app", submitOnChange:true, required:false, multiple:true
    }
    section(title: "Logging Options:", hideable: true, hidden: hideLoggingSection()) {
        if (detailedInstructions == true) {paragraph "Enable Info logging for 30 minutes will enable info logs to show up in the Hubitat logs for 30 minutes after which it will turn them off. Useful for checking if the app is performing actions as expected."}
        input name: "isInfo", type: "bool", title: "Enable Info logging for 30 minutes?", defaultValue: true
        if (detailedInstructions == true) {paragraph "Enable Debug logging for 30 minutes will enable debug logs to show up in the Hubitat logs for 30 minutes after which it will turn them off. Useful for troubleshooting problems."}
        input name: "isDebug", type: "bool", title: "Enable debug logging for 30 minutes?", defaultValue: true
        if (detailedInstructions == true) {paragraph "Enable Trace logging for 30 minutes will enable trace logs to show up in the Hubitat logs for 30 minutes after which it will turn them off. Useful for following the logic inside the application but usually not neccesary."}
        input name: "isTrace", type: "bool", title: "Enable Trace logging for 30 minutes?", defaultValue: true
        if (detailedInstructions == true) {paragraph "Logging level is used to permanantly set your logging level for the application.  This is useful if you prefer you logging set to a low level and then can use the logging toggles for specific use cases so you dont have to remember to go back in and change them later.  It's also useful if you are experiencing issues and need higher logging enabled for longer than 30 minutes."}
        input "ifLevel","enum", title: "Logging level", required: false, multiple: true, submitOnChange: false, options: logLevelOptions
        paragraph "NOTE: Logging level overrides the temporary logging selections."
    }
	}
}

// Application settings and startup
@Field static List<Map<String,String>> humidityResponseMethodOptions = [
    ["1": "Rate of change"],
    ["2": "Humidity Over fixed threshold"],
    ["3": "Rate of change and humidity over comparison sensor"],
    ["4": "Humidity Over comparison sensor"]
]

@Field static List<Map<String,String>> manualControlModeOptions = [
    ["1": "By Humidity"],
    ["2": "After Set Time"],
    ["3": "Manually"],
    ["4": "Never"]
]

// Application settings and startup
@Field static List<Map<String>> daysOptions = ["Monday","Tuesday","Wednesday","Thursday","Friday","Saturday","Sunday"]


@Field static List<Map<String,String>> logLevelOptions = [
    ["0": "None"],
    ["1": "Info"],
    ["2": "Debug"],
    ["3": "Trace"]
]

def installed() {
    ifTrace("installed")
    state.installed = true
    state.defaultHumidityThresholdValue = 65
    state.overThreshold = false
    initializeState()
    updated()
}

def updated() {
    ifDebug("Bathroom Humidity Fan Updated")
    initializeState()
    unsubscribe()
    unschedule()
    if (ifInfo) runIn(1800,infoOff)
    if (ifDebug) runIn(1800,debugOff)
    if (ifTrace) runIn(1800,traceOff)
    initialize()
    updateDeviceStatus()
}

// NEW: Centralized state initialization
private void initializeState() {
    if (state.installed == null) state.installed = false
    if (state.overThreshold == null) state.overThreshold = false
    if (state.automaticallyTurnedOn == null) state.automaticallyTurnedOn = false
    if (state.turnOffLaterStarted == null) state.turnOffLaterStarted = false
    if (state.turnOnLaterStarted == null) state.turnOnLaterStarted = false
    if (state.paused == null) state.paused = false
    if (state.disabled == null) state.disabled = false
    if (state.pausedOrDisabled == null) state.pausedOrDisabled = false
    if (state.fanOffRetries == null) state.fanOffRetries = 0
    if (state.fanOnRetries == null) state.fanOnRetries = 0
    if (state.createSmartSwitch == null) state.createSmartSwitch = false
}

// NEW: Update device status displays
private void updateDeviceStatus() {
    if (fanSwitch?.currentValue("switch") != null) {
        state.fanSwitchStatus = "[Fan: ${fanSwitch.currentValue("switch")}]"
    } else if (fanSwitch?.latestValue("switch") != null) {
        state.fanSwitchStatus = "[Fan: ${fanSwitch.latestValue("switch")}]"
    } else {
        state.fanSwitchStatus = "Fan:"
    }
    
    if (humiditySensor?.currentValue("humidity") != null) {
        state.humiditySensorStatus = "[Humidity: ${humiditySensor.currentValue("humidity")}]"
    } else if (humiditySensor?.latestValue("humidity") != null) {
        state.humiditySensorStatus = "[Humidity: ${humiditySensor.latestValue("humidity")}]"
    } else {
        state.humiditySensorStatus = "Humidity Sensor"
    }
    
    if (humiditySensor?.currentValue("battery") != null) {
        state.humiditySensorBatteryStatus = "[Battery: ${humiditySensor.currentValue("battery")}]"
    } else if (humiditySensor?.latestValue("battery") != null) {
        state.humiditySensorBatteryStatus = "[Battery: ${humiditySensor.latestValue("battery")}]"
    } else {
        state.humiditySensorBatteryStatus = " "
    }
    
    if (compareHumiditySensor?.currentValue("humidity") != null) {
        state.compareHumiditySensorStatus = "[Humidity: ${compareHumiditySensor.currentValue("humidity")}]"
    } else if (compareHumiditySensor?.latestValue("humidity") != null) {
        state.compareHumiditySensorStatus = "[Humidity: ${compareHumiditySensor.latestValue("humidity")}]"
    } else {
        state.compareHumiditySensorStatus = "Comparison Humidity Sensor"
    }
    
    if (compareHumiditySensor?.currentValue("battery") != null) {
        state.compareHumiditySensorBatteryStatus = "[Battery: ${compareHumiditySensor.currentValue("battery")}]"
    } else if (compareHumiditySensor?.latestValue("battery") != null) {
        state.compareHumiditySensorBatteryStatus = "[Battery: ${compareHumiditySensor.latestValue("battery")}]"
    } else {
        state.compareHumiditySensorBatteryStatus = " "
    }
    
    if (smartSwitch?.currentValue("switch") != null) {
        state.smartSwitchStatus = "[Smart Switch: ${smartSwitch.currentValue("switch")}]"
    } else if (smartSwitch?.latestValue("switch") != null) {
        state.smartSwitchStatus = "[Smart Switch: ${smartSwitch.latestValue("switch")}]"
    } else {
        state.smartSwitchStatus = "Smart Switch"
    }
}

// NEW: Helper function to parse humidity values safely
private Float parseHumidity(value) {
    try {
        if (value == null) return null
        String strValue = value.toString().replace("%", "").trim()
        return Float.parseFloat(strValue)
    } catch (Exception e) {
        log.error "Failed to parse humidity value: ${value}, error: ${e.message}"
        return null
    }
}

// NEW: Simplified check for whether to process humidity
private Boolean shouldProcessHumidity() {
    return getAllOk() && !state.pausedOrDisabled
}

// NEW: Wrapper functions for scheduling to avoid race conditions
private void scheduleTurnOn(Integer delaySeconds) {
    unschedule(turnOffFan)
    unschedule(turnOnFan)
    runIn(delaySeconds, turnOnFan)
    state.turnOnLaterStarted = true
    state.turnOffLaterStarted = false
}

private void scheduleTurnOff(Integer delaySeconds) {
    unschedule(turnOffFan)
    unschedule(turnOnFan)
    runIn(delaySeconds, turnOffFan)
    state.turnOffLaterStarted = true
    state.turnOnLaterStarted = false
}

def initialize() {
    ifTrace("initialize")
    ifDebug("Settings: ${settings}")
    subscribe(deviceActivation, "switch", deviceActivationHandler)
    subscribe(disabledSwitch, "switch", disabledHandler)
    subscribe(smartSwitch, "switch", smartSwitchHandler)
    subscribe(compareHumiditySensor, "humidity", compareHumidityHandler)
    subscribe(compareHumiditySensor, "battery", compareHumidityBatteryHandler)
    subscribe(fanSwitch, "switch", fanSwitchHandler)
    subscribe(humiditySensor, "humidity", humidityHandler)
    subscribe(humiditySensor, "battery", humidityBatteryHandler)
    if (getChildDevice("SmartSwitch_${app.id}")) {
        getChildDevice("SmartSwitch_${app.id}").currentValue(fanSwitch.currentValue("switch"))
    }
    setCreateSmartSwitchButtonName()
}

def modeChangeHandler() {
	ifTrace("modeChangeHandler")
    if (!getAllOk()) {
        ifInfo("modeChangeHandler: Entered a disabled mode, turning off the Fan")
	    turnOffFanSafely()
        if (smartSwitch != null) {
            smartSwitch.off()
        }
        state.status = "(Off)"
        state.automaticallyTurnedOn = false
        state.turnOffLaterStarted = false
        unschedule(turnOnFan)
        unschedule(turnOffFan)
        updateLabel()
    }
}

def disableInfoIn30Handler(evt) {
    if (isInfo) {
        runIn(1800, infoOff)
        log.info "Info logging disabling in 30 minutes."
    }
}

def disableDebugIn30Handler(evt) {
    if (isDebug) {
        runIn(1800, debugOff)
        log.debug "Debug logging disabling in 30 minutes."
    }
}

def disableTraceIn30Handler(evt) {
    if (isTrace) {
        runIn(1800, traceOff)
        log.trace "Trace logging disabling in 30 minutes."
    }
}


// Main Humidity Handler
def humidityHandler(evt) {
    // Device status
    if (evt.value) {
        state.humiditySensorStatus = "[Humidity: ${evt.value}]"
    } else if (humiditySensor?.currentValue("humidity") != null) {
        state.humiditySensorStatus = "[Humidity: ${humiditySensor.currentValue("humidity")}]"
    } else if (humiditySensor?.latestValue("humidity") != null) {
        state.humiditySensorStatus = "[Humidity: ${humiditySensor.latestValue("humidity")}]"
    } else {
        state.humiditySensorStatus = "Humidity Sensor"
    }
    
    // humidityHandler Action
	ifTrace("humidityHandler: Running Humidity Check")
	humidityHandlerVariablesBefore()
    
    if (state.currentHumidity != null) {
        state.lastHumidity = state.currentHumidity.toFloat()
        state.lastHumidityDate = state.currentHumidityDate
        // FIXED: Use parseHumidity helper
        state.currentHumidity = parseHumidity(evt.value)
    } else {
        state.currentHumidity = parseHumidity(evt.value)
    }
    
    state.currentHumidityDate = (evt.date.time)
    configureHumidityVariables()
    state.overThreshold = checkThreshold(evt)
	humidityHandlerVariablesAfter()
    
    // FIXED: Simplified condition check
	if (!shouldProcessHumidity()) {
        ifTrace("humidityHandler: Skipping - getAllOk() = ${getAllOk()} state.pausedOrDisabled = ${state.pausedOrDisabled}")
        return
    }
    
    // Humidity On Checks
    if (settings.humidityResponseMethod?.contains("1")) {rateOfChangeOn()}
    if (settings.humidityResponseMethod?.contains("2")) {overFixedThresholdOn()}
    if (settings.humidityResponseMethod?.contains("3")) {compareRateOfChangeOn()}
    if (settings.humidityResponseMethod?.contains("4")) {overComparisonOn()}
    
    // Humidity Off Checks
    if (settings.humidityResponseMethod?.contains("1")) {rateOfChangeOff()}
    if (settings.humidityResponseMethod?.contains("2")) {overFixedThresholdOff()}
    if (settings.humidityResponseMethod?.contains("3")) {compareRateOfChangeOff()}
    if (settings.humidityResponseMethod?.contains("4")) {overComparisonOff()}
}

def humidityBatteryHandler(evt) {
    // Device status
    if (evt.value) {
        state.humiditySensorBatteryStatus = "[Battery: ${evt.value}]"
    } else if (humiditySensor?.currentValue("battery") != null) {
        state.humiditySensorBatteryStatus = "[Battery: ${humiditySensor.currentValue("battery")}]"
    } else if (humiditySensor?.latestValue("battery") != null) {
        state.humiditySensorBatteryStatus = "[Battery: ${humiditySensor.latestValue("battery")}]"
    } else {
        state.humiditySensorBatteryStatus = " "
    }
    // FIXED: Removed leftover debug code (log.warn)
}


// Event Handlers
def checkThreshold(evt) {
	ifTrace("checkThreshold")
    // FIXED: Use parseHumidity helper and ensure threshold is Float
    Float humidityValue = parseHumidity(evt.value)
    if (humidityValue == null) return false
    
    Float threshold = (humidityThreshold ?: 65).toFloat()
    if (humidityValue >= threshold) {
		if (settings.humidityResponseMethod?.contains("2")) {
            ifDebug("checkThreshold: Humidity ${humidityValue} is above the Threshold ${threshold}")
        }
		return true
    } else {
		return false
	}
}

def compareHumidityHandler(evt) {
    ifTrace("compareHumidityHandler")
    // Device status
    if (evt.value) {
        state.compareHumiditySensorStatus = "[Humidity: ${evt.value}]"
    } else if (compareHumiditySensor?.currentValue("humidity") != null) {
        state.compareHumiditySensorStatus = "[Humidity: ${compareHumiditySensor.currentValue("humidity")}]"
    } else if (compareHumiditySensor?.latestValue("humidity") != null) {
        state.compareHumiditySensorStatus = "[Humidity: ${compareHumiditySensor.latestValue("humidity")}]"
    } else {
        state.compareHumiditySensorStatus = "Comparison Humidity Sensor"
    }
    
    configureHumidityVariables()
    
    // compareHumidtyHandler Action
    // FIXED: Use parseHumidity helper and ensure proper type conversion
    state.compareHumidityValue = parseHumidity(evt.value)
    
    if ((settings.humidityResponseMethod?.contains("3") || settings.humidityResponseMethod?.contains("4")) && compareHumiditySensor) {
        if (state.compareHumidityValue != null) {
            // FIXED: Ensure offset is Float to prevent integer math issues
            Float offset = (compareHumiditySensorOffset ?: 10).toFloat()
            state.compareHumidity = offset + state.compareHumidityValue.toFloat()
        }
    }
}

def compareHumidityBatteryHandler(evt) {
    ifTrace("compareHumidityBatteryHandler")
    // Device status
    if (evt.value) {
        state.compareHumiditySensorBatteryStatus = "[Battery: ${evt.value}]"
    } else if (compareHumiditySensor?.currentValue("battery") != null) {
        state.compareHumiditySensorBatteryStatus = "[Battery: ${compareHumiditySensor.currentValue("battery")}]"
    } else if (compareHumiditySensor?.latestValue("battery") != null) {
        state.compareHumiditySensorBatteryStatus = "[Battery: ${compareHumiditySensor.latestValue("battery")}]"
    } else {
        state.compareHumiditySensorBatteryStatus = " "
    }
    // FIXED: Removed leftover debug code (log.warn)
}

def fanSwitchHandler(evt) {
    ifTrace("fanSwitchHandler")
    // Device status
    if (evt.value) {
        state.fanSwitchStatus = "[Fan: ${evt.value}]"
    } else if (fanSwitch?.currentValue("switch") != null) {
        state.fanSwitchStatus = "[Fan: ${fanSwitch.currentValue("switch")}]"
    } else if (fanSwitch?.latestValue("switch") != null) {
        state.fanSwitchStatus = "[Fan: ${fanSwitch.latestValue("switch")}]"
    } else {
        state.fanSwitchStatus = "Fan:"
    }
    
    configureHumidityVariables()
    
    // fanSwitchHandler Action
    // FIXED: Simplified condition check
    if (!shouldProcessHumidity()) {
        ifTrace("fanSwitchHandler: Skipping - getAllOk() = ${getAllOk()} state.pausedOrDisabled = ${state.pausedOrDisabled}")
        return
    }
    
    if (evt.value == "on") {
        if (settings.manualControlMode?.contains("2") && !state.automaticallyTurnedOn && manualOffMinutes) {
            if (manualOffMinutes == 0) {
                // Manually turned on - Instant off
                ifDebug("fanSwitchHandler: Turning the Fan off now")
                unschedule(turnOffFan)
                turnOffFan()
                state.turnOffLaterStarted = false
            } else {
                // Manually turned on - Turn off in manualOffMinutes minutes
                ifDebug("Automatic cutoff for manual activation in ${manualOffMinutes} minutes.")
                Integer i = Math.round(manualOffMinutes * 60)
                runIn(i, turnOffFan)
                state.turnOffLaterStarted = false
                state.status = "(On)"
            }
        } else if (state.automaticallyTurnedOn && maxRunTime) {
            // Automatically turned on - Scheduled automatic cutoff in maxRunTime minutes
            ifDebug("Automatic cutoff scheduled in ${maxRunTime} minutes.")
            Integer i = Math.round(maxRunTime * 60)
            runIn(i, turnOffFanMaxTimeout)
        }
    } else if (evt.value == "off") {
        ifDebug("fanSwitchHandler: Switch turned off")
        state.status = "(Off)"
        state.automaticallyTurnedOn = false
        state.turnOffLaterStarted = false
        state.fanOffRetries = 0  // FIXED: Reset retry counter
        unscheduleFanSwitchCommands()
    }
    
    // Sync smart switch
    if (smartSwitch) {
        if (evt.value == "on") {
            smartSwitch.on()
        } else {
            smartSwitch.off()
        }
    }
    
    updateLabel()
}

def disabledHandler(evt) {
    ifTrace("disabledHandler")
    
    if (!getAllOk()) {
        ifTrace("disabledHandler: getAllOk() = ${getAllOk()}")
        return
    }
    
    if (!disabledSwitch) return
    
    disabledSwitch.each { it ->
        def disabledSwitchState = it.currentValue("switch")
        
        if (disabledSwitchState == "on") {
            state.disabled = false
            if (state.paused) {
                state.status = "(Paused)"
                state.pausedOrDisabled = true
            } else {
                state.paused = false
                state.disabled = false
                state.pausedOrDisabled = false
                if (fanSwitch.currentValue("switch") == "off") {
                    state.status = "(Off)"
                    ifDebug("disabledHandler: App was enabled or unpaused and fan was off.")
                }
            }
        } else if (disabledSwitchState == "off") {
            state.pauseButtonName = "Disabled by Switch"
            state.status = "(Disabled)"
            state.disabled = true
            updateLabel()
            ifDebug("disabledHandler: App was disabled and fan is ${fanSwitch.currentValue("switch")}.")
        }
    }
    
    updateLabel()
}

def deviceActivationHandler(evt) {
    ifTrace("deviceActivationHandler")
    
    // FIXED: Simplified condition check
    if (!shouldProcessHumidity()) {
        ifTrace("deviceActivationHandler: Skipping - getAllOk() = ${getAllOk()} state.pausedOrDisabled = ${state.pausedOrDisabled}")
        return
    }
    
    if (!deviceActivation) return
    
    if (evt.value == "on") {
        turnOnFan()
        state.turnOnLaterStarted = false
        state.turnOffLaterStarted = false
        ifTrace("deviceActivationHandler: Turning on the fan.")
    } else if (evt.value == "off") {
        unschedule(turnOnFan)
        turnOffFan()
        state.turnOnLaterStarted = false
        state.turnOffLaterStarted = false
        ifTrace("deviceActivationHandler: Turning off the fan.")
    }
}

def smartSwitchHandler(evt) {
    ifTrace("smartSwitchHandler")
    // Device status
    if (evt.value) {
        state.smartSwitchStatus = "[Smart Switch: ${evt.value}]"
    } else if (smartSwitch?.currentValue("switch") != null) {
        state.smartSwitchStatus = "[Smart Switch: ${smartSwitch.currentValue("switch")}]"
    } else if (smartSwitch?.latestValue("switch") != null) {
        state.smartSwitchStatus = "[Smart Switch: ${smartSwitch.latestValue("switch")}]"
    } else {
        state.smartSwitchStatus = "Smart Switch"
    }
    
    updateLabel()
}

// Application functions
def rateOfChangeOn() {
    ifTrace("rateOfChangeOn")
    
    // Initialize defaults
    if (state.humidityChangeRate == null) state.humidityChangeRate = 0
    def increaseRate = settings.humidityIncreaseRate ?: 3
    if (state.currentHumidity == null) state.currentHumidity = 0
    if (state.targetHumidity == null) state.targetHumidity = 0
    
    // FIXED: Simplified condition check
    if (!shouldProcessHumidity()) {
        ifTrace("rateOfChangeOn: Skipping - state.pausedOrDisabled = ${state.pausedOrDisabled}")
        return
    }
    
    if (settings.humidityResponseMethod?.contains("1") && 
        (fanSwitch?.currentValue("switch") == "off") && 
        ((state.humidityChangeRate.toFloat() > increaseRate) || 
         (state.currentHumidity.toFloat() > state.targetHumidity.toFloat()))) {
        
        ifTrace("The humidity is high (or rising fast) and the fan is off, kick on the fan")
        
        if ((fanOnDelay > 0) && (fanOnDelay != null)) {
            ifDebug("rateOfChangeOn: Turning on fan later")
            // FIXED: Use Math.round for clarity (handles fractional minutes)
            Integer i = Math.round(fanOnDelay * 60)
            scheduleTurnOn(i)
            ifTrace("rateOfChangeOn: Turning on the fan")
        } else {
            ifDebug("rateOfChangeOn: Turning on fan due to humidity increase")
            state.automaticallyTurnedOn = true
            unschedule(turnOffFan)
            turnOnFan()
            state.turnOnLaterStarted = false
            ifTrace("rateOfChangeOn: Turning on fan")
        }
        
        state.startingHumidity = state.lastHumidity
        state.highestHumidity = state.currentHumidity    
    } else if (settings.humidityResponseMethod?.contains("1") && 
               (fanSwitch?.currentValue("switch") == "on") && 
               (state.turnOffLaterStarted) && 
               ((state.humidityChangeRate.toFloat() > increaseRate) || 
                (state.currentHumidity.toFloat() > state.targetHumidity.toFloat()))) {
        
        ifTrace("The humidity is high (or rising fast) and the fan is on but scheduled to turn off. Leaving the fan on")
        unschedule(turnOffFan)
        state.turnOnLaterStarted = false
        state.turnOffLaterStarted = false
    }
    
    ifTrace("rateOfChangeOn: Complete")
}

def rateOfChangeOff() {
    ifTrace("rateOfChangeOff")
    
    // FIXED: Simplified condition check
    if (!shouldProcessHumidity()) {
        ifTrace("rateOfChangeOff: Skipping - state.pausedOrDisabled = ${state.pausedOrDisabled}")
        return
    }
    
    def dropTimeout = humidityDropTimeout ?: 0
    
    if (settings.humidityResponseMethod?.contains("1") && 
        state.automaticallyTurnedOn && 
        (fanSwitch?.currentValue("switch") == "on") && 
        (state.currentHumidity.toFloat() <= state.targetHumidity.toFloat())) {
        
        if (dropTimeout == 0) {
            ifDebug("rateOfChangeOff: Fan Off")
            unschedule(turnOnFan)
            turnOffFan()
            state.turnOffLaterStarted = false
            ifDebug("rateOfChangeOff: Turning off the fan. Humidity has returned to normal and it was turned on automatically.")
        } else if (!state.turnOffLaterStarted){
            ifDebug("rateOfChangeOff: Turn Fan off in ${dropTimeout} minutes.")
            Integer i = (dropTimeout * 60)
            scheduleTurnOff(i)
            ifDebug("Turning off the fan in ${dropTimeout} minutes.")
        }
    } else if (settings.manualControlMode?.contains("1") && 
               !state.automaticallyTurnedOn && 
               (fanSwitch?.currentValue("switch") == "on") && 
               (state.currentHumidity.toFloat() <= state.targetHumidity.toFloat())) {
        
        if (dropTimeout == 0) {
            ifDebug("rateOfChangeOff: Fan Off")
            unschedule(turnOnFan)
            turnOffFan()
            state.turnOffLaterStarted = false
            ifDebug("rateOfChangeOff: Turning off the fan. Humidity has returned to normal and it was turned on manually.")
        } else if (!state.turnOffLaterStarted){
            ifDebug("rateOfChangeOff: Turn Fan off in ${dropTimeout} minutes.")
            Integer i = (dropTimeout * 60)
            scheduleTurnOff(i)
            ifDebug("Turning off the fan in ${dropTimeout} minutes.")
        }
    }
    
    ifTrace("rateOfChangeOff: Complete")
}

def overFixedThresholdOn() {
    ifTrace("overFixedThresholdOn")
    
    // FIXED: Simplified condition check
    if (!shouldProcessHumidity()) {
        ifTrace("overFixedThresholdOn: Skipping - state.pausedOrDisabled = ${state.pausedOrDisabled}")
        return
    }
    
    if (settings.humidityResponseMethod?.contains("2") && 
        state.overThreshold && 
        (fanSwitch?.currentValue("switch") == "off")) {
        
        ifTrace("If the humidity is over fixed threshold and the fan is off, kick on the fan")
        
        if ((fanOnDelay > 0) && (fanOnDelay != null)) {
            ifDebug("overFixedThresholdOn: Turning on fan later")
            Integer i = (fanOnDelay * 60)
            scheduleTurnOn(i)
            ifTrace("overFixedThresholdOn: Turning on the fan")
        } else {
            ifDebug("overFixedThresholdOn: Humidity over threshold. Turning on fan now")
	        state.automaticallyTurnedOn = true
            unschedule(turnOffFan)
            turnOnFan()
            state.turnOnLaterStarted = false
            ifTrace("overFixedThresholdOn: Turning on fan")
        }
        
        state.startingHumidity = state.lastHumidity
        state.highestHumidity = state.currentHumidity    
    } else if (settings.humidityResponseMethod?.contains("2") && 
               state.overThreshold && 
               (fanSwitch?.currentValue("switch") == "on")) {
        
        ifTrace("The humidity is over fixed threshold and the fan is on but scheduled to turn off. Leaving the fan on")
        unschedule(turnOffFan)
        state.turnOnLaterStarted = false
        state.turnOffLaterStarted = false
    }
    
    ifTrace("overFixedThresholdOn: Complete")
}

def overFixedThresholdOff() {
    ifTrace("overFixedThresholdOff")
    
    // FIXED: Simplified condition check
    if (!shouldProcessHumidity()) {
        ifTrace("overFixedThresholdOff: Skipping - state.pausedOrDisabled = ${state.pausedOrDisabled}")
        return
    }
    
    def dropTimeout = humidityDropTimeout ?: 0
    
    if (settings.humidityResponseMethod?.contains("2") && 
        !state.overThreshold && 
        state.automaticallyTurnedOn) {
        
        ifTrace("overFixedThresholdOff: state.automaticallyTurnedOn = ${state.automaticallyTurnedOn} !state.turnOffLaterStarted = ${!state.turnOffLaterStarted}")
        
        if (dropTimeout == 0) {
            unschedule(turnOnFan)
            turnOffFan()
            state.turnOffLaterStarted = false
            ifDebug("overFixedThresholdOff: Turning off the fan. Humidity has returned to normal and it was kicked on by the humidity sensor.")
        } else if (!state.turnOffLaterStarted){
            ifTrace("overFixedThresholdOff: Turn Fan off in ${dropTimeout} minutes.")
            Integer i = (dropTimeout * 60)
            scheduleTurnOff(i)
            ifDebug("Turning off the fan in ${dropTimeout} minutes.")
            ifTrace("overFixedThresholdOff: state.turnOffLaterStarted = ${state.turnOffLaterStarted}")
        }   
    } else if (settings.manualControlMode?.contains("1") && 
               !state.overThreshold && 
               !state.automaticallyTurnedOn) {
        
        ifTrace("overFixedThresholdOff: state.automaticallyTurnedOn = ${state.automaticallyTurnedOn} !state.turnOffLaterStarted = ${!state.turnOffLaterStarted}")
        
        if (dropTimeout == 0) {
            unschedule(turnOnFan)
            turnOffFan()
            state.turnOffLaterStarted = false
            ifDebug("overFixedThresholdOff: Turning off the fan. Humidity has returned to normal and it was turned on manually.")
        } else if (!state.turnOffLaterStarted){
            ifTrace("overFixedThresholdOff: Turn Fan off in ${dropTimeout} minutes.")
            Integer i = (dropTimeout * 60)
            scheduleTurnOff(i)
            ifDebug("Turning off the fan in ${dropTimeout} minutes.")
            ifTrace("overFixedThresholdOff: state.turnOffLaterStarted = ${state.turnOffLaterStarted}")
        }
    }
    
    ifTrace("overFixedThresholdOff: Complete")
}

def compareRateOfChangeOn() {
    ifTrace("compareRateOfChangeOn")
    
    if (state.compareHumidity == null) {getComparisonValue()}
    
    // FIXED: Simplified condition check
    if (!shouldProcessHumidity()) {
        ifTrace("compareRateOfChangeOn: Skipping - state.pausedOrDisabled = ${state.pausedOrDisabled}")
        return
    }
    
    def increaseRate = settings.humidityIncreaseRate ?: 3
    
    ifTrace("settings.humidityResponseMethod?.contains(3) = ${settings.humidityResponseMethod?.contains("3")} compareHumiditySensor = ${compareHumiditySensor} state.compareHumidityValue = ${state.compareHumidityValue} compareHumiditySensorOffset = ${compareHumiditySensorOffset}")
    ifTrace("state.humidityChangeRate = ${state.humidityChangeRate} settings.humidityIncreaseRate = ${increaseRate} state.currentHumidity = ${state.currentHumidity} state.compareHumidity = ${state.compareHumidity}")
    
    if (settings.humidityResponseMethod?.contains("3") && 
        (fanSwitch?.currentValue("switch") == "off") && 
        (state.humidityChangeRate > increaseRate) && 
        (state.currentHumidity.toFloat() > state.compareHumidity.toFloat())) {
        
        if ((fanOnDelay > 0) && (fanOnDelay != null)) {
            ifDebug("compareRateOfChangeOn: Turning on fan later")
            Integer i = (fanOnDelay * 60)
            scheduleTurnOn(i)
            state.automaticallyTurnedOn = true
        } else {
            ifDebug("compareRateOfChangeOn: Turning on fan due to humidity increase and humidity over comparison sensor humidity")
	        state.automaticallyTurnedOn = true
            unschedule(turnOffFan)
            turnOnFan()
            ifTrace("compareRateOfChangeOn: Turning on fan")
            state.turnOnLaterStarted = false
        }
        
        state.startingHumidity = state.lastHumidity
        state.highestHumidity = state.currentHumidity    
        
        ifTrace("compareRateOfChangeOn: new state.humidityChangeRate = ${state.humidityChangeRate}")
        ifTrace("compareRateOfChangeOn: new settings.humidityIncreaseRate = ${increaseRate}")
        ifTrace("compareRateOfChangeOn: new state.currentHumidity = ${state.currentHumidity}")
        ifTrace("compareRateOfChangeOn: new state.compareHumidity = ${state.compareHumidity}")
    } else if (settings.humidityResponseMethod?.contains("3") && 
               (fanSwitch?.currentValue("switch") == "on") && 
               (state.turnOffLaterStarted) && 
               (state.humidityChangeRate.toFloat() > increaseRate) && 
               (state.currentHumidity.toFloat() > state.compareHumidity.toFloat())) {
        
        ifDebug("compareRateOfChangeOn: Leaving the fan on due to humidity increase and humidity over comparison sensor humidity")
        unschedule(turnOffFan)
        state.turnOnLaterStarted = false
        state.turnOffLaterStarted = false
    }
    
    ifTrace("compareRateOfChangeOn: Complete")
}       

def compareRateOfChangeOff() {
    ifTrace("compareRateOfChangeOff")
    
    if (state.compareHumidity == null) {getComparisonValue()}
    
    // FIXED: Simplified condition check
    if (!shouldProcessHumidity()) {
        ifTrace("compareRateOfChangeOff: Skipping - state.pausedOrDisabled = ${state.pausedOrDisabled}")
        return
    }
    
    def dropTimeout = humidityDropTimeout ?: 0
    
    if (settings.humidityResponseMethod?.contains("3") && 
        state.automaticallyTurnedOn && 
        (state.currentHumidity <= state.compareHumidity)) {
        
        if (dropTimeout == 0) {
            unschedule(turnOnFan)
            turnOffFan()
            state.turnOffLaterStarted = false
            ifDebug("compareRateOfChangeOff: Turning off the fan. Humidity has returned to normal and it was kicked on by the humidity sensor.")
        } else if (!state.turnOffLaterStarted){
            ifDebug("compareRateOfChangeOff: Turn Fan off in ${dropTimeout} minutes.")
            Integer i = (dropTimeout * 60)
            scheduleTurnOff(i)
            ifDebug("Turning off the fan in ${dropTimeout} minutes.")
            ifTrace("compareRateOfChangeOff: state.turnOffLaterStarted = ${state.turnOffLaterStarted}")
        }
    } else if (settings.manualControlMode?.contains("1") && 
               !state.automaticallyTurnedOn && 
               (state.currentHumidity <= state.compareHumidity)) {
        
        ifTrace("compareRateOfChangeOff: state.currentHumidity = ${state.currentHumidity} state.targetHumidity = ${state.targetHumidity}")
        
        if (dropTimeout == 0) {
            unschedule(turnOnFan)
            turnOffFan()
            state.turnOffLaterStarted = false
            ifDebug("compareRateOfChangeOff: Turning off the fan. Humidity has returned to normal and it was turned on manually.")
        } else if (!state.turnOffLaterStarted){
            ifDebug("compareRateOfChangeOff: Turn Fan off in ${dropTimeout} minutes.")
            Integer i = (dropTimeout * 60)
            scheduleTurnOff(i)
            ifDebug("Turning off the fan in ${dropTimeout} minutes.")
            ifTrace("compareRateOfChangeOff: state.turnOffLaterStarted = ${state.turnOffLaterStarted}")
        }
    }
    
    ifTrace("compareRateOfChangeOff: Complete")
}

def overComparisonOn() {
    ifTrace("overComparisonOn")
    
    if (state.compareHumidity == null) {getComparisonValue()}
    
    // FIXED: Simplified condition check
    if (!shouldProcessHumidity()) {
        ifTrace("overComparisonOn: Skipping - state.pausedOrDisabled = ${state.pausedOrDisabled}")
        return
    }
    
    // FIXED: Removed impossible condition (fan off AND automatically turned on)
    // Changed first condition to check for fan off WITHOUT checking automaticallyTurnedOn
    if (settings.humidityResponseMethod?.contains("4") && 
        state.currentHumidity && 
        state.compareHumidity && 
        (fanSwitch?.currentValue("switch") == "off") && 
        (state.currentHumidity.toFloat() > state.compareHumidity.toFloat())) {
        
        ifTrace("The humidity is higher than the comparison sensor and the fan is off, kick on the fan")
        ifTrace("state.currentHumidity = ${state.currentHumidity} state.compareHumidity = ${state.compareHumidity}")
        
        if ((fanOnDelay > 0) && (fanOnDelay != null)) {
            ifDebug("overComparisonOn: Turning on fan later")
            Integer i = (fanOnDelay * 60)
            scheduleTurnOn(i)
        } else {
            ifInfo("overComparisonOn: Turning on fan due to humidity increase")
	        state.automaticallyTurnedOn = true
            unschedule(turnOffFan)
            turnOnFan()
            ifTrace("overComparisonOn: Turning on fan")
            state.turnOnLaterStarted = false
        }
        
        state.startingHumidity = state.lastHumidity
        state.highestHumidity = state.currentHumidity    
        
        ifTrace("overComparisonOn: new state.startingHumidity = ${state.startingHumidity}")
        ifTrace("overComparisonOn: new state.highestHumidity = ${state.highestHumidity}")
        ifTrace("overComparisonOn: new state.targetHumidity = ${state.targetHumidity}")
    } else if (settings.humidityResponseMethod?.contains("4") && 
               state.currentHumidity && 
               state.compareHumidity && 
               (fanSwitch?.currentValue("switch") == "on") && 
               state.automaticallyTurnedOn && 
               (state.turnOffLaterStarted) && 
               (state.currentHumidity.toFloat() > state.compareHumidity.toFloat())) {
        
        ifTrace("The humidity is higher than the comparison sensor and the fan is on but scheduled to turn off. Leaving the fan on")
        unschedule(turnOffFan)
        state.turnOnLaterStarted = false
        state.turnOffLaterStarted = false
    }
    
    ifTrace("overComparisonOn: Complete")
}

def overComparisonOff() {
    ifTrace("overComparisonOff")
    
    if (state.compareHumidity == null) {getComparisonValue()}
    
    // FIXED: Simplified condition check
    if (!shouldProcessHumidity()) {
        ifTrace("overComparisonOff: Skipping - state.pausedOrDisabled = ${state.pausedOrDisabled}")
        return
    }
    
    def dropTimeout = humidityDropTimeout ?: 0
    
    if (settings.humidityResponseMethod?.contains("4") && 
        state.automaticallyTurnedOn && 
        (state.currentHumidity.toFloat() <= state.compareHumidity.toFloat())) {
        
        if (dropTimeout == 0) {
            turnOffFan()
            unschedule(turnOnFan)
			state.turnOffLaterStarted = false
            ifDebug("overComparisonOff: Turning off the fan. Humidity has returned to normal and it was kicked on by the humidity sensor.")
        } else if (!state.turnOffLaterStarted){
            ifDebug("overComparisonOff: Turn Fan off in ${dropTimeout} minutes.")
            Integer i = (dropTimeout * 60)
            scheduleTurnOff(i)
            ifDebug("Turning off the fan in ${dropTimeout} minutes.")
        }
    } else if (settings.manualControlMode?.contains("1") && 
               !state.automaticallyTurnedOn && 
               (state.currentHumidity.toFloat() <= state.compareHumidity.toFloat())) {
        
        if (dropTimeout == 0) {
            turnOffFan()
            unschedule(turnOnFan)
			state.turnOffLaterStarted = false
            ifDebug("overComparisonOff: Turning off the fan. Humidity has returned to normal and it was kicked on by the humidity sensor.")
        } else if (!state.turnOffLaterStarted){
            ifDebug("overComparisonOff: Turn Fan off in ${dropTimeout} minutes.")
            Integer i = (dropTimeout * 60)
            scheduleTurnOff(i)
            ifDebug("Turning off the fan in ${dropTimeout} minutes.")
        }
    }
    
    ifTrace("overComparisonOff: Complete")
}

def getComparisonValue() {
    ifTrace("getComparisonValue")
    
    // FIXED: Use parseHumidity helper and check for correct type
    if (compareHumiditySensor?.currentValue("humidity") != null) {
        state.compareHumidityValue = parseHumidity(compareHumiditySensor.currentValue("humidity"))
    }
    
    if ((settings.humidityResponseMethod?.contains("3") || settings.humidityResponseMethod?.contains("4")) && 
        compareHumiditySensor && 
        state.compareHumidityValue) {
        
        // FIXED: Ensure offset is Float with default value
        Float offset = (compareHumiditySensorOffset ?: 10).toFloat()
        state.compareHumidity = offset + state.compareHumidityValue.toFloat()
    }
    
    ifTrace("getComparisonValue: Complete")
}

def turnOffFanHumidity() {
    ifTrace("turnOffFanHumidity")
    
    // FIXED: Simplified condition check
    if (!shouldProcessHumidity()) {
        ifTrace("turnOffFanHumidity: Skipping - state.pausedOrDisabled = ${state.pausedOrDisabled}")
        return
    }
    
    def dropTimeout = humidityDropTimeout ?: 0
    
    if ((state.currentHumidity > state.targetHumidity) && 
        (fanSwitch?.currentValue("switch") == "on")) {
        
        ifInfo("turnOffFanHumidity: Didn't turn off fan because the humidity is higher than the target humidity ${state.targetHumidity}")
        
        if (dropTimeout == 0) {
            turnOffFan()
            unschedule(turnOnFan)
            state.turnOffLaterStarted = false
            ifDebug("turnOffFanHumidity: Turning off the fan. Humidity has returned to normal and it was kicked on by the humidity sensor.")
        } else {
            ifDebug("turnOffFanHumidity: Turn Fan off in ${dropTimeout} minutes.")
            Integer i = (dropTimeout * 60)
            scheduleTurnOff(i)
            ifDebug("Turning off the fan in ${dropTimeout} minutes.")
        }
    }
    
    updateLabel()
    ifTrace("turnOffFanHumidity: Complete")
}

// NEW: Safe wrapper for turning off fan
private void turnOffFanSafely() {
    try {
        fanSwitch.off()
    } catch (Exception e) {
        log.error "Failed to turn off fan: ${e.message}"
    }
}

// NEW: Safe wrapper for turning on fan  
private void turnOnFanSafely() {
    try {
        fanSwitch.on()
    } catch (Exception e) {
        log.error "Failed to turn on fan: ${e.message}"
    }
}

def turnOffFan() {
    ifTrace("turnOffFan")
    
    // FIXED: Simplified condition check
	if (!shouldProcessHumidity()) {
        ifTrace("turnOffFan: Skipping - state.pausedOrDisabled = ${state.pausedOrDisabled}")
        return
    }
    
    if (fanSwitch?.currentValue("switch") == "on"){
        ifInfo("Turning off the fan.")
        
        // FIXED: Removed immediate state check - rely on fanSwitchHandler event
        // The device needs time to report its new state (10-100ms typical)
        // fanSwitchHandler will update state when "off" event arrives
        try {
            fanSwitch.off()
            unschedule(turnOffFan)
            unschedule(turnOnFan)
            // Note: state cleanup happens in fanSwitchHandler when "off" event arrives
        } catch (Exception e) {
            log.error "Failed to send off command to fan: ${e.message}"
            // Retry on command failure, not state check failure
            if (state.fanOffRetries < 3) {
                state.fanOffRetries++
                runIn(10, turnOffFan)
                log.warn "Fan command failed, retry ${state.fanOffRetries}/3"
            } else {
                state.fanOffRetries = 0
                log.error "Fan command failed after 3 attempts. Giving up."
            }
        }
    }
    
    ifTrace("turnOffFan: Complete")
}

def turnOffFanMaxTimeout() {
    ifTrace("turnOffFanMaxTimeout")
    
    // FIXED: Simplified condition check
	if (!shouldProcessHumidity()) {
        ifTrace("turnOffFanMaxTimeout: Skipping - state.pausedOrDisabled = ${state.pausedOrDisabled}")
        return
    }
    
    if (fanSwitch?.currentValue("switch") == "on") {
        ifInfo("Turning off the fan due to max timeout.")
        
        // FIXED: Removed immediate state check - rely on fanSwitchHandler event
        try {
            fanSwitch.off()
            unschedule(turnOffFan)
            unschedule(turnOnFan)
            // Note: state cleanup happens in fanSwitchHandler when "off" event arrives
        } catch (Exception e) {
            log.error "Failed to send off command to fan (max timeout): ${e.message}"
            // Retry on command failure only
            if (state.fanOffRetries < 3) {
                state.fanOffRetries++
                runIn(10, turnOffFanMaxTimeout)
                log.warn "Fan command failed (max timeout), retry ${state.fanOffRetries}/3"
            } else {
                state.fanOffRetries = 0
                log.error "Fan command failed after 3 attempts (max timeout). Giving up."
            }
        }
    }
    
    ifTrace("turnOffFanMaxTimeout: Complete")
}

def turnOnFan() {
    ifTrace("turnOnFan")
    
    // FIXED: Simplified condition check
    if (!shouldProcessHumidity()) {
        ifTrace("turnOnFan: Skipping - state.pausedOrDisabled = ${state.pausedOrDisabled}")
        return
    }
    
    ifInfo("Turning on the fan.")
    state.automaticallyTurnedOn = true
    unschedule(turnOffFan)
    state.turnOffLaterStarted = false
    state.turnOnLaterStarted = false
    
    // FIXED: Added error handling
    try {
        fanSwitch.on()
    } catch (Exception e) {
        log.error "Failed to turn on fan: ${e.message}"
        return
    }
    
    state.status = "(On)"
    
    if (maxRunTime != null) {
        ifDebug("Maximum run time is ${maxRunTime} minutes")
        Integer i = Math.round(maxRunTime * 60)
        runIn(i, turnOffFanMaxTimeout)
        unschedule(turnOnFan)
    }
    
    updateLabel()
    ifTrace("turnOnFan: Complete")
}

def changeMode(mode) {
    ifTrace("changeMode")
    ifDebug("Changing Mode to: ${mode}")
	if (location?.mode != mode && location.modes?.find { it.name == mode}) {
        setLocationMode(mode)
    }
    updateLabel()
    ifTrace("changeMode: Complete")
}

def humidityHandlerVariablesBefore() {
    ifTrace("humidityHandlerVariablesBefore: Before")
    ifTrace("humidityHandlerVariablesBefore: state.overThreshold = ${state.overThreshold}")
	ifTrace("humidityHandlerVariablesBefore: state.automaticallyTurnedOn = ${state.automaticallyTurnedOn}")
	ifTrace("humidityHandlerVariablesBefore: state.turnOffLaterStarted = ${state.turnOffLaterStarted}")
	ifTrace("humidityHandlerVariablesBefore: state.lastHumidity = ${state.lastHumidity}")
	ifTrace("humidityHandlerVariablesBefore: state.lastHumidityDate = ${state.lastHumidityDate}")
	ifTrace("humidityHandlerVariablesBefore: state.currentHumidity = ${state.currentHumidity}")
	ifTrace("humidityHandlerVariablesBefore: state.currentHumidityDate = ${state.currentHumidityDate}")
	ifTrace("humidityHandlerVariablesBefore: state.startingHumidity = ${state.startingHumidity}")
	ifTrace("humidityHandlerVariablesBefore: state.highestHumidity = ${state.highestHumidity}")
	ifTrace("humidityHandlerVariablesBefore: state.humidityChangeRate = ${state.humidityChangeRate?.toFloat()?.round(2)}")
	ifTrace("humidityHandlerVariablesBefore: state.targetHumidity = ${state.targetHumidity}")
    if (settings.humidityResponseMethod?.contains("3") || settings.humidityResponseMethod?.contains("4")) {
        ifTrace("humidityHandlerVariablesBefore: state.compareHumidity = ${state.compareHumidity}")
    }
    if (settings.humidityResponseMethod?.contains("3")) {
        ifTrace("humidityHandlerVariablesBefore: state.compareHumidityValue = ${state.compareHumidityValue}")
    }
    if (settings.humidityResponseMethod?.contains("4")) {
        ifTrace("humidityHandlerVariablesBefore: compareHumiditySensorOffset = ${compareHumiditySensorOffset}")
    }
    ifTrace("humidityHandlerVariablesBefore: settings.humidityResponseMethod?.contains(1) = ${settings.humidityResponseMethod?.contains("1")}")
    ifTrace("humidityHandlerVariablesBefore: settings.humidityResponseMethod?.contains(2) = ${settings.humidityResponseMethod?.contains("2")}")
    ifTrace("humidityHandlerVariablesBefore: settings.humidityResponseMethod?.contains(3) = ${settings.humidityResponseMethod?.contains("3")}")
    ifTrace("humidityHandlerVariablesBefore: settings.humidityResponseMethod?.contains(4) = ${settings.humidityResponseMethod?.contains("4")}")
    ifTrace("humidityHandlerVariablesBefore: Complete")
}

def configureHumidityVariables() {
    // FIXED: Validate and ensure all humidity state variables are Floats
    // This prevents type corruption from other code or state persistence issues
    
    // If bogus humidity reset to current humidity
    if (state.currentHumidity == null) {
        state.currentHumidity = parseHumidity(humiditySensor.currentValue("humidity"))
    } else {
        // Ensure it's a Float
        state.currentHumidity = state.currentHumidity.toFloat()
    }
    
    if (state.highestHumidity == null) {
        state.highestHumidity = state.currentHumidity
    } else if ((state.highestHumidity > 99) || (state.highestHumidity < 1)) {
        state.highestHumidity = state.currentHumidity.toFloat()
    } else {
        state.highestHumidity = state.highestHumidity.toFloat()
    }
    
    if (state.targetHumidity == null) {
        state.targetHumidity = state.currentHumidity.toFloat()
    } else if (state.targetHumidity.toFloat() > 99) {
        state.targetHumidity = state.currentHumidity.toFloat()
    } else {
        state.targetHumidity = state.targetHumidity.toFloat()
    }
    
    if (state.startingHumidity == null) {
        state.startingHumidity = state.currentHumidity.toFloat()
    } else if (state.startingHumidity.toFloat() > 99) {
        state.startingHumidity = state.currentHumidity.toFloat()
    } else {
        state.startingHumidity = state.startingHumidity.toFloat()
    }
    
    // Ensure lastHumidity is Float
    if (state.lastHumidity != null) {
        state.lastHumidity = state.lastHumidity.toFloat()
    }
    
    // Calculate humidity change rate
    if ((state.currentHumidity != null) && (state.lastHumidity != null)) {
        state.humidityChangeRate = (state.currentHumidity - state.lastHumidity)
    } else {
        state.humidityChangeRate = 0.0
    }
    
    if (state.currentHumidity) {
        state.lastHumidity = state.currentHumidity
    }
    
    if (!state.startingHumidity) {
        state.startingHumidity = state.currentHumidity.toFloat()
    }
    
    if (!state.highestHumidity) {
        state.highestHumidity = state.currentHumidity.toFloat()
    }
    
	if (state.currentHumidity > state.highestHumidity) {
        state.highestHumidity = state.currentHumidity.toFloat()
    }
    
    // FIXED: Ensure dropLimit is Float AND use 100.0 for float division
    def dropLimit = (humidityDropLimit ?: 25).toFloat()
	state.targetHumidity = (state.startingHumidity.toFloat() + 
                            ((dropLimit / 100.0) * 
                             (state.highestHumidity.toFloat() - state.startingHumidity.toFloat())))
    
    ifTrace("configureHumidityVariables: Complete")
}

def humidityHandlerVariablesAfter() {
    ifTrace("humidityHandlerVariablesAfter: After")
    ifTrace("humidityHandlerVariablesAfter: state.overThreshold = ${state.overThreshold}")
	ifTrace("humidityHandlerVariablesAfter: state.automaticallyTurnedOn = ${state.automaticallyTurnedOn}")
	ifTrace("humidityHandlerVariablesAfter: state.turnOffLaterStarted = ${state.turnOffLaterStarted}")
	ifTrace("humidityHandlerVariablesAfter: state.lastHumidity = ${state.lastHumidity}")
	ifTrace("humidityHandlerVariablesAfter: state.lastHumidityDate = ${state.lastHumidityDate}")
	ifTrace("humidityHandlerVariablesAfter: state.currentHumidity = ${state.currentHumidity}")
	ifTrace("humidityHandlerVariablesAfter: state.currentHumidityDate = ${state.currentHumidityDate}")
	ifTrace("humidityHandlerVariablesAfter: state.startingHumidity = ${state.startingHumidity}")
	ifTrace("humidityHandlerVariablesAfter: state.highestHumidity = ${state.highestHumidity}")
	ifTrace("humidityHandlerVariablesAfter: state.humidityChangeRate = ${state.humidityChangeRate?.round(2)}")
	ifTrace("humidityHandlerVariablesAfter: state.targetHumidity = ${state.targetHumidity}")
    if ((settings.humidityResponseMethod?.contains("3")) || (settings.humidityResponseMethod?.contains("4"))) {
        ifTrace("humidityHandlerVariablesAfter: state.compareHumidity = ${state.compareHumidity}")
    }
    if (settings.humidityResponseMethod?.contains("3")) {
        ifTrace("humidityHandlerVariablesAfter: state.compareHumidityValue = ${state.compareHumidityValue}")
    }
    if (settings.humidityResponseMethod?.contains("4")) {
        ifTrace("humidityHandlerVariablesAfter: compareHumiditySensorOffset = ${compareHumiditySensorOffset}")
    }
    ifTrace("humidityHandlerVariablesAfter: settings.humidityResponseMethod?.contains(1) = ${settings.humidityResponseMethod?.contains("1")}")
    ifTrace("humidityHandlerVariablesAfter: settings.humidityResponseMethod?.contains(2) = ${settings.humidityResponseMethod?.contains("2")}")
    ifTrace("humidityHandlerVariablesAfter: settings.humidityResponseMethod?.contains(3) = ${settings.humidityResponseMethod?.contains("3")}")
    ifTrace("humidityHandlerVariablesAfter: settings.humidityResponseMethod?.contains(4) = ${settings.humidityResponseMethod?.contains("4")}")
    ifTrace("humidityHandlerVariablesAfter: Complete")
}

//Label Updates
void updateLabel() {
    ifTrace("updateLabel")
    
    state.pausedOrDisabled = (state.paused || state.disabled)
    
    if (!getAllOk()) {
        state.status = "(Disabled by Time, Day, or Mode)"
        appStatus = "<span style=color:brown>(Disabled by Time, Day, or Mode)</span>"
    } else if (state.disabled) {
        state.status = "(Disabled)"
        appStatus = "<span style=color:red>(Disabled)</span>"
    } else if (state.paused) {
        state.status = "(Paused)"
        appStatus = "<span style=color:red>(Paused)</span>"
    } else if (fanSwitch?.currentValue("switch") == "on") {
        state.status = "(On)"
        appStatus = "<span style=color:green>(On)</span>"
    } else if (fanSwitch?.currentValue("switch") == "off") {
        state.status = "(Off)"
        appStatus = "<span style=color:blue>(Off)</span>"
    } else {
        initialize()
        state.pausedOrDisabled = false
        state.status = " "
        appStatus = "<span style=color:white> </span>"
    }
    
    app.updateLabel("${state.thisName} ${appStatus}")
}

//Smart Switch, and Enable, Resume, Pause button
def appButtonHandler(btn) {
    ifTrace("appButtonHandler ${btn}")
    
    if ((btn == "CreateSmartSwitch") && 
        (state.createSmartSwitchButtonName == "Create Smart Switch") && 
        !getChildDevice("SmartSwitch_${app.id}")) {
        
        ifTrace("Creating Smart Switch")
        addChildDevice("hubitat", "Virtual Switch", "SmartSwitch_${app.id}", null, 
                      [label: thisName, name: thisName])
        setCreateSmartSwitchButtonName()
    } else if ((btn == "CreateSmartSwitch") && 
               (state.createSmartSwitchButtonName == "Delete Smart Switch") && 
               getChildDevice("SmartSwitch_${app.id}")) {
        
        ifTrace("Deleting Smart Switch")
        deleteChildDevice("SmartSwitch_${app.id}")  // FIXED: Removed extra parentheses
        setCreateSmartSwitchButtonName()
    } else if (btn == "On") {
        try {
            fanSwitch.on()
            ifDebug("On command sent")
        } catch (Exception e) {
            log.error "Failed to turn on fan: ${e.message}"
        }
	    runIn(5, updateLabel)
    } else if (btn == "Off") {
        try {
            fanSwitch.off()
            ifDebug("Off command sent")
        } catch (Exception e) {
            log.error "Failed to turn off fan: ${e.message}"
        }
        runIn(5, updateLabel)
    } else if (btn == "Disabled by Switch") {
        state.disabled = false
        unschedule()
        unsubscribe()
    } else if (btn == "Resume") {
        state.disabled = false
        state.paused = !state.paused
    } else if (btn == "Pause") {
        state.paused = !state.paused
        if (state.paused) {
            unschedule()
            unsubscribe()
            subscribe(disabledSwitch, "switch", disabledHandler)
            subscribe(fanSwitch, "switch", fanSwitchHandler)
            subscribe(humiditySensor, "humidity", humidityHandler)
            subscribe(humiditySensor, "battery", humidityBatteryHandler)
            subscribe(compareHumiditySensor, "humidity", compareHumidityHandler)
            subscribe(compareHumiditySensor, "battery", compareHumidityBatteryHandler)
			subscribe(smartSwitch, "switch", smartSwitchHandler)
        } else {
            initialize()
            state.pausedOrDisabled = false
            if (fanSwitch?.currentValue("switch") == "on") {
                ifTrace("appButtonHandler: App was enabled or unpaused and fan was on. Turning off the fan.")
                turnOffFan()
            }
        }
    }
    
    updateLabel()
}

def setCreateSmartSwitchButtonName() {
    if (getChildDevice("SmartSwitch_${app.id}")) {
        state.createSmartSwitchButtonName = "Delete Smart Switch"
    } else {
        state.createSmartSwitchButtonName = "Create Smart Switch"
    }
}

def setPauseButtonName() {
    if (state.disabled) {
        state.pauseButtonName = "Disabled by Switch"
        unsubscribe()
        unschedule()
        updateLabel()
    } else if (state.paused) {
        state.pauseButtonName = "Resume"
        unsubscribe()
        unschedule()
        updateLabel()
    } else {
        state.pauseButtonName = "Pause"
        updated()
        subscribe(disabledSwitch, "switch", disabledHandler)
        subscribe(fanSwitch, "switch", fanSwitchHandler)
        subscribe(humiditySensor, "humidity", humidityHandler)
        subscribe(compareHumiditySensor, "humidity", compareHumidityHandler)
        subscribe(smartSwitch, "switch", smartSwitchHandler)
        updateLabel()
    }
}

def unscheduleFanSwitchCommands() {
    unschedule(turnOnFan)
    unschedule(turnOffFan)
}

// Application Page settings
private hideComparisonSensorSection() {
    return (compareHumiditySensor || compareHumiditySensorOffset) ? false : true
}

private hideNotificationSection() {
    return (notifyOnLowBattery || lowBatteryNotificationDevices || 
            lowBatteryDevicesToNotifyFor || lowBatteryAlertThreshold || 
            notifyOnFailure || failureNotificationDevices || 
            failureNotifications) ? false : true
}

private hideAdditionalFeaturesSection() {
    return (deviceActivation || Create || smartSwitch) ? false : true
}

private hideOptionsSection() {
    return (timeIntervalInput || starting || ending || days || modes || disabledSwitch) ? false : true
}

private hideLoggingSection() {
    return (isInfo || isDebug || isTrace || ifLevel) ? false : true
}

def getAllOk() {
    return (modeOk && daysOk && timeOk)
}

private getModeOk() {
	return (!modes || modes.contains(location.mode))
}

private getDaysOk() {
	def result = true
	if (days) {
		def df = new java.text.SimpleDateFormat("EEEE")
		if (location.timeZone) {
            df.setTimeZone(location.timeZone)
        } else {
            df.setTimeZone(TimeZone.getTimeZone("America/New_York"))
        }
		def day = df.format(new Date())
		result = days.contains(day)
	}
	return result
}

private getTimeOk() {
	def result = true
	if ((starting != null) && (ending != null)) {
		def currTime = now()
		def start = timeToday(starting).time
		def stop = timeToday(ending).time
		result = start < stop ? currTime >= start && currTime <= stop : currTime <= stop || currTime >= start
	}
	return result
}

private hhmm(time, fmt = "h:mm a") {
	def t = timeToday(time, location.timeZone)
	def f = new java.text.SimpleDateFormat(fmt)
	f.setTimeZone(location.timeZone ?: timeZone(time))
	return f.format(t)
}

private timeIntervalLabel() {
	return (starting && ending) ? hhmm(starting) + "-" + hhmm(ending, "h:mm a z") : ""
}

// Logging functions
def infoOff() {
    app.updateSetting("isInfo",[value:"false",type:"bool"])
    if (!isInfo) {
        log.warn "${state.thisName}: Info logging disabled."
    }
}

def debugOff() {
    app.updateSetting("isDebug",[value:"false",type:"bool"])
    if (!isDebug) {
        log.warn "${state.thisName}: Debug logging disabled."
    }
}

def traceOff() {
    app.updateSetting("isTrace",[value:"false",type:"bool"])
    if (!isTrace) {
        log.warn "${state.thisName}: Trace logging disabled."
    }
}

def ifWarn(msg) {
    log.warn "${state.thisName}: ${msg}"
}

def ifInfo(msg) {
    if (!settings.ifLevel?.contains("1") && !isInfo) return
    log.info "${state.thisName}: ${msg}"
}

def ifDebug(msg) {
    if (!settings.ifLevel?.contains("2") && !isDebug) return
    log.debug "${state.thisName}: ${msg}"
}

def ifTrace(msg) {
    if (!settings.ifLevel?.contains("3") && !isTrace) return
    log.trace "${state.thisName}: ${msg}"
}
