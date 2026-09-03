/*
 *	Notification Router (Parent)
 *
 *	Author: J.R. Farrar
 *
 *	v1.0.0  2026-09-02  initial
 *
 *	Central fan-out for notifications across the whole hub ecosystem.
 *
 *	Each child app owns one "channel" -- a Virtual Notification Channel device
 *	that lives on this hub and is shared to the other hubs via Hub Mesh. Apps
 *	anywhere call deviceNotification() on the meshed channel device; the child
 *	app on this hub decides who actually hears about it.
 *
 *	The point: changing who gets alerted means editing ONE app. No app anywhere
 *	else in the ecosystem ever has to be touched again.
 */

definition(
    name: "Notification Router",
    namespace: "jrfarrar",
    singleInstance: true,
    author: "J.R. Farrar",
    description: "Central notification fan-out. Channels in, devices out, routing in one place.",
    category: "My Apps",
    iconUrl: "",
    iconX2Url: "",
    iconX3Url: "",
    importUrl: "https://raw.githubusercontent.com/jrfarrar/hubitat/master/Apps/NotificationRouter/NotificationRouterParent.groovy",
)

preferences {
    page(name: "mainPage")
    page(name: "pageGlobals")
    page(name: "pageAbout")
}

def mainPage() {
    return dynamicPage(name: "mainPage", title: "", install: true, uninstall: true) {
        if (!state.NRInstalled) {
            section("Hit Done to install the Notification Router!") {
                paragraph "Then re-open it to add channels."
            }
        } else {
            section(getFormat("title", "Notification Router")) {
                paragraph statusBlock()
            }
            section(getFormat("header-green", "CHANNELS")) {
                app(name: "childApps", appName: "Notification Router Channel", namespace: "jrfarrar",
                        title: "Add a notification channel", multiple: true)
            }
            section(getFormat("header-green", "GLOBAL")) {
                href "pageGlobals", title: "Global overrides", description: globalSummary()
            }
            section(getFormat("header-green", "LOGGING")) {
                input(name: "logLevel", title: "IDE logging level", multiple: false, required: true,
                        type: "enum", options: getLogLevels(), submitOnChange: false, defaultValue: "1")
            }
            section {
                href "pageAbout", title: "How this works / Hub Mesh setup", description: ""
            }
        }
    }
}

def pageGlobals() {
    dynamicPage(name: "pageGlobals", title: getFormat("title", "Global overrides"), install: false, uninstall: false) {
        section(getFormat("header-green", "MASTER SWITCH")) {
            paragraph "These apply to <b>every</b> channel. Individual channels can still be more restrictive."
            input(name: "globalDisable", type: "bool", title: "Disable ALL routing (messages are logged, nothing is sent)",
                    defaultValue: false, submitOnChange: true)
            input(name: "muteSwitch", type: "capability.switch", title: "Mute switch (optional)",
                    required: false, multiple: false, submitOnChange: true)
            if (muteSwitch) {
                input(name: "muteWhen", type: "enum", title: "Mute when that switch is...",
                        options: ["on", "off"], defaultValue: "on", required: true)
            }
            input(name: "globalMinSev", type: "enum", title: "Drop anything below this severity, everywhere",
                    options: sevOptions(), defaultValue: "INFO", required: true)
        }
        section(getFormat("header-green", "MUTE BYPASS")) {
            paragraph "Severity that gets through even when muted or globally floored. Set to 'never' to make mute absolute."
            input(name: "globalBypassSev", type: "enum", title: "Always deliver at or above",
                    options: sevOptions() + ["never": "never bypass"], defaultValue: "CRITICAL", required: true)
        }
        section(getFormat("header-green", "DEFAULT QUIET HOURS")) {
            paragraph "Channels can inherit these instead of setting their own. Leave blank for none."
            input(name: "defQuietStart", type: "time", title: "Default quiet hours start", required: false)
            input(name: "defQuietEnd", type: "time", title: "Default quiet hours end", required: false)
        }
    }
}

def pageAbout() {
    dynamicPage(name: "pageAbout", title: getFormat("title", "How this works"), install: false, uninstall: false) {
        section(getFormat("header-green", "THE SHAPE OF IT")) {
            paragraph "<pre style='font-size:0.85em'>" +
                    "apps on ANY hub  --deviceNotification()-->  meshed channel device\n" +
                    "                                                    |\n" +
                    "                                        (Hub Mesh relays to this hub)\n" +
                    "                                                    v\n" +
                    "                                    Virtual Notification Channel device\n" +
                    "                                                    |\n" +
                    "                                     Notification Router Channel app\n" +
                    "                                                    |\n" +
                    "                        +---------------+-----------+-----------+\n" +
                    "                        v               v                       v\n" +
                    "                  push devices     speakers/TTS             switches\n" +
                    "</pre>"
        }
        section(getFormat("header-green", "HUB MESH SETUP")) {
            paragraph "1. Add a channel here. It creates a device named after the channel.<br>" +
                    "2. Settings &rarr; Hub Mesh on THIS hub &rarr; tick that device to share it.<br>" +
                    "3. On each other hub, Settings &rarr; Hub Mesh &rarr; accept the linked device.<br>" +
                    "4. Apps on those hubs now see it in any 'notification device' picker.<br><br>" +
                    "<b>Unverified:</b> this design assumes Hub Mesh relays the deviceNotification " +
                    "<i>command</i> from a remote hub back to the source device, and that the linked " +
                    "device keeps its Notification capability in remote pickers. Confirm with one " +
                    "channel before migrating any real app onto it."
        }
        section(getFormat("header-green", "LOOP GUARD")) {
            paragraph "A channel will refuse to deliver to another channel device. Chaining channels " +
                    "would let a message loop forever, so it is blocked outright rather than " +
                    "depth-limited."
        }
    }
}

def statusBlock() {
    def kids = getChildApps()
    if (!kids) return "No channels yet."
    def sb = "<table style='font-size:0.9em'><tr><th align=left>Channel&nbsp;&nbsp;</th>" +
            "<th align=left>Targets&nbsp;&nbsp;</th><th align=left>Last message</th></tr>"
    kids.sort { it.label }.each { k ->
        def s = [:]
        try { s = k.getChannelStatus() ?: [:] } catch (e) { s = [name: k.label, targets: "?", last: "error: ${e.message}"] }
        sb += "<tr><td>${s.name ?: k.label}</td><td>${s.targets ?: 0}</td><td>${s.last ?: '-'}</td></tr>"
    }
    return sb + "</table>"
}

def globalSummary() {
    def bits = []
    if (globalDisable) bits << "<b>ALL ROUTING DISABLED</b>"
    if (muteSwitch) bits << "mute when ${muteSwitch} is ${muteWhen ?: 'on'}"
    if (globalMinSev && globalMinSev != "INFO") bits << "floor ${globalMinSev}"
    if (defQuietStart && defQuietEnd) bits << "default quiet hours set"
    return bits ? bits.join(", ") : "no global overrides"
}

def installed() {
    state.NRInstalled = true
    infolog "installed"
    initialize()
}

def updated() {
    infolog "updated"
    unsubscribe()
    initialize()
}

def initialize() {
}

/*
 * Read by every child on every message. Returns plain data only -- the child's
 * routing decision has to stay testable, so nothing here is a device object.
 */
def getGlobalConfig() {
    boolean muted = false
    if (muteSwitch) {
        String want = muteWhen ?: "on"
        muted = (muteSwitch.currentValue("switch") == want)
    }
    return [
            disabled     : (globalDisable == true),
            muted        : muted,
            muteSwitchName: (muteSwitch ? "${muteSwitch}" : null),
            minSev       : (globalMinSev ?: "INFO"),
            bypassSev    : (globalBypassSev ?: "CRITICAL"),
            defQuietStart: defQuietStart,
            defQuietEnd  : defQuietEnd
    ]
}

def sevOptions() {
    return ["INFO": "INFO", "LOW": "LOW", "NORMAL": "NORMAL", "HIGH": "HIGH", "CRITICAL": "CRITICAL"]
}

def getFormat(type, myText = "") {			// Modified from @Stephack Code
    if (type == "header-green") return "<div style='color:#ffffff;font-weight: bold;background-color:#81BC00;border: 1px solid;box-shadow: 2px 3px #A9A9A9'>${myText}</div>"
    if (type == "line") return "<hr style='background-color:#1A77C9; height: 1px; border: 0;'>"
    if (type == "title") return "<h2 style='color:#1A77C9;font-weight: bold'>${myText}</h2>"
    if (type == "title2") return "<div style='color:#1A77C9;font-weight: bold'>${myText}</div>"
}

def debuglog(statement) {
    def logL = 0
    if (logLevel) logL = logLevel.toInteger()
    if (logL == 0) { return }
    else if (logL >= 2) { log.debug("Notification Router: " + statement) }
}

def infolog(statement) {
    def logL = 0
    if (logLevel) logL = logLevel.toInteger()
    if (logL == 0) { return }
    else if (logL >= 1) { log.info("Notification Router: " + statement) }
}

def getLogLevels() {
    return [["0": "None"], ["1": "Running"], ["2": "NeedHelp"]]
}
