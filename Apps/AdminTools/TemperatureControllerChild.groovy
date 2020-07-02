/*
 *	Temperature Turn ON/OFF
 *
 *	Author: J.R. Farrar
 * 
 * 
 * 
 */

definition(
  name: "Temperature Controller Child",
  namespace: "jrfarrar",
  author: "J.R. Farrar",
  description: "Temperature Turn ON/OFF",
  parent: "jrfarrar:Admin tools",    
  iconUrl: "",
  iconX2Url: "",
  iconX3Url: "",
  importUrl: "https://raw.githubusercontent.com/jrfarrar/hubitat/master/Apps/AdminTools/TemperatureControllerChild.groovy"
)


preferences {
page(name: "pageConfig")
}

def pageConfig() {
dynamicPage(name: "", title: "", install: true, uninstall: true, refreshInterval:0) { 
    
  section(getFormat("header-green", "Devices")) {
		  paragraph "- Default is as a Heater. Use toggle below to use as Cooler."
	      input (name: "tempSensor", type: "capability.temperatureMeasurement", title: "Temperature Sensor", submitOnChange: true)
          input (name: "tempSwitch", type: "capability.switch", title: "Switch to turn on/off", submitOnChange: true, multiple: false)
          input (name: "desiredTemp", type: "number", title: "Desired Temperature?")
          input (name: "useAsCool", type: "bool", defaultValue: "false", title: "Use in reverse? (turn switch on when temp goes above)", submitOnChange: true)
    }
    section(getFormat("header-green", "RESTRICTIONS")) {
		  paragraph "- These restrict the above triggers based on what's set here."
          input (name: "mySwitch", type: "capability.switch", title: "Switch to restrict running", submitOnChange: true, multiple: false)
          input (name: "onSwitch", type: "bool", defaultValue: "false", title: "Only run if switch on?", submitOnChange: true)
          input (name: "offSwitch", type: "bool", defaultValue: "false", title: "Only run if switch off?", submitOnChange: true)
          input (name: "noRunModes", type: "mode", title: "Select Mode NOT to run in", submitOnChange: true, multiple: true)
    }
    section(getFormat("header-green", "LOGGING")){                       
			input(name: "logLevel",title: "IDE logging level",multiple: false,required: true,type: "enum",options: getLogLevels(),submitOnChange : false,defaultValue : "1")
    }
    section(getFormat("header-green", "APP NAME")){
        input (name: "thisName", type: "text", title: "App Name", submitOnChange: true)
			//if(thisName) app.updateLabel("$thisName") else app.updateSetting("thisName", "Temperature turn on/off")
        if(thisName) app.updateLabel("$thisName") else {if (tempSwitch) app.updateSetting("thisName", "Temp Control - $tempSwitch")}
    }
  }  
} 

def installed() {
  infolog "installed"
  initialize()
}

def updated() {
    infolog "updated"
    initialize()
    if ( tempSwitch.latestValue( "switch" ) == "on" ) {
        app.updateLabel("$thisName <span style=\"color:green;\">(ON)</span>")
    } else {
        app.updateLabel("$thisName <span style=\"color:red;\">(OFF)</span>")
    }
}

def initialize() {
  infolog "initialize"
  //unschedule all jobs and unsubscribe all events
  unschedule() 
  unsubscribe()
  //subscribe to events for chosen devices
  subscribeToEvents()
}

def uninstalled() {
  unschedule()
  unsubscribe()
  log.debug "uninstalled"
}

def subscribeToEvents() {
    if (tempSensor) {
    subscribe(tempSensor, "temperature", temperatureHandler)
    }
}

def temperatureHandler(evt) {
    debuglog "temperature: $evt.value, $evt.device"
if (canWeRun()) {    
    if (useAsCool) {    
        if (Double.parseDouble(evt.value.replace("%", "")) <= desiredTemp) {
            if ( tempSwitch.latestValue( "switch" ) != "off" ) {
                infolog "Turning Cooler off"
                tempSwitch.off()
                app.updateLabel("$thisName <span style=\"color:red;\">(OFF)</span>")
            }
        }
        else if (Double.parseDouble(evt.value.replace("%", "")) > desiredTemp ) {
            if ( tempSwitch.latestValue( "switch" ) != "on" ) {
                infolog "Turning Cooler on"
                tempSwitch.on()
                app.updateLabel("$thisName <span style=\"color:green;\">(ON)</span>")
            }  
        }
        else {
            debuglog "Current temp is ${evt.value}"
        }
    } else {
        //useing as a humidifier
        if (Double.parseDouble(evt.value.replace("%", "")) <= desiredTemp) {
            if ( tempSwitch.latestValue( "switch" ) != "on" ) {
                infolog "Turning heater on"
                tempSwitch.on()
                app.updateLabel("$thisName <span style=\"color:green;\">(ON)</span>")
            }
        }
        else if (Double.parseDouble(evt.value.replace("%", "")) > desiredTemp ) {
            if ( tempSwitch.latestValue( "switch" ) != "off" ) {
                infolog "Turning heater off"
                tempSwitch.off()
                app.updateLabel("$thisName <span style=\"color:red;\">(OFF)</span>")
            }  
        }
        else {
            debuglog "Current temp is ${evt.value}"
        }        
    }
 }
}


def canWeRun(){
    def isItOn
    def isItOff
    def isModeOk

    if (onSwitch && mySwitch.currentValue('switch').contains('off')) {
        isItOn = false
        debuglog "Only Run when switch is ON and it's OFF"
    } else { 
        isItOn = true 
    }

    if (offSwitch && mySwitch.currentValue('switch').contains('on')) {
        isItOff = false
        debuglog "Only Run when switch is off and it's ON"
    } else { 
        isItOff = true 
    }

    if (noRunModes) {
        if (noRunModes.contains(location.mode)) {
            isModeOk = false
            debuglog "Mode " + location.mode + " - RESTRICED MODE"
        } else {
            isModeOk = true
            debuglog "Mode " + location.mode + " - NOT RESTRICED"
        }
    } else {
        isModeOk = true
        debuglog "No Restriced Modes Selected"
    }
    
    if (isItOff && isItOn && isModeOk) {
        return true
    } else {
        return false
    }
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
