/**
 *  _MakeVSwitch  --  v1.0.0  2026-09-06
 *
 *  Creates virtual switches. That is all it does.
 *
 *  WHY THIS EXISTS: Hubitat's Add-Device wizard is a Vue flow that will not advance from
 *  synthetic clicks, and `/device/create` renders blank. Creating a child device from a tiny app
 *  is the documented reliable route (see HUBITAT_ACCESS_PATHS.md).
 *
 *  Built to give the production Bathroom Humidity Fan a virtual fan, so it keeps running as a
 *  comparison while Fan NG takes the real fan -- J.R., 2026-09-06.
 *
 *  The children are COMPONENT devices of this app, so deleting this app deletes them. Do not
 *  point anything long-lived at them without moving them out first.
 */

definition(
    name: "_MakeVSwitch",
    namespace: "jrfarrar",
    author: "J.R. Farrar",
    description: "Creates virtual switches (scaffolding)",
    category: "Convenience",
    iconUrl: "", iconX2Url: "",
    importUrl: "https://raw.githubusercontent.com/jrfarrar/hubitat/master/Apps/AdminTools/MakeVSwitch.groovy",
    singleThreaded: true,
    installOnOpen: true
)

preferences { page(name: "mainPage") }

def mainPage() {
    dynamicPage(name: "mainPage", title: "", install: true, uninstall: true) {
        section("<b>Name</b>") {
            input "thisName", "text", title: "Name this instance", required: true,
                  defaultValue: "_MakeVSwitch", submitOnChange: true
            if (thisName) app.updateLabel(thisName)
        }
        section("<b>Switches to create</b>") {
            input "switchNames", "text",
                  title: "Comma-separated names. Existing ones are left alone.",
                  required: false, submitOnChange: true
            input "makeNow", "bool", title: "Create them now", defaultValue: false,
                  submitOnChange: true
        }
        section("<b>Created</b>") {
            def kids = getChildDevices()
            paragraph kids ? kids.collect { "&bull; ${it.displayName} (${it.deviceNetworkId})" }.join("<br>")
                           : "<i>none yet</i>"
        }
    }
}

def installed() { initialize() }
def updated()   { initialize() }

def initialize() {
    if (makeNow == true && switchNames) {
        switchNames.split(",").each { raw ->
            String nm = raw.trim()
            if (!nm) return
            String dni = "vsw_${app.id}_${nm.replaceAll(/[^A-Za-z0-9_]/, '_')}"
            if (getChildDevice(dni)) {
                log.info "${app.label}: ${nm} already exists"
                return
            }
            try {
                addChildDevice("hubitat", "Virtual Switch", dni,
                               [name: nm, label: nm, isComponent: false])
                log.info "${app.label}: created virtual switch ${nm}"
            } catch (e) {
                log.warn "${app.label}: could not create ${nm}: ${e.message}"
            }
        }
        // One-shot: clear the flag so a later Done does not try again.
        app.updateSetting("makeNow", [type: "bool", value: false])
    }
}

def uninstalled() {
    log.warn "${app.label}: uninstalled - its child switches go with it."
}
