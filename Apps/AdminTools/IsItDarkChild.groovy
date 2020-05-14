/**
 *
 *
 *  Light/Dark Switch
 *
 *
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
            state.isItDark = false
        } else {
            debuglog "${lightSensor}" + "'s current reading is:" + lightSensor.currentIlluminance?.toInteger() + " which is lower than the current setting of: " + illumLight
            switchOff()
            state.isItDark = true
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
            state.isItDark = true
        }
    } else {
        checkIllumincation()
    }
}

def checkIllumincation(){
    def crntLux = lightSensor.currentValue("illuminance").toInteger()
    //debuglog "Lightsensor value: " + crntLux
    
    if ((crntLux >= illumLight) && state.isItDark) {
        debuglog "illum greater than setting and IsItDark is true"
        debuglog "unschedule"
        unschedule()
        if (delayMinutes) {
            infolog "$crntLux went above $illumLight delay for $delayMinutes min"
            runIn(delayMinutes*60, switchOn, [overwrite: true])
        } else {
            infolog "$crntLux went above $illumLight"
            switchOn()
        }
        state.isItDark = false
    } 
    if ((crntLux < illumDark) && !state.isItDark){
        debuglog "illum less or equal to setting and IsItDark is false"
        debuglog "unschedule"
        unschedule()
        if (delayMinutes) {
            infolog "$crntLux went below $illumDark delay for $delayMinutes min"
            runIn(delayMinutes*60, switchOff, [overwrite: true])
        } else {
            infolog "$crntLux went below $illumDark"
            switchOff()
        }
        state.isItDark = true
    }    
}

def switchOff() {
    if (mySwitch.currentValue("switch") == "on") {
        infolog "Turning switch off"
        mySwitch.off()
    } else {
        debuglog "Switch already off"
    }
}
def switchOn() {
    if (mySwitch.currentValue("switch") == "off") {
        infolog "Turning switch on"
        mySwitch.on()
    } else {
        debuglog "Switch already on"
    }
}

/*
def delayTimeCheck(){
    if (delayMinutes) {
        return delayMinutes * 60
        infolog "Delaytime set to: ${delayTime}"
    } else {
        return 1
        infolog "Delaytime set to: ${delayTime}"
    }
}
*/

def astroCheck() {
    infolog "astrocheck starting"
	def s = getSunriseAndSunset(zipCode: zipCode, sunriseOffset: sunriseOffset, sunsetOffset: sunsetOffset)
    infolog s
	state.riseTime = s.sunrise.time
	state.setTime = s.sunset.time
	debuglog "rise: ${new Date(state.riseTime)}($state.riseTime), set: ${new Date(state.setTime)}($state.setTime)"
}

/*
private enabled() {
	def result
	if (lightSensor) {
		result = lightSensor.currentIlluminance?.toInteger() < 30
	}
	else {
		def t = now()
		result = t < state.riseTime || t > state.setTime
	}
	result
}
*/

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
		log.debug(statement)
	}
}
def infolog(statement)
{       
	def logL = 0
    if (logLevel) logL = logLevel.toInteger()
    if (logL == 0) {return}//bail
    else if (logL >= 1)
	{
		log.info(statement)
	}
}
def getLogLevels(){
    return [["0":"None"],["1":"Running"],["2":"NeedHelp"]]
}
