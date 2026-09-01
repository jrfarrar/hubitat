/**
 *  Garden Moisture Logger Child
 *
 *  Copyright 2026 J.R. Farrar
 *
 *  Observes one Ecowitt WH51 soil probe and records:
 *    - a fixed-cadence sample grid, one CSV per day in File Manager
 *    - one summary per wetting event (rain or manual), with +6/12/24 h follow-ups
 *    - rolling estimates of field capacity, stress point and dry-down rate
 *
 *  DELIBERATELY PASSIVE. No notifications, no valve control, no commands to the
 *  sensor or any other device. The only command it ever sends is turning its own
 *  marker switches back off after it has recorded the mark, and that is optional.
 *
 *  ---------------------------------------------------------------------------
 *  ATTRIBUTE TRAP: the Ecowitt RF Sensor driver maps soil moisture onto the
 *  "humidity" attribute - there is no soilMoisture attribute. The WH51 child
 *  presents as a Relative Humidity Measurement device. Raw A/D arrives as
 *  "soilAD". From the WS90: rainRate, rainEvent, rainDaily, and "raining" which
 *  is a STRING "true"/"false", not a boolean.
 *  ---------------------------------------------------------------------------
 *
 *  Design notes:
 *    - The WH51 percentage is remapped capacitance, not volumetric water content.
 *      No fixed threshold is meaningful until anchored against THIS garden, so
 *      both anchors are rolling estimates that keep refining for the life of the
 *      app. soilAD is logged alongside because it survives a recalibration that
 *      would shift the percentage scale, and because integer percent quantises a
 *      slow dry-down into a staircase.
 *    - Costs are asymmetric. A missed notification costs nothing; a false one
 *      costs credibility and gets the whole thing muted. Everything here is
 *      built to be conservative later - hence the confidence gate.
 *    - Daily CSVs pruned at years, not the 30-file window used by
 *      LaundryCycleLogger. If this takes seasons to dial in, the history is the
 *      asset. ~9 KB/day, ~3 MB/year.
 *      Daily rather than monthly on purpose: a month buffer would either sit in
 *      state (hundreds of KB re-serialised on every sample) or live in a @Field
 *      that a reboot wipes, which would then truncate the month's file on the
 *      next write. A day is small enough to keep in state and rewrite whole
 *      every sample, so a reboot costs nothing and there is no recovery path to
 *      get wrong.
 *    - Learned anchors are mirrored to File Manager as JSON, because state does
 *      not survive an app reinstall and the anchors cannot be regenerated.
 *    - The probe is pulled for winter and re-seated in spring, in a slightly
 *      different spot at a slightly different depth. That shifts the scale, so
 *      a season restart carries anchors forward as a prior but drops FC
 *      confidence until a soaking event re-confirms it.
 *
 *  v0.1.0  2026-08-31  Initial release.
 *  v0.2.2  2026-09-01  Fix found on the real install, within minutes of the
 *                      probe being connected: the app had already banked an
 *                      implicit stress observation of 0%.
 *
 *                      When a soil device is first attached its reading is 0
 *                      until real data arrives. The jump from 0 to the first
 *                      genuine value (44%) is a large rise with no rain, so it
 *                      was classified as a manual watering and 0 was recorded
 *                      as "the moisture at which this garden gets watered" -
 *                      which is the anchor the whole threshold rests on.
 *
 *                      Two guards: checkRise will not open an event whose
 *                      baseline is at or below the probe-out level, and
 *                      recordImplicitStress refuses such a reading outright and
 *                      says so loudly. The simulator could not have produced
 *                      this - the sim device always starts at a sane value, so
 *                      only attaching a real sensor exposes it.
 *  v0.2.1  2026-08-31  Fix: three locals were declared twice inside anchors()
 *                      (needSt, sorted, k), which Groovy refuses to compile.
 *                      Found by J.R. on install for the first, then by a new
 *                      duplicate-local checker for the other two - the same
 *                      structural pass that had been verifying brace balance
 *                      and handler names all along could not see this class of
 *                      error, and now can.
 *  v0.2.0  2026-08-31  STRESS ANCHOR NO LONGER NEEDS A BUTTON PRESS.
 *                      It is now inferred from the moisture reading at the
 *                      moment the garden gets watered by hand - which the app
 *                      already detects - using a low percentile of those
 *                      readings, filtered to the drier half of the soil's own
 *                      range. Explicit marks still win when present, but are
 *                      optional; nothing has to appear on anyone's dashboard.
 *
 *                      Why a LOW percentile: some watering is routine rather
 *                      than because it is dry, and only the lower tail is
 *                      evidence about dryness. Validated in season_harness.py
 *                      across 80 simulated seasons - the old button-only design
 *                      produced a threshold in 5% of seasons (0% if she never
 *                      pressed); this produces one in 100%, within ~2 points of
 *                      truth for mostly-reactive watering, never firing early.
 *
 *                      Added a SAFETY CLAMP, because an inferred anchor drifts
 *                      up if watering becomes more routine over time, and a
 *                      too-high stress point means notifying when the soil is
 *                      not actually dry. The threshold may never sit above
 *                      clampFrac of the way down from field capacity, measured
 *                      against the soil's own observed range. It barely engages
 *                      for reactive watering and halves the error for routine.
 *  v0.1.10 2026-08-31  Floored the rain-attribution grace in sim mode. At 5000x
 *                      the 15-minute window scaled to 180 ms - shorter than
 *                      Hubitat's own event-delivery jitter (6-357 ms observed) -
 *                      so when a scenario's rain and its t0 fell in the same
 *                      step, detecting the rain at all was a coin flip. That is
 *                      why toThreshold cycle 1 read "manual" while cycles 2 and
 *                      3, whose t0 precedes the soaking, read "rain". Harness
 *                      scaling only; production uses 15 real minutes.
 *  v0.1.9  2026-08-31  t0 was taken from the FIRST sample tying the minimum
 *                      rather than the last. Soil sits flat before it rises, so
 *                      several samples tie routinely, and Groovy's min() returns
 *                      the earliest - putting t0 minutes before the rise really
 *                      started. That inflated rise duration, and took startAD
 *                      and the rain baseline from a stale sample, which is how
 *                      one soaking reported 0.5 in of rain and an identical one
 *                      reported 0.0. Now takes the last tying sample.
 *  v0.1.8  2026-08-31  Four more, all found in the logger's own log:
 *                      (a) a SHALLOW event still fed the field-capacity anchor.
 *                          Its settled value is the dry baseline it fell back
 *                          to, not field capacity - the run recorded "FC
 *                          observation: 23" and flagged the same event shallow
 *                          on the next line. Shallow is now decided first and
 *                          excludes the event from the anchor.
 *                      (b) recordFollowUp was not idempotent, so the runIn and
 *                          the backfill sweep both processed the same event and
 *                          one soaking produced two FC observations.
 *                      (c) the probe-out grace scaled to 1.44 s at 5000x, so a
 *                          single low reading could suspend learning. Floored.
 *                      (d) the "not learned from" log lumped three guards
 *                          together and omitted stale, which made a dropped
 *                          stress mark impossible to diagnose. It now names the
 *                          actual guard.
 *  v0.1.7  2026-08-31  Three faults the full suite run exposed:
 *                      (a) state.recent was not pruned when an event closed, so
 *                          the pre-event low stayed in the lookback and every
 *                          following flat reading re-opened an event off it.
 *                          Production was shielded only by settleMin (60 min)
 *                          happening to outlast the lookback (55 min) - a
 *                          coincidence, not a guarantee.
 *                      (b) rainAtT0 was captured when the event OPENED rather
 *                          than at t0, by which point rain was already counted,
 *                          so before == after and rainInches was always 0.0.
 *                          That is the input to the whole rain-efficiency
 *                          question. Now taken from the same sample as startPct.
 *                      (c) the sim-scaled stale window fell below the gap
 *                          between scenarios, so the app went stale in every
 *                          pause and follow-ups landing there skipped their FC
 *                          observation. Floored at 30 s in sim mode.
 *  v0.1.6  2026-08-31  In sim mode, run checkStale() on a fast schedule. Stale
 *                      detection fires when readings STOP, so it can never be
 *                      driven by an arriving reading; its only other caller is
 *                      the real 15-minute sampleTick, which meant the
 *                      sensorSilent scenario could never trigger it.
 *  v0.1.5  2026-08-31  Added simBoundary subscription so the scenario runner can
 *                      reset this app between tests through a device event
 *                      rather than either app touching the other's state.
 *                      Guarded on simActive() so it is inert in production.
 *                      clearLearned() now also drops the volatile cross-run
 *                      state (rain attribution, stale flags, dry-down window),
 *                      not just the anchors.
 *  v0.1.4  2026-08-31  Fix: scenarios contaminated each other through the rise
 *                      detector. state.recent is time-windowed at riseWindowMin
 *                      (45 real minutes, deliberately unscaled), so it carried a
 *                      previous scenario's readings into the next one and
 *                      checkRise could open an event off the old run's tail. In
 *                      sim mode it is now bounded by count.
 *  v0.1.3  2026-08-31  Fix: two rain-attribution faults found while running the
 *                      manual-watering scenario.
 *                      (a) rainEventHandler stamped "rain just rose" whenever
 *                          the previous value was null, so the FIRST rainEvent
 *                          reading ever seen set the marker even at value 0.
 *                          A hand watering within the next 15 minutes would be
 *                          classified as rain. Now only a real increase counts.
 *                      (b) the +/-15 min rain grace window in closeEvent was not
 *                          sim-scaled, so it spanned entire scenario runs: a
 *                          rainEvent run followed within 15 real minutes by a
 *                          manualWater run leaked the rain marker across and
 *                          mis-classified the watering.
 *  v0.1.2  2026-08-31  Fix: day-boundary work was unreachable under simulation.
 *                      dayRollover() only ran from the midnight cron, and it is
 *                      the only caller of recordDailyLevel() (which feeds the
 *                      PRIMARY field-capacity estimate) and computeDryDown().
 *                      So no scenario could bank a daily reading, record a
 *                      dry-down, or ever reach a threshold - the main estimator
 *                      path in this file was untestable on a hub. In sim mode it
 *                      is now driven by reading count via simSamplesPerDay, and
 *                      computeDryDown's real-time span guard is bypassed since a
 *                      simulated day is not a real one.
 *  v0.1.1  2026-08-31  Fix: the wetting-event settle window was not sim-scaled,
 *                      so under simulation an event opened and then sat open for
 *                      60 REAL minutes before closing. Nothing downstream of
 *                      closeEvent() - classification, the +6/12/24 h follow-ups,
 *                      the field-capacity observation - could run inside a
 *                      scenario. The manual-watering attribution window had the
 *                      same problem. Both now go through scaleMs().
 *                      NOTE: riseWindowMin is deliberately NOT scaled. Shrinking
 *                      the detection window would stop the sim seeing the
 *                      pre-rain low at all.
 */

import groovy.transform.Field
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.text.SimpleDateFormat

@Field static final String VERSION = "0.2.2"

definition(
    name: "Garden Moisture Logger Child",
    namespace: "jrfarrar",
    author: "J.R. Farrar",
    description: "Records one garden zone's soil moisture. Child of Garden Moisture Logger.",
    category: "",
    parent: "jrfarrar:Garden Moisture Logger",
    iconUrl: "",
    iconX2Url: "",
    iconX3Url: "",
    singleThreaded: true,
    importUrl: "https://raw.githubusercontent.com/jrfarrar/hubitat/master/Apps/GardenMoisture/GardenMoistureLoggerChild.groovy"
)

preferences {
    page(name: "mainPage")
}

/* ------------------------------------------------------------------ UI -- */

def mainPage() {
    dynamicPage(name: "mainPage", title: " ", install: true, uninstall: true) {
        section("<b>Zone</b>") {
            input "thisName", "text", title: "Name for this zone", submitOnChange: true, required: true
            if (thisName) app.updateLabel(thisName)
            paragraph "<i>The WH51 child device reports soil moisture as <b>humidity</b>, so pick " +
                      "it from the humidity list below.</i>"
            input "soil", "capability.relativeHumidityMeasurement", title: "Soil probe device (WH51)",
                  required: true, multiple: false
            input "rainDev", "capability.sensor", title: "Rain source (WS90 child) - optional but strongly recommended",
                  required: false, multiple: false
            input "tempDev", "capability.temperatureMeasurement", title: "Outdoor temperature (for freeze gating and dry-down context)",
                  required: false, multiple: false
        }

        section("<b>Her markers</b>") {
            paragraph "<i>These are the highest-value inputs in the whole app. The stress point " +
                      "cannot be learned without them. Virtual switches on a dashboard work well.<br>" +
                      "Note: morning wilt is real stress; afternoon wilt in heat usually is not.</i>"
            input "markNeeded", "capability.switch", title: "\"Garden needed water\" marker switch",
                  required: false, multiple: false
            input "markWatered", "capability.switch", title: "\"I just watered\" marker switch",
                  required: false, multiple: false
            input "autoResetMarkers", "bool",
                  title: "Turn marker switches back off after recording " +
                         "<b>(this SENDS AN OFF COMMAND - only pick virtual switches above)</b>",
                  defaultValue: true
        }

        section("<b>Season</b>") {
            paragraph "<i>The probe gets pulled when the garden is done for the year. While the " +
                      "season is off, sampling continues but nothing is learned - a probe sitting " +
                      "in the garage would otherwise teach the app that 3% is a normal reading.</i>"
            input "seasonSwitch", "capability.switch", title: "\"Garden season active\" switch (optional)",
                  required: false, multiple: false
            input "seasonAssumeActive", "bool", title: "If no switch is selected, assume the season is active", defaultValue: true
            input "outOfGroundPct", "decimal", title: "Suspect the probe is out of the ground below this reading", defaultValue: 6, required: true
        }

        section("<b>Sampling</b>") {
            input "sampleMin", "enum", title: "Sample interval", defaultValue: "15",
                  options: ["5": "5 minutes", "10": "10 minutes", "15": "15 minutes", "30": "30 minutes"], required: true
            input "freezeGuardF", "decimal", title: "Suspend learning when outdoor temperature is below (°F)", defaultValue: 36, required: true
            paragraph "<i>Frozen soil reads dry - a capacitive probe measures dielectric constant, " +
                      "and ice is roughly 3-4 against liquid water's 80. Without this guard a cold " +
                      "snap would teach the app that 8% is a normal dry reading.</i>"
        }

        section("<b>Wetting event detection</b>") {
            input "riseThreshold", "decimal", title: "Call it a wetting event after a rise of this many points", defaultValue: 4, required: true
            input "riseWindowMin", "number", title: "...within this many minutes", defaultValue: 45, required: true
            input "settleMin", "number", title: "Close the event after this many minutes with no further rise", defaultValue: 60, required: true
            input "fcMinRise", "decimal", title: "Only events rising at least this much inform the field-capacity estimate", defaultValue: 10, required: true
        }

        section("<b>Threshold model</b>") {
            input "madFraction", "decimal", title: "Management allowed depletion (0.5 = water at half the available water used)",
                  defaultValue: 0.5, required: true
            paragraph "<i>Crop dependent: shallow-rooted salad greens ~0.3-0.35, established " +
                      "tomatoes ~0.6. threshold = FC - mad x (FC - stress)</i>"
            input "anchorWindowDays", "number", title: "Only use anchor observations from the last N days", defaultValue: 730, required: true
            input "minFcObs", "number", title: "Confidence gate: field-capacity observations needed", defaultValue: 3, required: true
            input "minStressObs", "number", title: "Confidence gate: explicit stress marks needed (only if the buttons are used)", defaultValue: 2, required: true
            input "minImplicitObs", "number",
                  title: "Waterings needed before the stress point can be inferred", defaultValue: 4, required: true
            input "implicitPct", "number",
                  title: "Percentile of watering-start readings used as the stress point", defaultValue: 25, required: true
            input "clampFrac", "decimal",
                  title: "Safety clamp: threshold may not sit above this fraction of the way down from field capacity",
                  defaultValue: 0.35, required: true
            paragraph "<i>The stress point is normally <b>inferred</b> from the moisture reading at the " +
                      "moment the garden gets watered by hand - no button press needed. A low percentile " +
                      "is used because some watering is routine rather than because it is dry, and the " +
                      "lower tail is where it genuinely got dry. Simulation across 80 seasons: with " +
                      "mostly-reactive watering this lands within about 2 points of truth and never " +
                      "fires early. The clamp bounds the damage if watering becomes more routine over " +
                      "time.</i>"
        }

        section("<b>Forecast</b>") {
            input "useForecast", "bool", title: "Fetch daily rain and ET0 from Open-Meteo (free, no API key)", defaultValue: true
            input "latOverride", "decimal", title: "Latitude (blank = use hub location)", required: false
            input "lonOverride", "decimal", title: "Longitude (blank = use hub location)", required: false
        }

        section("<b>Data</b>") {
            input "writeFiles", "bool", title: "Write a daily sample CSV to File Manager", defaultValue: true
            input "keepDays", "number", title: "Keep this many daily files", defaultValue: 800, required: true
            input "keepEvents", "number", title: "Keep this many wetting-event summaries in state", defaultValue: 60, required: true
            input "keepDryDays", "number", title: "Keep this many dry-down day records in state", defaultValue: 400, required: true
        }

        section("<b>Status</b>") {
            paragraph statusText()
        }

        section("<b>Maintenance</b>", hideable: true, hidden: true) {
            paragraph "<i>Wipes every learned anchor, event and dry-down record, and overwrites the " +
                      "anchors backup file. <b>Use this after running simulations</b> - otherwise the " +
                      "app carries simulation garbage into the real season. There is no undo.</i>"
            input "confirmClear", "bool", title: "I understand this cannot be undone", defaultValue: false, submitOnChange: true
            if (confirmClear) input "btnClearLearned", "button", title: "Clear learned data now"
            if (state.lastClearIso) paragraph "<i>Last cleared: ${state.lastClearIso}</i>"
        }

        section("<b>Sensor health</b>") {
            input "staleHours", "decimal",
                  title: "Flag the sensor as stale if BOTH moisture and A/D are unchanged for this many hours",
                  defaultValue: 6, required: true
            paragraph "<i>Requires both to be frozen. Integer percent legitimately sits still for " +
                      "hours, but the raw A/D always jitters, so both being frozen means the sensor " +
                      "has stopped - not that the soil is stable. Learning is suspended while stale, " +
                      "which is how a dead probe gets caught before it poisons a season.</i>"
        }

        section("<b>Testing</b>", hideable: true, hidden: true) {
            input "simSpeedup", "number",
                  title: "Simulation speed-up divisor (1 = normal). <b>Leave at 1 for real use.</b>",
                  defaultValue: 1, required: true
            input "simSamplesPerDay", "number",
                  title: "Readings per simulated day (sim mode only)",
                  defaultValue: 1, required: true
            paragraph "<i>Day-boundary work - banking the daily reading that feeds the " +
                      "field-capacity estimate, and recording the dry-down - normally runs from a " +
                      "midnight cron, which never fires inside a scenario. In sim mode it is driven " +
                      "by reading count instead. At 1, every reading is a simulated day, so a " +
                      "60-step scenario banks 60 days and can actually reach a threshold.</i>"
            paragraph "<i>Divides every long duration - the +6/12/24 h follow-ups, the probe-out " +
                      "grace period, the season-restart guard, the dry-down day window and the " +
                      "anchor window - so a simulated month can run in minutes. At any value above " +
                      "1 the app also samples on every humidity event instead of waiting for the " +
                      "15-minute schedule, so a scenario runner can drive it as fast as it likes.<br>" +
                      "<b>Nothing learned while this is above 1 should be trusted.</b></i>"
        }

        section("<b>Logging</b>") {
            input "logEnable", "bool", title: "Debug logging", defaultValue: false
            input "txtEnable", "bool", title: "Description text logging", defaultValue: true
        }
    }
}

private String statusText() {
    StringBuilder sb = new StringBuilder()
    Map a = anchors()

    sb.append("Version ${VERSION}<br>")
    if (simActive()) {
        sb.append("<div style='background:#fdd;border:2px solid #b00;padding:6px;margin:4px 0'>" +
                  "<b>SIMULATION MODE - speed-up x${simSpeedup}.</b> Durations are compressed and " +
                  "sampling is event-driven. Anchors learned in this state are meaningless. " +
                  "Set the speed-up back to 1 and clear the learned data before real use.</div>")
    }
    if (state.sensorStale) {
        sb.append("<div style='background:#ffd;border:2px solid #b80;padding:6px;margin:4px 0'>" +
                  "<b>SENSOR LOOKS STALE</b> - ${state.staleReason ?: 'no change'}. Learning suspended.</div>")
    }
    sb.append("Season: <b>${seasonActive() ? 'active' : 'off - not learning'}</b>")
    if (state.suspectOutOfGround) sb.append(" &nbsp;<span style='color:#b00'>(probe may be out of the ground)</span>")
    sb.append("<br>")
    sb.append("Last sample: <b>${state.lastSampleMs ? isoOf(state.lastSampleMs) : 'none yet'}</b>")
    sb.append(" &nbsp; moisture <b>${state.lastPct ?: '-'}</b>%")
    sb.append(" &nbsp; A/D <b>${state.lastAD ?: '-'}</b>")
    sb.append(" &nbsp; battery <b>${state.lastBattery ?: '-'}</b><br>")

    sb.append("<br><b>What it has learned</b><br>")
    sb.append("<table style='width:100%'>")
    sb.append("<tr><td>Field capacity estimate</td><td align='right'><b>${a.fc ?: '-'}</b></td>" +
              "<td align='right'>${a.dailyCount ?: 0} days</td>" +
              "<td align='right'>${a.fcSource ?: (a.fcObs + ' soaking obs')}</td></tr>")
    sb.append("<tr><td>Stress point estimate</td><td align='right'><b>${a.stress ?: '-'}</b></td>" +
              "<td align='right'>${a.implicitObs ?: 0} waterings</td>" +
              "<td align='right'>${a.stressSource ?: 'not yet inferred'}</td></tr>")
    sb.append("<tr><td>Lowest survived reading</td><td align='right'><b>${state.lowestSurvived ?: '-'}</b></td>" +
              "<td colspan='2'><i>informational - not used by the gate in v${VERSION}</i></td></tr>")
    sb.append("<tr><td>Derived threshold</td><td align='right'><b>${a.threshold ?: 'not yet'}</b></td>" +
              "<td colspan='2'>${a.threshold ? 'dead band &plusmn;' + a.band : ''}" +
              "${a.clamped ? ' &nbsp;<b>(safety clamp applied - inferred stress looked too high)</b>' : ''}</td></tr>")
    sb.append("<tr><td>Confidence</td><td align='right'><b>${a.confidence}</b></td>" +
              "<td colspan='2'>${a.gateReason ?: ''}</td></tr>")
    sb.append("</table>")

    sb.append("<br><b>Would it notify right now?</b> <b>${wouldNotifyText(a)}</b><br>")
    sb.append("<i>v${VERSION} never actually sends anything. This line is here so the logic can be " +
              "watched against reality for a season before it is trusted.</i><br>")

    if (state.forecast) {
        sb.append("<br><b>Forecast</b> (fetched ${state.forecast.fetchedIso ?: '?'})<br>")
        sb.append("Rain next 48 h: <b>${state.forecast.rain48 ?: '-'}</b> in &nbsp; " +
                  "ET0 today: <b>${state.forecast.et0Today ?: '-'}</b> in<br>")
    }

    List ev = state.events ?: []
    if (ev) {
        sb.append("<br><b>Recent wetting events</b><br>")
        sb.append("<table style='width:100%'><tr>" +
                  "<th align='left'>start</th><th>type</th><th align='right'>from</th><th align='right'>peak</th>" +
                  "<th align='right'>rise/h</th><th align='right'>rain in</th><th align='right'>+24h gain</th></tr>")
        ev.reverse().take(10).each { e ->
            sb.append("<tr><td>${isoOf(e.t0)}</td><td>${e.classification}</td>" +
                      "<td align='right'>${e.startPct}</td><td align='right'>${e.peakPct}</td>" +
                      "<td align='right'>${e.riseRatePerHr ?: '-'}</td>" +
                      "<td align='right'>${e.rainInches != null ? e.rainInches : '-'}</td>" +
                      "<td align='right'><b>${e.effectiveGain != null ? e.effectiveGain : 'pending'}</b></td></tr>")
        }
        sb.append("</table>")
        sb.append("<i>The peak is the least useful number here. A big rise with a small +24 h gain " +
                  "means shallow watering, or a probe that is not where the water lands.</i><br>")
    }

    List dd = state.dryDays ?: []
    if (dd) {
        // Deliberately in double space. BigDecimal division here would throw
        // ArithmeticException on any non-terminating result (10/3), which is
        // exactly the trap called out in LaundryCycleLogger's fmt helpers.
        double tot = 0.0d
        dd.each { tot += ((it.dropPct ?: 0) as Number).doubleValue() }
        sb.append("<br><b>Dry-down</b>: ${dd.size()} day(s) recorded, " +
                  "mean ${fmt2(tot / dd.size())} pts/day<br>")
    }

    sb.append("<br>Samples recorded today: <b>${(state.rows?.size()) ?: 0}</b>")
    if (state.lastFile) sb.append(" &nbsp; last file: <b>${state.lastFile}</b>")
    return sb.toString()
}

private String wouldNotifyText(Map a) {
    if (!seasonActive()) return "no - season is off"
    if (state.suspectOutOfGround) return "no - probe may be out of the ground"
    if (state.sensorStale) return "no - sensor looks stale"
    if (a.threshold == null) return "no - ${a.gateReason ?: 'no threshold yet'}"
    if (state.lastPct == null) return "no - no reading"
    BigDecimal pct = safeDec(state.lastPct)
    BigDecimal fire = safeDec(a.threshold) - safeDec(a.band)
    if (pct > fire) return "no - ${pct} is above the fire line ${fmt2(fire)}"
    BigDecimal rain48 = safeDec(state.forecast?.rain48)
    if (rain48 != null && rain48 >= 0.25) return "no - ${rain48} in of rain forecast in 48 h"
    if ((state.lastRaining ?: "false") == "true") return "no - it is raining"
    return "YES - ${pct} is below the fire line ${fmt2(fire)}"
}

/* -------------------------------------------------------------- lifecycle */

def installed() {
    state.events = []
    state.dryDays = []
    state.fcObs = []
    state.stressObs = []
    state.recent = []
    restoreAnchors()
    initialize()
}

def updated() {
    unsubscribe()
    unschedule()
    initialize()
}

def initialize() {
    if (state.events == null)    state.events = []
    if (state.dryDays == null)   state.dryDays = []
    if (state.fcObs == null)     state.fcObs = []
    if (state.stressObs == null) state.stressObs = []
    if (state.recent == null)    state.recent = []
    if (state.rows == null)      state.rows = []
    if (state.fcDaily == null)   state.fcDaily = []
    if (state.implicitObs == null) state.implicitObs = []

    // If state came back empty (reinstall, restore), the anchors file is the
    // only copy of several seasons of observation. Try it before running blind.
    // The guard must cover everything restoreAnchors() overwrites, not just the
    // obs lists - otherwise, during the whole first season (when both lists are
    // legitimately empty) every press of Done would roll dryDays back to the
    // last saved snapshot.
    if (!state.fcObs && !state.stressObs && !state.dryDays) restoreAnchors()

    subscribe(soil, "humidity", soilHandler)
    subscribe(soil, "soilAD", adHandler)
    subscribe(soil, "battery", batteryHandler)
    // Only ever acted on while simActive(); harmless on a real WH51, which has
    // no such attribute and will never fire it.
    subscribe(soil, "simBoundary", simBoundaryHandler)

    if (rainDev) {
        subscribe(rainDev, "raining", rainingHandler)
        subscribe(rainDev, "rainEvent", rainEventHandler)
    }
    if (markNeeded)  subscribe(markNeeded, "switch.on", markNeededHandler)
    if (markWatered) subscribe(markWatered, "switch.on", markWateredHandler)
    if (seasonSwitch) subscribe(seasonSwitch, "switch", seasonHandler)

    if (state.seasonState == null && seasonSwitch) {
        state.seasonState = seasonSwitch.currentValue("switch")
    }

    Integer mins = (sampleMin ?: "15") as Integer
    switch (mins) {
        case 5:  runEvery5Minutes("sampleTick");  break
        case 10: runEvery10Minutes("sampleTick"); break
        case 30: runEvery30Minutes("sampleTick"); break
        default: runEvery15Minutes("sampleTick"); break
    }
    schedule("0 5 0 * * ?", "dayRollover")
    if (useForecast != false) {
        schedule("0 20 4 * * ?", "fetchForecast")
    }

    // These kickstarts MUST use different method names from the recurring jobs
    // above. runIn defaults to overwrite:true and Hubitat keys overwrite on the
    // HANDLER NAME, so runIn(5, "sampleTick") would silently cancel the
    // runEvery15Minutes("sampleTick") job created moments earlier - leaving the
    // app with no scheduled sampling at all.
    // Stale detection is timer-driven by nature: it fires when readings STOP,
    // so it can never be triggered by a reading arriving. In sim mode the only
    // other caller (sampleTick) is still on its real 15-minute schedule, which
    // is far too slow for a scenario - so the sensorSilent test could never
    // fire. A fast checker closes that.
    if (simActive()) runEvery1Minute("simStaleTick")

    runIn(5, "sampleTickNow")
    if (useForecast != false) runIn(30, "fetchForecastNow")

    if (simActive()) {
        log.warn "${app.label}: SIMULATION MODE ACTIVE (speed-up x${simFactor()}). Durations are " +
                 "compressed and sampling is event-driven. Do not trust anything learned in this state."
    }
    logInfo "initialised v${VERSION} - sampling every ${mins} min, season ${seasonActive() ? 'active' : 'off'}"
}

def appButtonHandler(String btn) {
    if (btn == "btnClearLearned") clearLearned()
}

private void clearLearned() {
    Integer hadFc = (state.fcObs ?: []).size()
    Integer hadSt = (state.stressObs ?: []).size()
    state.fcObs = []
    state.fcDaily = []
    state.stressObs = []
    state.implicitObs = []
    state.dryDays = []
    state.events = []
    state.recent = []
    state.lowestSurvived = null
    state.seasonStartedMs = null
    state.remove("openEvent")
    state.simSampleCount = 0
    // Volatile cross-run state. Without these, a scenario boundary would still
    // leak rain attribution, stale flags and the dry-down window into the next
    // test - the anchors would be clean but the classification would not be.
    state.remove("lastRainEvent")
    state.remove("lastRainRiseMs")
    state.remove("lastManualWaterMs")
    state.remove("lastRaining")
    state.remove("dayStartPct")
    state.remove("dayStartMs")
    state.remove("lowReadingSinceMs")
    state.remove("staleRefPct")
    state.remove("staleRefAD")
    state.remove("lastChangeMs")
    state.remove("lastEventMs")
    state.suspectOutOfGround = false
    state.sensorStale = false
    state.staleReason = null
    state.lastClearIso = isoOf(now())
    saveAnchors()
    app.updateSetting("confirmClear", [type: "bool", value: false])
    log.warn "${app.label}: LEARNED DATA CLEARED - discarded ${hadFc} field-capacity and " +
             "${hadSt} stress observation(s). Daily CSVs on disk are untouched."
}

def simStaleTick() { if (simActive()) checkStale() }

def sampleTickNow()    { sampleTick() }
def fetchForecastNow() { fetchForecast() }

def uninstalled() {
    log.info "${app.label}: removed"
}

/* -------------------------------------------------------------- handlers */

def soilHandler(evt) {
    BigDecimal pct = safeDec(evt.value)
    if (pct == null) return
    Long ms = now()
    noteSensorActivity(pct, safeDec(state.lastAD))
    state.lastPct = pct
    state.lastPctMs = ms
    pushRecent(ms, pct)
    checkRise(ms, pct)
    checkOutOfGround(pct)

    // In simulation the scenario runner drives events far faster than the
    // 15-minute schedule, so the whole sample path has to run per event or the
    // sim would exercise only rise detection and nothing downstream of it.
    if (simActive()) {
        state.lastSampleMs = ms
        trackLowestSurvived(pct)
        appendRow(ms, pct, null)
        flush()
        backfillFollowUps()

        // dayRollover() is otherwise only reachable from the midnight cron, and
        // it is the sole caller of recordDailyLevel() and computeDryDown().
        // Without this the simulator could never exercise the field-capacity
        // estimator or produce a single dry-down record - i.e. the main
        // estimator path in the shipped Groovy would be untested on-hub.
        Integer n = intSetting(state.simSampleCount, 0) + 1
        state.simSampleCount = n
        Integer per = Math.max(1, intSetting(simSamplesPerDay, 1))
        if (n % per == 0) {
            logDebug "sim: simulated day boundary after ${n} reading(s)"
            dayRollover()
        }
    }
}

def adHandler(evt) {
    BigDecimal ad = safeDec(evt.value)
    if (ad == null) return
    noteSensorActivity(safeDec(state.lastPct), ad)
    state.lastAD = ad
}

def batteryHandler(evt) {
    BigDecimal b = safeDec(evt.value)
    if (b != null) state.lastBattery = b
}

def rainingHandler(evt) {
    // Driver publishes this as the STRING "true"/"false", not a boolean.
    state.lastRaining = evt.value?.toString()
    if (state.lastRaining == "true") state.lastRainingMs = now()
}

def rainEventHandler(evt) {
    BigDecimal r = safeDec(evt.value)
    if (r == null) return
    BigDecimal prev = safeDec(state.lastRainEvent)
    // Only an actual INCREASE counts as rain arriving. The old test also fired
    // when prev was null, so the very first rainEvent reading ever seen stamped
    // "rain just rose" even when its value was 0 - a false marker that could
    // mis-attribute a hand watering in the first 15 minutes after install.
    if (prev != null && r > prev) state.lastRainRiseMs = now()
    state.lastRainEvent = r
}

def markNeededHandler(evt) {
    recordStressMark("she marked it as needing water")
    if (autoResetMarkers != false) runIn(5, "resetNeededMarker")
}

def markWateredHandler(evt) {
    state.lastManualWaterMs = now()
    logInfo "marked as watered by hand at ${isoOf(now())} (moisture ${state.lastPct})"
    noteRow("marked-watered")
    if (autoResetMarkers != false) runIn(5, "resetWateredMarker")
}

def resetNeededMarker()  { try { markNeeded?.off() }  catch (ex) { logDebug "marker reset failed - ${ex.message}" } }
def resetWateredMarker() { try { markWatered?.off() } catch (ex) { logDebug "marker reset failed - ${ex.message}" } }

/**
 * Pulsed by the scenario runner between tests so each scenario starts from a
 * known-empty logger. Guarded on simActive(): in production this is inert, so a
 * stray device event cannot destroy real anchors.
 */
def simBoundaryHandler(evt) {
    if (!simActive()) {
        log.warn "${app.label}: simBoundary received but simulation mode is OFF - ignoring. " +
                 "Nothing was cleared."
        return
    }
    clearLearned()
    log.info "${app.label}: === scenario boundary - learned and volatile state reset ==="
}

def seasonHandler(evt) {
    // EDGE DETECTION IS LOAD-BEARING. Turning the season on clears the
    // field-capacity observations, and a dashboard switch will happily re-send
    // "on" - a double tap, or a rule that sets it on every morning. Without
    // this guard, one mis-tap destroys the season's anchors and saveAnchors()
    // immediately mirrors the empty list over the backup.
    String prev = state.seasonState
    state.seasonState = evt.value
    if (evt.value == prev) {
        logDebug "season switch re-sent ${evt.value} - ignoring, no state change"
        return
    }

    if (evt.value == "on") {
        // The probe has just gone back in the ground, almost certainly in a
        // slightly different spot at a slightly different depth. Last season's
        // anchors are a reasonable prior but must not be trusted as fact, so
        // FC confidence is dropped until a real soaking re-confirms it.
        Long started = state.seasonStartedMs as Long
        if (started != null && (now() - started) < scaleMs(30L * 86400000L)) {
            logInfo "season switched on again only ${daysSince(started)} day(s) after the last start - " +
                    "keeping field-capacity observations rather than assuming the probe moved"
            state.suspectOutOfGround = false
            return
        }
        state.seasonStartedMs = now()
        state.fcObs = []
        state.fcDaily = []
        state.suspectOutOfGround = false
        state.lowestSurvived = null
        saveAnchors()
        logInfo "season started - field-capacity observations cleared, waiting for a soaking to re-anchor. " +
                "Stress marks kept as a prior."
    } else {
        logInfo "season ended - learning suspended, sampling continues"
    }
}

/* ------------------------------------------------------------- sampling */

def sampleTick() {
    BigDecimal pct = safeDec(soil?.currentValue("humidity"))
    if (pct == null) {
        logDebug "no humidity reading available yet"
        return
    }
    Long ms = now()
    state.lastPct = pct
    state.lastSampleMs = ms
    state.lastAD = safeDec(soil?.currentValue("soilAD")) ?: state.lastAD
    state.lastBattery = safeDec(soil?.currentValue("battery")) ?: state.lastBattery
    if (rainDev) {
        state.lastRaining   = rainDev.currentValue("raining")?.toString() ?: state.lastRaining
        state.lastRainDaily = safeDec(rainDev.currentValue("rainDaily"))
        state.lastRainRate  = safeDec(rainDev.currentValue("rainRate"))
    }
    if (tempDev) state.lastTempF = safeDec(tempDev.currentValue("temperature"))

    pushRecent(ms, pct)
    checkRise(ms, pct)
    checkStale()
    checkOutOfGround(pct)
    trackLowestSurvived(pct)
    appendRow(ms, pct, null)
    flush()
    backfillFollowUps()
}

private void pushRecent(Long ms, BigDecimal pct) {
    List r = state.recent ?: []
    r << [ms: ms, pct: pct, ad: state.lastAD, rainEv: state.lastRainEvent]
    Integer windowMin = intSetting(riseWindowMin, 45)
    if (simActive()) {
        // A time-based window is meaningless in simulation: readings arrive
        // seconds apart, so 45 minutes holds the ENTIRE previous scenario and
        // checkRise finds its low point - e.g. a run ending at 42 followed by
        // one starting at 46 opens a spurious 4-point event before the new
        // scenario has done anything. Bound by count instead; the priming steps
        // the sim runner prepends then flush it completely between runs.
        Integer simCap = 10
        if (r.size() > simCap) r = r[(-simCap)..-1]
        state.recent = r
        return
    }
    Long cutoff = ms - ((windowMin * 60000L) + 600000L)
    r = r.findAll { (it.ms as Long) >= cutoff }
    // Size the cap from the window, not a fixed 60. The WH51 transmits about
    // every 70 s, so a flat 60 would truncate to ~50 minutes of history and
    // silently override any riseWindowMin longer than that - the setting would
    // appear to work while doing nothing.
    Integer cap = Math.max(60, (windowMin * 2) + 30)
    if (r.size() > cap) r = r[(-cap)..-1]
    state.recent = r
}

private void noteRow(String note) {
    appendRow(now(), safeDec(state.lastPct), note)
    flush()
}

private void appendRow(Long ms, BigDecimal pct, String note) {
    if (writeFiles == false) return
    String dk = dayKey(ms)
    if (state.dayKey != dk) {
        state.dayKey = dk
        state.rows = []
    }
    List rows = state.rows ?: []
    rows << [
        ms      : ms,
        pct     : pct,
        ad      : state.lastAD,
        batt    : state.lastBattery,
        rate    : state.lastRainRate,
        daily   : state.lastRainDaily,
        raining : state.lastRaining,
        tempF   : state.lastTempF,
        et0     : state.forecast?.et0Today,
        fcst48  : state.forecast?.rain48,
        season  : seasonActive() ? 1 : 0,
        frozen  : freezing() ? 1 : 0,
        note    : note
    ]
    // Guard against a runaway event stream filling state on a bad day.
    while (rows.size() > 400) rows.remove(0)
    state.rows = rows
}

def dayRollover() {
    recordDailyLevel()
    computeDryDown()
    flush()
    backfillFollowUps()
    saveAnchors()
}

/* ----------------------------------------------------- wetting events -- */

private void checkRise(Long ms, BigDecimal pct) {
    // Closing an open event comes FIRST and must not sit behind the history
    // guard below - that guard is about having enough history to detect a new
    // rise, and has nothing to do with settling an event already in flight.
    if (state.openEvent) {
        Map oe = state.openEvent
        if (pct > safeDec(oe.peakPct)) {
            oe.peakPct = pct
            oe.peakMs = ms
        }
        BigDecimal rr = safeDec(state.lastRainRate)
        if (rr != null && (oe.rainRateMax == null || rr > safeDec(oe.rainRateMax))) {
            oe.rainRateMax = rr
        }
        state.openEvent = oe
        Long since = ms - (oe.peakMs as Long)
        if (since > scaleMs(intSetting(settleMin, 60) * 60000L)) closeEvent(ms)
        return
    }

    List r = state.recent ?: []
    if (r.size() < 2) return

    // Find the lowest point inside the rise window and see how far we have come up.
    //
    // When several samples tie at the minimum - which is the normal case, since
    // soil sits flat before it rises - take the LAST one, not the first.
    // Groovy's min() returns the earliest, which puts t0 further back than the
    // rise actually began. That inflates the rise duration (and so distorts
    // rise/h), and it takes startAD and the rain baseline from a sample whose
    // context may be minutes stale - which is how a soaking could report 0.0 in
    // of rain while an identical one reported 0.5.
    BigDecimal lowVal = null
    r.each { BigDecimal v = safeDec(it.pct); if (v != null && (lowVal == null || v < lowVal)) lowVal = v }
    if (lowVal == null) return
    Map lowest = null
    r.each { if (safeDec(it.pct) == lowVal) lowest = it }   // last match wins
    if (lowest == null) return
    BigDecimal rise = pct - safeDec(lowest.pct)
    // A rise up from a probe-out / no-data baseline is not a wetting event. When
    // the device is first attached the reading is 0 until real data arrives, and
    // the jump to the first genuine value looks exactly like a large watering -
    // which then banks a stress observation at 0%. Seen on the real install; the
    // simulator could never produce it because the sim device always starts sane.
    BigDecimal lowPct = safeDec(lowest.pct)
    if (lowPct != null && lowPct <= numSetting(outOfGroundPct, 6)) {
        logDebug "ignoring a rise from ${lowPct} - baseline is at or below the probe-out level, " +
                 "so this is the sensor coming online rather than water going in"
        return
    }
    if (rise >= numSetting(riseThreshold, 4)) {
        state.openEvent = [
            t0         : lowest.ms,
            startPct   : safeDec(lowest.pct),
            startAD    : lowest.ad,          // from the same sample as startPct
            peakPct    : pct,
            peakMs     : ms,
            // From the SAME sample as startPct, not from now. Captured at
            // event-open time this was already post-rain, so before == after
            // and every event reported 0.0 inches - which would have silently
            // emptied the rain-efficiency data for a whole season.
            rainAtT0   : lowest.rainEv,
            rainRateMax: state.lastRainRate
        ]
        logInfo "wetting event opened - up ${rise} points from ${lowest.pct} since ${isoOf(lowest.ms)}"
    }
}

private void closeEvent(Long ms) {
    Map oe = state.openEvent
    if (oe == null) return

    Long t0 = oe.t0 as Long
    Long peakMs = oe.peakMs as Long
    BigDecimal riseMin = new BigDecimal(peakMs - t0).divide(new BigDecimal(60000), 1, java.math.RoundingMode.HALF_UP)
    BigDecimal magnitude = safeDec(oe.peakPct) - safeDec(oe.startPct)

    // Classify from what the rain gauge was doing during the rise.
    Boolean manualNear = (state.lastManualWaterMs != null &&
                          (state.lastManualWaterMs as Long) >= t0 - scaleMs(3600000L))
    String cls = "manual"
    BigDecimal rainIn = null
    if (rainDev) {
        Long rainRise = state.lastRainRiseMs as Long
        // Scaled: unscaled, this 15-minute grace spans whole simulated runs, so
        // a rain scenario followed by a manual-watering scenario would leak its
        // rain marker across and mis-classify the watering.
        // Floored. At 5000x the raw 15 min becomes 180 ms, which is SHORTER than
        // Hubitat's own event-delivery jitter between two sendEvents in the same
        // step (measured 6-357 ms). When a scenario's rain and its t0 land in the
        // same step - which happens whenever the rise begins immediately, as in
        // toThreshold's first cycle - whether the rain is seen at all becomes a
        // coin flip. Production is unaffected: there the window is 15 real
        // minutes.
        Long grace = scaleMs(900000L)
        if (simActive() && grace < 30000L) grace = 30000L
        Boolean rainedDuring = (rainRise != null && rainRise >= t0 - grace && rainRise <= peakMs + grace)
        Boolean rainingNow = ((state.lastRaining ?: "false") == "true")
        if (rainedDuring || rainingNow) {
            cls = "rain"
            BigDecimal before = safeDec(oe.rainAtT0)
            BigDecimal after = safeDec(state.lastRainEvent)
            if (before != null && after != null && after >= before) rainIn = after - before
            else rainIn = safeDec(state.lastRainDaily)
            // A rise that began before any rain, then got rained on, cannot be
            // attributed cleanly. Flag it rather than guess - same discipline as
            // possibleMergedRun in the washer app.
            if (manualNear) cls = "ambiguous"
        }
    } else {
        // No rain device configured. Her "I just watered" marker is then the
        // only classifier available, so it must still be honoured here - the
        // old code short-circuited to unknown and threw the marker away.
        cls = manualNear ? "manual-confirmed" : "unknown-no-rain-source"
    }
    if (cls == "manual" && manualNear) cls = "manual-confirmed"

    BigDecimal ratePerHr = null
    if (riseMin > 0) {
        ratePerHr = magnitude.multiply(new BigDecimal(60)).divide(riseMin, 2, java.math.RoundingMode.HALF_UP)
    }

    Map rec = [
        t0            : t0,
        closedMs      : ms,
        startPct      : oe.startPct,
        startAD       : oe.startAD,
        peakPct       : oe.peakPct,
        peakMs        : peakMs,
        riseMin       : riseMin,
        magnitude     : magnitude,
        riseRatePerHr : ratePerHr,
        rainInches    : rainIn,
        rainRateMax   : oe.rainRateMax,
        classification: cls,
        frozen        : freezing(),
        seasonActive  : seasonActive(),
        id            : "ev-${t0}"
    ]
    // The reading at which she chose to water IS a stress-point observation -
    // her judgement, without a button. Filtered to the drier half of this
    // garden's own range: watering a wet garden is routine, not evidence about
    // dryness, and counting it drags the anchor up and the app notifies early.
    if (cls == "manual" || cls == "manual-confirmed") {
        recordImplicitStress(safeDec(rec.startPct), t0)
    }

    pushEvent(rec)
    state.remove("openEvent")

    // Once an event has completed, the dry baseline that preceded it must leave
    // the lookback buffer. Otherwise the very next flat reading is still "up 24
    // points from 21" and opens a fresh event off the same stale low, again and
    // again. The suite showed this plainly: four near-identical rain events per
    // soaking, every one starting at 21.
    //
    // Production was accidentally shielded from this - the 60-minute settle
    // outlasts the 55-minute lookback, so the old low had already aged out by
    // the time an event closed. That is a coincidence of two settings, not a
    // guarantee, and it breaks the moment either is changed.
    List trimmed = (state.recent ?: []).findAll { (it.ms as Long) >= peakMs }
    state.recent = trimmed

    logInfo "wetting event closed - ${cls}, ${oe.startPct} -> ${oe.peakPct} " +
            "(+${magnitude}) over ${riseMin} min" + (rainIn != null ? ", ${rainIn} in rain" : "")

    // The drainage tail is what actually matters. Peak is nearly meaningless.
    runIn(scaleSec(6  * 3600), "followUp6",  [data: [id: rec.id], overwrite: false])
    runIn(scaleSec(12 * 3600), "followUp12", [data: [id: rec.id], overwrite: false])
    runIn(scaleSec(24 * 3600), "followUp24", [data: [id: rec.id], overwrite: false])
}

def followUp6(data)  { recordFollowUp(data?.id, "pctPlus6") }
def followUp12(data) { recordFollowUp(data?.id, "pctPlus12") }
def followUp24(data) { recordFollowUp(data?.id, "pctPlus24") }

/**
 * runIn does NOT survive a hub reboot, and these are scheduled up to 24 h out -
 * so a reboot would leave effectiveGain permanently "pending" on any event in
 * flight. Every sample, sweep for follow-ups that are overdue and fill them in.
 * Belt and braces: the runIn fires first when nothing has gone wrong.
 */
private void backfillFollowUps() {
    List ev = state.events
    if (!ev) return
    Long n = now()
    Long slop = scaleMs(3 * 3600000L)   // accept a sample up to 3 h late; beyond that it is not a follow-up
    Boolean dirty = false

    // findAll returns a fresh list, so recordFollowUp writing into state.events
    // inside this loop is safe - no concurrent modification.
    ev.findAll { it.pctPlus24 == null }.each { e ->
        Long t = e.peakMs as Long
        if (t == null) return
        ["pctPlus6": 6L, "pctPlus12": 12L, "pctPlus24": 24L].each { field, hrs ->
            if (e[field] != null) return
            Long due = scaleMs(hrs * 3600000L)
            if ((n - t) < due) return
            if ((n - t) <= due + slop) {
                recordFollowUp(e.id, field)
            } else {
                // Too late to mean anything. Record that it was missed rather
                // than writing a current reading and pretending it is a +6 h
                // value - and mark the event so it cannot feed the FC anchor.
                e[field] = "missed"
                e.followUpLate = true
                dirty = true
                logDebug "follow-up ${field} for ${e.id} missed by ${daysSince(t + due)} day(s)"
            }
        }
    }
    if (dirty) state.events = ev
}

private void recordFollowUp(String id, String field) {
    if (!id) return
    List ev = state.events ?: []
    Integer idx = ev.findIndexOf { it.id == id }
    if (idx < 0) return
    Map e = ev[idx]
    // Idempotent. The runIn and the backfill sweep can both reach the same
    // event a second or two apart, and the suite showed each "settled at" line
    // twice, each one adding a field-capacity observation - so one soaking
    // counted as two.
    if (e[field] != null) return
    BigDecimal pct = safeDec(state.lastPct)
    if (pct == null) {
        // Writing null would leave the field unset and backfillFollowUps would
        // retry it on every sample forever.
        logDebug "follow-up ${field} skipped for ${id} - no current reading"
        return
    }
    e[field] = pct

    if (field == "pctPlus24") {
        e.effectiveGain = pct - safeDec(e.startPct)
        logInfo "event ${isoOf(e.t0)} settled at ${pct} - effective 24 h gain ${e.effectiveGain} " +
                "(peaked at ${e.peakPct})"
        // A large event that has settled tells us what this soil holds against
        // gravity. That is the field-capacity anchor - but ONLY if the +24 h
        // reading was actually taken near +24 h. A backfilled sample three days
        // late would poison the one dataset that cannot be regenerated.
        BigDecimal minRise = numSetting(fcMinRise, 10)

        // Decide SHALLOW first. A shallow event's settled value is the dry
        // baseline it fell back to, not field capacity - the suite recorded
        // "field-capacity observation: 23" from an event flagged shallow in the
        // very next line, which would drag the FC estimate toward the dry end.
        Boolean shallow = (safeDec(e.magnitude) >= minRise &&
                           e.effectiveGain != null &&
                           safeDec(e.effectiveGain) < (safeDec(e.magnitude) / 3))
        if (shallow) {
            e.shallowSuspect = true
            logInfo "event ${isoOf(e.t0)} looks shallow - rose ${e.magnitude} but only ${e.effectiveGain} left after 24 h"
        }

        // Only a large event that actually HELD its water tells us what this
        // soil holds against gravity. Late follow-ups are excluded because the
        // reading would not be a +24 h value at all.
        if (canLearn() && !e.followUpLate && !shallow && safeDec(e.magnitude) >= minRise) {
            addFcObservation(pct, e.t0 as Long)
        } else if (shallow) {
            logInfo "event ${isoOf(e.t0)} did NOT feed the field-capacity anchor - it drained away"
        }
    }
    ev[idx] = e
    state.events = ev
}

private void pushEvent(Map rec) {
    List ev = state.events ?: []
    ev << rec
    Integer keep = Math.max(1, intSetting(keepEvents, 60))
    while (ev.size() > keep) ev.remove(0)
    state.events = ev
}

/* ------------------------------------------------------------- anchors -- */

private Boolean seasonActive() {
    if (seasonSwitch) {
        String v = seasonSwitch.currentValue("switch")
        if (v == null) {
            log.warn "${app.label}: season switch ${seasonSwitch.displayName} has no value yet - " +
                     "treating the season as OFF, so nothing is being learned. Toggle it once to set it."
            return false
        }
        return (v == "on")
    }
    return (seasonAssumeActive != false)
}

private Boolean freezing() {
    BigDecimal t = safeDec(state.lastTempF)
    if (t == null) return false
    return t < numSetting(freezeGuardF, 36)
}

/** Names the specific guard, because "freeze or probe-out" lumped three
 *  different causes together and left the last suite run undiagnosable. */
private String blockReason() {
    if (!seasonActive())          return "season is off"
    if (freezing())               return "temperature ${state.lastTempF} F is below the freeze guard ${numSetting(freezeGuardF, 36)}"
    if (state.suspectOutOfGround) return "probe is flagged as possibly out of the ground"
    if (state.sensorStale)        return "sensor is flagged stale (${state.staleReason ?: 'no detail'})"
    return "no guard is set - this should not happen, please report it"
}

private Boolean canLearn() {
    if (!seasonActive()) return false
    if (freezing()) return false
    if (state.suspectOutOfGround) return false
    if (state.sensorStale) return false
    return true
}

/**
 * A dead or wedged sensor is the quiet killer here: it keeps reporting a
 * plausible number, learning carries on against it, and a season of anchors is
 * silently wrong. Requires BOTH the percentage and the raw A/D to be frozen -
 * integer percent legitimately sits still for hours, but A/D always jitters, so
 * both frozen means the sensor stopped, not that the soil is stable.
 */
private void noteSensorActivity(BigDecimal pct, BigDecimal ad) {
    Long ms = now()
    state.lastEventMs = ms
    Boolean changed = false
    if (pct != null && safeDec(state.staleRefPct) != pct) changed = true
    if (ad  != null && safeDec(state.staleRefAD)  != ad)  changed = true
    if (changed) {
        state.staleRefPct = pct
        state.staleRefAD  = ad
        state.lastChangeMs = ms
        if (state.sensorStale) {
            state.sensorStale = false
            state.staleReason = null
            logInfo "sensor readings are moving again - learning resumed"
        }
    }
}

private void checkStale() {
    BigDecimal hrs = numSetting(staleHours, 6)
    if (hrs == null || hrs <= 0) return
    Long win = scaleMs((long) (hrs.doubleValue() * 3600000.0d))
    // At high speed-ups this scales below the gap between scenarios (4.3 s at
    // 5000x), so the app flagged itself stale during every pause - and while
    // stale, canLearn() is false, so a follow-up firing in that gap silently
    // skipped its field-capacity observation. Floor it above the gap but well
    // under the 90 s that sensorSilent holds.
    if (simActive() && win < 30000L) win = 30000L
    Long ms = now()
    Long lastEvt = state.lastEventMs as Long
    Long lastChg = state.lastChangeMs as Long
    String reason = null

    if (lastEvt != null && (ms - lastEvt) > win) {
        reason = "no events at all for ${fmt2((ms - lastEvt) / 3600000.0d)} h - check battery and RF"
    } else if (lastChg != null && (ms - lastChg) > win) {
        reason = "moisture and A/D both frozen for ${fmt2((ms - lastChg) / 3600000.0d)} h"
    }

    if (reason != null && !state.sensorStale) {
        state.sensorStale = true
        state.staleReason = reason
        log.warn "${app.label}: sensor looks stale - ${reason}. Learning suspended."
    } else if (reason == null && state.sensorStale) {
        state.sensorStale = false
        state.staleReason = null
        logInfo "sensor no longer stale - learning resumed"
    }
}

/** Scaled, but floored: at 5000x the raw 2 h grace becomes 1.44 s, so a single
 *  low reading would trip the probe-out guard and silently stop learning. */
private Long outGraceMs() {
    Long g = scaleMs(7200000L)
    if (simActive() && g < 30000L) g = 30000L
    return g
}

private void checkOutOfGround(BigDecimal pct) {
    BigDecimal limit = numSetting(outOfGroundPct, 6)
    if (pct <= limit) {
        if (state.lowReadingSinceMs == null) state.lowReadingSinceMs = now()
        else if ((now() - (state.lowReadingSinceMs as Long)) > outGraceMs() && !state.suspectOutOfGround) {
            state.suspectOutOfGround = true
            log.warn "${app.label}: reading has sat at or below ${limit}% for over 2 h - " +
                     "probe may be out of the ground. Learning suspended until it recovers."
        }
    } else {
        state.lowReadingSinceMs = null
        if (state.suspectOutOfGround) {
            state.suspectOutOfGround = false
            logInfo "reading recovered above ${limit}% - learning resumed"
        }
    }
}

private void trackLowestSurvived(BigDecimal pct) {
    if (!canLearn()) return
    BigDecimal cur = safeDec(state.lowestSurvived)
    if (cur == null || pct < cur) state.lowestSurvived = pct
}

private void addFcObservation(BigDecimal pct, Long ms) {
    List o = state.fcObs ?: []
    o << [ms: ms, pct: pct]
    while (o.size() > 40) o.remove(0)
    state.fcObs = o
    logInfo "field-capacity observation recorded: ${pct} (now ${o.size()} obs)"
    saveAnchors()
}

private void recordImplicitStress(BigDecimal pct, Long ms) {
    if (pct == null || !canLearn()) return
    // Never anchor on a reading the probe-out guard would call "not in soil".
    if (pct <= numSetting(outOfGroundPct, 6)) {
        log.warn "${app.label}: refusing to bank ${pct} as a stress observation - at or below " +
                 "the probe-out level, so it is not a real watering baseline"
        return
    }
    List dl = state.fcDaily ?: []
    if (dl.size() >= 15) {
        List vals = dl.collect { safeDec(it.pct) }.findAll { it != null }.sort()
        BigDecimal mid = vals[vals.size().intdiv(2)]
        if (mid != null && pct >= mid) {
            logDebug "watering at ${pct} ignored for the stress anchor - not in the drier half (median ${mid})"
            return
        }
    }
    List o = state.implicitObs ?: []
    o << [ms: ms, pct: pct]
    while (o.size() > 60) o.remove(0)
    state.implicitObs = o
    logInfo "watering-start reading ${pct} banked as an implicit stress observation (now ${o.size()})"
    saveAnchors()
}

private void recordStressMark(String why) {
    BigDecimal pct = safeDec(state.lastPct)
    if (pct == null) {
        log.warn "${app.label}: stress mark ignored - no current reading"
        return
    }
    noteRow("marked-needed-water")
    if (!canLearn()) {
        logInfo "stress mark noted in the log but NOT learned from - ${blockReason()}"
        return
    }
    List o = state.stressObs ?: []
    o << [ms: now(), pct: pct]
    while (o.size() > 40) o.remove(0)
    state.stressObs = o
    logInfo "stress observation recorded: ${pct} - ${why} (now ${o.size()} obs)"
    saveAnchors()
}

/**
 * Both anchors are rolling MEDIANS, not means and not extremes, so one odd
 * reading cannot drag the whole scale. Windowed rather than all-time because
 * compost, tilling and root growth genuinely do change the soil.
 */
private Map anchors() {
    Long cutoff = now() - scaleMs(((long) Math.max(1, intSetting(anchorWindowDays, 730))) * 86400000L)
    List fcIn = (state.fcObs ?: []).findAll { (it.ms as Long) >= cutoff }
    List stIn = (state.stressObs ?: []).findAll { (it.ms as Long) >= cutoff }
    List impIn = (state.implicitObs ?: []).findAll { (it.ms as Long) >= cutoff }
    List dlIn = (state.fcDaily ?: []).findAll { (it.ms as Long) >= cutoff }

    // Primary FC: median of the top decile of ordinary daily readings.
    // Fallback: median of the rise-based observations.
    BigDecimal fc = null
    String fcSource = null
    Integer fcCount = 0
    if (dlIn.size() >= 20) {
        List sorted = dlIn.collect { safeDec(it.pct) }.findAll { it != null }.sort()
        Integer k = Math.max(1, sorted.size().intdiv(10))
        fc = medianOf(sorted[(-k)..-1])
        fcSource = "top decile of ${dlIn.size()} daily readings"
        fcCount = dlIn.size()
    } else if (fcIn.size() >= Math.max(1, intSetting(minFcObs, 3))) {
        fc = medianOf(fcIn.collect { it.pct })
        fcSource = "${fcIn.size()} soaking event(s)"
        fcCount = fcIn.size()
    }
    // Stress point. Explicit marks win when they exist - a button press is
    // unambiguous - but they are NOT required. Normally this is inferred from
    // the readings at which the garden actually got watered.
    BigDecimal st = null
    String stSource = null
    Integer needSt = Math.max(1, intSetting(minStressObs, 2))
    Integer needImp = Math.max(2, intSetting(minImplicitObs, 4))
    if (stIn.size() >= needSt) {
        st = medianOf(stIn.collect { it.pct })
        stSource = "${stIn.size()} explicit mark(s)"
    } else if (impIn.size() >= needImp) {
        // Distinct names: 'sorted' and 'k' are already used by the field-capacity
        // block further down this same method.
        List impSorted = impIn.collect { safeDec(it.pct) }.findAll { it != null }.sort()
        Integer pctile = Math.max(1, Math.min(99, intSetting(implicitPct, 25)))
        Integer impIdx = (int) Math.round((pctile / 100.0d) * (impSorted.size() - 1))
        if (impIdx < 0) impIdx = 0
        if (impIdx > impSorted.size() - 1) impIdx = impSorted.size() - 1
        st = impSorted[impIdx]
        stSource = "${pctile}th pct of ${impIn.size()} watering(s)"
    }

    Map out = [
        fc          : fc,
        stress      : st,
        fcObs       : fcIn.size(),
        stressObs   : stIn.size(),
        implicitObs : impIn.size(),
        stressSource: stSource,
        clamped     : false,
        fcAgeDays   : fcIn ? daysSince(fcIn[-1].ms as Long) : null,
        stressAgeDays: stIn ? daysSince(stIn[-1].ms as Long)
                            : (impIn ? daysSince(impIn[-1].ms as Long) : null),
        threshold   : null,
        band        : null,
        confidence  : "none",
        gateReason  : null,
        fcSource    : null,
        dailyCount  : 0
    ]

    // needSt and needImp are already declared above, where the stress source is
    // chosen. Re-declaring needSt here is what broke the v0.2.0 compile.
    Integer needFc = Math.max(1, intSetting(minFcObs, 3))

    out.fcSource = fcSource
    out.dailyCount = dlIn.size()

    if (fc == null) {
        Integer needDays = 20 - dlIn.size()
        out.gateReason = (needDays > 0)
            ? "needs ${needDays} more day(s) of readings (or ${Math.max(0, needFc - fcIn.size())} more soaking event(s))"
            : "no usable field-capacity estimate yet"
        return out
    }
    if (st == null) {
        out.gateReason = "needs ${Math.max(0, needImp - impIn.size())} more watering(s) to infer the stress point"
        return out
    }
    if (fc == null || st == null || fc <= st) {
        out.gateReason = "anchors do not make sense yet (FC must sit above stress)"
        return out
    }

    BigDecimal mad = numSetting(madFraction, 0.5)
    BigDecimal thr = fc - (fc - st).multiply(mad)

    // SAFETY CLAMP. The stress anchor is inferred from behaviour, so it drifts
    // upward if watering becomes routine rather than reactive - and a too-high
    // stress gives a too-high threshold, which notifies when the soil is not
    // actually dry. Independently of the anchor, never let the threshold sit in
    // the upper part of this soil's own observed range.
    if (dlIn.size() >= 20) {
        List dv = dlIn.collect { safeDec(it.pct) }.findAll { it != null }.sort()
        BigDecimal floorVal = dv[(int) Math.round(0.05d * (dv.size() - 1))]
        if (floorVal != null) {
            BigDecimal cap = fc - (fc - floorVal).multiply(numSetting(clampFrac, 0.35))
            if (thr > cap) {
                thr = cap
                out.clamped = true
            }
        }
    }
    out.threshold = round1(thr)

    // Confidence widens the dead band. Low confidence means the reading has to
    // sit clearly below the threshold before anything would ever fire - a false
    // "go water it" costs credibility, a missed one costs nothing.
    Integer score = 0
    if (dlIn.size() >= 60 || fcIn.size() >= needFc + 2) score++
    if (stIn.size() >= needSt + 2 || impIn.size() >= needImp + 4) score++
    if (out.fcAgeDays != null && out.fcAgeDays < 120) score++
    if (out.stressAgeDays != null && out.stressAgeDays < 240) score++

    if (score >= 3)      { out.confidence = "good";   out.band = round1((fc - st).multiply(new BigDecimal("0.05"))) }
    else if (score >= 1) { out.confidence = "medium"; out.band = round1((fc - st).multiply(new BigDecimal("0.10"))) }
    else                 { out.confidence = "low";    out.band = round1((fc - st).multiply(new BigDecimal("0.18"))) }

    return out
}

/* ------------------------------------------------------------ dry-down -- */

private void computeDryDown() {
    BigDecimal startPct = safeDec(state.dayStartPct)
    BigDecimal endPct   = safeDec(state.lastPct)
    Long dayStart       = state.dayStartMs as Long
    Long nowMs          = now()
    try {
        if (!canLearn()) return
        if (startPct == null || endPct == null || dayStart == null) return

        // Reject anything that is not roughly one day. Without this, a day
        // skipped by the freeze or season gate would leave dayStartMs pinned to
        // an older day and the next qualifying day would record a multi-day
        // drop as a single day's rate.
        // In sim mode a "day" is a reading count, not elapsed time, so the
        // real-time span guard would reject every one of them.
        Long spanMs = nowMs - dayStart
        if (!simActive() && (spanMs < scaleMs(20L * 3600000L) || spanMs > scaleMs(30L * 3600000L))) {
            logDebug "dry-down skipped - span was ${spanMs / 3600000L} h, not about a day"
            return
        }

        Boolean wetToday = (state.events ?: []).any { (it.t0 as Long) >= dayStart }
        if (!wetToday && endPct < startPct) {
            List dd = state.dryDays ?: []
            dd << [
                ms      : dayStart,
                dropPct : startPct - endPct,
                startPct: startPct,
                et0     : state.forecast?.et0Today,
                tempF   : state.lastTempF
            ]
            Integer keep = Math.max(1, intSetting(keepDryDays, 400))
            while (dd.size() > keep) dd.remove(0)
            state.dryDays = dd
            logDebug "dry-down recorded: ${startPct - endPct} pts, ET0 ${state.forecast?.et0Today}"
        }
    } finally {
        // Must run even when the gates above bail out, or the window drifts.
        state.dayStartPct = state.lastPct
        state.dayStartMs  = nowMs
    }
}

/**
 * One settled reading banked per day. This is the PRIMARY field-capacity input.
 *
 * The original design took FC only from a wetting event of >= fcMinRise points,
 * sampled 24 h later. Replaying synthetic seasons through that math (see
 * season_harness.py) showed it produces a usable threshold in only about 19% of
 * seasons: a garden that gets watered before it dries out rarely swings 10
 * points in one go, so the observations never accumulate. The top decile of
 * ordinary daily readings converged in 100% of the same seasons with roughly a
 * quarter of the error, because every day contributes instead of a handful.
 *
 * The rise-based observations are still recorded - they are directly meaningful
 * and worth reading - but they are now the fallback, not the main source.
 */
private void recordDailyLevel() {
    if (!canLearn()) return
    BigDecimal pct = safeDec(state.lastPct)
    if (pct == null) return
    List dl = state.fcDaily ?: []
    dl << [ms: now(), pct: pct]
    while (dl.size() > 800) dl.remove(0)
    state.fcDaily = dl
}

/* ------------------------------------------------------------ forecast -- */

def fetchForecast() {
    if (useForecast == false) return
    // Explicit null tests: Groovy truth would treat a legitimate 0.0 as false.
    BigDecimal lat = (latOverride != null) ? safeDec(latOverride) : safeDec(location?.latitude)
    BigDecimal lon = (lonOverride != null) ? safeDec(lonOverride) : safeDec(location?.longitude)
    if (lat == null || lon == null) {
        log.warn "${app.label}: no latitude/longitude available - set them in the app or on the hub"
        return
    }
    String tz = location?.timeZone?.ID ?: "America/New_York"
    Map params = [
        uri  : "https://api.open-meteo.com",
        path : "/v1/forecast",
        query: [
            latitude        : lat.toString(),
            longitude       : lon.toString(),
            daily           : "precipitation_sum,precipitation_probability_max,et0_fao_evapotranspiration",
            forecast_days   : "3",
            timezone        : tz,
            // Verified 2026-08-31 against a live call: precipitation_unit=inch
            // DOES apply to et0_fao_evapotranspiration - the response's
            // daily_units block came back with et0 reported as "inch", not mm.
            // Worth re-checking if the ET0 numbers ever look 25x too big.
            precipitation_unit: "inch"
        ],
        timeout: 20
    ]
    try {
        asynchttpGet("forecastCallback", params)
    } catch (ex) {
        log.warn "${app.label}: forecast request failed - ${ex.message}"
    }
}

def forecastCallback(resp, data) {
    try {
        if (resp?.status != 200) {
            log.warn "${app.label}: forecast HTTP ${resp?.status}"
            return
        }
        Map j = resp.json
        List sums = j?.daily?.precipitation_sum
        List probs = j?.daily?.precipitation_probability_max
        List et0s = j?.daily?.et0_fao_evapotranspiration
        if (sums == null || sums.size() < 2) {
            log.warn "${app.label}: forecast response missing precipitation_sum"
            return
        }
        // Open-Meteo can return null inside the daily arrays when a value is
        // unavailable for a date; null + BigDecimal throws.
        BigDecimal d0 = safeDec(sums[0]) ?: new BigDecimal("0")
        BigDecimal d1 = safeDec(sums[1]) ?: new BigDecimal("0")
        BigDecimal rain48 = d0 + d1
        state.forecast = [
            fetchedMs  : now(),
            fetchedIso : isoOf(now()),
            rainToday  : safeDec(sums[0]),
            rainTomorrow: safeDec(sums[1]),
            rain48     : round2(rain48),
            probToday  : probs ? probs[0] : null,
            et0Today   : et0s ? safeDec(et0s[0]) : null,
            et0Tomorrow: (et0s && et0s.size() > 1) ? safeDec(et0s[1]) : null
        ]
        logDebug "forecast: ${state.forecast.rain48} in over 48 h, ET0 today ${state.forecast.et0Today}"
    } catch (ex) {
        log.warn "${app.label}: forecast parse failed - ${ex.message}"
    }
}

/* ------------------------------------------------------------ file output */

/**
 * Rewrites the whole of today's file. Cheap at ~9 KB, and it means a reboot
 * loses nothing and there is no append/recovery path that can corrupt history.
 */
private void flush() {
    if (writeFiles == false) return
    List rows = state.rows
    if (!rows) return
    try {
        String fname = "${filePrefix()}${state.dayKey}.csv"
        StringBuilder sb = new StringBuilder()
        sb.append("# garden-moisture-logger v${VERSION} app=${app.label} device=${soil?.displayName}\n")
        sb.append("# day=${state.dayKey} sampleMin=${sampleMin ?: 15}\n")
        sb.append("# moisturePct is the Ecowitt 'humidity' attribute - remapped capacitance, NOT volumetric water content\n")
        sb.append("epochMs,iso,moisturePct,soilAD,battery,rainRate,rainDaily,raining,outdoorTempF,et0Today,fcstRain48h,seasonActive,frozen,note\n")
        rows.each { r ->
            sb.append("${r.ms},${isoOf(r.ms)},${nz(r.pct)},${nz(r.ad)},${nz(r.batt)},")
            sb.append("${nz(r.rate)},${nz(r.daily)},${nz(r.raining)},${nz(r.tempF)},")
            sb.append("${nz(r.et0)},${nz(r.fcst48)},${r.season},${r.frozen},${nz(r.note)}\n")
        }
        uploadHubFile(fname, sb.toString().getBytes("UTF-8"))
        state.lastFile = fname
        if (state.lastPruneDay != state.dayKey) {
            pruneFiles()
            state.lastPruneDay = state.dayKey
        }
        logDebug "wrote ${fname} (${rows.size()} rows)"
    } catch (ex) {
        log.warn "${app.label}: sample file write failed - ${ex.message}"
    }
}

/**
 * Keyed on app.id, NOT on the label. The label is user-editable and
 * submitOnChange means it changes mid-edit; if filenames tracked it, renaming a
 * zone would orphan the anchors file - which the header above calls the only
 * copy of several seasons of observation - and leave every old CSV unmatched by
 * pruneFiles, accumulating forever. The label goes in the CSV header comment
 * instead, where it is readable but not load-bearing.
 */
private String filePrefix() {
    return "garden_${app.id}_"
}

private void pruneFiles() {
    try {
        Integer keep = Math.max(1, intSetting(keepDays, 800))
        String prefix = filePrefix()
        List names = []
        getHubFiles()?.each { f ->
            String n = (f instanceof Map) ? (f.name ?: f.fileName ?: f.get("name")) : "${f}"
            // Exact shape, not startsWith: a prefix match would let one zone
            // delete a sibling zone's history.
            if (n && n ==~ /\Q${prefix}\E\d{4}-\d{2}-\d{2}\.csv/) names << n
        }
        names = names.sort()
        while (names.size() > keep) {
            String victim = names.remove(0)
            deleteHubFile(victim)
            logDebug "pruned ${victim}"
        }
    } catch (ex) {
        log.warn "${app.label}: prune failed - ${ex.message}"
    }
}

/**
 * State does not survive an app reinstall, and the anchors are the one thing
 * here that cannot be regenerated - they represent seasons of observation.
 */
private String anchorFileName() { return "${filePrefix()}anchors.json" }

private void saveAnchors() {
    try {
        Map payload = [
            version      : VERSION,
            savedIso     : isoOf(now()),
            fcObs        : state.fcObs ?: [],
            fcDaily      : state.fcDaily ?: [],
            implicitObs  : state.implicitObs ?: [],
            stressObs    : state.stressObs ?: [],
            lowestSurvived: state.lowestSurvived,
            dryDays      : state.dryDays ?: [],
            seasonStartedMs: state.seasonStartedMs
        ]
        uploadHubFile(anchorFileName(), JsonOutput.toJson(payload).getBytes("UTF-8"))
        logDebug "anchors saved"
    } catch (ex) {
        log.warn "${app.label}: anchor save failed - ${ex.message}"
    }
}

private void restoreAnchors() {
    try {
        byte[] raw = downloadHubFile(anchorFileName())
        if (raw == null) return
        Map j = new JsonSlurper().parseText(new String(raw, "UTF-8"))
        if (j == null) return
        state.fcObs = j.fcObs ?: []
        state.fcDaily = j.fcDaily ?: []
        state.implicitObs = j.implicitObs ?: []
        state.stressObs = j.stressObs ?: []
        state.lowestSurvived = j.lowestSurvived
        state.dryDays = j.dryDays ?: []
        state.seasonStartedMs = j.seasonStartedMs
        log.info "${app.label}: restored anchors from ${anchorFileName()} - " +
                 "${state.fcObs.size()} FC obs, ${state.stressObs.size()} stress obs"
    } catch (ex) {
        logDebug "no anchor file to restore (${ex.message})"
    }
}

/* ----------------------------------------------------------------- utils */

/* ------------------------------------------------- simulation support -- */

/**
 * Test-only time compression. Divides every LONG duration so a simulated month
 * can be exercised in minutes. It scales durations only - it never changes a
 * decision, a threshold or a classification, so what is under test stays the
 * real logic.
 */
private Integer simFactor() {
    Integer f = intSetting(simSpeedup, 1)
    return (f == null || f < 1) ? 1 : f
}

private Boolean simActive() { return simFactor() > 1 }

// intdiv, not `/`. In Groovy, Long / Integer produces a BigDecimal, and casting
// that back to Long is a runtime coin-flip. intdiv keeps it in integer space.
private Long scaleMs(Long ms) {
    Integer f = simFactor()
    if (f <= 1 || ms == null) return ms
    Long out = ms.intdiv((long) f)
    return (out < 1000L) ? 1000L : out
}

private Integer scaleSec(Integer sec) {
    Integer f = simFactor()
    if (f <= 1 || sec == null) return sec
    Integer out = sec.intdiv(f)
    return (out < 1) ? 1 : out
}

private String dayKey(Long ms) {
    SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd")
    if (location?.timeZone) f.setTimeZone(location.timeZone)
    return f.format(new Date(ms))
}

private Integer daysSince(Long ms) {
    if (ms == null) return null
    return (int) ((now() - ms) / 86400000L)
}

private BigDecimal medianOf(List vals) {
    if (!vals) return null
    List s = vals.findAll { it != null }.collect { (it as Number).doubleValue() }.sort()
    if (!s) return null
    int n = s.size()
    double m = (n % 2 == 1) ? s[n.intdiv(2)] : ((s[n.intdiv(2) - 1] + s[n.intdiv(2)]) / 2.0d)
    return new BigDecimal(String.format(java.util.Locale.US, "%.2f", m))
}

private BigDecimal safeDec(def v) {
    if (v == null) return null
    try { return new BigDecimal(v.toString()) } catch (ex) { return null }
}

/**
 * Numeric settings must NOT use the elvis operator for their default: 0 is
 * falsy in Groovy, so `freezeGuardF ?: 36` silently turns a deliberate 0 degF
 * into 36 degF. Same for a mad fraction of 0, or disabling a guard with 0.
 */
private BigDecimal numSetting(def v, def dflt) {
    if (v == null) return safeDec(dflt)
    BigDecimal d = safeDec(v)
    // NOT `safeDec(v) ?: safeDec(dflt)` - BigDecimal ZERO is falsy in Groovy,
    // which would resurrect the very trap this helper exists to prevent.
    return (d != null) ? d : safeDec(dflt)
}

private Integer intSetting(def v, Integer dflt) {
    if (v == null) return dflt
    try { return (v as BigDecimal).intValue() } catch (ex) { return dflt }
}

/**
 * Locale.US is not optional here. String.format with the JVM default locale
 * emits "3,45" on a comma-decimal locale, and new BigDecimal("3,45") throws
 * NumberFormatException - which in medianOf would propagate out of anchors()
 * and stop the config page rendering at all.
 */
private BigDecimal round1(def v) {
    if (v == null) return null
    try { return new BigDecimal(String.format(java.util.Locale.US, "%.1f", ((v as Number).doubleValue()))) } catch (ex) { return null }
}

private BigDecimal round2(def v) {
    if (v == null) return null
    try { return new BigDecimal(String.format(java.util.Locale.US, "%.2f", ((v as Number).doubleValue()))) } catch (ex) { return null }
}

private String fmt2(def v) {
    if (v == null) return "-"
    try { return String.format(java.util.Locale.US, "%.2f", ((v as Number).doubleValue())) } catch (ex) { return "-" }
}

private String nz(def v) { return (v == null) ? "" : v.toString() }

private String isoOf(def ms) {
    if (ms == null) return ""
    SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss")
    if (location?.timeZone) f.setTimeZone(location.timeZone)
    return f.format(new Date(ms as Long))
}

private void logDebug(String m) { if (logEnable) log.debug "${app.label}: ${m}" }

private void logInfo(String m) { if (txtEnable != false) log.info "${app.label}: ${m}" }
