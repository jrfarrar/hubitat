/**
*  Smart Humidity Fan
*
*  Turns on a fan when you start taking a shower... turns it back off when you are done.
*    -Uses humidity change between two humidity sensors
*    -Timeout option when manaully controled (for stench mitigation)
*
*  Copyright 2018 Craig Romei (Modifiled by J.R. Farrar for 2 sensor comparison)
*  GNU General Public License v2 (https://www.gnu.org/licenses/gpl-2.0.txt)
*
*/

definition(
    name: "Smart Humidity Fan Comparison Version",
    namespace: "J.R. Farrar",
    author: "J.R. Farrar",
    description: "Control a fan (switch) based on relative humidity difference between a humidity sensor(bathroom) and a baseline humidity sensor(house thermostat or other)",
    category: "Convenience",
    iconUrl: "https://raw.githubusercontent.com/napalmcsr/SmartThingsStuff/master/smartapps/craig-romei/Bathroom_Fan.jpg",
    iconX2Url: "https://raw.githubusercontent.com/napalmcsr/SmartThingsStuff/master/smartapps/craig-romei/Bathroom_Fan.jpg",
    iconX3Url: "https://raw.githubusercontent.com/napalmcsr/SmartThingsStuff/master/smartapps/craig-romei/Bathroom_Fan.jpg",
    importUrl: "https://raw.githubusercontent.com/jrfarrar/hubitat/master/Apps/SmartHumidityFanComparison.groovy"
)

preferences {
    page(name: "pageConfig") // Doing it this way elimiates the default app name/mode options.
}
def pageConfig()
{
	dynamicPage(name: "", title: "", install: true, uninstall: true, refreshInterval:0) {
		section("Instructions")
		{
		    paragraph "Use this to turn on a bathroom fan when humditity in the bathroom rises above humidity of another sensor in the house."  
		    paragraph "The bathroom sensor should report fairly frequently or it will be quite a bit of time before the fan turns on."
		    paragraph "The manual mode is used to turn the fan on for reasons other than humidity and has it's own set turn off delay."
		    paragraph "However if the humidity rises while the fan is on manually the automatic mode will take over."
		    paragraph "This is for the times also when someone remembers to turn the fan on before the shower. The auto mode will kick in and take over and turn off NOT after a set time but when the humidity drops to the prescribed amount."
		    paragraph "The first time you install/run this the baseline humidity is set to 50% until your baseline humidity sensor reports in."
		}
		section("Bathroom Devices")
		{
			paragraph "NOTE: The bathroom humidity sensor you select will need to report about 5 min or less. Not important for the baseline sensor."
			input "HumiditySensor", "capability.relativeHumidityMeasurement", title: "Bathroom Humidity Sensor:", required: true
			input "FanSwitch", "capability.switch", title: "Fan switch to turn on:", required: true
           		input "CompareHumiditySensor", "capability.relativeHumidityMeasurement", title: "Compare to this baseline Humidity Sensor:", required: true
		}
		section("Fan Activation")
		{
			input "HumidityIncreasedBy", "number", title: "When humidity rises above or equal to this amount plus the baseline sensor humidity turn on the fan: ", required: true, defaultValue: 9
		}
		section("Fan Deactivation")
		{
			input "HumidityDecreasedBy", "number", title: "When humidity drops below this amount plus the baseline sensor humidity start the turn off delay: ", required: true, defaultValue: 6
            		input "HumidityDropTimeout", "number", title: "How long after the humidity drops below the turn off threshold should the fan turn off (minutes):", required: true, defaultValue:  10
		}
		section("Manual Activation")
		{
			paragraph "When should the fan turn off when turned on manually?"
			input "ManualControlMode", "enum", title: "Off After Manual-On?", required: true, options: ["Manually", "By Humidity", "After Set Time"], defaultValue: "After Set Time"
			paragraph "How many minutes until the fan is auto-turned-off?"
			input "ManualOffMinutes", "number", title: "Auto Turn Off Time (minutes)?", required: false, defaultValue: 10
		}
		section("Disable Modes")
		{
			paragraph "What modes do you not want this to run in?"
			input "modes", "mode", title: "select a mode(s)", multiple: true
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
				,defaultValue : "10"
				)  
		}
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
	state.AutomaticallyTurnedOn = false
	state.TurnOffLaterStarted = false
    	subscribe(HumiditySensor, "humidity", HumidityHandler)
    	subscribe(CompareHumiditySensor, "humidity", HumidityHandler)
    	subscribe(FanSwitch, "switch", FanSwitchHandler)
	subscribe(location, "mode", modeChangeHandler)
    	version()
	if (!state.baselineHumidity)
	{
		state.baselineHumidity = 50
	}
}

def modeChangeHandler(evt)
{
	def allModes = settings.modes
	if(allModes)
	{
		if(allModes.contains(location.mode))
		{
			debuglog "modeChangeHandler: Entered a disable mode, turning off the Fan"
			TurnOffFanSwitch()
		}
	} 
	else
	{	
		debuglog "modeChangeHandler: Entered a disable mode, turning off the Fan"
		TurnOffFanSwitch()
	}
}

def HumidityHandler(evt)
{
    state.threshold = state.baselineHumidity + HumidityIncreasedBy
    state.thresholdOff = state.baselineHumidity + HumidityDecreasedBy
    if (evt.deviceId == CompareHumiditySensor.deviceId) 
    {
        state.baselineHumidity = Double.parseDouble(evt.value.replace("%", ""))
        //debuglog "Baseline humidity update : ${state.baselineHumidity} from: ${evt.device}"
    } 
    else 
    {
        state.currentHumidity = Double.parseDouble(evt.value.replace("%", ""))
        //debuglog "Current humidity update: ${state.currentHumidity} from: ${evt.device}"
    }
    
    
    //debuglog "TEST: evt: ${evt.descriptionText}"
    //debuglog "TEST: evt.device: ${evt.device}"
    //debuglog "TEST: evt.deviceId: ${evt.deviceId}"
    //debuglog "TEST: CompareHumiditySensor: ${CompareHumiditySensor.device}"
    //debuglog "TEST: CompareHumiditySensor-ID: ${CompareHumiditySensor.deviceId}"
    debuglog "Current humidity: ${state.currentHumidity}"
    debuglog "Baseline humidity: ${state.baselineHumidity}"
    debuglog "Turn ON Humidity threshold: ${state.threshold}"
    debuglog "Turn OFF Humidity threshold: ${state.thresholdOff}"
    
    //infolog "HumidityHandler:running humidity check: ${state.currentHumidity}"
	def allModes = settings.modes
	def modeStop = false
	
	if(allModes)
	{
		if(allModes.contains(location.mode))
		{
			modeStop = true
		}
	}
  
    //If fan is on manually but then the humidity rises above the threshold, set variable to allow auto mode to take over
    if ( ((state.currentHumidity)>=(state.threshold)) && (FanSwitch.currentValue("switch") == "on") && !modeStop && (state.AutomaticallyTurnedOn == false))
    {
        state.AutomaticallyTurnedOn = true
        state.TurnOffLaterStarted = false
        state.AutomaticallyTurnedOnAt = new Date().format("yyyy-MM-dd HH:mm")
        infolog "HumidityHandler:Automatic mode took over manual mode due to humidity increase"
	infolog "Value exceeded: ${state.thresholdOff}, Current humidity: ${state.currentHumidity}"
    }
    
    
    if ( ((state.currentHumidity)>=(state.threshold)) && (FanSwitch.currentValue("switch") == "off") && !modeStop)
        {
            state.AutomaticallyTurnedOn = true
            state.TurnOffLaterStarted = false
            state.AutomaticallyTurnedOnAt = new Date().format("yyyy-MM-dd HH:mm")
            infolog "HumidityHandler:Turn On Fan due to humidity increase"
	    infolog "Value exceeded: ${state.threshold}, Current humidity: ${state.currentHumidity}"
            FanSwitch.on()
            debuglog "Humidity above threshold (baseline plus increase amount): ${state.threshold}" 
            debuglog "Current humidity: ${state.currentHumidity}"
            //debuglog "Baseline humidity: ${state.baselineHumidity}"
            //debuglog "Increased by: ${settings.HumidityIncreasedBy}"
        }
	//turn off the fan when humidity returns to normal and it was kicked on by the humidity sensor
	else if((state.AutomaticallyTurnedOn || ManualControlMode == "By Humidity")&& !state.TurnOffLaterStarted)
	{    
        if(state.currentHumidity<state.thresholdOff)
        {  
            //debuglog "CURRENT HUMIDITY: ${state.currentHumidity}  STATE.THRESHOLDOFF: ${state.thresholdOff}"
            if(HumidityDropTimeout == 0)
            {
                infolog "HumidityHandler:Fan Off"
                TurnOffFanSwitch()
            }
            else
            {
				infolog "HumidityHandler:Turn Fan off in ${HumidityDropTimeout} minutes."
		    		infolog "Humidity: ${state.currentHumidity}"
				state.TurnOffLaterStarted = true
				runIn(60 * HumidityDropTimeout.toInteger(), TurnOffFanSwitchCheckHumidity)
				debuglog "HumidityHandler: state.TurnOffLaterStarted = ${state.TurnOffLaterStarted}"
			}
		}
	}
}

def FanSwitchHandler(evt)
{
	infolog "FanSwitchHandler::Switch changed"
	debuglog "FanSwitchHandler: ManualControlMode = ${ManualControlMode}"
	debuglog "FanSwitchHandler: ManualOffMinutes = ${ManualOffMinutes}"
	debuglog "HumidityHandler: state.AutomaticallyTurnedOn = ${state.AutomaticallyTurnedOn}"
	switch(evt.value)
	{
		case "on":
			if(!state.AutomaticallyTurnedOn && (ManualControlMode == "After Set Time") && ManualOffMinutes)
			{
				if(ManualOffMinutes == 0)
				{
					debuglog "FanSwitchHandler::Fan Off"
					TurnOffFanSwitchManual()
				}
					else
				{
					debuglog "FanSwitchHandler::Will turn off later"
					runIn(60 * ManualOffMinutes.toInteger(), TurnOffFanSwitchManual)
				}
			}
			break
        case "off":
			debuglog "FanSwitchHandler::Switch turned off"
			state.AutomaticallyTurnedOn = false
			state.TurnOffLaterStarted = false
			break
    }
}

def TurnOffFanSwitchCheckHumidity()
{
    debuglog "TurnOffFanSwitchCheckHumidity: Function Start"
	if(FanSwitch.currentValue("switch") == "on")
    {
		debuglog "TurnOffFanSwitchCheckHumidity: state.currentHumidity ${state.currentHumidity} : state.thresholdOff ${state.currentHumidity}"
		if (state.currentHumidity >= state.thresholdOff)
        {
            		infolog "TurnOffFanSwitchCheckHumidity: Didn't turn off fan because humdity: ${state.currentHumidity} is greater than turn off threshold"
			state.AutomaticallyTurnedOn = true
			state.AutomaticallyTurnedOnAt = now()
			state.TurnOffLaterStarted = false
		}
		else
		{
			debuglog "TurnOffFanSwitchCheckHumidity: Turning the Fan off now"
			TurnOffFanSwitch()
		}
	}
}

def TurnOffFanSwitch()
{
    if(FanSwitch.currentValue("switch") == "on")
    {
        infolog "TurnOffFanSwitch:Fan Off"
        FanSwitch.off()
        state.AutomaticallyTurnedOn = false
        state.TurnOffLaterStarted = false
    }
}

def TurnOffFanSwitchManual()
{
    if ((FanSwitch.currentValue("switch") == "on") && (state.AutomaticallyTurnedOn == false))
    {
        infolog "TurnOffFanSwitch:Fan Off"
        FanSwitch.off()
        state.AutomaticallyTurnedOn = false
        state.TurnOffLaterStarted = false
    }
    else
    {
        infolog "Not turning off switch, either the swtich was off or the Auto routine kicked in"
    }
}

def CheckThreshold(evt)
{
	double lastevtvalue = Double.parseDouble(evt.value.replace("%", ""))
	if(lastevtvalue >= HumidityThreshold)
	{  
		infolog "IsHumidityPresent: Humidity is above the Threashold"
		return true
	}
	else
	{
		return false
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
def version(){
	unschedule()
	//schedule("0 0 9 ? * FRI *", updateCheck) // Cron schedule - How often to perform the update check - (This example is 9am every Friday)
	//updateCheck()  
}

def display(){
	if(state.Status){
		section{paragraph "Version: $state.version -  $state.Copyright"}
		if(state.Status != "Current"){
			section{ 
			paragraph "$state.Status"
			paragraph "$state.UpdateInfo"
			}
		}
	}
}


def updateCheck(){
    setVersion()
	def paramsUD = [uri: "https://napalmcsr.github.io/Hubitat_Napalmcsr/versions.json"]   // This is the URI & path to your hosted JSON file
	try {
		httpGet(paramsUD) { respUD ->
		//  log.warn " Version Checking - Response Data: ${respUD.data}"   // Troubleshooting Debug Code 
			def copyrightRead = (respUD.data.copyright)
			state.Copyright = copyrightRead
			def newVerRaw = (respUD.data.versions.Application.(state.InternalName))
			def newVer = (respUD.data.versions.Application.(state.InternalName).replace(".", ""))
			def currentVer = state.version.replace(".", "")
			state.UpdateInfo = (respUD.data.versions.UpdateInfo.Application.(state.InternalName))
			state.author = (respUD.data.author)
				   
			if(newVer == "NLS"){
				state.Status = "<b>** This app is no longer supported by $state.author  **</b> (But you may continue to use it)"       
				log.warn "** This app is no longer supported by $state.author **"      
			}           
			else if(currentVer < newVer){
				state.Status = "<b>New Version Available (Version: $newVerRaw)</b>"
				log.warn "** There is a newer version of this app available  (Version: $newVerRaw) **"
				log.warn "** $state.UpdateInfo **"
			} 
			else{ 
				state.Status = "Current"
				log.info "You are using the current version of this app"
			}
		}
	} 
	catch (e) {
		state.Status = "Error"
        log.error "Something went wrong: CHECK THE JSON FILE AND IT'S URI -  $e"
	} 
}

def setVersion(){
	state.version = "1.0.0" // Version number of this app
	state.InternalName = "SmartHumidityFanComparison"   // this is the name used in the JSON file for this app
}

