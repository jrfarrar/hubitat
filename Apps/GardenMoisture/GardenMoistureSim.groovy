/**
 *  Garden Moisture Sim  (TESTING ONLY)
 *
 *  Copyright 2026 J.R. Farrar
 *
 *  Drives a "Garden Sim Sensor" virtual device through scripted scenarios so the
 *  Garden Moisture Logger's logic can be exercised without waiting for weather.
 *
 *  HOW TO USE
 *    1. Create a virtual device using the "Garden Sim Sensor" driver.
 *    2. In the logger child, select that one device for the soil probe, the rain
 *       source AND the outdoor temperature - it satisfies all three.
 *       Create virtual switches for the two markers and the season, and select
 *       those too.
 *    3. Set the logger child's Testing > "Simulation speed-up divisor" to
 *       something like 500. Without it the +6/12/24 h follow-ups stay at real
 *       hours and most scenarios cannot complete.
 *    4. Pick a scenario here, press Run, and watch the logger child's status
 *       block and the logs.
 *    5. Run as many scenarios as you like, back to back. Each one primes itself,
 *       so no clearing is needed between runs.
 *    6. WHEN FINISHED WITH ALL OF THEM: set the speed-up back to 1 and use
 *       "Clear learned data" on the logger, or every anchor it holds is
 *       simulation garbage. That is a once-at-the-end step, not a per-run one.
 *
 *  Each scenario prints the expected outcome to the log when it starts, so the
 *  log itself reads as a pass/fail checklist.
 *
 *  This app commands only the sim device and the sim switches. It never touches
 *  the logger app's state directly - everything goes through real device events,
 *  which is the point: the logger is tested through its real inputs.
 *
 *  v0.1.0  2026-08-31  Initial release.
 *  v0.1.1  2026-08-31  Scenarios now prime themselves so they can be run
 *                      back to back without cross-contamination.
 */

import groovy.transform.Field

@Field static final String VERSION = "0.1.1"

@Field static final Map SCENARIOS = [
    "dryDown"      : "Core: steady dry-down, no events, dry-down records banked",
    "rainEvent"    : "Core: rain event, classified as rain, FC anchor recorded",
    "manualWater"  : "Core: manual watering with marker, classified manual-confirmed",
    "shallowWater" : "Core: big spike that drains away - should flag shallowSuspect",
    "toThreshold"  : "Core: END TO END - soakings + stress marks until a real threshold appears",
    "probePulled"  : "Edge: reading collapses and stays - probe-out guard",
    "seasonCycle"  : "Edge: season off, then on - FC cleared, stress kept",
    "seasonDoubleTap": "Edge: season switched on twice - anchors must SURVIVE",
    "freeze"       : "Edge: cold snap drives readings down - must not be learned",
    "ambiguous"    : "Edge: manual watering then rain - must classify ambiguous",
    "rapidFire"    : "Abuse: many events back to back - pruning and state limits",
    "sensorSilent" : "Abuse: sensor stops reporting mid-event - stale detector",
    "jitterOnly"   : "Abuse: noise below the rise threshold - must NOT open events"
]

definition(
    name: "Garden Moisture Sim",
    namespace: "jrfarrar",
    author: "J.R. Farrar",
    description: "Test harness that drives a virtual sensor through scenarios for the Garden Moisture Logger.",
    category: "My Apps",
    iconUrl: "",
    iconX2Url: "",
    iconX3Url: "",
    singleInstance: true,
    installOnOpen: true,
    importUrl: "https://raw.githubusercontent.com/jrfarrar/hubitat/master/Apps/GardenMoisture/GardenMoistureSim.groovy"
)

preferences {
    page(name: "mainPage")
}

def mainPage() {
    dynamicPage(name: "mainPage", title: "<b>Garden Moisture Sim</b> v${VERSION}", install: true, uninstall: true) {
        section {
            paragraph "<div style='background:#fdd;border:2px solid #b00;padding:6px'>" +
                      "<b>Test fixture.</b> Point the logger child at the sim device, set its " +
                      "Testing &gt; speed-up divisor above 1, run scenarios, then set the divisor " +
                      "back to 1 and clear the logger's learned data before real use.</div>"
        }

        section("<b>Devices</b>") {
            input "simDev", "capability.relativeHumidityMeasurement",
                  title: "Sim sensor (must use the Garden Sim Sensor driver)", required: true, multiple: false
            input "simNeeded", "capability.switch", title: "Marker switch: \"needed water\"", required: false, multiple: false
            input "simWatered", "capability.switch", title: "Marker switch: \"just watered\"", required: false, multiple: false
            input "simSeason", "capability.switch", title: "Season switch", required: false, multiple: false
        }

        section("<b>Scenario</b>") {
            input "scenario", "enum", title: "Pick one", options: SCENARIOS, required: true, submitOnChange: true
            if (scenario) paragraph "<i>${SCENARIOS[scenario]}</i>"
            input "stepSec", "number", title: "Seconds between simulated readings", defaultValue: 3, required: true
            paragraph "<i>Each step is one sensor reading. Keep it at 2 s or more - the logger " +
                      "writes a file per sample, and a faster drip just queues work on the hub.</i>"
        }

        section("<b>Run</b>") {
            input "btnRun",  "button", title: "Run scenario"
            input "btnStop", "button", title: "Stop"
            input "btnReset","button", title: "Reset sim device to neutral"
            paragraph statusText()
        }

        section("<b>Logging</b>") {
            input "logEnable", "bool", title: "Debug logging", defaultValue: true
        }
    }
}

private String statusText() {
    if (!state.running) {
        return state.lastResult ? "<br><b>Last run:</b> ${state.lastResult}" : "<br><i>Idle.</i>"
    }
    return "<br><b>Running ${state.scenario}</b> - step ${state.step} of ${state.plan?.size() ?: '?'}" +
           "<br><i>Refresh this page to update.</i>"
}

/* -------------------------------------------------------------- lifecycle */

def installed() { initialize() }
def updated()   { unschedule(); initialize() }
def initialize() { logDebug "sim ready" }

def appButtonHandler(String btn) {
    switch (btn) {
        case "btnRun":   startScenario(); break
        case "btnStop":  stopScenario();  break
        case "btnReset": resetSim();      break
    }
}

/* --------------------------------------------------------------- control */

private void resetSim() {
    unschedule("stepTick")
    state.running = false
    try { simDev.resetAll() } catch (ex) { log.warn "resetAll failed - is this the Garden Sim Sensor driver? ${ex.message}" }
    simNeeded?.off(); simWatered?.off()
    log.info "SIM: device reset to neutral"
}

private void stopScenario() {
    unschedule("stepTick")
    state.running = false
    state.lastResult = "stopped at step ${state.step}"
    log.info "SIM: stopped"
}

private void startScenario() {
    if (!simDev) { log.warn "SIM: pick a sim device first"; return }
    unschedule("stepTick")

    List plan = primed(buildPlan(scenario))
    if (!plan) { log.warn "SIM: no plan built for '${scenario}'"; return }

    state.scenario = scenario
    state.plan = plan
    state.step = 0
    state.running = true
    state.lastResult = null

    log.info "=========================================================="
    log.info "SIM: starting '${scenario}' - ${SCENARIOS[scenario]}"
    log.info "SIM: EXPECT -> ${expectationFor(scenario)}"
    log.info "SIM: ${plan.size()} steps at ${stepSec ?: 3} s each"
    log.info "=========================================================="

    stepTick()
}

def stepTick() {
    if (!state.running) return
    List plan = state.plan
    Integer i = (state.step ?: 0) as Integer
    if (plan == null || i >= plan.size()) {
        state.running = false
        state.lastResult = "'${state.scenario}' completed ${plan?.size() ?: 0} steps at ${new Date()}"
        log.info "=========================================================="
        log.info "SIM: '${state.scenario}' COMPLETE. Now check the logger child's status block against:"
        log.info "SIM: EXPECT -> ${expectationFor(state.scenario)}"
        log.info "=========================================================="
        return
    }

    Map s = plan[i]
    applyStep(s)
    state.step = i + 1

    Integer gap = Math.max(1, (stepSec ?: 3) as Integer)
    runIn(gap, "stepTick")
}

private void applyStep(Map s) {
    try {
        if (s.note)     log.info "SIM step ${state.step}: ${s.note}"
        if (s.temp   != null) simDev.setTemperature(s.temp)
        if (s.raining!= null) simDev.setRaining(s.raining ? "true" : "false")
        if (s.rainEv != null) simDev.setRainEvent(s.rainEv)
        if (s.rainDay!= null) simDev.setRainDaily(s.rainDay)
        if (s.rainRt != null) simDev.setRainRate(s.rainRt)
        if (s.batt   != null) simDev.setBattery(s.batt)
        // Soil last, so the logger sees rain context already updated when the
        // moisture event that triggers checkRise arrives.
        if (s.soil   != null) simDev.setSoil(s.soil)

        if (s.markNeeded)  { simNeeded?.on();  log.info "SIM: pressed 'needed water'" }
        if (s.markWatered) { simWatered?.on(); log.info "SIM: pressed 'just watered'" }
        if (s.season != null) {
            if (s.season) simSeason?.on() else simSeason?.off()
            log.info "SIM: season switch -> ${s.season ? 'on' : 'off'}"
        }
    } catch (ex) {
        log.error "SIM: step failed - ${ex.message}"
        stopScenario()
    }
}

/* ------------------------------------------------------------ scenarios */

private String expectationFor(String sc) {
    switch (sc) {
        case "dryDown":     return "NO wetting events at all. Moisture falls steadily, lowest-survived tracks down, and one dry-down record is banked per simulated day (see simSamplesPerDay)."
        case "rainEvent":   return "ONE event, classification=rain, rainInches populated, and after the +24h follow-up an FC observation is added (obs count goes up)."
        case "manualWater": return "ONE event, classification=manual-confirmed (the marker was pressed), rainInches empty."
        case "shallowWater":return "ONE event with a big magnitude but a SMALL +24h effectiveGain, flagged shallowSuspect in the log. Should NOT look like a good soaking."
        case "toThreshold": return "The full end-to-end run: ~60 daily readings banked, 3 stress marks pressed, after which Field capacity shows a number, 'Derived threshold' stops saying 'not yet', and Confidence leaves 'none'. This is the only scenario that exercises the whole estimator."
        case "probePulled": return "After the grace period, a warning that the probe may be out of the ground, learning suspended, and 'Would it notify' says probe may be out."
        case "seasonCycle": return "On season OFF nothing is learned. On season ON, FC observations reset to 0 but stress observations are KEPT."
        case "seasonDoubleTap": return "CRITICAL: the second 'on' must be ignored - FC observation count must NOT drop a second time, and the 30-day guard should log that it kept them."
        case "freeze":      return "Readings fall but temperature is below the freeze guard, so NO anchors are learned and no FC observation appears."
        case "ambiguous":   return "ONE event classified 'ambiguous' - a manual watering that rain landed on top of, which must not be attributed to either."
        case "rapidFire":   return "Many events recorded, state stays bounded (event list capped at the keepEvents setting), no errors in the log."
        case "sensorSilent":return "After the stale window with no readings, a 'sensor looks stale' warning and learning suspended."
        case "jitterOnly":  return "NO events at all - noise below the rise threshold must not open one."
        default: return "see scenario description"
    }
}

/**
 * Every scenario is prefixed with a run of readings at its own opening value.
 *
 * Scenarios must be independent, and they were not: the logger's rise detector
 * keeps a lookback buffer, so a run ending at 42 followed by one starting at 46
 * looked like a 4-point rise and opened a wetting event before the new scenario
 * had done anything. Priming fills that buffer with the new baseline first, so
 * you can run all thirteen back to back without clearing anything in between.
 */
private List primed(List plan) {
    if (!plan) return plan
    Map f = plan[0]
    List out = []
    out << [soil: f.soil, temp: f.temp, raining: f.raining, rainEv: f.rainEv,
            rainDay: f.rainDay, rainRt: f.rainRt, batt: f.batt,
            note: "priming - settling the detector on this scenario's baseline"]
    (1..11).each { out << [soil: f.soil] }
    out.addAll(plan)
    return out
}

/**
 * Plans are plain lists of steps. Kept explicit rather than clever so that when
 * a scenario misbehaves you can read exactly what was fed in.
 */
private List buildPlan(String sc) {
    List p = []
    switch (sc) {

        case "dryDown":
            p << [note: "baseline, warm and dry", temp: 75, raining: false, rainDay: 0, rainEv: 0, soil: 46]
            (0..14).each { i -> p << [soil: 46 - i, note: (i % 5 == 0 ? "drying, day ${i}" : null)] }
            break

        case "rainEvent":
            p << [note: "dry starting point", temp: 68, raining: false, rainEv: 0, rainDay: 0, soil: 26]
            p << [soil: 26]
            p << [note: "rain begins", raining: true, rainRt: 0.20, rainEv: 0.10, rainDay: 0.10, soil: 29]
            p << [rainEv: 0.28, rainDay: 0.28, soil: 36]
            p << [rainEv: 0.45, rainDay: 0.45, soil: 43]
            p << [note: "rain stops", raining: false, rainRt: 0, rainEv: 0.52, rainDay: 0.52, soil: 47]
            // settle, so the event closes and the follow-ups fire
            (0..8).each { p << [soil: 45] }
            break

        case "manualWater":
            p << [note: "dry starting point", temp: 78, raining: false, rainEv: 0, rainDay: 0, soil: 24]
            p << [soil: 24]
            p << [note: "she waters, and marks it", markWatered: true, soil: 30]
            p << [soil: 38]
            p << [soil: 44]
            (0..8).each { p << [soil: 42] }
            break

        case "shallowWater":
            p << [note: "dry", temp: 85, raining: false, rainEv: 0, rainDay: 0, soil: 22]
            p << [note: "a quick sprinkle - surface only", markWatered: true, soil: 30]
            p << [soil: 38]
            // drains straight back: big peak, negligible 24h gain
            p << [soil: 32]
            p << [soil: 27]
            p << [soil: 24]
            (0..8).each { p << [soil: 23] }
            break

        case "toThreshold":
            // Three soak/dry cycles to build FC observations, with stress marks
            // pressed at the bottom of each.
            (1..3).each { c ->
                p << [note: "cycle ${c}: soaking", temp: 72, raining: true, rainRt: 0.3,
                      rainEv: 0.5, rainDay: 0.5, soil: 30]
                p << [soil: 40]
                p << [soil: 48]
                p << [note: "rain stops", raining: false, rainRt: 0, soil: 46]
                (0..6).each { p << [soil: 45] }          // settle for the +24h follow-up
                (1..6).each { i -> p << [soil: 45 - (i * 4)] }   // dry down
                p << [note: "cycle ${c}: she says it needs water", markNeeded: true, soil: 21]
                p << [rainEv: 0, rainDay: 0, soil: 21]
            }
            break

        case "probePulled":
            p << [note: "normal", temp: 70, raining: false, soil: 38]
            p << [soil: 37]
            p << [note: "probe yanked out of the ground", soil: 3]
            (0..14).each { p << [soil: 2] }
            break

        case "seasonCycle":
            p << [note: "season on, normal reading", season: true, temp: 70, raining: false, soil: 40]
            p << [note: "a soaking to bank an FC observation", raining: true, rainEv: 0.5, rainDay: 0.5, soil: 50]
            p << [note: "rain stops", raining: false, soil: 48]
            (0..8).each { p << [soil: 47] }
            p << [note: "she marks it as needing water later", markNeeded: true, soil: 22]
            p << [note: "END OF SEASON - probe pulled", season: false, soil: 3]
            (0..3).each { p << [soil: 2] }
            p << [note: "NEW SEASON - probe back in, different spot", season: true, soil: 33]
            (0..3).each { p << [soil: 33] }
            break

        case "seasonDoubleTap":
            p << [note: "season on", season: true, temp: 70, raining: false, soil: 40]
            p << [note: "soaking to bank an FC observation", raining: true, rainEv: 0.5, rainDay: 0.5, soil: 52]
            p << [note: "rain stops", raining: false, soil: 49]
            (0..8).each { p << [soil: 48] }
            p << [note: "*** second 'on' - a dashboard double tap. Anchors MUST survive. ***", season: true, soil: 48]
            (0..3).each { p << [soil: 47] }
            break

        case "freeze":
            p << [note: "cold snap - below the freeze guard", temp: 25, raining: false, soil: 40]
            p << [note: "frozen soil reads dry - ice dielectric is ~3 vs water's ~80", soil: 22]
            p << [soil: 12]
            p << [soil: 9]
            p << [note: "thaw - a big apparent 'rise' that is not water", temp: 45, soil: 38]
            (0..8).each { p << [soil: 38] }
            break

        case "ambiguous":
            p << [note: "dry", temp: 80, raining: false, rainEv: 0, rainDay: 0, soil: 23]
            p << [note: "she waters by hand and marks it", markWatered: true, soil: 29]
            p << [note: "...and then it rains on top of it", raining: true, rainRt: 0.4,
                  rainEv: 0.35, rainDay: 0.35, soil: 40]
            p << [note: "rain stops", raining: false, rainRt: 0, soil: 46]
            (0..8).each { p << [soil: 45] }
            break

        case "rapidFire":
            p << [note: "hammering the event detector", temp: 70, raining: false, soil: 20]
            (1..25).each { c ->
                p << [soil: 20, note: (c % 5 == 0 ? "burst ${c}" : null)]
                p << [soil: 32]
                p << [soil: 20]
            }
            break

        case "sensorSilent":
            p << [note: "normal", temp: 70, raining: false, soil: 36]
            p << [note: "rise begins...", soil: 44]
            p << [note: "...and the sensor goes silent mid-event. Nothing follows.", soil: 44]
            // No further steps: the plan ends, the device stops updating, and the
            // logger's stale detector should notice on its next scheduled tick.
            break

        case "jitterOnly":
            p << [note: "noise only - nothing should open an event", temp: 70, raining: false, soil: 35]
            (1..20).each { i -> p << [soil: (i % 2 == 0) ? 36 : 34] }
            break
    }
    return p
}

/* ----------------------------------------------------------------- utils */

private void logDebug(String m) { if (logEnable != false) log.debug "GardenSim: ${m}" }
