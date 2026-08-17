/*
 *  Blue Iris Mode Manager
 *
 *  Author: J.R. Farrar
 *
 *  Keeps Blue Iris in the correct profile based on Hubitat mode + a "dark outside"
 *  switch, using the companion "Blue Iris Status (MQTT)" driver to both READ Blue
 *  Iris's current profile and SET it. Event-driven (reacts to mode changes, the dark
 *  switch, and Blue Iris drifting on its own), with a slow timer as a backstop.
 *
 *  Desired profile logic (this install: 1=Away, 2=Day, 3=Night):
 *      - dark outside (switch off) -> Night (3)   [always wins, even if HE mode is Away]
 *      - else HE mode is an away mode -> Away (1)
 *      - else -> Day (2)
 */

definition(
    name: "Blue Iris Mode Manager",
    namespace: "jrfarrar",
    author: "J.R. Farrar",
    description: "Keep Blue Iris in the correct profile via the MQTT status driver",
    iconUrl: "",
    iconX2Url: "",
    iconX3Url: "",
    importUrl: "https://raw.githubusercontent.com/jrfarrar/hubitat/master/Apps/BlueIrisModeManager/BlueIrisModeManager.groovy"
)

preferences {
    page(name: "pageConfig")
}

def pageConfig() {
    dynamicPage(name: "pageConfig", title: "", install: true, uninstall: true, refreshInterval: 0) {

        section("Blue Iris device") {
            paragraph "Select the 'Blue Iris Status (MQTT)' virtual device."
            input "biDevice", "capability.refresh", title: "Blue Iris Status (MQTT) device", required: true, multiple: false
        }

        section("Mode logic") {
            input "darkOutside", "capability.switch", title: "Is-it-dark switch (off = dark -> Night)", required: true
            input "awayModes", "mode", title: "Hubitat modes that mean AWAY", required: true, multiple: true
            paragraph "Profiles used: Away = 1, Day = 2, Night = 3. Dark always forces Night, even in an away mode."
        }

        section("Safety net / timing") {
            input "backstop", "bool", title: "Run a 30-minute backstop check", defaultValue: true
            input "cooldownSec", "number", title: "Don't re-send the same correction more often than (seconds)", defaultValue: 60, required: true
        }

        section("Logging") {
            input "logEnable", "bool", title: "Enable debug logging", defaultValue: true
        }

        section("App name") {
            input "thisName", "text", title: "App name", submitOnChange: true
            if (thisName) app.updateLabel(thisName) else app.updateSetting("thisName", "Blue Iris Mode Manager")
        }
    }
}

// ---------- lifecycle ----------

def installed() { log.info "Blue Iris Mode Manager installed"; initialize() }
def updated()   { log.info "Blue Iris Mode Manager updated";   initialize() }

def initialize() {
    unschedule()
    unsubscribe()

    subscribe(location, "mode", modeHandler)
    subscribe(darkOutside, "switch", switchHandler)
    subscribe(biDevice, "profile", biProfileHandler)
    subscribe(biDevice, "appStatus", biAppHandler)

    if (backstop) runEvery30Minutes("backstopCheck")

    // Give the driver a moment to have current values, then sync once.
    runIn(5, "initialCorrect")
    debug "initialized; subscriptions and backstop set"
}

def uninstalled() {
    unschedule()
    unsubscribe()
    log.info "Blue Iris Mode Manager uninstalled"
}

// ---------- event handlers ----------

def modeHandler(evt)      { debug "HE mode -> ${evt.value}";        correctIfNeeded("mode change") }
def switchHandler(evt)    { debug "dark switch -> ${evt.value}";    correctIfNeeded("dark switch") }
def biProfileHandler(evt) { debug "BI reported profile ${evt.value}"; correctIfNeeded("BI drift") }
def biAppHandler(evt)     { debug "BI app status -> ${evt.value}";  correctIfNeeded("BI app status") }
def backstopCheck()       { correctIfNeeded("30-min backstop") }
def initialCorrect()      { correctIfNeeded("startup") }

// ---------- core logic ----------

private Integer desiredProfile() {
    if (darkOutside?.currentValue("switch") == "off") return 3            // dark -> Night, always
    if (awayModes && awayModes.contains(location.mode)) return 1          // away
    return 2                                                             // day
}

private correctIfNeeded(String source) {
    if (!biDevice) { log.warn "No Blue Iris device selected"; return }

    def raw = biDevice.currentValue("profile")
    if (raw == null) { debug "BI profile not known yet (trigger: ${source})"; return }
    Integer current = (raw as BigDecimal).toInteger()

    Integer desired = desiredProfile()

    if (current == desired) {
        debug "BI already on correct profile ${desired} (${source})"
        return
    }

    // Loop guard: if we JUST asked for this same profile and BI still hasn't taken it,
    // don't hammer it. A real change in 'desired' bypasses this immediately.
    long nowE = now()
    long cd = ((cooldownSec ?: 60) as long) * 1000
    if (state.lastTryProfile == desired && state.lastTryEpoch && (nowE - state.lastTryEpoch) < cd) {
        log.warn "BI still on ${current}, wanted ${desired}, but correction was sent " +
                 "${((nowE - state.lastTryEpoch) / 1000) as int}s ago — waiting. BI may be rejecting/overriding it."
        return
    }

    log.info "Correcting Blue Iris: ${current} -> ${desired} (trigger: ${source})"
    state.lastTryProfile = desired
    state.lastTryEpoch = nowE
    biDevice.setProfile(desired)
}

// ---------- logging ----------

private debug(msg) {
    if (logEnable) log.debug "${thisName ?: 'BI Mode Mgr'}: ${msg}"
}