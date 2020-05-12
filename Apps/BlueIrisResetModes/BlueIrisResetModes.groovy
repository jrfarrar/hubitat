/*
 *	BlueIris RESET BI Modes
 *
 *	Author: J.R. Farrar
 * 
 * 
 * 
 */

definition(
  name: "BlueIris Reset Modes",
  namespace: "jrfarrar",
  author: "J.R. Farrar",
  description: "Reset BlueIris modes in case it reboots",
  iconUrl: "",
  iconX2Url: "",
  iconX3Url: "",
  importUrl: "https://raw.githubusercontent.com/jrfarrar/hubitat/master/Apps/BlueIrisResetModes/BlueIrisResetModes.groovy"
)


preferences {
page(name: "pageConfig")
}

def pageConfig() {
dynamicPage(name: "", title: "", install: true, uninstall: true, refreshInterval:0) { 
   
	      section("Reset Mode after BlurIris restart"){
          paragraph "<b>Notes:</b>"
		  paragraph "- Sometimes BlueIris restarts when that happens it defaults to away mode which is not always correct. This checks for proper mode every x minutes"
          input "darkOutside", "capability.switch", title: "Is it dark outside switch(off = dark)", submitOnChange: true, required: true
          input (name: "awayModes", type: "mode", title: "Modes when BI should be in AWAY mode", submitOnChange: true, multiple: true)
          input "BItimer", "number", title: "Reset mode every how many Minutes?", description: "Delay in Minutes", defaultValue : "30"
          input "BIcommandNight", "text", title: "BlueIris http command to send for Night Mode"
          input "BIcommandAway", "text", title: "BlueIris http command to send for Away mode"
          input "BIcommandDay", "text", title: "BlueIris http command to send for Home mode"
    }
    section("Logging"){                       
			input(name: "logLevel",title: "IDE logging level",multiple: false,required: true,type: "enum",options: getLogLevels(),submitOnChange : false,defaultValue : "10")
    }
    section("App Name"){
        input "thisName", "text", title: "App Name", submitOnChange: true
			if(thisName) app.updateLabel("$thisName") else app.updateSetting("thisName", "BlueIris Mode Setter")
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
  //subscribeToEvents()
    
  if (settings.BItimer) {
        debuglog "Delay time in Min: ${settings.BItimer}"
        def int delayTime = settings.BItimer * 60
        runIn(delayTime,'goTime')
    } else {
        debuglog "No Delay time specified...doing nothing"
    }
}

def uninstalled() {
  unschedule()
  unsubscribe()
  log.debug "uninstalled"
}

def subscribeToEvents() {
//    subscribeLocation(location, "mode", eventHandler)
//    subscribe(darkOutside, "switch.on", eventHandler)
//    subscribe(darkOutside, "switch.off", eventHandler)
}

def eventHandler(evt) {
    debuglog "eventHandler"

}
    
def goTime() {
    debuglog "SWITCH " + darkOutside + " IS " + darkOutside.currentValue("switch")
    
    if (darkOutside.currentValue("switch") == "off"){
            infolog "Setting BlueIris to Night mode"
            def myString1 = "${settings.BIcommandNight}"
            myString1 = myString1?.trim()
            if (myString1 != "null") {
                def params = [uri: "${settings.BIcommandNight}"]
                try {
                    httpGet(params) {
                    resp -> resp.headers.each {
                    debuglog "Response: ${it.name} : ${it.value}"
                    }
                } // End try    
            } catch (e) {log.error "something went wrong: $e"}
            }
    } else if (awayModes.contains(location.mode)) {
            infolog "Setting BlueIris to AWAY mode"
            def myString1 = "${settings.BIcommandAway}"
            myString1 = myString1?.trim()
            if (myString1 != "null") {
                def params = [uri: "${settings.BIcommandAway}"]
                try {
                    httpGet(params) {
                    resp -> resp.headers.each {
                    debuglog "Response: ${it.name} : ${it.value}"
                    }
                } // End try    
            } catch (e) {log.error "something went wrong: $e"}
         }   
    } else {
            infolog "Setting BlueIris to Home mode"
            def myString1 = "${settings.BIcommandDay}"
            myString1 = myString1?.trim()
            if (myString1 != "null") {
                def params = [uri: "${settings.BIcommandDay}"]
                try {
                    httpGet(params) {
                    resp -> resp.headers.each {
                    debuglog "Response: ${it.name} : ${it.value}"
                    }
                } // End try    
            } catch (e) {log.error "something went wrong: $e"}
         }           
    }    

    if (settings.BItimer) {
        debuglog "Delay time in Min: ${settings.BItimer}"
        def int delayTime = settings.BItimer * 60
        runIn(delayTime,'goTime')
    } else {
        debuglog "No Delay time specified...doing nothing"
    }
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
