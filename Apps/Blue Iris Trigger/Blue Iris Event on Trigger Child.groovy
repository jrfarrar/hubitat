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
  iconX3Url: ""
  importUrl: "https://raw.githubusercontent.com/jrfarrar/hubitat/master/Apps/Blue%20Iris%20Trigger/Blue%20Iris%20Event%20on%20Trigger%20Child.groovy"
)


preferences {
page(name: "pageConfig")
}

def pageConfig() {
dynamicPage(name: "", title: "", install: true, uninstall: true, refreshInterval:0) { 
    
  section("") {
	      input "contactSensor", "capability.contactSensor", title: "Contact Sensor", submitOnChange: true, multiple: true 
      } 
    section("Commands to Send"){
          input "BIcommand1", "text", title: "BlueIris http command to send", required: true
          input "BIcommand2", "text", title: "BlueIris http command to send"
          input "BIcommand3", "text", title: "BlueIris http command to send"
          input "BIcommand4", "text", title: "BlueIris http command to send"
    }
    section("Commands to Send after Delay in Min"){
          input "Delay", "number", title: "Delay (Min)", description: "Delay in Minutes"
          input "BIcommand1ad", "text", title: "BlueIris http command to send"
          input "BIcommand2ad", "text", title: "BlueIris http command to send"
          input "BIcommand3ad", "text", title: "BlueIris http command to send"
          input "BIcommand4ad", "text", title: "BlueIris http command to send"
    }
    section("Logging"){                       
			input(name: "logLevel",title: "IDE logging level",multiple: false,required: true,type: "enum",options: getLogLevels(),submitOnChange : false,defaultValue : "10")
    }
    section("App Name"){
        input "thisName", "text", title: "App Name", submitOnChange: true
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
	subscribe(contactSensor, "contact.open", eventHandler)
	subscribe(contactSensor, "contact.closed", eventHandler)
}

def eventHandler(evt) {
    debuglog "eventHandler"
	sendHttp()
}

def sendHttp() {
    debuglog "sendHttp"    
    infolog "${contactSensor} - Triggered"
    
    debuglog "Command #1"
    def myString1 = "${settings.BIcommand1}"
    myString1 = myString1?.trim()
    if (myString1 != "null") {
        def params = [uri: "${settings.BIcommand1}"]
        try {
        httpGet(params) {
            resp -> resp.headers.each {
                debuglog "Response: ${it.name} : ${it.value}"
            }
        } // End try    
        } catch (e) {
            log.error "something went wrong: $e"
            }
     }
    
    debuglog "Command #2"
    def myString2 = "${settings.BIcommand2}"
    myString2 = myString2?.trim()
    if (myString2 != "null") {
        def params = [uri: "${settings.BIcommand2}"]
        try {
        httpGet(params) {
            resp -> resp.headers.each {
                debuglog "Response: ${it.name} : ${it.value}"
            }
        } // End try    
        } catch (e) {
            log.error "something went wrong: $e"
            }
     } else {
        debuglog "no command 2"
    }
    
    debuglog "Command #3"
    def myString3 = "${settings.BIcommand3}"
    myString3 = myString3?.trim()
    if (myString3 != "null") {
        def params = [uri: "${settings.BIcommand3}"]
        try {
        httpGet(params) {
            resp -> resp.headers.each {
                debuglog "Response: ${it.name} : ${it.value}"
            }
        } // End try    
        } catch (e) {
            log.error "something went wrong: $e"
            }
     } else {
        debuglog "no command 3"
    }
    
    debuglog "Command #4"
    def myString4 = "${settings.BIcommand4}"
    myString4 = myString4?.trim()
    if (myString4 != "null") {
        def params = [uri: "${settings.BIcommand4}"]
        try {
        httpGet(params) {
            resp -> resp.headers.each {
                debuglog "Response: ${it.name} : ${it.value}"
            }
        } // End try    
        } catch (e) {
            log.error "something went wrong: $e"
            }
    } else {
        debuglog "no command 4"
    }
    
    
    
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
                debuglog "Response: ${it.name} : ${it.value}"
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
                debuglog "Response: ${it.name} : ${it.value}"
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
                debuglog "Response: ${it.name} : ${it.value}"
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
                debuglog "Response: ${it.name} : ${it.value}"
            }
        } // End try    
        } catch (e) {
            log.error "something went wrong: $e"
            }
    } else {
        debuglog "no command 4 after delay"
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
