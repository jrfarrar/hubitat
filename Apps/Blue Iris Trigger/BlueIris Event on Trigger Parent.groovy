/*
 *	BlueIris Event on Trigger (Parent)
 *
 *	Author: J.R. Farrar
 * 
 * 
 * 
 */

definition(
    name: "BlueIris Event on Trigger",
    namespace: "jrfarrar",
    singleInstance: true,
    author: "J.R. Farrar",
    description: "Trigger BlueIris for Events - Parent Manager",
    category: "My Apps",
    iconUrl: "",
    iconX2Url: "",
    iconX3Url: "",
)

preferences {
	page(name: "mainPage")
}

def mainPage() {
	return dynamicPage(name: "mainPage", title: "", install: true, uninstall: true) {
        if(!state.BITInstalled) {
            section("Hit Done to install BlueIris Triggers Manager App!") {
        	}
        }
        else {
        	section("Create a new BlueIris Triggers Instance.") {
            	app(name: "childApps", appName: "Blue Iris Trigger Child", namespace: "jrfarrar", title: "New Blue Iris Trigger Instance", multiple: true)
        	}
    	}
    }
}

def installed() {
    state.BITInstalled = true
	initialize()
}

def updated() {
	unsubscribe()
	initialize()
}

def initialize() {
}
