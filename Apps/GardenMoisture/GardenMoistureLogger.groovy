/**
 *  Garden Moisture Logger  (PARENT)
 *
 *  Copyright 2026 J.R. Farrar
 *
 *  Parent container for per-zone garden moisture loggers. Holds no logic of its own.
 *
 *  v0.1.0  2026-08-31  Initial release. Collection and estimation only - no
 *                      notifications, no valve control, no device commands
 *                      other than resetting its own marker switches.
 */

definition(
    name: "Garden Moisture Logger",
    namespace: "jrfarrar",
    author: "J.R. Farrar",
    description: "Records garden soil moisture, rain and ET for analysis. Parent app.",
    category: "My Apps",
    iconUrl: "",
    iconX2Url: "",
    iconX3Url: "",
    singleInstance: true,
    installOnOpen: true,
    importUrl: "https://raw.githubusercontent.com/jrfarrar/hubitat/master/Apps/GardenMoisture/GardenMoistureLogger.groovy"
)

preferences {
    page(name: "mainPage")
}

def mainPage() {
    dynamicPage(name: "mainPage", title: "<b>Garden Moisture Logger</b>", install: true, uninstall: true) {
        section {
            paragraph "Adds one logger per soil probe / garden zone. Each child samples soil " +
                      "moisture on a fixed grid, records wetting events, and maintains rolling " +
                      "estimates of field capacity and stress point.<br>" +
                      "<i>This version only observes and estimates - it sends no notifications " +
                      "and controls nothing.</i>"
        }
        section("<b>Zones</b>") {
            app(name: "childApps", appName: "Garden Moisture Logger Child", namespace: "jrfarrar",
                title: "Add a garden zone", multiple: true)
        }
        section("<b>Logging</b>") {
            input "logEnable", "bool", title: "Debug logging", defaultValue: false
        }
    }
}

def installed() { initialize() }

def updated() { initialize() }

def initialize() {
    if (logEnable) log.debug "Garden Moisture Logger: ${childApps?.size() ?: 0} child app(s)"
}

def uninstalled() {
    log.info "Garden Moisture Logger removed"
}
