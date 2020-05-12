/*
 *	BlueIris Event on Trigger (Child)
 *
 *	Author: J.R. Farrar
 * 
 * 
 * 
 */

definition(
  name: "BlueIris Event on Trigger Child",
  namespace: "jrfarrar",
  author: "J.R. Farrar",
  description: "Trigger BlueIris for an Event",
  parent: "jrfarrar:BlueIris Event on Trigger", 
  iconUrl: "",
  iconX2Url: "",
  iconX3Url: "",
  importUrl: "https://raw.githubusercontent.com/jrfarrar/hubitat/master/Apps/BlueIrisEventOnTrigger/BlueIrisEventOnTriggerChild.groovy"
)


preferences {
page(name: "pageConfig")
}

def pageConfig() {
dynamicPage(name: "", title: "", install: true, uninstall: true, refreshInterval:0) { 
    
  section(getFormat("header-green", "TRIGGERS")) {
		  paragraph "- These are the triggers that will trigger the below commands. One or Multiples can be used."
	      input (name: "contactSensor", type: "capability.contactSensor", title: "Contact Sensors", submitOnChange: true, multiple: true)
          input (name: "motionSensor", type: "capability.motionSensor", title: "Motion Sensors", submitOnChange: true, multiple: true)
          input (name: "runSwitch", type: "capability.switch", title: "Switch to use as trigger", submitOnChange: true, multiple: false)
          input (name: "useRunSwitchOn", type: "bool", defaultValue: "false", title: "Use the switch above as a trigger for ON?", submitOnChange: true)
          input (name: "useRunSwitchOff", type: "bool", defaultValue: "false", title: "Use the switch above as a trigger for OFF?", submitOnChange: true) 
          input (name: "runModes", type: "mode", title: "When Modes changes to these modes - run commands", submitOnChange: true, multiple: true)
          input (name: "sunriseEnable", type: "bool", defaultValue: "false", title: "Run at sunrise?", submitOnChange: true)
          input (name: "sunsetEnable", type: "bool", defaultValue: "false", title: "Run at sunset?", submitOnChange: true)
    }
    section(getFormat("header-green", "RESTRICTIONS")) {
		  paragraph "- These restrict the above triggers based on what's set here."
          input (name: "darkSwitch", type: "capability.switch", title: "Day/Night Switch Determines when it's dark out(Dark when on)", submitOnChange: true, multiple: false)
          input (name: "daySwitch", type: "bool", defaultValue: "false", title: "Only run if Daylight?", submitOnChange: true)
          input (name: "nightSwitch", type: "bool", defaultValue: "false", title: "Only run if Dark?", submitOnChange: true)
          input (name: "noRunModes", type: "mode", title: "Select Mode NOT to run in", submitOnChange: true, multiple: true)
    }
    section(getFormat("header-green", "COMMANDS TO SEND")){
		  paragraph "- These are the HTTP commands to send when triggered."          
          input (name: "BIcommand1", type: "text", title: "BlueIris http command to send(http://192.168.1.1:81/admin....)", required: true)
          input (name: "BIcommand2", type: "text", title: "BlueIris http command to send")
          input (name: "BIcommand3", type: "text", title: "BlueIris http command to send")
          input (name: "BIcommand4", type: "text", title: "BlueIris http command to send")
    }
    section(getFormat("header-green", "COMMANDS TO SEND AFTER A DELAY")){
		  paragraph "- These are additional commands you can send after a delay from the first set. Useful for resetting say a PTZ camera."           
          input (name: "Delay", type: "number", title: "Delay (Min)", description: "Delay in Minutes")
          input (name: "BIcommand1ad", type: "text", title: "BlueIris http command to send")
          input (name: "BIcommand2ad", type: "text", title: "BlueIris http command to send")
          input (name: "BIcommand3ad", type: "text", title: "BlueIris http command to send")
          input (name: "BIcommand4ad", type: "text", title: "BlueIris http command to send")
    }
    section(getFormat("header-green", "LOGGING")){                       
			input(name: "logLevel",title: "IDE logging level",multiple: false,required: true,type: "enum",options: getLogLevels(),submitOnChange : false,defaultValue : "1")
    }
    section(getFormat("header-green", "APP NAME")){
        input (name: "thisName", type: "text", title: "App Name", submitOnChange: true)
			if(thisName) app.updateLabel("$thisName") else app.updateSetting("thisName", "BIT - Child")
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
    if (contactSensor) {
	subscribe(contactSensor, "contact.open", eventHandler)
    //subscribe(contactSensor, "contact.closed", eventHandler)    
    }
    if (motionSensor) {
    subscribe(motionSensor, "motion.active", eventHandler)
    }
    if (useRunSwitchOn && runSwitch) {
        subscribe(runSwitch, "switch.on", eventHandler)
    }
    if (useRunSwitchOff && runSwitch) {
        subscribe(runSwitch, "switch.off", eventHandler)
    }
    if (sunriseEnable) {
        subscribe(location, "sunrise", sunHandler)    
    }
    if (sunsetEnable) {
        subscribe(location, "sunset", sunHandler)    
    }
    if (runModes) {
        subscribe(location, "mode", modeHandler)
    }
}

def eventHandler(evt) {
    debuglog "eventHandler called"
    if (canWeRun()) {
        infolog "Switch or sensor: " + evt.device + " triggered"
        sendHttp()
    } else {
        infolog "Switch or sensor: " + evt.device + ": Restrictions - not running"
    }
}

def sunHandler(evt){
        if (sunriseEnable){
            if (canWeRun()) {
                infolog "Sunrise - running commands" 
                sendHttp()
            } else {
                infolog "Sunrise - Restrictions - not running"
            }
         }

         if (sunsetEnable) { 
            if (canWeRun()) {
                infolog "Sunset - running commands"
                sendHttp()
            } else {
                 infolog "Sunset - Restrictions - not running"
            }
         }
}

def modeHandler(evt){
    if (runModes.contains(location.mode)) {
        if (canWeRun()) {
            infolog "Mode: " + location.mode + " - running commands"
            sendHttp()
        } else {
            infolog "Mode: " + location.mode + "Restrictions - not running"
        }
    }
}

def sendHttp() {
    debuglog "sendHttp"
      
    def myString1 = "${settings.BIcommand1}"
    myString1 = myString1?.trim()
    if (myString1 != "null") {
        def params = [uri: "${settings.BIcommand1}"]
        try {
            debuglog "Command #1"    
            httpGet(params) {
                resp -> resp.headers.each {
                    //debuglog "Response: ${it.name} : ${it.value}"
                    }
                } // End try    
            } catch (e) {
                log.error "something went wrong: $e"
            }
     }
    
    def myString2 = "${settings.BIcommand2}"
    myString2 = myString2?.trim()
    if (myString2 != "null") {
        def params = [uri: "${settings.BIcommand2}"]
        try {
            debuglog "Command #2"
            httpGet(params) {
                resp -> resp.headers.each {
                    debuglog "Response: ${it.name} : ${it.value}"
                    }
                }     
            } catch (e) {
                log.error "something went wrong: $e"
            }
     } else {
        debuglog "no command 2"
    }

    def myString3 = "${settings.BIcommand3}"
    myString3 = myString3?.trim()
    if (myString3 != "null") {
        def params = [uri: "${settings.BIcommand3}"]
        try {
            debuglog "Command #3"
            httpGet(params) {
                resp -> resp.headers.each {
                    debuglog "Response: ${it.name} : ${it.value}"
                    }
                }    
            } catch (e) {
                log.error "something went wrong: $e"
            }
     } else {
        debuglog "no command 3"
    }
    
    def myString4 = "${settings.BIcommand4}"
    myString4 = myString4?.trim()
    if (myString4 != "null") {
        def params = [uri: "${settings.BIcommand4}"]
        try {
               debuglog "Command #4"
                httpGet(params) {
                    resp -> resp.headers.each {
                        debuglog "Response: ${it.name} : ${it.value}"
                    }
                }     
            } catch (e) {
                log.error "something went wrong: $e"
                }
    } else {
        debuglog "no command 4"
    }
    
// determine if there are delayed commands to run
    if (settings.Delay) {
        debuglog "Delay time in Min: ${settings.Delay}"
        def int delayTime = settings.Delay * 60
        debuglog "Delay time in sec: $delayTime"
        runIn(delayTime,'timeOut')
    } else {
        debuglog "No Delay time specified, skipping delay events"
    }
    
}

def timeOut() {
    debuglog "timeOut"

    debuglog "Command #1 after delay"
    def myString1ad = "${settings.BIcommand1ad}"
    myString1ad = myString1ad?.trim()
    if (myString1ad != "null") {
        def params = [uri: "${settings.BIcommand1ad}"]
        try {
        httpGet(params) {
            resp -> resp.headers.each {
                //debuglog "Response: ${it.name} : ${it.value}"
            }
        } // End try    
        } catch (e) {
            log.error "something went wrong: $e"
            }
     }else {
        debuglog "no command 1 after delay"
    }
    
    debuglog "Command #2 ad"
    def myString2ad = "${settings.BIcommand2ad}"
    myString2ad = myString2ad?.trim()
    if (myString2ad != "null") {
        def params = [uri: "${settings.BIcommand2ad}"]
        try {
        httpGet(params) {
            resp -> resp.headers.each {
                //debuglog "Response: ${it.name} : ${it.value}"
            }
        } // End try    
        } catch (e) {
            log.error "something went wrong: $e"
            }
     } else {
        debuglog "no command 2 after delay"
    }
    
    debuglog "Command #3 ad"
    def myString3ad = "${settings.BIcommand3ad}"
    myString3ad = myString3ad?.trim()
    if (myString3ad != "null") {
        def params = [uri: "${settings.BIcommand3ad}"]
        try {
        httpGet(params) {
            resp -> resp.headers.each {
                //debuglog "Response: ${it.name} : ${it.value}"
            }
        } // End try    
        } catch (e) {
            log.error "something went wrong: $e"
            }
     } else {
        debuglog "no command 3 after delay"
    }
    
    debuglog "Command #4 ad"
    def myString4ad = "${settings.BIcommand4ad}"
    myString4ad = myString4ad?.trim()
    if (myString4ad != "null") {
        def params = [uri: "${settings.BIcommand4ad}"]
        try {
        httpGet(params) {
            resp -> resp.headers.each {
                //debuglog "Response: ${it.name} : ${it.value}"
            }
        } // End try    
        } catch (e) {
            log.error "something went wrong: $e"
            }
    } else {
        debuglog "no command 4 after delay"
    }   
    
}

def canWeRun(){
    def isItDayLight
    def isItNightTime
    def isModeOk
    
    if (daySwitch && darkSwitch.currentValue('switch').contains('on')) {
        isItDayLight = false
        debuglog "Only Run during Daylight is on and Day/Night switch is ON(dark)"
    } else { 
        isItDayLight = true 
    }
    
    if (nightSwitch && darkSwitch.currentValue('switch').contains('off')) {
        isItNightTime = false
        debuglog "Only Run while Dark is on and Day/Night Switch is OFF(light)"
    } else { 
        isItNightTime = true 
    }

    if (noRunModes) {
        if (noRunModes.contains(location.mode)) {
            isModeOk = false
            debuglog "Mode " + location.mode + " - RESTRICTED MODE"
        } else {
            isModeOk = true
            debuglog "Mode " + location.mode + " - NOT RESTRICTED"
        }
    } else {
        isModeOk = true
        debuglog "No Restriced Modes Selected"
    }
    
    if (isItDayLight && isItNightTime && isModeOk) {
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
