/*
 *	Septic Pump
 *
 *	Author: J.R. Farrar
 * 
 *  V 1.3 - 2025-11-11 Code cleanup - removed dead code, improved naming, added state recovery
 *  V 1.2 - 2020-07-07 Added duration
 *  V 1.1 - 2020-07-02
 *  V 1.0 - 2020-06-23 
 * 
 */

definition(
  name: "Septic Pump Child",
  namespace: "jrfarrar",
  author: "J.R. Farrar",
  description: "Septic Pump Alert",
  parent: "jrfarrar:Admin tools",    
  iconUrl: "",
  iconX2Url: "",
  iconX3Url: "",
  importUrl: "https://raw.githubusercontent.com/jrfarrar/hubitat/master/Apps/AdminTools/SepticPump.groovy"
)


preferences {
    page(name: "pageConfig")
}

def pageConfig() {
    dynamicPage(name: "", title: "", install: true, uninstall: true, refreshInterval:0) { 
        
        section(getFormat("header-green", "Devices")) {
            paragraph "- Turn on switch when Septic Pump hasn't run in X days."
            input (name: "pwrClamp", type: "capability.powerMeter", title: "Power Meter", submitOnChange: true, required: true)
            input (name: "pumpSwitch", type: "capability.switch", title: "Switch to turn on while pump is running?", submitOnChange: true, required: false, multiple: false)      
            input (name: "tempSwitch", type: "capability.switch", title: "Switch to turn on if pump hasn't run in X days?", submitOnChange: true, required: true, multiple: false)
            input (name: "watts", type: "number", title: "Watt trigger?", required: true)
            input (name: "xdays", type: "number", title: "How many days without pump running?", required: true)
            input (name: "tooLong", type: "number", title: "How many minutes is too many for the pump to be running?", required: true)
            input (name: "tooLongSwitch", type: "capability.switch", title: "Switch to turn on if pump has been running longer than X minutes?", submitOnChange: true, required: true, multiple: false)
        }
        
        section(getFormat("header-green", "LOGGING")){                       
            input(name: "logLevel", title: "IDE logging level", multiple: false, required: true, type: "enum", options: getLogLevels(), submitOnChange: false, defaultValue: "1")
        }
        
        section(getFormat("header-green", "APP NAME")){
            input (name: "thisName", type: "text", title: "App Name", submitOnChange: true)
            if(thisName) {
                app.updateLabel("$thisName")
            } else {
                if (tempSwitch) app.updateSetting("thisName", "Septic Pump alert")
            }
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
    unschedule()
    initialize()
}

def initialize() {
    infolog "initialize"
    unsubscribe()
    unschedule()
    subscribeToEvents()
    
    // Check current power state on startup to recover from hub restart
    if (pwrClamp) {
        def currentPower = pwrClamp.currentValue("power")
        if (currentPower != null && currentPower > watts) {
            state.running = true
            state.onTime = now()
            state.lastrun = new Date().format("yyyy-MM-dd HH:mm:ss")
            infolog "Initialize: Pump detected as running (${currentPower}W)"
            pumpSwitch?.on()
            runIn(60 * tooLong.toInteger(), pumpRunningLong)
        } else {
            state.running = false
            infolog "Initialize: Pump detected as off"
        }
    }
    
    // Schedule the overdue check
    if (state.lastrun) {
        def lastRunDate = Date.parse("yyyy-MM-dd HH:mm:ss", state.lastrun)
        def nextCheck = new Date(lastRunDate.time + (xdays.toInteger() * 24 * 60 * 60 * 1000))
        if (nextCheck > new Date()) {
            runOnce(nextCheck, pumpOverdue)
            debuglog "Scheduled overdue check for: ${nextCheck}"
        } else {
            // Already overdue
            pumpOverdue()
        }
    }
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
    def powerReading = Double.parseDouble(evt.value).round(0)
    debuglog "power: ${powerReading}W, ${evt.device}"
    
    // Filter out erroneous readings  
    if (powerReading >= 10000) {
        log.warn "Ignoring erroneous power reading: ${powerReading}W"
        return
    }
    
    if (powerReading > watts) {
        // Pump is running
        if (state.running == false) {
            unschedule(pumpOverdue)
            state.lastrun = new Date().format("yyyy-MM-dd HH:mm:ss")
            state.onTime = now()
            state.running = true
            infolog "Pump turned on, time: ${state.lastrun}"
            
            pumpSwitch?.on()
            
            app.updateLabel("$thisName <span style=\"color:green;\">(RUNNING - ${state.lastrun})</span>")
            
            // Schedule alert if pump runs too long (convert minutes to seconds)
            runIn(60 * tooLong.toInteger(), pumpRunningLong)
            
            // Schedule next overdue check
            def timeToRun = new Date() + xdays.toInteger()
            debuglog "Next overdue check scheduled for: ${timeToRun}"
            runOnce(timeToRun, pumpOverdue)
            
            // Turn off the overdue alert if it's on
            if (tempSwitch?.latestValue("switch") == "on") {
                infolog "Turning off overdue alert switch"
                tempSwitch.off()
            }
        }
    } else {
        // Pump is off
        if (state.running) {
            state.running = false
            state.lastoff = new Date().format("yyyy-MM-dd HH:mm:ss")
            state.offTime = now()
            
            // Calculate runtime in minutes
            def durationMs = state.offTime - state.onTime
            def durationMinutes = (durationMs / 1000) / 60
            state.duration = (durationMinutes as double).round(2)
            
            infolog "Pump shut off, time: ${state.lastoff}"
            infolog "Run time: ${state.duration} minutes"
            
            pumpSwitch?.off()
            
            unschedule(pumpRunningLong)
            
            // Turn off the "running too long" alert if it's on
            if (tooLongSwitch?.latestValue("switch") == "on") {
                tooLongSwitch.off()
            }
            
            app.updateLabel("$thisName <span style=\"color:black;\">(${state.lastoff})(Runtime: ${state.duration}min)</span>")
        }
    }
}
 
void pumpOverdue() {
    // Turn on alert switch when pump hasn't run in X days
    if (tempSwitch?.latestValue("switch") != "on") {
        log.warn "Pump hasn't run in ${xdays} days, turning on alert switch"
        tempSwitch.on()
    }  
}

void pumpRunningLong() {
    // Turn on alert switch when pump has been running too long
    log.warn "Alert! Septic Pump running longer than ${tooLong} minutes! Turning on switch - ${tooLongSwitch}"
    tooLongSwitch?.on() 
}

def getFormat(type, myText=""){			// Modified from @Stephack Code   
    if(type == "header-green") return "<div style='color:#ffffff;font-weight: bold;background-color:#81BC00;border: 1px solid;box-shadow: 2px 3px #A9A9A9'>${myText}</div>"
    if(type == "line") return "<hr style='background-color:#1A77C9; height: 1px; border: 0;'>"
    if(type == "title") return "<h2 style='color:#1A77C9;font-weight: bold'>${myText}</h2>"
    if(type == "title2") return "<div style='color:#1A77C9;font-weight: bold'>${myText}</div>"
}

def debuglog(statement) {   
    def logL = 0
    if (logLevel) logL = logLevel.toInteger()
    if (logL == 0) {return}
    else if (logL >= 2) {
        log.debug("${thisName}: ${statement}")
    }
}

def infolog(statement) {       
    def logL = 0
    if (logLevel) logL = logLevel.toInteger()
    if (logL == 0) {return}
    else if (logL >= 1) {
        log.info("${thisName}: ${statement}")
    }
}

def getLogLevels(){
    return [["0":"None"],["1":"Running"],["2":"NeedHelp"]]
}
