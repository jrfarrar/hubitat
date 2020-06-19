definition(
    name: "Smart Illuminance Switch",
    namespace: "B. Chan",
    author: "Brian Chan",
    description: "Control a switch based on a illuminance sensor",
    category: "Convenience",
    iconUrl: "",
    iconX2Url: "",
    iconX3Url: "",
    importUrl: ""
)

preferences {
    page(name: "pageConfig") // Doing it this way elimiates the default app name/mode options.
}
def pageConfig()
{
	dynamicPage(name: "", title: "", install: true, uninstall: true, refreshInterval:0) {
		section("Instructions")
		{
		    paragraph "Use this to turn on/off a switch fan when illuminance changes."  
		}
		section("Bathroom Devices")
		{
			input "IlluminanceSensor", "capability.illuminanceMeasurement", title: "Illuminance Sensor:", required: true
			input "Switch", "capability.switch", title: "Switch to control:", required: true
		}
		section("Switch Activation")
		{
			input "SwitchOnIlluminance", "number", title: "When illuminance drops below or equal to this amount turn on the switch: ", required: true, defaultValue: 10
			input "AutomaticOnTimeout", "number", title: "Minimum time switch is ON when triggered automatically.", required: true, defaultValue: 10
			input "AutomaticOffTimeout", "number", title: "Minimum time switch is OFF when triggered automatically.", required: false
		}
		section("Switch Deactivation")
		{
			input "SwitchOffIllumanceDelta", "number", title: "When illuminance rises above this amount plus the illuminance trigger level turn off the switch: ", required: true, defaultValue: 1
		}
		section("Manual Activation")
		{
			paragraph "How many minutes until the switch switches back to auto after being turned on manually?"
			input "ManualOnMinutes", "number", title: "Manual On Reset Time (minutes)?", required: true, defaultValue: 10
        }
		section("Manual De-Activation")
		{
			paragraph "How many minutes until the switch switches back to auto after being turned off manually?"
			input "ManualOffMinutes", "number", title: "Manual Off Reset Time (minutes)?", required: false
		}
		section("Logging")
		{                       
			input(
				name: "logLevel"
				,title: "IDE logging level" 
				,multiple: false
				,required: true
				,type: "enum"
				,options: getLogLevels()
				,submitOnChange : false
				,defaultValue : "1"
				)  
		}
        section() {label title: "Enter a name for this automation", required: false}
	}
}

def installed()
{
	initialize()
}

def updated()
{
	unsubscribe()
	initialize()
}

def initialize()
{
	infolog "Initializing"

	ResetState()

    subscribe(IlluminanceSensor, "illuminance", IlluminanceHandler)
    subscribe(Switch, "switch", SwitchHandler)

	IlluminanceHandler(null)
}

def IlluminanceHandler(evt)
{
	state.currentIllumanince = IlluminanceSensor.currentValue("illuminance")
	state.threshold = SwitchOnIlluminance

	def thresholdOffDelta = 1

	if(SwitchOffIllumanceDelta != null && SwitchOffIllumanceDelta > 1)
	{
		thresholdOffDelta = SwitchOffIllumanceDelta
	}
	state.thresholdOff = state.threshold + thresholdOffDelta
	debuglog "Turn ON Illuminance threshold: ${state.threshold}"
	debuglog "Turn OFF Illuminance threshold: ${state.thresholdOff} (${thresholdOffDelta})"
	debuglog "Current Illuminance: ${state.currentIllumanince}"
	debuglog "Automatic ON Timeout: ${AutomaticOnTimeout}"

	def automaticOffTimeout = AutomaticOnTimeout.toInteger()

	if(automaticOffTimeout != null && automaticOffTimeout > 0)
	{
		automaticOffTimeout = AutomaticOffTimeout.toInteger()
	}
	debuglog "Automatic OFF Timeout: ${automaticOffTimeout}"

	if (state.currentIllumanince <= state.threshold && Switch.currentValue("switch") == "off" && state.AutomaticallyTurnedOff)
	{
		state.AutomaticallyTurnedOn = true
		state.AutomaticallyTurnedOnAt = new Date().format("yyyy-MM-dd HH:mm")
		infolog "IlluminanceHandler: Turn On Switch due to illuminance decrease"
		infolog "Value reached: ${state.threshold}, Current illuminance: ${state.currentIllumanince}"
		Switch.on()

		state.AutomaticTimeOutStarted = true
		runIn(60 * AutomaticOnTimeout.toInteger(), SwitchToAutomatic)
	}
	else if(state.currentIllumanince >= state.thresholdOff && state.AutomaticallyTurnedOn)
	{
		state.AutomaticallyTurnedOffAt = new Date().format("yyyy-MM-dd HH:mm")
		infolog "IlluminanceHandler:Turn Switch off."
		infolog "${IlluminanceSensor} : ${state.currentIllumanince}"
		state.AutomaticallyTurnedOn = false
		state.AutomaticallyTurnedOff = true
		Switch.off()

		state.AutomaticTimeOutStarted = true
		runIn(60 * automaticOffTimeout, SwitchToAutomatic)
	}
}

def SwitchHandler(evt)
{
	infolog "SwitchHandler::Switch changed"
	debuglog "SwitchHandler: ManualOnMinutes = ${ManualOnMinutes}"

	def manualOffMinutes = ManualOnMinutes.toInteger()
	if(ManualOffMinutes != null && ManualOffMinutes > 0) manualOffMinutes = ManualOffMinutes.toInteger()
	debuglog "SwitchHandler: ManualOffMinutes = ${ManualOffMinutes}"
	debuglog "SwitchHandler: state.AutomaticallyTurnedOn = ${state.AutomaticallyTurnedOn}"

	switch(evt.value)
	{
		case "on":
			state.AutomaticallyTurnedOff = false
			if(!state.AutomaticallyTurnedOn)
			{
                debuglog "Scheduling switch back to automatic in ${ManualOnMinutes.toInteger()} minutes"
				runIn(60 * ManualOnMinutes.toInteger(), SwitchToAutomatic)
			}
			break
        case "off":
			debuglog "SwitchHandler::Switch turned off"
			state.AutomaticallyTurnedOn = false

			if(!state.AutomaticallyTurnedOff)
            {
                debuglog "Scheduling switch back to automatic in ${manualOffMinutes} minutes"
				runIn(60 * manualOffMinutes, SwitchToAutomatic)
            }
			break
    }

}

def ResetState()
{
	state.AutomaticallyTurnedOn = (Switch.currentValue("switch") == "on")
	state.AutomaticallyTurnedOff = !state.AutomaticallyTurnedOn
	state.AutomaticTimeOutStarted = false
}

def SwitchToAutomatic()
{
	debuglog "SwitchToAutomatic: Function Start"
	ResetState()
	IlluminanceHandler(null)
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
