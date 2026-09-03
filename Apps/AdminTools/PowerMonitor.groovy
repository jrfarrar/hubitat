/*
 *	Power monitor
 *
 *	Author: J.R. Farrar
 *
 * V1.3 2026-09-03 - The switch is now OPTIONAL.
 *      V1.2 added notifications but left "Switch to turn on/off" required, so the
 *      switch it was meant to make unnecessary still could not be removed. With a
 *      notification device configured the switch is now genuinely optional.
 *      Behaviour is unchanged wherever a switch IS configured: the switch's own state
 *      remains the authority on whether the condition is currently tripped, so a
 *      manual override still behaves exactly as it always did. Only when there is no
 *      switch does the app fall back to tracking that itself.
 *
 * V1.2 2026-09-03 - Notifications.
 *      The switch this app turns on was, in at least one install, only ever read by a
 *      Notifier app on another hub -- so the switch and a Hub Mesh share existed purely
 *      to carry one boolean to something that could send a message. This app can now
 *      send the message itself, which makes the switch optional rather than structural.
 *      Mirrors Power Cycle Monitor v2.43: one notification when the condition trips,
 *      then a reminder on an interval until it clears, because the outlet is still on
 *      while you are not reading the first message.
 *      Instances with no notification device configured behave exactly as before.
 * V1.1 2020-07-02
 * V1.0 2020-06-25 - rewrote logic
 *
 */

definition(
  name: "Power Monitor Child",
  namespace: "jrfarrar",
  author: "J.R. Farrar",
  description: "Power Monitor",
  parent: "jrfarrar:Admin tools",    
  iconUrl: "",
  iconX2Url: "",
  iconX3Url: "",
  importUrl: "https://raw.githubusercontent.com/jrfarrar/hubitat/master/Apps/AdminTools/PowerMonitor.groovy"
)


preferences {
page(name: "pageConfig")
}

def pageConfig() {
dynamicPage(name: "", title: "", install: true, uninstall: true, refreshInterval:0) { 
    
  section(getFormat("header-green", "Devices")) {
		  paragraph "- Turn on when power rises above."
	      input (name: "pwrClamp", type: "capability.powerMeter", title: "Power Meter", submitOnChange: true, required: true)
          input (name: "tempSwitch", type: "capability.switch", title: "Switch to turn on/off (optional)", submitOnChange: true, multiple: false, required: false)
          if (!tempSwitch && !notifyDevice) paragraph "<div style='background-color:#ffe0e0; padding:8px; border-radius:4px;'><b>Nothing will happen.</b> Set a switch, a notification device, or both &mdash; otherwise this instance detects the condition and then does nothing with it.</div>"
          input (name: "watts", type: "number", title: "Watt trigger?", defaultValue: 10, required: true)
          input (name: "delayBeforeOn", type: "bool", defaultValue: "false", title: "After power goes above, wait before turning switch on?", submitOnChange: true)
          if (delayBeforeOn) input (name: "delayOn", type: "number", title: "Wait this many minutes before turning switch on", defaultValue: 3)
          input (name: "delayBeforeOff", type: "bool", defaultValue: "false", title: "After power drops below, wait before turning switch off?", submitOnChange: true)
          if (delayBeforeOff) input (name: "delayOff", type: "number", title: "Wait this many minutes before turning switch off", defaultValue: 3)
    }
    section(getFormat("header-green", "RESTRICTIONS")) {
		  paragraph "- These restrict the above triggers based on what's set here."
          input (name: "mySwitch", type: "capability.switch", title: "Switch to restrict running", submitOnChange: true, multiple: false)
          input (name: "onSwitch", type: "bool", defaultValue: "false", title: "Only run if switch on?", submitOnChange: true)
          input (name: "offSwitch", type: "bool", defaultValue: "false", title: "Only run if switch off?", submitOnChange: true)
          input (name: "noRunModes", type: "mode", title: "Select Mode NOT to run in", submitOnChange: true, multiple: true)
    }
    section(getFormat("header-green", "NOTIFICATIONS")) {
          paragraph "- Send a message when the switch turns on. Leave the device blank and this app behaves exactly as it always has."
          input (name: "notifyDevice", type: "capability.notification", title: "Send alerts to", required: false, multiple: true, submitOnChange: true)
          if (notifyDevice) {
              input (name: "alertMessage", type: "text", title: "Message to send", required: false,
                     description: "Blank sends a generated line naming the meter and the watt threshold.")
              input (name: "notifyRepeatMinutes", type: "number", title: "Repeat every (min) until it clears, 0 = notify once",
                     defaultValue: 15, required: false)
              paragraph "<small style='color:#666;'>The reminder repeats while the switch is still on and stops the moment it turns off &mdash; " +
                        "no acknowledgement needed, and nothing is left scheduled once the condition clears.</small>"
          }
    }
    section(getFormat("header-green", "LOGGING")){
			input(name: "logLevel",title: "IDE logging level",multiple: false,required: true,type: "enum",options: getLogLevels(),submitOnChange : false,defaultValue : "1")
    }
    section(getFormat("header-green", "APP NAME")){
        input (name: "thisName", type: "text", title: "App Name", submitOnChange: true)
			//if(thisName) app.updateLabel("$thisName") else app.updateSetting("thisName", "Temperature turn on/off")
        if(thisName) app.updateLabel("$thisName") else {if (pwrClamp) app.updateSetting("thisName", "$pwrClamp - Power Monitor")}
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
  state.running = false
  // unschedule() above already dropped any pending reminder; clear the flag with it
  // so a saved app can never come back believing an alert is still outstanding.
  state.notifyActive = false
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
      
    if (canWeRun(rndPower)) {    
        if (rndPower > watts ) {
            if (state.running == false) {
                debuglog "power rose above ${watts}: " + rndPower
                unschedule(turnOff)
                state.running = true
                if (delayBeforeOn) {
                    debuglog "Waiting ${delayOn} minutes to turn on"
                    runIn(60 * delayOn.toInteger(), turnOn)
                } else {
                    turnOn()
                }
            }
        } else {
            if (state.running) {
                debuglog "power dropped below or equal ${watts}: " + rndPower
                unschedule(turnOn)
                state.running = false
                if (delayBeforeOff) {
                    debuglog "Waiting ${delayOff} minutes to turn off"
                    runIn(60 * delayOff.toInteger(), turnOff)
                } else {
                    turnOff()
                }
        
            }
        }
    }
}


void turnOff() {
    if ( isTripped() ) {
        state.lastoff = new Date().format("yyyy-MM-dd HH:mm")
        state.offTime = now()
        dur = ((state.offTime - state.onTime)/1000)/60
        state.duration = (dur as double).round(2)
        infolog "shut off, time: " + state.lastoff
        infolog "Runtime: ${state.duration} Minutes"
        state.tripped = false
        tempSwitch?.off()
        // The condition has cleared, so stop reminding. Cancelling here rather than
        // relying on the repeat handler to notice means a stale reminder can never
        // outlive the thing it was reminding about.
        state.notifyActive = false
        unschedule("notifyRepeatCheck")
        app.updateLabel("$thisName <span style=\"color:black;\">(${state.lastoff})(${state.duration}min)</span>")
    }
}

void turnOn(){
    if ( !isTripped() ) {
        state.laston = new Date().format("yyyy-MM-dd HH:mm")
        state.onTime = now()
        infolog "turned on, time: " + state.laston
        state.tripped = true
        tempSwitch?.on()
        app.updateLabel("$thisName <span style=\"color:green;\">(ON)</span>")
        notifyTripped()
    }
}

// ----------------------------------------------------------------------------
//   NOTIFICATIONS (v1.2)
// ----------------------------------------------------------------------------

// Is the condition currently tripped?
//
// When a switch is configured the SWITCH is the authority, exactly as it was before
// the switch became optional -- so a manual override still behaves the way it always
// has, and this change cannot alter any existing install. Only when there is no
// switch does the app fall back to its own flag.
// The ?. is redundant under the if, and deliberate: the build script enforces that
// NO bare tempSwitch. dereference exists anywhere in this file. An absolute rule can
// be checked mechanically; "safe except where someone reasoned about it" cannot.
def isTripped() {
    if (tempSwitch) return (tempSwitch?.latestValue("switch") == "on")
    return (state.tripped == true)
}

def alertText() {
    if (alertMessage) return alertMessage
    return "${thisName ?: pwrClamp?.displayName}: power above ${watts}W"
}

def notifyTripped() {
    if (!notifyDevice) return
    state.notifyActive = true
    sendNotify(alertText())
    scheduleNotifyRepeat()
}

def scheduleNotifyRepeat() {
    if (!notifyDevice) return
    Integer every = (notifyRepeatMinutes == null) ? 15 : (notifyRepeatMinutes as Integer)
    if (every <= 0) return
    runIn(every * 60, "notifyRepeatCheck", [overwrite: true])
}

// Re-notifies only while the condition is genuinely still true. Two independent
// checks on purpose: state.notifyActive is this app's own view, and the switch is
// the world's -- if anything else turned the switch off, the reminder stops too.
def notifyRepeatCheck() {
    if (!state.notifyActive) {
        debuglog "Reminder: no longer active, stopping"
        return
    }
    if (!isTripped()) {
        debuglog "Reminder: condition cleared, stopping"
        state.notifyActive = false
        return
    }
    Integer mins = 0
    if (state.onTime) mins = ((now() - (state.onTime as Long)) / 60000L) as Integer
    sendNotify("${alertText()} (still on, ${mins} min)")
    scheduleNotifyRepeat()
}

// One dead notifier must not abort the loop and silence the others.
def sendNotify(msg) {
    if (!notifyDevice) return
    notifyDevice.each { d ->
        try {
            d.deviceNotification(msg)
        } catch (e) {
            log.warn "$thisName: notification to ${d} failed: ${e.message}"
        }
    }
}



def canWeRun(pwr){
    def isItOn
    def isItOff
    def isModeOk
    def powerOk
    debuglog "Checking if power is over 10,000: " + pwr

    if (pwr < 10000){
        powerOk = true
    } else {
        powerOk = false
        infolog "Power received was out of bounds" + pwr
    }   
    if (onSwitch && mySwitch && mySwitch.currentValue('switch')?.contains('off')) {
        isItOn = false
        debuglog "Only Run when switch is ON and it's OFF"
    } else { 
        isItOn = true 
    }

    if (offSwitch && mySwitch && mySwitch.currentValue('switch')?.contains('on')) {
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
    
    if (isItOff && isItOn && isModeOk && powerOk) {
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
