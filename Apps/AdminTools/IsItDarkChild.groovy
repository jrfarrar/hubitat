/**
 *
 *
 *  Light/Dark Switch
 *
 *  2025/11/11 - update debounce logic
 */
definition(
    name: "Is It Dark Child",
    namespace: "jrfarrar",
    author: "J.R. Farrar",
    description: "Turns off a switch when illuminance is below a threshold.",
    parent: "jrfarrar:Admin tools", 
    category: "Convenience",
    iconUrl: "",
    iconX2Url: "",
    importUrl: "https://raw.githubusercontent.com/jrfarrar/hubitat/master/Apps/AdminTools/IsItDarkChild.groovy"
)

preferences {
    page(name: "pageConfig")
}

def pageConfig() {
    dynamicPage(name: "", title: "", install: true, uninstall: true, refreshInterval:0) { 
        section(getFormat("header-green", "Control this switch(on for light, off for dark)")){
            input "mySwitch", "capability.switch", required: true
        }
        section(getFormat("header-green", "Illuminance sensor to use and settings(Optional if using sunset/rise)")){
            def illumNow
            if (lightSensor) {illumNow = lightSensor.currentIlluminance}
            input "lightSensor", "capability.illuminanceMeasurement", title: "Illuminance Sensor to use? $illumNow", submitOnChange: true
            input "illumLight", "number", title: "Illuminance value to act on for light? (>= this value is light)"
            input "illumDark", "number", title: "Illuminance value to act on for dark? (< this value is dark)"        
            input "delayMinutes", "number", title: "Minutes to wait before flipping switch(debounce)?"
            input (name: "onlyRunDuringDay", type: "bool", defaultValue: "false", title: "Only runing during daylight hours?", submitOnChange: true)
        }
        section (getFormat("header-green", "Use Sunrise/Sunset options (optional)...")) {
            input (name: "useSunriseSunsetOptions", type: "bool", defaultValue: "false", title: "Use sunrise/sunset options?", submitOnChange: true)
        }
        if (useSunriseSunsetOptions) {
            section (getFormat("header-green", "Sunrise offset (optional)...")) {
                input "sunriseOffsetValue", "text", title: "HH:MM", required: false
                input "sunriseOffsetDir", "enum", title: "Before or After", required: false, options: ["Before","After"]
            }
            section (getFormat("header-green", "Sunset offset (optional)...")) {
                input "sunsetOffsetValue", "text", title: "HH:MM", required: false
                input "sunsetOffsetDir", "enum", title: "Before or After", required: false, options: ["Before","After"]
            }
            section (getFormat("header-green", "Zip code (optional, defaults to location coordinates when location services are enabled)...")) {
                input "zipCode", "text", title: "Zip code", required: false
            }
        }
        section(getFormat("header-green", "LOGGING")){                       
            input(name: "logLevel",title: "IDE logging level",multiple: false,required: true,type: "enum",options: getLogLevels(),submitOnChange : false,defaultValue : "1")
        }
        section(getFormat("header-green", "APP NAME")){
            input (name: "thisName", type: "text", title: "App Name", submitOnChange: true)
            if(thisName) app.updateLabel("$thisName") else {if (lightSensor) app.updateSetting("thisName", "Is It Dark - $lightSensor")}
        }
    }
}

def installed() {
    infolog "Installed..."
    initialize()
}

def updated() {
    infolog "Updated..."
    unsubscribe()
    unschedule()
    initialize()
    if ( mySwitch.latestValue( "switch" ) == "on" ) {
        app.updateLabel("$thisName <span style=\"color:green;\">(LIGHT)</span>")
    } else {
        app.updateLabel("$thisName <span style=\"color:black;\">(DARK)</span>")
    }    
}

def initialize() {
    infolog "Initialized..."
    if (lightSensor) {
        subscribe(lightSensor, "illuminance", illuminanceHandler, [filterEvents: false])
        if (onlyRunDuringDay) {
            subscribe(location, "position", locationPositionChange)
            subscribe(location, "sunriseTime", sunriseSunsetTimeHandler)
            subscribe(location, "sunsetTime", sunriseSunsetTimeHandler)
            astroCheck()
        }
        if (lightSensor.currentIlluminance?.toInteger() >= illumLight) {
            debuglog "${lightSensor}" + "'s current reading is:" + lightSensor.currentIlluminance?.toInteger() + " which is higher than the current setting of: " + illumLight
            switchOn()
        } else {
            debuglog "${lightSensor}" + "'s current reading is:" + lightSensor.currentIlluminance?.toInteger() + " which is lower than the current setting of: " + illumLight
            switchOff()
        }
    }
    else {
        subscribe(location, "position", locationPositionChange)
        subscribe(location, "sunriseTime", sunriseSunsetTimeHandler)
        subscribe(location, "sunsetTime", sunriseSunsetTimeHandler)
        astroCheck()
    }
}

def locationPositionChange(evt) {
    infolog "locationChange()"
    astroCheck()
}

def sunriseSunsetTimeHandler(evt) {
    state.lastSunriseSunsetEvent = now()
    debuglog "($app.name)"
    astroCheck()
    def t = now()
    if (!lightSensor) {
        if (t < state.riseTime || t > state.setTime) {
            infolog "Sunset..."
            switchOff()
        } else {
            infolog "Sunrise..."
            switchOn()
        }   
    } else {
        illuminanceHandler()
    }
}

def illuminanceHandler(evt) {
    if (evt) debuglog "${evt.device} ${evt.name} value: ${evt.value}"
    
    if (onlyRunDuringDay) {
        if (timeBetweenSunriseSunset()) {
            checkIllumincation()
        } else {
            debuglog "Time between sunset and sunrise, turning switch off"
            switchOff()
        }
    } else {
        checkIllumincation()
    }
}

def checkIllumincation(){
    def crntLux = lightSensor.currentValue("illuminance").toInteger()
    
    if ((crntLux >= illumLight) && state.isItDark) {
        // Want to go LIGHT - reset timer on each qualifying event (true debounce)
        debuglog "Illum $crntLux >= $illumLight and currently dark"
        unschedule(switchOn)  // Cancel any pending switchOn
        if (delayMinutes) {
            infolog "$crntLux >= $illumLight, (re)starting $delayMinutes min debounce"
            runIn(delayMinutes*60, switchOn, [overwrite: true])
        } else {
            infolog "$crntLux >= $illumLight, switching immediately"
            switchOn()
        }
    } 
    
    if ((crntLux < illumDark) && !state.isItDark){
        // Want to go DARK - reset timer on each qualifying event (true debounce)
        debuglog "Illum $crntLux < $illumDark and currently light"
        unschedule(switchOff)  // Cancel any pending switchOff
        if (delayMinutes) {
            infolog "$crntLux < $illumDark, (re)starting $delayMinutes min debounce"
            runIn(delayMinutes*60, switchOff, [overwrite: true])
        } else {
            infolog "$crntLux < $illumDark, switching immediately"
            switchOff()
        }
    }
    
    // If illuminance moved back across threshold, cancel pending actions
    if (crntLux < illumLight && !state.isItDark) {
        debuglog "Illum dropped below light threshold while waiting - canceling pending switchOn"
        unschedule(switchOn)
    }
    if (crntLux >= illumDark && state.isItDark) {
        debuglog "Illum rose above dark threshold while waiting - canceling pending switchOff"
        unschedule(switchOff)
    }
}

def switchOff() {
    // Revalidate before switching
    if (lightSensor) {
        def crntLux = lightSensor.currentValue("illuminance")?.toInteger()
        if (crntLux >= illumDark) {
            infolog "Canceling switch off - illuminance is now $crntLux (>= $illumDark)"
            return
        }
    }
    
    // If only running during day, verify we're still in nighttime
    if (onlyRunDuringDay && timeBetweenSunriseSunset()) {
        infolog "Canceling switch off - now in daytime hours"
        return
    }
    
    if (mySwitch.currentValue("switch") == "on") {
        infolog "Turning switch ${mySwitch} off"
        mySwitch.off()
        state.isItDark = true  // Only set when switch actually flips
        app.updateLabel("$thisName <span style=\"color:black;\">(DARK)</span>")
    } else {
        debuglog "Switch already off"
        state.isItDark = true  // Ensure state matches reality
    }
}

def switchOn() {
    // Revalidate before switching
    if (lightSensor) {
        def crntLux = lightSensor.currentValue("illuminance")?.toInteger()
        if (crntLux < illumLight) {
            infolog "Canceling switch on - illuminance is now $crntLux (< $illumLight)"
            return
        }
    }
    
    // If only running during day, verify we're still in daytime
    if (onlyRunDuringDay && !timeBetweenSunriseSunset()) {
        infolog "Canceling switch on - now in nighttime hours"
        return
    }
    
    if (mySwitch.currentValue("switch") == "off") {
        infolog "Turning switch ${mySwitch} on"
        mySwitch.on()
        state.isItDark = false  // Only set when switch actually flips
        app.updateLabel("$thisName <span style=\"color:green;\">(LIGHT)</span>")
    } else {
        debuglog "Switch already on"
        state.isItDark = false  // Ensure state matches reality
    }
}

def astroCheck() {
    infolog "astrocheck starting"
    def s = getSunriseAndSunset(zipCode: zipCode, sunriseOffset: sunriseOffset, sunsetOffset: sunsetOffset)
    infolog s
    state.riseTime = s.sunrise.time
    state.setTime = s.sunset.time
    debuglog "rise: ${new Date(state.riseTime)}($state.riseTime), set: ${new Date(state.setTime)}($state.setTime)"
}

def timeBetweenSunriseSunset(){
    def t = now()
    if (t < state.riseTime || t > state.setTime) {
        return false  
    } else { 
        return true
    }
}

private getSunriseOffset() {
    sunriseOffsetValue ? (sunriseOffsetDir == "Before" ? "-$sunriseOffsetValue" : sunriseOffsetValue) : null
}

private getSunsetOffset() {
    sunsetOffsetValue ? (sunsetOffsetDir == "Before" ? "-$sunsetOffsetValue" : sunsetOffsetValue) : null
}

def getFormat(type, myText=""){			// Modified from @Stephack Code   
    if(type == "header-green") return "<div style='color:#ffffff;font-weight: bold;background-color:#81BC00;border: 1px solid;box-shadow: 2px 3px #A9A9A9'>${myText}</div>"
    if(type == "line") return "<hr style='background-color:#1A77C9; height: 1px; border: 0;'>"
    if(type == "title") return "<h2 style='color:#1A77C9;font-weight: bold'>${myText}</h2>"
    if(type == "title2") return "<div style='color:#1A77C9;font-weight: bold'>${myText}</div>"
}

def debuglog(statement)
{   
    def logL = 0
    if (logLevel) logL = logLevel.toInteger()
    if (logL == 0) {return}//bail
    else if (logL >= 2)
    {
        log.debug("$thisName: " + statement)
    }
}

def infolog(statement)
{       
    def logL = 0
    if (logLevel) logL = logLevel.toInteger()
    if (logL == 0) {return}//bail
    else if (logL >= 1)
    {
        log.info("$thisName: " + statement)
    }
}

def getLogLevels(){
    return [["0":"None"],["1":"Running"],["2":"NeedHelp"]]
}
