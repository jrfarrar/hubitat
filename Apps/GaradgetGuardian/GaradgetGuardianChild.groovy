/**
 *  Garadget Guardian Child  -  one per garage door
 *
 *  Watches a Garadget (driver "Garadget MQTT" 1.4.6+, which publishes the "health" attribute) and,
 *  when the unit is offline, its reflection sensor has frozen, or an open/close was not confirmed by
 *  the sensor, power-cycles the Garadget's own switched outlet, proves the unit came back, and tells
 *  you what it now sees - above all whether the door is actually OPEN.
 *
 *  v1.0.3  2026-10-31  first HPM release. Cancels the pending boot check once a restart has been seen.
 *                      End-to-end tested on real hardware (3 Garadgets, Zigbee outlets via Hub Mesh).
 *  v1.0.2  2026-10-31  "stopped" alone is not proof the unit works: Door 2 reported stopped while its
 *                      laser was blind (read 0) and the door was really closed. Skip the power cycle only
 *                      when the reflection reading actually moved around the failed command.
 *  v1.0.1  2026-10-31  (superseded) never power-cycled a failed command while the door reported "stopped".
 *  v1.0.0  2026-10-31  first version.
 *
 *  Why: 2026-10-22 -> 10-30 Garage Door 3's sensor froze for 7 days while the unit stayed online and
 *  kept reporting "closed", including a night the door was open. A power cycle fixed it. The driver
 *  now detects that; this app does the power cycle and the telling.
 *
 *  Safety rules (each one is enforced in code below):
 *   - it never moves the door. It only switches the outlet that powers the Garadget. A reboot does not
 *     move the door (J.R., confirmed 2026-10-31; Door 1 measured: back on MQTT 12.1 s after power-on).
 *   - never cycles while the door is opening/closing or within N minutes of a door change.
 *   - at most N power cycles per rolling 24 h, then it stops and tells you the unit needs you.
 *   - the outlet is never left off: turned back on at hub start, on a timer, and by a periodic check.
 *   - after a successful reboot it waits (frozen grace) for the reading to move before trying again,
 *     because a healthy reading can legitimately sit still for hours (measured p99 9.3 h on Door 3).
 */

definition(
    name: "Garadget Guardian Child",
    namespace: "jrfarrar",
    author: "J.R. Farrar",
    description: "Power-cycles a Garadget that has gone offline, frozen, or failed a command, and reports what it sees afterwards.",
    category: "Safety & Security",
    parent: "jrfarrar:Garadget Guardian Parent",
    iconUrl: "", iconX2Url: "",
    importUrl: "https://raw.githubusercontent.com/jrfarrar/hubitat/master/Apps/GaradgetGuardian/GaradgetGuardianChild.groovy",
    singleThreaded: true
)

preferences { page(name: "mainPage") }

String APP_VERSION() { return "1.0.3" }

def mainPage() {
    dynamicPage(name: "mainPage", title: "", install: true, uninstall: true) {
        section {
            input "thisName", "text", title: "<b>Name for this door</b>", submitOnChange: true, required: true, defaultValue: "Garadget Guardian"
            if (thisName) app.updateLabel(thisName)
        }
        section("<b>Devices</b>") {
            input "garadget", "capability.garageDoorControl", title: "Garadget door (driver Garadget MQTT 1.4.6+)", required: true, submitOnChange: true
            input "outlet", "capability.switch", title: "Outlet that powers ONLY this Garadget (never the opener)", required: true
            if (garadget && garadget.currentValue("health") == null) {
                paragraph "<span style='color:red'>This device has no 'health' attribute yet - it needs the Garadget MQTT driver 1.4.6 or later.</span>"
            }
        }
        section("<b>What to do</b>") {
            input "mode", "enum", title: "Action", options: ["cycle": "Power-cycle and notify", "notify": "Notify only (never touch the outlet)"],
                  defaultValue: "cycle", required: true
            input "notifyAlert", "capability.notification", title: "Notify when it NEEDS YOU, or when a door turns out to be open (e.g. vNotify-ALERT)", multiple: true, required: false
            input "notifyInfo", "capability.notification", title: "Notify when it FIXED ITSELF (e.g. vNotify-INFO)", multiple: true, required: false
        }
        section("<b>Limits</b>", hideable: true, hidden: true) {
            input "offSec", "number", title: "Seconds the outlet stays off", defaultValue: 15, range: "5..120", required: true
            input "bootWaitSec", "number", title: "Seconds to wait for the Garadget to come back after power-on (measured 12 s)", defaultValue: 90, range: "30..600", required: true
            input "maxCyclesPerDay", "number", title: "Most power cycles in any 24 hours", defaultValue: 2, range: "1..6", required: true
            input "quietAfterMoveMin", "number", title: "Never cycle within this many minutes of the door moving", defaultValue: 5, range: "1..60", required: true
            input "frozenGraceHours", "number", title: "After a successful reboot, hours to wait for the reading to move before cycling again", defaultValue: 12, range: "1..48", required: true
        }
        section("<b>Status</b>") {
            paragraph statusText()
        }
    }
}

def installed() { initialize() }
def updated()   { unsubscribe(); unschedule(); initialize() }

def initialize() {
    if (state.cycles == null) state.cycles = []
    subscribe(garadget, "health", onHealth)
    subscribe(garadget, "lastBoot", onBoot)
    subscribe(outlet, "switch", onOutlet)
    subscribe(location, "systemStart", onSystemStart)
    schedule("37 4/30 * ? * *", housekeeping)
    //separate handler on purpose: runIn() overwrites existing jobs for the SAME handler, which
    //would silently cancel the 30-minute housekeeping schedule above
    runIn(20, firstHousekeeping)
    logInfo "initialized v${APP_VERSION()} - ${garadget?.displayName} on ${outlet?.displayName}, mode ${mode}"
}

/* ------------------------------------------------------------------ events */

def onHealth(evt) {
    String h = evt.value
    if (h == "ok") {
        if (state.problem) {
            String was = state.problem.kind
            Boolean afterCycle = state.lastCycleOkAt && (state.lastCycleOkAt as Long) > (state.problem.since as Long)
            state.problem = null
            state.notifiedNeedsYou = false
            state.notifiedNotifyOnly = false
            state.notedPhysical = false
            sendInfo("${doorName()}: back to normal (${was} cleared${afterCycle ? ' after a power cycle' : ''}). Door reports ${garadget.currentValue('door')}.")
        }
        return
    }
    if (!state.problem || state.problem.kind != h) {
        state.problem = [kind: h, since: now(), reason: garadget.currentValue("healthReason")]
        logWarn "${doorName()} health ${h}: ${garadget.currentValue('healthReason')}"
    }
    considerRecovery()
}

def onBoot(evt) {
    if (state.phase == "booting") recovered(evt.value)
}

def onOutlet(evt) {
    if (evt.value == "off" && state.phase != "off") {
        logWarn "${outlet.displayName} turned off by something else - turning it back on in 60 s"
        runIn(60, ensureOutletOn)
    }
}

def onSystemStart(evt) {
    //a hub reboot mid-cycle must never strand the Garadget unpowered
    if (state.phase) { logWarn "hub restarted during a power cycle (${state.phase}) - restoring power"; state.phase = null }
    runIn(30, ensureOutletOn)
}

/* ------------------------------------------------------------------ decision */

def considerRecovery() {
    if (state.phase) return                                  // a cycle is already running
    String h = garadget.currentValue("health")
    if (!h || h == "ok") return
    Long t = now()

    String door = garadget.currentValue("door")
    Long doorAge = t - (garadget.currentState("door")?.date?.time ?: 0L)

    //"stopped" is ambiguous. If the reflection reading MOVED around the failed command, the unit was
    //watching a real door that did not finish (obstruction, safety eyes): a reboot cannot fix that and
    //the existing "door stopped" alarm covers it. If the reading did NOT move, the unit itself is
    //blind - exactly what happened live 2026-10-31: Door 2's laser read 0 from the moment it was
    //re-plugged at 08:21, the door was really closed, and the Garadget reported opening/open/stopped.
    if (h == "commandFailed" && door == "stopped" && readingMovedRecently()) {
        if (!state.notedPhysical) {
            state.notedPhysical = true
            logInfo "${doorName()}: command not completed, door reports STOPPED and the reading moved - physical problem, not a Garadget fault; not power-cycling"
        }
        return
    }
    Long quietMs = ((quietAfterMoveMin ?: 5) as Long) * 60000L
    if (door in ["opening", "closing"] || doorAge < quietMs) {
        logInfo "door ${door}, moved ${Math.round(doorAge / 1000)} s ago - waiting before any action"
        runIn(Math.max(60, Math.round((quietMs - doorAge) / 1000) as Integer), considerRecovery)
        return
    }

    //after a successful reboot, give a frozen reading time to move before calling it a failure
    Long graceMs = ((frozenGraceHours ?: 12) as Long) * 3600000L
    if (h == "sensorFrozen" && state.lastCycleOkAt && t - (state.lastCycleOkAt as Long) < graceMs) {
        Long left = graceMs - (t - (state.lastCycleOkAt as Long))
        logInfo "rebooted ${Math.round((t - (state.lastCycleOkAt as Long)) / 60000)} min ago - waiting up to ${Math.round(left / 60000)} min for the reading to move"
        runIn(((left / 1000) as Integer) + 60, considerRecovery)
        return
    }

    List recent = recentCycles()
    Integer maxC = (maxCyclesPerDay ?: 2) as Integer
    Integer tried = (state.problem?.attempts ?: 0) as Integer
    //Two limits: cycles in any 24 h, AND cycles spent on this one problem. Without the second, a unit
    //that is permanently broken gets rebooted every grace period forever and never asks for help.
    if (recent.size() >= maxC || tried >= maxC) {
        if (!state.notifiedNeedsYou) {
            state.notifiedNeedsYou = true
            sendAlert("${doorName()} NEEDS YOU: still ${h} after ${Math.max(tried, recent.size())} power cycle(s). ${garadget.currentValue('healthReason')}. The door may not be where the hub thinks it is - it reports ${door}. Unit may be failing.")
        }
        if (tried < maxC) {                  // only the 24 h limit is holding us: look again when it rolls
            Long oldest = recent.min() as Long
            runIn((((oldest + 86400000L - t) / 1000) as Integer) + 60, considerRecovery)
        }
        return                               // per-problem limit: no more cycles until health is ok again
    }

    if (mode == "notify") {
        if (!state.notifiedNotifyOnly) {
            state.notifiedNotifyOnly = true
            sendAlert("${doorName()}: ${h} - ${garadget.currentValue('healthReason')}. Door reports ${door}. (Notify-only mode: outlet not touched.)")
        }
        return
    }

    startCycle(h)
}

//Did the reflection reading change in the few minutes before this problem was raised?
Boolean readingMovedRecently() {
    Long since = ((state.problem?.since ?: now()) as Long) - 180000L
    try {
        def evts = garadget.statesSince("sensor", new Date(since), [max: 20])
        return (evts && evts.size() > 0)
    } catch (e) {
        logWarn "could not read sensor history (${e.message}) - assuming the unit is at fault"
        return false
    }
}

/* ------------------------------------------------------------------ the power cycle */

def startCycle(String why) {
    Long t = now()
    List c = (state.cycles ?: []) as List
    c << t
    state.cycles = c
    state.phase = "off"
    state.cycleWhy = why
    state.cycleStartedAt = t
    state.preBoot = garadget.currentValue("lastBoot")
    def p = state.problem ?: [kind: why, since: t]
    p.attempts = ((p.attempts ?: 0) as Integer) + 1
    state.problem = p
    logWarn "power-cycling ${outlet.displayName} for ${doorName()} (${why})"
    outlet.off()
    runIn((offSec ?: 15) as Integer, powerOn)
}

def powerOn() {
    outlet.on()
    state.phase = "booting"
    state.powerOnAt = now()
    runIn((bootWaitSec ?: 90) as Integer, verifyBoot)
}

def verifyBoot() {
    if (state.phase != "booting") return
    if (garadget.currentValue("lastBoot") != state.preBoot) { recovered(garadget.currentValue("lastBoot")); return }
    //no boot seen yet: ask the unit directly, once
    garadget.refresh()
    runIn(30, verifyBootFinal)
}

def verifyBootFinal() {
    if (state.phase != "booting") return
    if (garadget.currentValue("lastBoot") != state.preBoot) { recovered(garadget.currentValue("lastBoot")); return }
    state.phase = null
    logWarn "${doorName()}: no sign of a restart after the power cycle"
    if (outlet.currentValue("switch") != "on") outlet.on()
    if (recentCycles().size() < ((maxCyclesPerDay ?: 2) as Integer)) {
        runIn(300, considerRecovery)          // one more try in 5 minutes
    } else {
        considerRecovery()                    // limit reached: this sends "needs you"
    }
}

def recovered(String bootStr) {
    state.phase = null
    unschedule("verifyBoot")
    unschedule("verifyBootFinal")
    state.lastCycleOkAt = now()
    String door = garadget.currentValue("door")
    Long secs = Math.round((now() - (state.powerOnAt as Long)) / 1000)
    String msg = "${doorName()}: ${state.cycleWhy} - power-cycled the Garadget; it restarted (${secs} s after power-on) and now reports the door ${door?.toUpperCase()}."
    if (door == "open") {
        sendAlert(msg + " The door is OPEN.")       // the reason this app exists
    } else {
        sendInfo(msg)
    }
    logInfo msg
}

/* ------------------------------------------------------------------ housekeeping */

def firstHousekeeping() { housekeeping() }

def housekeeping() {
    //a cycle that never finished (hub busy, handler lost) must not leave the outlet off
    if (state.phase && now() - ((state.cycleStartedAt ?: 0L) as Long) > 10 * 60000L) {
        logWarn "power cycle stuck in '${state.phase}' - clearing it"
        state.phase = null
    }
    ensureOutletOn()
    state.cycles = recentCycles()
    if (garadget.currentValue("health") && garadget.currentValue("health") != "ok") considerRecovery()
}

def ensureOutletOn() {
    if (state.phase == "off") return
    if (outlet.currentValue("switch") == "off") {
        logWarn "${outlet.displayName} found off with no power cycle running - turning it on"
        outlet.on()
        sendInfo("${doorName()}: its Garadget outlet (${outlet.displayName}) was found OFF and has been turned back on.")
    }
}

List recentCycles() {
    Long cutoff = now() - 86400000L
    return ((state.cycles ?: []) as List).findAll { (it as Long) > cutoff }
}

/* ------------------------------------------------------------------ output */

String doorName() { garadget?.displayName ?: "Garage door" }

void sendAlert(String m) { logWarn "ALERT: ${m}"; notifyAlert?.each { it.deviceNotification(m) } }
void sendInfo(String m)  { logInfo "INFO: ${m}"; notifyInfo?.each { it.deviceNotification(m) } }

void logInfo(String m) { log.info "${app.label}: ${m}" }
void logWarn(String m) { log.warn "${app.label}: ${m}" }

String statusText() {
    if (!garadget) return "Pick a Garadget."
    String h = garadget.currentValue("health") ?: "(no health attribute - driver 1.4.6 needed)"
    String r = garadget.currentValue("healthReason") ?: ""
    return "Health: <b>${h}</b> ${r ? '- ' + r : ''}<br>Door: ${garadget.currentValue('door')} &nbsp; Outlet: ${outlet?.currentValue('switch')}<br>" +
           "Power cycles in last 24 h: ${recentCycles().size()} &nbsp; Last reboot seen: ${garadget.currentValue('lastBoot') ?: '-'}" +
           (state.phase ? "<br><b>Power cycle in progress: ${state.phase}</b>" : "")
}

/** Read by the parent's status list. */
Map publicStatus() {
    return [v: APP_VERSION(), health: garadget?.currentValue("health"), door: garadget?.currentValue("door"),
            outlet: outlet?.currentValue("switch"), cycles24h: recentCycles().size(), phase: state.phase, mode: mode]
}
