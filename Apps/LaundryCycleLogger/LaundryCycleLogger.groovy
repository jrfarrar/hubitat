/**
 *  Laundry Cycle Logger  (PARENT)
 *
 *  Copyright 2026 J.R. Farrar
 *
 *  Parent container for per-appliance cycle loggers. Holds no logic of its own.
 *
 *  v0.1.0  2026-08-30  Initial release. Collection only - no commands, no
 *                      notifications, no device writes.
 */

definition(
    name: "Laundry Cycle Logger",
    namespace: "jrfarrar",
    author: "J.R. Farrar",
    description: "Records appliance power cycles for analysis. Parent app.",
    category: "My Apps",
    iconUrl: "",
    iconX2Url: "",
    iconX3Url: "",
    singleInstance: true,
    installOnOpen: true,
    importUrl: "https://raw.githubusercontent.com/jrfarrar/hubitat/master/Apps/LaundryCycleLogger/LaundryCycleLogger.groovy"
)

preferences {
    page(name: "mainPage")
}

def mainPage() {
    dynamicPage(name: "mainPage", title: "<b>Laundry Cycle Logger</b>", install: true, uninstall: true) {
        section {
            paragraph "Adds one logger per appliance. Each child records power cycles " +
                      "to a rolling summary and writes a downsampled profile to File Manager.<br>" +
                      "<i>This app only observes - it sends no commands and no notifications.</i>"
        }
        section("<b>Appliances</b>") {
            app(name: "childApps", appName: "Laundry Cycle Logger Child", namespace: "jrfarrar",
                title: "Add an appliance logger", multiple: true)
        }
        section("<b>Logging</b>") {
            input "logEnable", "bool", title: "Debug logging", defaultValue: false
        }
    }
}

def installed() { initialize() }

def updated() { initialize() }

def initialize() {
    if (logEnable) log.debug "Laundry Cycle Logger: ${childApps?.size() ?: 0} child app(s)"
}

def uninstalled() {
    log.info "Laundry Cycle Logger removed"
}
