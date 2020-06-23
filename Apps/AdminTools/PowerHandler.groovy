/*
 *	Power Handler
 *
 *	Author: J.R. Farrar
 * 
 *  V 1.0 - 2020-06-23 
 * 
 */

definition(
  name: "Power Handler Child",
  namespace: "jrfarrar",
  author: "J.R. Farrar",
  description: "Power Handler",
  parent: "jrfarrar:Admin tools",    
  iconUrl: "",
  iconX2Url: "",
  iconX3Url: "",
  importUrl: "https://raw.githubusercontent.com/jrfarrar/hubitat/master/Apps/AdminTools/PowerHandler.groovy"
)


preferences {
page(name: "pageConfig")
}

def pageConfig() {
dynamicPage(name: "", title: "", install: true, uninstall: true, refreshInterval:0) { 
    
  section(getFormat("header-green", "Power reporting device")) {
		  paragraph "- Power reporting device to act on."
	      input (name: "pwrClamp", type: "capability.powerMeter", title: "Power Meter", submitOnChange: true, required: true)
  }
    section(getFormat("header-green", "Do these things when power is above")) {
          input (name: "watts", type: "number", title: "Greater than this many Watts is considered on", required: true)
          input (name: "daysSwitch", type: "bool", defaultValue: "false", title: "Turn on a switch if this hasn't turned on in x days?", submitOnChange: true)
          if(daysSwitch) input (name: "xdays", type: "number", title: "How many days without turning on?")
          if(daysSwitch) input (name: "tempSwitch", type: "capability.switch", title: "Switch to turn on if meter hasn't run in X days?", submitOnChange: true, multiple: false)
          input (name: "onTooLongOn", type: "bool", defaultValue: "false", title: "Turn ON a switch if this power meter stays above threshold (${watts} watts)?", submitOnChange: true)
          if(onTooLongOn) input (name: "tooLongOn", type: "number", title: "How many minutes is too many for the device to be running?", defaultValue: 5)
          if(onTooLongOn) input (name: "tooLongSwitchOn", type: "capability.switch", title: "Switch to turn on if running longer than ${tooLongOn} minutes?", submitOnChange: true,  multiple: false)
          input (name: "onTooLongOff", type: "bool", defaultValue: "false", title: "Turn OFF a switch if this power meter stays above threshold (${watts} watts)?", submitOnChange: true)
          if(onTooLongOff) input (name: "tooLongOff", type: "number", title: "How many minutes is too many for the device to be running?", defaultValue: 5)
          if(onTooLongOff) input (name: "tooLongSwitchOff", type: "capability.switch", title: "Switch to turn OFF if running longer than ${tooLongOff} minutes?", submitOnChange: true,  multiple: false)

      //input (name: "useReverse", type: "bool", defaultValue: "false", title: "Use in reverse? (turn switch off when power goes above)", submitOnChange: true)
    }
    
    /*
    section(getFormat("header-green", "RESTRICTIONS")) {
		  paragraph "- These restrict the above triggers based on what's set here."
          input (name: "mySwitch", type: "capability.switch", title: "Switch to restrict running", submitOnChange: true, multiple: false)
          input (name: "onSwitch", type: "bool", defaultValue: "false", title: "Only run if switch on?", submitOnChange: true)
          input (name: "offSwitch", type: "bool", defaultValue: "false", title: "Only run if switch off?", submitOnChange: true)
          input (name: "noRunModes", type: "mode", title: "Select Mode NOT to run in", submitOnChange: true, multiple: true)
    }
    */
    section(getFormat("header-green", "LOGGING")){                       
			input(name: "logLevel",title: "IDE logging level",multiple: false,required: true,type: "enum",options: getLogLevels(),submitOnChange : false,defaultValue : "1")
    }
    section(getFormat("header-green", "APP NAME")){
        input (name: "thisName", type: "text", title: "App Name", submitOnChange: true)
			//if(thisName) app.updateLabel("$thisName") else app.updateSetting("thisName", "Temperature turn on/off")
        if(thisName) app.updateLabel("$thisName") else {if (pwrClamp) app.updateSetting("thisName", "Power Handler - ${pwrClamp}")}
    }
  }  
} 

def installed() {
  infolog "installed"
  initialize()
}

def updated() {
    infolog "updated"
    unsubscribe()
    subscribeToEvents()
    //initialize()
}

def initialize() {
  infolog "initialize"
  //unschedule all jobs and unsubscribe all events
  unschedule() 
  unsubscribe()
  //subscribe to events for chosen devices
  subscribeToEvents()
  state.running = false  
}

def uninstalled() {
  unschedule()
  unsubscribe()
  infolog "uninstalled"
}

def subscribeToEvents() {
    if (pwrClamp) {
    subscribe(pwrClamp, "power", powerHandler)
    }
}

def powerHandler(evt) {
    dblePower = Double.parseDouble(evt.value)
    rndPower = dblePower.round(0)
    debuglog "power: $rndPower, $evt.device"

    if (rndPower > watts) {
        if (state.running == false) {
            unschedule(itsBeenSevenDays)
            state.lastrun = new Date().format("yyyy-MM-dd HH:mm:ss")
            state.running = true
            infolog "turned on, time: " + state.lastrun
            // see if we need to turn things on or off
            if (onTooLongOn) runIn(60 * tooLongOn.toInteger(), tooLongOnHandler)
            if (onTooLongOff) runIn(60 * tooLongOff.toInteger(), tooLongOffHandler)                

            // see if we need to check if this hasn't run in x days
            if (daysSwitch) {
                timeToRun = new Date() + xdays.toInteger()
                debuglog "Time to run: " + timeToRun
                runOnce(timeToRun, itsBeenSevenDays)
                if ( tempSwitch.latestValue( "switch" ) != "off" ) {
                    infolog "Turning off " + tempSwitch
                    tempSwitch.off()
                }
            }
        }
    } else {
        if (state.running) {
            state.running = false
            state.lastoff = new Date().format("yyyy-MM-dd HH:mm:ss")
            infolog "turned off, time: " + state.lastoff
            unschedule(tooLongOnHandler)
            unschedule(tooLongOffHandler)
            if ( tooLongSwitch.latestValue( "switch" ) != "off" ) {
                tooLongSwitch.off()
            }
        }
    }
}
 
void itsBeenSevenDays() {
    //turn on switch here
    if ( tempSwitch.latestValue( "switch" ) != "on" ) {
        log.warn "It's been ${xdays} days since power has been on, turning on switch"
        tempSwitch.on()
    }  
}

void tooLongOnHandler() {
        log.warn "Alert! Running longer than allowed! Turning ON switch - ${tooLongSwitchOn}"
        tooLongSwitchOn.on() 
}
void tooLongOffHandler() {
        log.warn "Alert! Running longer than allowed! Turning OFF switch - ${tooLongSwitchOff}"
        tooLongSwitchOff.off()     
}
/*
def powerHandler(evt) {
    dblePower = Double.parseDouble(evt.value)
    rndPower = dblePower.round(0)
    debuglog "power: $rndPower, $evt.device"
if (canWeRun()) {    
    if (useReverse) {    
        if (rndPower <= watts) {
            if ( tempSwitch.latestValue( "switch" ) != "on" ) {
                infolog "Turning on"
                tempSwitch.on()
            }
        }
        else if (rndPower > watts ) {
            if ( tempSwitch.latestValue( "switch" ) != "off" ) {
                infolog "Turning off"
                tempSwitch.off()
            }  
        }
        else {
            debuglog "Current power in watts is ${evt.value}"
        }        
    } else {
        if (rndPower <= watts) {
            if ( tempSwitch.latestValue( "switch" ) != "off" ) {
                infolog "Turning off"
                tempSwitch.off()
            }
        }
        else if (rndPower > watts ) {
            if ( tempSwitch.latestValue( "switch" ) != "on" ) {
                infolog "Turning on"
                tempSwitch.on()
            }  
        }
        else {
            debuglog "Current power in watts is ${evt.value}"
        }        
    }
 }
}
*/

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
