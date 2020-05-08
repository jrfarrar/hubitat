definition(
    name: "Mode Change Action Control",
    namespace: "djdizzyd",
    author: "Bryan Copeland",
    description: "Stuff to do on mode changes",
    category: "Convenience",
    iconUrl: "",
    iconX2Url: "")

preferences {
	page(name: "mainPage")
}

def mainPage() {
	dynamicPage(name: "mainPage", title: " ", install: true, uninstall: true) {
		section {
			input "thisMode", "mode", title: "Select Mode", submitOnChange: true, required: true
			if(thisMode) app.updateLabel("ModeActions-${thisMode}")
			input "switchOffDevs", "capability.switch", title: "Select Switches to Turn Off", submitOnChange: true, multiple: true
			input "switchOnDevs", "capability.switch", title: "Select Switches to Turn On", submitOnChange:true, multiple: true
			input "dimmerDevs", "capability.switchLevel", title: "Dimmers to set", submitOnChange: true, multiple: true
			if (dimmerDevs) { 
				input "dimmerValue", "number", title: "Dimmer Level", submitOnChange: true, required: true
			}
            input "dimmerDevs2", "capability.switchLevel", title: "Dimmers to set 2", submitOnChange: true, multiple: true
			if (dimmerDevs2) { 
				input "dimmerValue2", "number", title: "Dimmer Level", submitOnChange: true, required: true
			}
            input "dimmerDevs3", "capability.switchLevel", title: "Dimmers to set 3", submitOnChange: true, multiple: true
			if (dimmerDevs3) { 
				input "dimmerValue3", "number", title: "Dimmer Level", submitOnChange: true, required: true
			}
            input "dimmerDevs4", "capability.switchLevel", title: "Dimmers to set 4", submitOnChange: true, multiple: true
            if (dimmerDevs4) { 
				input "dimmerValue4", "number", title: "Dimmer Level", submitOnChange: true, required: true
			}
            //input "colorDevs", "capability.color", title: "Lights to set color", submitOnChange: true, multiple: true
			//if (colorDevs) { 
			//	input "colorDevs", "number", title: "Color", submitOnChange: true, required: true
			//}
			input "volumeDevs", "capability.audioVolume", title: "Audio Volume To Adjust", submitOnChange: true, multiple: true
			if (volumeDevs) {
				input "volumeValue", "number", title: "Audio Level", submitOnChange: true, required: true
			}
            input "thermostatDevs", "capability.thermostat", title: "Thermostats To Adjust", submitOnChange: true, multiple: true
            if (thermostatDevs) {
                input "thermostatCoolingValue", "number", title: "Thermostat Cooling Setpoint", submitOnChange: true, required: true
                input "thermostatHeatingValue", "number", title: "Thermostat Heating Setpoint", submitOnChange: true, required: true
            }
            input "thermostatDevs2", "capability.thermostat", title: "Thermostats 2 To Adjust", submitOnChange: true, multiple: true
            if (thermostatDevs2) {
                input "thermostatCoolingValue2", "number", title: "Thermostat Cooling Setpoint", submitOnChange: true, required: true
                input "thermostatHeatingValue2", "number", title: "Thermostat Heating Setpoint", submitOnChange: true, required: true
            }
            input "thermostatDevs3", "capability.thermostat", title: "Thermostats 3 To Adjust", submitOnChange: true, multiple: true
            if (thermostatDevs3) {
                input "thermostatCoolingValue3", "number", title: "Thermostat Cooling Setpoint", submitOnChange: true, required: true
                input "thermostatHeatingValue3", "number", title: "Thermostat Heating Setpoint", submitOnChange: true, required: true
            }
			//input "enableAlexaSwitch", "bool", title: "Enable Alexa Virtual Switch", submitOnChange: true
			input "testButton", "button", title: "Test Actions"
		}
	}
}

def installed() {
	initialize()
}

def updated() {
	unsubscribe()
	initialize()
}

def initialize() {
	subscribe(location, "mode", modeHandler)
	if (enableAlexaSwitch) { 
		def alexaSwitchDev = getChildDevice("alexaModeControl_${app.id}") 
		if (!alexaSwitchDev) { alexaSwitchDev = addChildDevice("hubitat", "Virtual Switch", "alexaModeControl_${app.id}", null, [label: "alexaModeControl-${thisMode}", name: "aleaModeControl-${thisName}"]) } 
		subscribe(alexaSwitchDev, "switch.on",  alexaHandler)
	} else {
		def alexaSwitchDev = getChildDevice("alexaModeControl_${app.id}") 
		if (alexaSwitchDev) { deleteChildDevice(alexaSwitchDev.getDeviceNetworkId()) }
		subscribe(alexaSwitchDev, "switch.on",  alexaHandler)
	}
	
}

def appButtonHandler(btn) {
	switch(btn) {
		case "testButton": doActions()
			break
	}
}

def doActions() {
	if (switchOffDevs) {
		switchOffDevs.each{ it.off() }
	}
	if (switchOnDevs) {
		switchOnDevs.each { it.on() }
	}
	if (dimmerDevs) {
		dimmerDevs.each { it.setLevel(dimmerValue) }
	}
    if (dimmerDevs2) {
		dimmerDevs2.each { it.setLevel(dimmerValue2) }
	}
    if (dimmerDevs3) {
		dimmerDevs3.each { it.setLevel(dimmerValue3) }
	}
    if (dimmerDevs4) {
		dimmerDevs4.each { it.setLevel(dimmerValue4) }
	}
	if (volumeDevs) {
		volumeDevs.each { it.setVolume(volumeValue) }
	}
    if (thermostatDevs) {
        thermostatDevs.each { 
            it.setCoolingSetpoint(thermostatCoolingValue)
            it.setHeatingSetpoint(thermostatHeatingValue)
        }   
    }
    if (thermostatDevs2) {
        thermostatDevs2.each { 
            it.setCoolingSetpoint(thermostatCoolingValue2)
            it.setHeatingSetpoint(thermostatHeatingValue2)

        }   
    }
    if (thermostatDevs3) {
        thermostatDevs3.each { 
            it.setCoolingSetpoint(thermostatCoolingValue3)
            it.setHeatingSetpoint(thermostatHeatingValue3)

        }   
    }
}

def alexaSwitch(value) { 
	def alexaSwitchDev = getChildDevice("alexaModeControl_${app.id}")
	if (alexaSwitchDev.currentValue("switch") != value) { 
		unsubscribe(alexaSwitchDev)
		switch(value) {
			case "on": 
				if (alexaSwitchDev.currentValue("switch")=="off") { alexaSwitchDev.on() }
				break
			case "off":
				alexaSwitchDev.off()
				break
		}
		pause(500)
		subscribe(alexaSwitchDev, "switch.on",  alexaHandler)
	}
}

def alexaHandler(evt) {
	log.info "Got alexa mode trigger"
	location.setMode(thisMode)
}

def modeHandler(evt) {
	log.info "Got mode change: ${evt.value}"
	if (evt.value == thisMode) { 
		doActions()
		if (enableAlexaSwitch) { alexaSwitch("on") }
	} else {
		if (enableAlexaSwitch) { alexaSwitch("off") }
	}
}
