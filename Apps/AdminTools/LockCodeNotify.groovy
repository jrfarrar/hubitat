/*
 *	Lock Code Notify
 *
 *	Author: J.R. Farrar
 * 
 * 
 * 
 */

definition(
  name: "Lock Code Notify Child",
  namespace: "jrfarrar",
  author: "J.R. Farrar",
  description: "Lock Code Notify",
  parent: "jrfarrar:Admin tools",
  iconUrl: "",
  iconX2Url: "",
  iconX3Url: "",
  importUrl: ""
)

preferences {
page(name: "pageConfig")
}

def pageConfig() {
dynamicPage(name: "", title: "", install: true, uninstall: true, refreshInterval:0) { 
    
  section(getFormat("header-green", "Devices")) {
		  paragraph "Notify when door lock code is entered."
          input (name: "lock", type: "capability.lock", title: "Lock", submitOnChange: true, required: true)
          input ("sendPushMessage", "capability.notification", title: "Notification device", multiple: true, required: true, submitOnChange: true)
          //if (sendPushMessage) input ("message", "text", title: "Message to send?", required: false)
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
	    if(thisName) app.updateLabel("$thisName") else app.updateSetting("thisName", "Lock Code Notify")
        //if(state.appname) app.updateLabel("${state.appname}") else {if (dehumidifierSwitch) app.updateSetting("thisName", "Humidity Control - $dehumidifierSwitch")}
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
    if(lock) {
    //subscribe(lock, "lastCodeName", lockHandler, ["filterEvents": false])
    subscribe(lock, "lock.unlocked", lockHandler)    
    }
}

    //debuglog "EVENT: $evt
    //debuglog "Value: $evt.value"
    //codes = lock.currentValue("lockCodes")
    //debuglog "Codes: $codes"
    //debuglog "-------------------evt.value: $evt.value"
    //debuglog "-------------------evt.type: $evt.type"

def lockHandler(evt) {
    debuglog "Unlock event: ${evt.name} : ${evt.descriptionText}"
    lastName = lock.currentValue("lastCodeName")
    //myResult = evt.descriptionText.endsWith('command [digital]')
    if (evt.descriptionText.endsWith('thumbturn [physical]') || evt.descriptionText.endsWith('command [digital]')) {
        infolog "$lock.displayName was unlocked manually or electronically"
    } else {
        infolog "$lock.displayName was unlocked by CODE: $lastName"
        sendPushMessage.deviceNotification("$lock.displayName was unlocked by: $lastName")
        //sendPushMessage.deviceNotification(message)
        app.updateLabel("$thisName <span style=\"color:black;\">(${lastName})</span>")
    }
}



/*
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

*/

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
