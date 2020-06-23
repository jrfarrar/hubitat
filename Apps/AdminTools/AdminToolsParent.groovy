/*
 *	Admin tools (Parent)
 *
 *	Author: J.R. Farrar
 * 
 * 
 * 
 */

definition(
    name: "Admin tools",
    namespace: "jrfarrar",
    singleInstance: true,
    author: "J.R. Farrar",
    description: "Admin tools",
    category: "My Apps",
    iconUrl: "",
    iconX2Url: "",
    iconX3Url: "",
    importUrl: "https://raw.githubusercontent.com/jrfarrar/hubitat/master/Apps/AdminTools/AdminToolsParent.groovy",
)

preferences {
	page(name: "mainPage")
}

def mainPage() {
	return dynamicPage(name: "mainPage", title: "", install: true, uninstall: true) {
        if(!state.ATInstalled) {
            section("Hit Done to install Admin tools Manager App!") {
        	}
        }
        else {
        	section("Create a new child app.") {
            	app(name: "childApps", appName: "Temperature Controller Child", namespace: "jrfarrar", title: "New Temperature Turn ON/OFF Instance", multiple: true)
                app(name: "childApps", appName: "Humidity Controller Child", namespace: "jrfarrar", title: "New Humidity Turn ON/OFF Instance", multiple: true)
                app(name: "childApps", appName: "Is It Dark Child", namespace: "jrfarrar", title: "New Is It Dark Instance", multiple: true)
                app(name: "childApps", appName: "Power Monitor Child", namespace: "jrfarrar", title: "New Power Monitor Instance", multiple: true)
                app(name: "childApps", appName: "Septic Pump Child", namespace: "jrfarrar", title: "New Septic Pump Instance", multiple: true)
                //Location Notifier Child
                app(name: "childApps", appName: "Location Notifier Child", namespace: "jrfarrar", title: "New Location Notifier Instance", multiple: true)
        	}
    	}
    }
}

def installed() {
    state.ATInstalled = true
	initialize()
}

def updated() {
	unsubscribe()
	initialize()
}

def initialize() {
} 
