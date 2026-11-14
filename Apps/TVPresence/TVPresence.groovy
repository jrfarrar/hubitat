/**
 *  TV Presence
 *
 *  A TV follows room occupancy: on when motion says someone arrived, off after a per-mode
 *  stay time with no motion. It also minds the timing a TV needs:
 *    - no On too soon after an Off (a TV that is still shutting down ignores the wake),
 *    - On is retried until the TV reports on.
 *  Every value is an option on the app page; nothing is hard coded.
 *
 *  NOTE: installOnOpen is true - opening this app from "Add user app" creates a LIVE
 *  instance immediately. It does nothing until a TV and a motion sensor are chosen.
 *
 *  1.0.0 - 2026-11-14 - first version. Built for the office Samsung Frame (driver
 *          "Samsung TV Remote" on C7 Other, used on the main hub over Hub Mesh).
 */
import groovy.transform.Field

@Field static final String APP_VERSION = "1.0.0"

definition(
    name: "TV Presence",
    namespace: "jrfarrar",
    author: "J.R. Farrar",
    description: "TV follows room occupancy: on with motion, off after a per-mode stay time, with on/off timing guards",
    category: "Convenience",
    iconUrl: "",
    iconX2Url: "",
    installOnOpen: true,
    singleThreaded: true,
    importUrl: "https://raw.githubusercontent.com/jrfarrar/hubitat/master/Apps/TVPresence/TVPresence.groovy"
)

preferences {
    page(name: "mainPage")
}

Map mainPage() {
    dynamicPage(name: "mainPage", title: "TV Presence v${APP_VERSION}", install: true, uninstall: true) {
        section("Name") {
            input "thisName", "text", title: "Name for this instance", submitOnChange: true, required: false
            if (thisName) refreshLabel()
            paragraph statusText()
        }
        section("TV") {
            input "tv", "capability.switch", title: "TV to control (its on and off commands are used)",
                multiple: false, required: false, submitOnChange: true
        }
        section("Occupancy") {
            input "motionSensors", "capability.motionSensor", title: "Motion sensors for the room",
                multiple: true, required: false, submitOnChange: true
        }
        section("Stay time - minutes with no motion before the TV is turned off") {
            input "defaultStay", "decimal", title: "Default stay minutes", defaultValue: 15,
                required: false, submitOnChange: true
            location.modes.sort { it.name }.each { m ->
                input "stay_${m.id}", "decimal", title: "In ${m.name} (blank = default)",
                    required: false, submitOnChange: true, width: 4
            }
        }
        section("Turning the TV on") {
            input "turnOnEnabled", "bool", title: "Turn the TV on when someone arrives", defaultValue: true,
                required: false, submitOnChange: true
            input "onArrivalOnly", "bool", defaultValue: true, required: false, submitOnChange: true,
                title: "Only on arrival (room was empty). Off = any new motion turns it back on, even after you switched it off by hand"
            input "onModes", "enum", title: "Modes in which the TV may be turned on (none chosen = all modes)",
                options: modeOptions(), multiple: true, required: false, submitOnChange: true
            input "minOffGapSec", "number", title: "Seconds to wait after an Off before sending On", defaultValue: 90,
                required: false, submitOnChange: true, width: 4
            input "onRetrySec", "number", title: "Seconds between On and the check that it worked", defaultValue: 20,
                required: false, submitOnChange: true, width: 4
            input "onRetries", "number", title: "Extra On attempts if the TV still reports off", defaultValue: 3,
                required: false, submitOnChange: true, width: 4
            input "homeAfterOn", "bool", defaultValue: false, required: false, submitOnChange: true,
                title: "Send the TV's Home command once it reports on (only if the TV device has one)"
        }
        section("Turning the TV off") {
            input "turnOffEnabled", "bool", title: "Turn the TV off when the stay time runs out", defaultValue: true,
                required: false, submitOnChange: true
            input "offOnlyIfOn", "bool", title: "Send Off only when the TV reports on", defaultValue: true,
                required: false, submitOnChange: true
        }
        section("Pause") {
            input "pauseSwitch", "capability.switch", title: "Pause switch (optional) - while paused the TV is left alone",
                multiple: false, required: false, submitOnChange: true
            input "pauseWhen", "enum", title: "Paused when that switch is", options: ["on", "off"], defaultValue: "on",
                required: false, submitOnChange: true, width: 4
        }
        section("Logging") {
            input "infoLog", "bool", title: "Log what the app does", defaultValue: true, required: false, submitOnChange: true
            input "debugLog", "bool", title: "Debug logging (turns itself off after 30 minutes)", defaultValue: false,
                required: false, submitOnChange: true
        }
    }
}

Map modeOptions() {
    return location.modes.sort { it.name }.collectEntries { [(it.id.toString()): it.name] }
}

// ===== lifecycle =====
void installed() { initialize() }

void updated() {
    unsubscribe()
    unschedule()
    initialize()
}

void initialize() {
    if (state.occupied == null) state.occupied = false
    state.pendingOn = false
    if (motionSensors) subscribe(motionSensors, "motion", "motionHandler")
    if (tv) subscribe(tv, "switch", "tvHandler")
    if (pauseSwitch) subscribe(pauseSwitch, "switch", "pauseHandler")
    subscribe(location, "mode", "modeHandler")
    if (boolSetting("debugLog", false)) runIn(1800, "debugOff")
    // pick the room state back up: a stay timer that was running is restarted from where it was
    if (tv && motionSensors) {
        if (anyMotion()) {
            state.occupied = true
            state.inactiveSince = null
        } else if (state.occupied) {
            if (!state.inactiveSince) state.inactiveSince = now()
            scheduleOff()
        }
    }
    refreshLabel()
}

// ===== events =====
void motionHandler(evt) {
    logDebug "motion ${evt.value} on ${evt.displayName}"
    if (evt.value == "active") {
        boolean wasOccupied = (state.occupied == true)
        unschedule("stayExpired")
        state.occupied = true
        state.inactiveSince = null
        if (!wasOccupied || !boolSetting("onArrivalOnly", true)) {
            requestOn("motion on ${evt.displayName}")
        }
    } else if (!anyMotion()) {
        state.inactiveSince = now()
        scheduleOff()
    }
}

void tvHandler(evt) {
    logDebug "TV switch ${evt.value}"
    if (evt.value == "off") {
        // remember when the TV went off, whoever did it, so the next On keeps its distance
        Long last = state.tvOffAt as Long
        if (last == null || now() - last > 15000L) state.tvOffAt = now()
    }
    refreshLabel()
}

void pauseHandler(evt) {
    if (paused()) {
        note("paused by ${pauseSwitch.displayName}")
    } else {
        note("pause ended")
        if (anyMotion()) {
            state.occupied = true
            state.inactiveSince = null
            requestOn("pause ended with motion active")
        } else {
            // nobody there: give the room one full stay time from now, then turn off
            state.inactiveSince = now()
            scheduleOff()
        }
    }
    refreshLabel()
}

void modeHandler(evt) {
    logDebug "mode is now ${evt.value}"
    // a stay timer that is running takes the new mode's stay time, counted from when motion stopped
    if (state.inactiveSince && !anyMotion()) {
        unschedule("stayExpired")
        scheduleOff()
    }
    refreshLabel()
}

// ===== stay timer =====
void scheduleOff() {
    BigDecimal mins = stayMinutes()
    Long since = state.inactiveSince as Long
    long elapsedSec = since != null ? ((now() - since).intdiv(1000L) as long) : 0L
    long secs = (mins * 60).longValue() - elapsedSec
    if (secs < 1L) secs = 1L
    logDebug "no motion: off in ${secs}s (stay ${mins} min in ${location.currentMode?.name})"
    runIn(secs, "stayExpired")
}

void stayExpired() {
    if (anyMotion()) {
        logDebug "stay time ran out but motion is active again - nothing to do"
        return
    }
    BigDecimal mins = stayMinutes()
    state.occupied = false
    state.inactiveSince = null
    requestOff("no motion for ${mins} min")
}

// ===== turning on =====
void requestOn(String why) {
    if (!tv) return
    if (!boolSetting("turnOnEnabled", true)) { note("on skipped (${why}): turning on is disabled"); return }
    if (paused()) { note("on skipped (${why}): paused by ${pauseSwitch.displayName}"); return }
    if (!onAllowedInMode()) { note("on skipped (${why}): mode ${location.currentMode?.name} is not an on-mode"); return }
    if (state.pendingOn) return
    long gapMs = intSetting("minOffGapSec", 90) * 1000L
    Long offAt = state.tvOffAt as Long
    boolean tooSoon = (offAt != null) && (now() - offAt < gapMs)
    if (!tooSoon && tv.currentValue("switch") == "on") {
        logDebug "TV already reports on (${why})"
        refreshLabel()
        return
    }
    state.pendingOn = true
    state.onTries = 0
    if (tooSoon) {
        long agoSec = (now() - offAt).intdiv(1000L) as long
        long waitSec = ((gapMs - (now() - offAt)).intdiv(1000L) as long) + 1L
        note("on delayed ${waitSec}s (${why}): the TV went off ${agoSec}s ago")
        runIn(waitSec, "doOn")
    } else {
        doOn()
    }
    refreshLabel()
}

void doOn() {
    if (!state.pendingOn || !tv) return
    if (!state.occupied || paused() || !onAllowedInMode()) {
        state.pendingOn = false
        note("on cancelled: the room no longer qualifies")
        refreshLabel()
        return
    }
    state.onTries = ((state.onTries ?: 0) as int) + 1
    note("turning ${tv.displayName} on (try ${state.onTries})")
    state.lastOnAt = now()
    tv.on()
    runIn(intSetting("onRetrySec", 20), "verifyOn")
}

void verifyOn() {
    if (!state.pendingOn || !tv) return
    int tries = (state.onTries ?: 0) as int
    if (tv.currentValue("switch") == "on") {
        state.pendingOn = false
        note("TV reports on after ${tries} try(s)")
        if (boolSetting("homeAfterOn", false) && tv.hasCommand("home")) tv.home()
    } else if (tries < 1 + intSetting("onRetries", 3)) {
        doOn()
    } else {
        state.pendingOn = false
        note("gave up: TV still reports off after ${tries} try(s)")
        log.warn "${appName()}: ${tv.displayName} still reports off after ${tries} On attempt(s)"
    }
    refreshLabel()
}

// ===== turning off =====
void requestOff(String why) {
    if (!tv) return
    unschedule("doOn")
    unschedule("verifyOn")
    state.pendingOn = false
    if (!boolSetting("turnOffEnabled", true)) { note("off skipped (${why}): turning off is disabled"); refreshLabel(); return }
    if (paused()) { note("off skipped (${why}): paused by ${pauseSwitch.displayName}"); refreshLabel(); return }
    if (boolSetting("offOnlyIfOn", true) && tv.currentValue("switch") != "on") {
        note("off skipped (${why}): TV already reports off")
        refreshLabel()
        return
    }
    note("turning ${tv.displayName} off: ${why}")
    state.tvOffAt = now()
    tv.off()
    refreshLabel()
}

// ===== helpers =====
boolean anyMotion() {
    return motionSensors ? motionSensors.any { it.currentValue("motion") == "active" } : false
}

boolean paused() {
    if (!pauseSwitch) return false
    return pauseSwitch.currentValue("switch") == (pauseWhen ?: "on")
}

boolean onAllowedInMode() {
    if (!onModes) return true
    List<String> ids = (onModes instanceof Collection) ? onModes.collect { it.toString() } : [onModes.toString()]
    return ids.contains(location.currentMode?.id?.toString())
}

BigDecimal stayMinutes() {
    def v = settings["stay_${location.currentMode?.id}"]
    if (v == null || v.toString() == "") v = settings["defaultStay"]
    if (v == null || v.toString() == "") v = 15
    return new BigDecimal(v.toString())
}

boolean boolSetting(String name, boolean dflt) {
    def v = settings[name]
    return (v == null) ? dflt : (v.toString() == "true")
}

int intSetting(String name, int dflt) {
    def v = settings[name]
    if (v == null || v.toString() == "") return dflt
    return new BigDecimal(v.toString()).intValue()
}

String appName() { return thisName ?: "TV Presence" }

void refreshLabel() {
    String s
    String c
    if (!tv || !motionSensors) { s = "not set up"; c = "red" }
    else if (paused()) { s = "Paused"; c = "orange" }
    else if (state.pendingOn) { s = "Turning on"; c = "blue" }
    else if (tv.currentValue("switch") == "on") { s = "TV on"; c = "green" }
    else { s = "TV off"; c = "gray" }
    app.updateLabel("${appName()} <span style='color:${c}'>(${s})</span>")
}

String statusText() {
    if (!tv || !motionSensors) return "Choose a TV and at least one motion sensor. Until then the app does nothing."
    String room = state.occupied ? "occupied" : "empty"
    String t = tv.currentValue("switch") ?: "unknown"
    String p = paused() ? " - PAUSED" : ""
    String last = state.lastNote ?: "nothing yet"
    return "Room: <b>${room}</b>. TV reports: <b>${t}</b>${p}. Mode: ${location.currentMode?.name}, stay ${stayMinutes()} min.<br>Last action: ${last}"
}

void note(String msg) {
    state.lastNote = "${new Date().format('yyyy-MM-dd HH:mm:ss', location.timeZone)} ${msg}"
    if (boolSetting("infoLog", true)) log.info "${appName()}: ${msg}"
}

void logDebug(String msg) {
    if (boolSetting("debugLog", false)) log.debug "${appName()}: ${msg}"
}

void debugOff() {
    app.updateSetting("debugLog", [value: "false", type: "bool"])
    log.info "${appName()}: debug logging turned off"
}
