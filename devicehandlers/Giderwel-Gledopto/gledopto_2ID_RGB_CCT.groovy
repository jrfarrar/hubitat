/*
    GLEDOPTO ZigBee RGB+CCT Controller GL-C-008 2ID
    by Matt Connew (@mconnew)

    For GLEDOPTO RGBW+CCT 2ID Controllers

    Version 1.0 -
      Initial released version June 12 2022
*/

import groovy.transform.Field

metadata {
    definition (name: "GLEDOPTO ZigBee RGB+CCT Light GL-C-008 2ID", namespace: "mconnew", author: "Matt Connew", importUrl: "https://raw.githubusercontent.com/mconnew/Hubitat/main/drivers/gledopto/gledopto_2ID_RGB_CCT.groovy") {
        capability "Actuator"
        capability "Bulb"
        capability "Configuration"
        capability "Refresh"
        capability "Switch"
        capability "Light"
        capability "ColorMode"

        fingerprint(
            manufacturer: "GLEDOPTO",
            model: "GL-C-007",
            deviceJoinName: "GLEDOPTO Zigbee RGB+CCT Controller 2ID",
            inClusters: "0000,0003,0004,0005,0006,0008,0300",
            outClusters: "",
            profileId: "C05E",
        )
    }

    preferences {
        input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: true
        input name: "txtEnable", type: "bool", title: "Enable descriptionText logging", defaultValue: true
    }
}

def logsOff(){
    log.warn "debug logging disabled..."
    device.updateSetting("logEnable",[value:"false",type:"bool"])
}

def updated(){
    log.info "updated..."
    log.warn "Hue in degrees is: ${hiRezHue == true}"
    log.warn "debug logging is: ${logEnable == true}"
    log.warn "description logging is: ${txtEnable == true}"
    if (logEnable) runIn(1800,logsOff)
}

def parse(String description) {
    if (logEnable) log.debug "Raw description: ${description}"
    def descMap = zigbee.parseDescriptionAsMap(description)
    if (logEnable) log.debug "Parsed description:${descMap}"
    def endpoint
    if (descMap.containsKey("endpoint")) {
        endpoint = descMap.endpoint
    } else if (descMap.containsKey("sourceEndpoint")) {
        endpoint = descMap.sourceEndpoint
    } else {
        log.debug "No endpoint in description so can't route message"
        return
    }
    log.debug "endpoint:${endpoint}"
    def cd // Child Device
    switch (endpoint){
        case "0B": // RGB endpoint
          cd = fetchChild("RGB")
          cd.parse(description)
          break
        case "0F": // CT endpoint
          cd = fetchChild("CT")
          cd.parse(description)
          break
    }
}

def on() {
    def cm = device.currentValue("colorMode")
    if (!cm) {
        if (logEnable) log.debug "colorMode attribute not set: \"${cm}\" so defaulting to RGB"
        cm = "RGB"
    } else {
        if (logEnable) log.debug "colorMode attribute is ${cmd} so turning on those LEDs"
    }
    
    switch (cm) {
        case "RGB":
           fetchChild("RGB").on();
           break;
        case "CT":
           fetchChild("CT").on();
           break;
        default:
            log.warn "Invalid color mode \"${colorMode}\""
            return
    }
}

def off() {
    fetchChild("RGB").off()
    fetchChild("CT").off()
}

def refresh() {
    if (logEnable) log.debug "refresh"
    def cmds = []
    cmds.addAll(fetchChild("RGB").refresh())
    cmds.addAll(fetchChild("CT").refresh())
    return cmds
}

def setColorMode(String colorMode)
{
    switch (colorMode) {
        case "RGB":
           fetchChild("CT").off();
           break;
        case "CT":
           fetchChild("RGB").off();
           break;
        default:
            log.warn "Invalid color mode \"${colorMode}\""
            return
    }

    descriptionText = "${device.displayName} colorMode is ${colorMode}"
    if (logEnable) log.debug "evt- rawValue:${colorMode}, value: ${colorMode}, descT: ${descriptionText}"
    if (txtEnable) log.info "${descriptionText}"
    sendEvent(name:"colorMode", value:colorMode, descriptionText:descriptionText, unit: null)
}

def configure() {
    log.warn "configure..."
    runIn(1800,logsOff)
    def cmds = []
    for(child in getChildDevices())
    {
        cmds.addAll(child.configure())
    }
    return cmds
}

def installed() {
    fetchChild("RGB")
    fetchChild("CT")
}

def fetchChild(String type){
    String thisId = device.id
    def cd = getChildDevice("${thisId}-${type}")
    if (!cd) {
        cd = addChildDevice("GLEDOPTO ZigBee RGB+CCT Light GL-C-008 2ID ${type}", "${thisId}-${type}", [name: "${device.displayName} ${type}", isComponent: true])
    }

    return cd 
}
