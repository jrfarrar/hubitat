/*
 *	Location Notifier
 *
 *	Author: J.R. Farrar
 * 
 * V1.0 2020-07-02
 * 
 */

definition(
  name: "Location Notifier Child",
  namespace: "jrfarrar",
  author: "J.R. Farrar",
  description: "Location Notifier",
  parent: "jrfarrar:Admin tools",    
  iconUrl: "",
  iconX2Url: "",
  iconX3Url: "",
  importUrl: "https://raw.githubusercontent.com/jrfarrar/hubitat/master/Apps/AdminTools/LocationNotifier.groovy"
)


preferences {
page(name: "pageConfig")
}

def pageConfig() {
dynamicPage(name: "", title: "", install: true, uninstall: true, refreshInterval:0) { 
    
  section(getFormat("header-green", "Devices")) {
		  paragraph "- Turn on switch when user arrives at location, turn off when they leave."
	      input (name: "who", type: "capability.presenceSensor", title: "Who?", submitOnChange: true, required: true)
	      input (name: "where", type: "string", title: "where?", submitOnChange: true, required: true)      
          input (name: "arrivedSwitch", type: "capability.switch", title: "Switch to turn on/off", submitOnChange: true, required: true, multiple: false)
    }
    section(getFormat("header-green", "LOGGING")){                       
			input(name: "logLevel",title: "IDE logging level",multiple: false,required: true,type: "enum",options: getLogLevels(),submitOnChange : false,defaultValue : "1")
    }
    section(getFormat("header-green", "APP NAME")){
        input (name: "thisName", type: "text", title: "App Name", submitOnChange: true)
        if(thisName) app.updateLabel("$thisName") else {if (who && where) app.updateSetting("thisName", "Location Notifier - $who is $where")}
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
    if ( arrivedSwitch.latestValue( "switch" ) == "on" ) {
        app.updateLabel("$thisName <span style=\"color:green;\">(TRUE)</span>")
    } else {
        app.updateLabel("$thisName <span style=\"color:#FF0000;\">(FALSE)</span>")
    }
}

def initialize() {
  infolog "initialize"
  unsubscribe()
  subscribeToEvents()
  state.prevLocation = null
}

def uninstalled() {
  unschedule()
  unsubscribe()
  infolog "uninstalled"
}

def subscribeToEvents() {
    if (who) {
    subscribe(who, "address1", whoLocation)
    } else {
        log.warn "No sensor"
    }
}

def whoLocation(evt){
    debuglog "Location received: " + evt.value
    if (evt.value != state.prevLocation) {
        state.prevLocation = evt.value
        if (evt.value == where) {
            if ( arrivedSwitch.latestValue( "switch" ) != "on" ) {
                arrivedSwitch.on()
                infolog "${who} arrived at ${where}"
                app.updateLabel("$thisName <span style=\"color:green;\">(TRUE)</span>")
            } else {
                debuglog "MATCH - Location: " + evt.value
            }
        } else {
            if ( arrivedSwitch.latestValue( "switch" ) != "off" ) {
                arrivedSwitch.off()
                infolog "${who} left ${where}"
                app.updateLabel("$thisName <span style=\"color:#FF0000;\">(FALSE)</span>")
            } else {
                debuglog "NO MATCH - Location: " + evt.value
            }
        }
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
