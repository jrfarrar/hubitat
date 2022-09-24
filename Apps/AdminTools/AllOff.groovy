/**
 *  All Off Child
 *
 * 1.0 - 09/24/22 - Added forced off to this for the Room Lights Activators
 */

definition(
	name: "All Off Child",
	namespace: "jrfarrar",
	author: "J.R. Farrar",
	description: "Turn Devices Off with Recheck",
    parent: "jrfarrar:Admin tools",
	category: "Convenience",
	iconUrl: "",
	iconX2Url: ""
)


preferences {
	page(name: "mainPage")
}

Map mainPage() {
	dynamicPage(name: "mainPage", title: "All Off", uninstall: true, install: true) {
		section {
			input "appName", "text", title: "Name this instance of All Off", submitOnChange: true
			if(appName) app.updateLabel(appName)
			input "switches", "capability.switch", title: "Switches to turn off", multiple: true
			paragraph "For the trigger use a Virtual Switch with auto-off enabled, turning it on fires the main off command for the switches above"
			input "trigger", "capability.switch", title: "Trigger switch"
            input "force", "bool", title: "Force turning off all devices first then retry", devaultValue: False, submitOnChange: true
			input "retry", "number", title: "Select retry interval in seconds (default 1 second)", defaultValue: 1, submitOnChange: true, width: 4
			input "maxRetry", "number", title: "Maximum number of retries?", defaultValue: 5, submitOnChange: true, width: 4
			input "meter", "number", title: "Use metering (in milliseconds)", width: 4
		}
	}
}

void updated() {
	unsubscribe()
	initialize()
}

void installed() {
	initialize()
}

void initialize() {
	subscribe(trigger, "switch.off", handler)
	subscribe(switches, "switch.on", onHandler)
	atomicState.someOn = true
}

void handler(evt) {
	atomicState.retry = 0
	turnOffi()
}

void turnOffi() {
    if (force) {
		List whichOffi = []
		switches.each{
			it.off() 
			whichOffi += it
			if(meter) pause(meter)
			}
		log.info "Switches sent forced off commands: ${"$whichOffi" - "[" - "]"}"
    }
    turnOff()
}


void turnOff() {
	if(atomicState.someOn) {
		Boolean maybeOn = false
		List whichOff = []
		switches.each{
			if(it.currentSwitch == "on") {
				it.off() 
				maybeOn = true
				whichOff += it
				if(meter) pause(meter)
			}
		}
		atomicState.someOn = maybeOn
		if(maybeOn) {
			log.info "Switches sent off commands: ${"$whichOff" - "[" - "]"}"
			atomicState.retry++
			if(atomicState.retry < maxRetry) runIn(retry, turnOff)
			else log.info "Stopped after $maxRetry attempts: ${"$whichOff" - "[" - "]"} still on"
        } else { 
            log.info "All switches reported off"
            state.lastoff = new Date().format("yyyy-MM-dd HH:mm")
            app.updateLabel("$appName <span style=\"color:black;\">(${state.lastoff})</span>")
        }
	}
}

void onHandler(evt) {
	atomicState.someOn = true
}
