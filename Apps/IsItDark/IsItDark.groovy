/**
 *
 *
 *  Light/Dark Switch
 *
 *
 */
definition(
    name: "Is It Dark",
    namespace: "jrfarrar",
    author: "J.R. Farrar",
    description: "Turns off a switch when illuminance is below a threshold.",
    category: "Convenience",
    iconUrl: "",
    iconX2Url: "",
    importUrl: ""
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
		input "lightSensor", "capability.illuminanceMeasurement", title: "Illuminance Sensor to use? $lightSensor.currentIlluminance"
        input "illumLight", "number", title: "Illuminance value to act on for light? (>= this value is light)"
        input "illumDark", "number", title: "Illuminance value to act on for dark? (< this value is dark)"        
        input "delayMinutes", "number", title: "Minutes to wait before flipping switch(debounce)?"
	}
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
    section(getFormat("header-green", "LOGGING")){                       
			input(name: "logLevel",title: "IDE logging level",multiple: false,required: true,type: "enum",options: getLogLevels(),submitOnChange : false,defaultValue : "1")
    }
    section(getFormat("header-green", "APP NAME")){
        input (name: "thisName", type: "text", title: "App Name", submitOnChange: true)
			if(thisName) app.updateLabel("$thisName") else app.updateSetting("thisName", "Is It Dark")
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
        if (lightSensor.currentIlluminance?.toInteger() >= illumLight) {
            debuglog "current sensor higher than setting, setting to false"
            state.isItDark = false
        } else {
            debuglog "current sensor lower than setting, setting to true"
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
            infolog "Sunset turning switch off"
            switchOff()
        } else {
            infolog "Sunrise turning switch on"
            switchOn()
        }   
    }
}


def illuminanceHandler(evt) {
	debuglog "$evt.name: $evt.value, isItDark: $state.isItDark"
    //infolog "evt.integerValue= ${evt.integerValue}"
    //infolog "delay time= ${delayTime}"
    
    if ((evt.integerValue >= illumLight) && state.isItDark) {
        debuglog "illum greater than setting and IsItDark is true"
        debuglog "unschedule"
        unschedule()
        if (delayMinutes) {
            infolog "$evt.name: $evt.value went above $illumLight delay for $delayMinutes min"
            runIn(delayMinutes*60, switchOn, [overwrite: true])
        } else {
            switchOn()
        }
        state.isItDark = false
    } 
    if ((evt.integerValue < illumDark) && !state.isItDark){
        debuglog "illum less or equal to setting and IsItDark is false"
        debuglog "unschedule"
        unschedule()
        if (delayMinutes) {
            infolog "$evt.name: $evt.value went below $illumDark delay for $delayMinutes min"
            runIn(delayMinutes*60, switchOff, [overwrite: true])
        } else {
            switchOff()
        }
        state.isItDark = true
    }
}

def switchOff() {
    infolog "Turning switch off"
    mySwitch.off()
}
def switchOn() {
    infolog "Turning switch on"
    mySwitch.on()
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
