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
 *      asset. ~23 KB/day at 5-minute sampling, ~8 MB/year retained.
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
 *  v0.3.3  2026-09-04  The season switch is now a PAUSE, not a reset.
 *
 *                      J.R.'s question: why throw away what we learned about
 *                      field capacity every spring, instead of keeping a rolling
 *                      window and letting new observations displace the old?
 *
 *                      Answered off-hub rather than by argument. Fixing
 *                      season_harness.py to be able to test it at all was the
 *                      first finding: its two restart modes, 'clear' and
 *                      'demote', BOTH wiped the daily pool - which is the only
 *                      thing the shipped percentile FC method reads - so they
 *                      were the same estimator and the existing comparison said
 *                      nothing about the config actually running.
 *
 *                      With a real 'keep' mode and a cold-start measurement
 *                      added, across 4 seasons x 12 seeds:
 *                        - blind days at the start of each season: 19 -> 0
 *                        - fires-too-late: FEWER when keeping, at 1x, 2x and 3x
 *                          the modelled re-seat offset. The wipe's negative bias
 *                          is what made it fire late.
 *                        - cost: MAE up 0.3-3 points, high-side tail up to +16
 *                          at 3x offsets - i.e. early alerts, not missed ones.
 *
 *                      The app already had every bound needed to do this:
 *                      anchorWindowDays at 730 days exists precisely so
 *                      observations survive across seasons, and the handler was
 *                      wiping them anyway. The two contradicted each other.
 *
 *                      Retires state.seasonStartedMs as a gate (kept as a record),
 *                      the 30-day guard, and the seasonDoubleTap failure class.
 *                      See SEASON_WIPE_VS_KEEP.md.
 *
 *  v0.3.2  2026-09-04  Probe-out detection rebuilt on measured numbers.
 *
 *                      The probe was deliberately pulled and logged at 10 s
 *                      resolution. Removal is a CLIFF, not a curve: soilAD went
 *                      277 -> 59 in a single report cycle, ~105 s after the
 *                      pull, and sat at 58-59 the whole time it was out. Dry in
 *                      open air at pairing it read 47. So the out-of-ground
 *                      band is AD 47-59, against a driest-soil-all-season of
 *                      AD 228. One reading is enough; no rate rule, no
 *                      multi-sample confirmation.
 *
 *                      1. Threshold is now a SETTING in soilAD units
 *                         (outOfGroundAD, default 90) rather than a percentage.
 *                         Percent is clamped to 0 below AD 67, so it cannot
 *                         tell "out of the ground" from "very dry soil" - both
 *                         read 0. AD can. Falls back to the old percentage test
 *                         when soilAD is unavailable, so a sensor that does not
 *                         report it still works.
 *
 *                      2. The confirm delay was 2 HOURS, hardcoded. Given a
 *                         105 s cliff that is indefensible; it is now a setting
 *                         (outGraceMin, default 10).
 *
 *                      3. trackLowestSurvived had no probe-out guard, unlike
 *                         its two siblings, and the grace period did NOT cover
 *                         it - it banks on the FIRST low sample, long before
 *                         suspectOutOfGround latches. It is display-only, so
 *                         this was cosmetic rather than dangerous, but a status
 *                         page reading 0% is still a lie.
 *
 *                      4. Rows are TAGGED. The CSV note column has been empty
 *                         on every row ever written; out-of-ground samples now
 *                         say so, so the archive carries what the app knew.
 *
 *                      5. Learning is held for a period after the probe comes
 *                         back (returnHoldMin, default 30). On reinsertion it
 *                         read 48% against a pre-pull 59% and climbed for
 *                         30+ min. NOTE: that figure is CONFOUNDED - rain
 *                         stopped mid-test, so real drainage and insertion
 *                         deficit are mixed and the true settling time is not
 *                         known. 30 min is a placeholder pending a repeat in
 *                         stable dry weather, not a measured value.
 *
 *  v0.3.1  2026-09-02  Fix: rainEvent is not monotonic, and closeEvent
 *                      assumed it was.
 *
 *                      Two storms hit within three hours. Between them the
 *                      WS90 started a new gauge "event" and reset its counter
 *                      to 0 - caught in the CSV at 09:29, dropping 0.56 to
 *                      0.05 between consecutive samples. closeEvent read that
 *                      as the counter running backwards and fell through to
 *                      rainDaily, attributing the WHOLE DAY (0.83 in, which
 *                      included the earlier storm's 0.50 in) to the second
 *                      event alone. The recorded figure was roughly triple the
 *                      truth and looked entirely plausible.
 *
 *                      rainDaily is monotonic within a day, so it is now the
 *                      primary source; rainEvent is the fallback for an event
 *                      spanning midnight; and if both counters go backwards the
 *                      result is null with a warning naming the reason, rather
 *                      than a wrong number that reads as real. rainSource on
 *                      each event record says which path produced the value.
 *
 *                      Third time the rain accounting has been wrong, and each
 *                      time the symptom was a plausible number rather than an
 *                      error. Worth stating the general rule: a counter read
 *                      from a device is only monotonic if the DEVICE promises
 *                      it is, and a fallback that silently substitutes a
 *                      different quantity is worse than no value at all.
 *  v0.3.0  2026-09-02  Recording changes only - no new gates. J.R.'s call
 *                      after the first real rain: "skip putting in any gates
 *                      right now and just do pure data recording and analysis."
 *
 *                      Rationale: gravity drainage rate depends on how far
 *                      above field capacity the soil starts, so "time to
 *                      plateau" is not a constant and any fixed drainage gate
 *                      would be wrong in both directions. Record first, derive
 *                      the rule from several events of different sizes later.
 *
 *                      (a) Sample interval default 15 -> 5 min. Resolution is
 *                          the ONLY thing that cannot be recovered afterwards;
 *                          every other threshold in this app merely shapes
 *                          derived anchors, which can be recomputed offline
 *                          from the raw CSV. Observed on 2026-09-02: the
 *                          gateway read 70% while the app still held 51%.
 *                      (b) rainEvent added to the CSV. rainRate and rainDaily
 *                          were logged but not rainEvent - the per-event
 *                          accumulator, which is what rainAtT0 uses and the
 *                          only clean way to pair rainfall with a rise.
 *                          rainDaily cannot substitute: it resets at midnight
 *                          and cannot separate two events in one day.
 *                      (c) Row cap 400 -> 900 to suit 5-minute sampling.
 *                      (d) Forecast accuracy log - see flushForecastLog().
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

@Field static final String VERSION = "0.5.1"

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
            input "outOfGroundAD", "number", title: "Suspect the probe is out of the ground below this <b>soilAD</b> reading", defaultValue: 90, required: true
            paragraph "<i>Measured 2026-09-04 by pulling the probe: in soil it read AD 277, out of " +
                      "the ground 58-59, and dry in open air at pairing 47. The driest soil all " +
                      "season was AD 228. The default of 90 sits between those with room on both " +
                      "sides. AD is used rather than the percentage because the percentage clamps " +
                      "to 0 below about AD 67, so it cannot tell a removed probe from very dry soil.</i>"
            input "outOfGroundPct", "decimal", title: "Fallback: suspect removal below this <b>percentage</b> (used only if soilAD is unavailable)", defaultValue: 6, required: true
            input "outGraceMin", "number", title: "...but only after the reading has stayed there this many minutes", defaultValue: 10, required: true
            paragraph "<i>Removal shows up as a cliff within about 105 s - one sensor report - so a " +
                      "long confirmation window buys nothing and leaves the probe unguarded " +
                      "meanwhile. This was hardcoded at 2 hours before v0.3.2.</i>"
            input "returnHoldMin", "number", title: "After the probe returns, wait this many minutes before learning again", defaultValue: 30, required: true
            paragraph "<i>A reinserted probe reads low until soil contact re-establishes. The one " +
                      "measurement of this is confounded (rain stopped mid-test, so drainage and " +
                      "insertion deficit are mixed), so 30 is a placeholder, not a measured value.</i>"
        }

        section("<b>Sampling</b>") {
            input "sampleMin", "enum", title: "Sample interval", defaultValue: "5",
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

        section("<b>Learned data - export / import</b>", hideable: true, hidden: true) {
            paragraph "<i>Anchors take a season to learn. App <b>state</b> is destroyed by a " +
                      "reinstall, a parent/child rewrite or a move to another hub - and the " +
                      "automatic anchors file is keyed on this app's internal id, which CHANGES " +
                      "on reinstall, so it cannot find itself afterwards. Give the export a name " +
                      "you choose and it survives all three.</i>"
            input "learnFile", "text", title: "Export file name (a name YOU choose, not the app id)",
                  description: "blank = derived from this zone's name", required: false
            input "placementLabel", "text",
                  title: "Placement label - which bed, which probe, what depth",
                  description: "e.g. Ky's garden, north bed, probe 8in mid-row", required: false
            paragraph "<i>Placement is the part that matters. A table learned at one probe position " +
                      "is not valid at another, and without a label an import silently carries the " +
                      "wrong numbers forward.</i>"
            input "learnPersist", "bool",
                  title: "Keep the export file current automatically (recommended)", defaultValue: true
            paragraph "<i>An export you have to remember to run does not help when the move was not " +
                      "planned - a dead hub, a bad reinstall. Leaving this on means the file is " +
                      "always current and moving is just copying it.</i>"
            input "btnExportNow", "button", title: "Export learned data now"
            if (state.learnExportIso) paragraph "<i>Last export: ${state.learnExportIso} -> ${state.learnExportFile ?: learnFileName()}</i>"
            else paragraph "<i>Nothing exported yet. The export is skipped while there is nothing learned, so it cannot overwrite a good file with an empty one.</i>"
            if (state.learnWriteError) paragraph "<b style='color:red'>Last export FAILED: ${state.learnWriteError}</b>"

            paragraph "<hr><b>Import into this instance</b>"
            input "seedFile", "text", title: "Import from this file name", required: false
            input "seedNow", "bool", title: "Import now (one shot - clears itself)",
                  defaultValue: false, submitOnChange: true
            if (state.learnSeededFrom) paragraph "<i>Seeded from: ${state.learnSeededFrom}</i>"
            paragraph "<i>⚠ Local hub backups do NOT include File Manager files - only cloud backup " +
                      "does. Copy the export off the hub as well.</i>"
        }

        section("<b>Sensor health</b>") {
            paragraph "<i>Silence is no longer used to detect a missing probe. The driver's " +
                      "<b>orphaned</b> attribute says so directly, and replaying real recorded " +
                      "events showed the old adaptive window would still have fired 50 false " +
                      "alarms. Only a STUCK sensor - one the gateway still hears, whose value " +
                      "never moves - is detected here now.</i>"
            input "staleMaxHours", "decimal",
                  title: "Absolute ceiling - always flag after this many hours of silence",
                  defaultValue: 48, required: true
            input "heartbeatDev", "capability.*",
                  title: "Liveness reference (no longer required - kept for drivers with no 'orphaned' attribute)",
                  description: "unused when the soil device publishes 'orphaned', which the Ecowitt driver does",
                  multiple: false, required: false
            paragraph "<i>Without a liveness reference, a dead radio and a soil reading that simply " +
                      "is not changing look identical from the hub. In dry weather this soil moves " +
                      "about a point a DAY, so a fixed threshold reports a healthy probe as failed " +
                      "every night - and a detector that cries wolf nightly trains you to ignore " +
                      "the one time it is right. The window now adapts to how often this sensor " +
                      "actually changes.</i>"
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
    // One-shot import. Checked here rather than on a button so the file name
    // typed alongside it is committed before the import runs. learnSeed()
    // clears the flag itself, so a later Done cannot silently re-import.
    if (seedNow == true) learnSeed()
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
    // Seed from the device rather than assuming false: the app can be reloaded
    // in the middle of an outage.
    try {
        state.probeOrphaned = (soil?.currentValue("orphaned")?.toString() == "true")
    } catch (ex) { logDebug "no orphaned attribute on ${soil?.displayName} - ${ex.message}" }

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
    // The Ecowitt driver publishes this when the GATEWAY loses the sensor. It is
    // an authoritative liveness signal - vastly better than inferring silence
    // from an unchanging value, which is what checkStale() was reduced to doing.
    // Verified 2026-09-09 to survive Hub Mesh: present on the .40 mirror, not
    // just the native device on .38.
    subscribe(soil, "orphaned", orphanHandler)
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

    Integer mins = (sampleMin ?: "5") as Integer
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
    if (btn == "btnExportNow") learnSave(true)
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
    state.remove("learnHoldUntilMs")
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

/**
 * The probe is orphaned - the gateway is no longer hearing it.
 *
 * Measured from TimescaleDB on 2026-09-09: 43 orphan cycles in four days,
 * individual outages of 18 to 57 minutes, and 57 gaps over 20 minutes in the
 * soilAD stream including stretches of 4.2 h, 3.7 h and 2.55 h. The WH51 sits
 * at about -98 dBm, twenty-five dB worse than any other Ecowitt sensor here.
 * These dropouts are real and frequent, not an artifact of a slow-changing
 * value - an earlier diagnosis in this project got that ranking backwards.
 *
 * Why it matters beyond the warning: while orphaned, currentValue() keeps
 * returning the LAST reading, so the sampler happily writes a frozen number
 * into the CSV every 5 minutes as though it were a fresh measurement. A 4.2 h
 * outage is ~50 rows of invented data that is indistinguishable from real
 * data after the fact. That is this project's signature failure mode, so the
 * rows are now marked at the time of writing.
 */
def orphanHandler(evt) {
    Boolean orph = (evt?.value?.toString() == "true")
    Long ms = now()
    if (orph && !state.probeOrphaned) {
        state.probeOrphaned  = true
        state.orphanSinceMs  = ms
        state.orphanCount    = ((state.orphanCount ?: 0) as Integer) + 1
        log.warn "${app.label}: probe ORPHANED - the gateway has lost it. Learning suspended and " +
                 "samples marked until it returns. (outage #${state.orphanCount})"
    } else if (!orph && state.probeOrphaned) {
        Long since = state.orphanSinceMs as Long
        BigDecimal mins = (since != null) ? new BigDecimal(ms - since).divide(new BigDecimal(60000), 1, java.math.RoundingMode.HALF_UP) : null
        state.probeOrphaned    = false
        state.orphanSinceMs    = null
        state.lastOrphanEndMs  = ms
        if (mins != null) {
            state.orphanMinutes = (safeDec(state.orphanMinutes) ?: new BigDecimal(0)) + mins
        }
        logInfo "probe back after ${mins ?: '?'} min orphaned - learning resumes"
    }
}

/** True if an orphan outage overlapped [fromMs, now]. A rise measured across one
 *  is not a rise: the reading was frozen, so the change is an artifact of the
 *  sensor returning, not of water arriving. */
private Boolean orphanSpanned(Long fromMs) {
    if (fromMs == null) return false
    if (state.probeOrphaned) return true
    Long ended = state.lastOrphanEndMs as Long
    return (ended != null && ended >= fromMs)
}

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
        // v0.3.3: the season switch is a PAUSE, not a reset.
        //
        // This used to wipe fcObs, fcDaily and lowestSurvived, on the theory that
        // a re-seated probe invalidates last season's scale. Measured off-hub over
        // 4 synthetic seasons x 12 seeds (season_harness.py; write-up in
        // SEASON_WIPE_VS_KEEP.md) the wipe was worse on both counts that matter:
        //
        //   - It cost 19 BLIND DAYS at the start of every season - the 20-day
        //     minimum on the percentile pool - during which no threshold exists.
        //   - It made the app fire LATE more often, not less. Wiping leaves a
        //     strong negative bias (-3.6, -2.1 in two of three gardener profiles),
        //     so it under-estimates the threshold. Keeping the history had FEWER
        //     fires-too-late at every re-seat offset tested: 1x, 2x and 3x the
        //     modelled +/-3.5 points.
        //
        // Nothing needs discarding here, because the bounds already exist and were
        // always meant to do this job: anchorWindowDays (730 d) ages out genuinely
        // stale observations, fcDaily caps at 800 entries and fcObs at 40 so new
        // readings displace old ones, and FC is a MEDIAN of the top decile - so a
        // re-seat offset drags the estimate gradually rather than lurching it.
        //
        // A probe moved to a genuinely different bed is a different event, and
        // Clear Learned Data already exists for exactly that.
        //
        // Known cost: at large re-seat offsets the estimate runs high for the first
        // few weeks, so expect some early "needs water" alerts. That is the safe
        // direction - a false alert costs credibility, a missed one costs plants -
        // but it is not free given this project exists to avoid notification
        // fatigue. If it bites, shorten anchorWindowDays from 730 to ~400 to carry
        // one winter instead of two.
        //
        // Removing the wipe also retires the old 30-day guard and the mis-tap
        // failure it only half-covered: a stray double tap can no longer cost a
        // season of anchors, and neither can a stray tap 31 days later.
        state.seasonStartedMs = now()
        state.suspectOutOfGround = false
        saveAnchors()
        logInfo "season started - carrying ${(state.fcDaily ?: []).size()} daily level(s) and " +
                "${(state.fcObs ?: []).size()} soaking observation(s) forward. Use Clear Learned " +
                "Data instead if the probe moved to a different bed."
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
    // Explicit null tests, NOT elvis: BigDecimal ZERO is falsy under Groovy
    // truth, so `?: previous` silently discards a legitimate reading of 0 and
    // pins the value at the last non-zero one forever. A dead cell reporting
    // battery 0 would log the last good value indefinitely.
    BigDecimal adNow = safeDec(soil?.currentValue("soilAD"))
    if (adNow != null) state.lastAD = adNow
    BigDecimal battNow = safeDec(soil?.currentValue("battery"))
    if (battNow != null) state.lastBattery = battNow
    if (rainDev) {
        state.lastRaining   = rainDev.currentValue("raining")?.toString() ?: state.lastRaining
        state.lastRainDaily = safeDec(rainDev.currentValue("rainDaily"))
        state.lastRainRate  = safeDec(rainDev.currentValue("rainRate"))
        // Same trap, and it matters more here: rainEvent legitimately reads 0
        // between storms and on every fresh install. A null or stale-high value
        // makes closeEvent fall back to the WHOLE DAY's rain instead of the
        // event's, which is the exact number this column exists to record.
        BigDecimal reNow = safeDec(rainDev.currentValue("rainEvent"))
        if (reNow != null) state.lastRainEvent = reNow
        // rainDaily resets at midnight but dayRollover does not run until 00:05,
        // by which time the day's total is already gone. So the running maximum
        // is banked against the day it belongs to BEFORE it can be overwritten.
        // Order matters: roll the day first, then take the maxima.
        rollRainDay()
        BigDecimal rdNow = safeDec(state.lastRainDaily)
        BigDecimal rdMax = safeDec(state.rainDailyMax)
        if (rdNow != null && (rdMax == null || rdNow > rdMax)) state.rainDailyMax = rdNow
        BigDecimal rrNow = safeDec(state.lastRainRate)
        BigDecimal rrMax = safeDec(state.rainRateMaxToday)
        if (rrNow != null && (rrMax == null || rrNow > rrMax)) state.rainRateMaxToday = rrNow
        noteForecastActual()
    }
    if (tempDev) state.lastTempF = safeDec(tempDev.currentValue("temperature"))

    pushRecent(ms, pct)
    checkRise(ms, pct)
    checkStale()
    checkOutOfGround(pct)
    trackLowestSurvived(pct)
    // Tag the row with what the app believed at the time. The note column has
    // been empty on every row ever written, which is why an archive scan cannot
    // tell a removed probe from a real reading without re-deriving it.
    appendRow(ms, pct, outOfGroundNote())
    flushThrottled()
    backfillFollowUps()
}

private void pushRecent(Long ms, BigDecimal pct) {
    List r = state.recent ?: []
    r << [ms: ms, pct: pct, ad: state.lastAD, rainEv: state.lastRainEvent,
          rainDy: state.lastRainDaily]
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
        event   : state.lastRainEvent,
        raining : state.lastRaining,
        tempF   : state.lastTempF,
        et0     : state.forecast?.et0Today,
        fcst48  : state.forecast?.rain48,
        season  : seasonActive() ? 1 : 0,
        frozen  : freezing() ? 1 : 0,
        // 1 = the gateway had lost the probe when this row was written, so the
        // reading is the last known value, not a fresh measurement.
        orphan  : state.probeOrphaned ? 1 : 0,
        note    : note
    ]
    // Guard against a runaway event stream filling state on a bad day.
    while (rows.size() > 900) rows.remove(0)
    state.rows = rows
}

def dayRollover() {
    rollRainDay()
    flushForecastLog()
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
    // Judge the BASELINE sample on its own recorded ad, not on the live reading -
    // by now the probe may well be back in the soil and reading normally.
    if (sampleOutOfGround(lowest.ad, lowPct)) {
        logDebug "ignoring a rise from ${lowPct} (AD ${lowest.ad}) - baseline is at or below the " +
                 "probe-out level, so this is the sensor coming online or the probe going back " +
                 "in the ground rather than water going in"
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
            rainAtT0     : lowest.rainEv,
            rainDailyAtT0: lowest.rainDy,
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
    String rainSource = null
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
            // rainEvent is NOT monotonic. The WS90 starts a new "event" after a
            // dry gap and resets the counter to 0 - observed 2026-09-02 09:29,
            // mid-storm, dropping 0.56 -> 0.05 between two samples. The old code
            // read that as the counter going backwards and fell through to
            // rainDaily, which attributed the ENTIRE DAY's rain (0.83 in,
            // including a previous storm's 0.50) to a single event.
            //
            // rainDaily IS monotonic within a day, so it is the primary source
            // now. rainEvent is the fallback, and both failing yields null
            // rather than a plausible-looking wrong number. rainSource records
            // which path was taken so the value is auditable afterwards.
            BigDecimal dBefore = safeDec(oe.rainDailyAtT0)
            BigDecimal dAfter  = safeDec(state.lastRainDaily)
            BigDecimal eBefore = safeDec(oe.rainAtT0)
            BigDecimal eAfter  = safeDec(state.lastRainEvent)
            if (dBefore != null && dAfter != null && dAfter >= dBefore) {
                rainIn = dAfter - dBefore
                rainSource = "daily-delta"
            } else if (eBefore != null && eAfter != null && eAfter >= eBefore) {
                // Crossed midnight: rainDaily reset but the gauge's event did not.
                rainIn = eAfter - eBefore
                rainSource = "event-delta"
            } else {
                rainIn = null
                rainSource = "unavailable"
                log.warn "${app.label}: cannot attribute rainfall to this event - " +
                         "rainDaily ${dBefore}->${dAfter}, rainEvent ${eBefore}->${eAfter}. " +
                         "Both counters went backwards (a midnight crossing during a " +
                         "gauge event reset). Recording null rather than a wrong number."
            }
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

    // A rise measured across an outage is not a rise - the reading was frozen,
    // so the jump on recovery is the sensor catching up, not water arriving.
    Boolean spanned = orphanSpanned(t0)
    if (spanned) {
        log.warn "${app.label}: this event spans a probe outage, so its magnitude and rate are not " +
                 "trustworthy - excluded from the field-capacity and stress anchors."
    }
    Map rec = [
        t0            : t0,
        closedMs      : ms,
        spannedOrphan : spanned,
        startPct      : oe.startPct,
        startAD       : oe.startAD,
        peakPct       : oe.peakPct,
        peakMs        : peakMs,
        riseMin       : riseMin,
        magnitude     : magnitude,
        riseRatePerHr : ratePerHr,
        rainInches    : rainIn,
        rainSource    : rainSource,
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
        if (canLearn() && !e.followUpLate && !e.spannedOrphan && !shallow &&
            safeDec(e.magnitude) >= minRise) {
            addFcObservation(pct, e.t0 as Long)
        } else if (e.spannedOrphan) {
            logInfo "event ${isoOf(e.t0)} did NOT feed the field-capacity anchor - it spans a probe outage"
        } else if (!canLearn()) {
            // Name the guard. A silent rejection here is why ev1 (2026-09-02,
            // magnitude 31, gain 15, not shallow, not late) banked nothing and
            // the reason is now unknowable. "A guard that suppresses learning
            // must log WHICH guard and why" - the rule already existed.
            logInfo "event ${isoOf(e.t0)} did NOT feed the field-capacity anchor - ${blockReason()}"
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
    if (state.probeOrphaned)      return "probe is ORPHANED - the gateway is not hearing it (since ${isoOf(state.orphanSinceMs)})"
    Long bh = state.learnHoldUntilMs as Long
    if (bh != null && now() < bh) return "learning is held until ${isoOf(bh)} after the probe returned"
    return "no guard is set - this should not happen, please report it"
}

private Boolean canLearn() {
    if (!seasonActive()) return false
    if (state.probeOrphaned) return false
    if (freezing()) return false
    if (state.suspectOutOfGround) return false
    if (state.sensorStale) return false
    // Set when the probe comes back from being out; see checkOutOfGround().
    Long hold = state.learnHoldUntilMs as Long
    if (hold != null && now() < hold) return false
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
        // changeGaps used to be accumulated here to drive an adaptive silence
        // window. That approach was deleted in v0.5.1 - see checkStale(). Dead
        // state that still LOOKS meaningful is its own hazard: a stale
        // fcSkipCount in this app's state once convinced a scheduled run that a
        // drainage guard was running months after its code had been deleted.
        state.remove("changeGaps")
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

/**
 * Is the sensor DEAD, or does it simply have nothing new to report?
 *
 * The original test was "no events for staleHours" against a fixed 6 h. That
 * was calibrated while watching a rainstorm, when the value moved every few
 * minutes. In a dry spell the soil changes about ONE POINT PER DAY, the Ecowitt
 * driver only raises an event when a value changes, and so a perfectly healthy
 * probe goes silent for 12+ hours every night. On 2026-09-08 it duly reported
 * "no events at all for 6.01 h - check battery and RF" about a sensor that was
 * fine. A detector that cries wolf nightly is worse than none: it trains the
 * reader to ignore the one time it is right.
 *
 * Two changes:
 *  1. The window ADAPTS to how often this sensor actually changes. If the last
 *     ten changes averaged 20 h apart, six hours of quiet means nothing.
 *     staleHours becomes a floor, staleMaxHours an absolute ceiling so a truly
 *     dead probe is still caught.
 *  2. An optional LIVENESS REFERENCE - any device that updates regardless of
 *     soil moisture. Ecowitt sensors are broadcast-only and share one gateway,
 *     so a WH31 temperature sibling is ideal: its value moves constantly, so
 *     fresh events from it prove the gateway and the Hubitat path are alive.
 *     That separates "the radio died" from "the number did not move", which
 *     cannot otherwise be told apart from the hub - and matters here because
 *     the WH51 sits at about -98 dBm, so real dropouts ARE plausible and must
 *     not be lost among nightly false alarms.
 */
/**
 * Is the sensor reporting but STUCK? That is all this does now.
 *
 * It used to also try to detect "the sensor is gone" by measuring silence, with
 * an adaptive window derived from how often the value changes. Replaying four
 * days of real recorded events through replay_harness.py showed that never
 * worked: changeGaps keeps the last ten intervals, during rain those are about
 * three minutes, so after any storm the median collapses and 3*median is
 * trivial - dropping the window back to the 6 h floor exactly before the quiet
 * night when the false alarms happen. Fifty would still have fired. Two
 * versions of tuning did not fix the case it existed for.
 *
 * The reason it could not work is that silence is ambiguous: a dead radio and a
 * soil reading that simply is not moving look identical from the hub. v0.5.0
 * subscribes to the driver's `orphaned` attribute, which is the gateway stating
 * the fact directly, so the inference is not merely unreliable - it is
 * unnecessary. Deleted rather than tuned a third time.
 *
 * What remains is the case `orphaned` does NOT cover: the gateway still hears
 * the sensor, events keep arriving, and the value never moves. A genuinely
 * frozen probe.
 */
private void checkStale() {
    BigDecimal maxH = numSetting(staleMaxHours, 48)
    if (maxH == null || maxH <= 0) return
    Long ceilWin = scaleMs((long) (maxH.doubleValue() * 3600000.0d))
    if (simActive() && ceilWin < 90000L) ceilWin = 90000L

    Long ms = now()
    Long lastChg = state.lastChangeMs as Long
    String reason = null
    if (!state.probeOrphaned && lastChg != null && (ms - lastChg) > ceilWin) {
        reason = "the gateway is still hearing the probe, but moisture and A/D have BOTH been " +
                 "frozen for ${fmt2((ms - lastChg) / 3600000.0d)} h - a stuck sensor, not a lost one"
    }

    if (reason != null && !state.sensorStale) {
        state.sensorStale = true
        state.staleReason = reason
        log.warn "${app.label}: ${reason}. Learning suspended."
    } else if (reason == null && state.sensorStale) {
        state.sensorStale = false
        state.staleReason = null
        logInfo "sensor readings are moving again - learning resumed"
    }
}

/** Scaled, but floored: at 5000x a 10 min grace becomes 0.12 s, so a single low
 *  reading would trip the probe-out guard and silently stop learning. */
private Long outGraceMs() {
    Long g = scaleMs(intSetting(outGraceMin, 10) * 60000L)
    if (simActive() && g < 30000L) g = 30000L
    return g
}

/** Same flooring rationale as outGraceMs(). */
private Long returnHoldMs() {
    Long g = scaleMs(intSetting(returnHoldMin, 30) * 60000L)
    if (simActive() && g < 30000L) g = 30000L
    return g
}

/**
 * True when the current sample does not look like it is in soil.
 *
 * Prefers soilAD. The percentage is clamped to 0 everywhere below about AD 67,
 * so on that axis "removed" and "bone dry" are the same number and cannot be
 * separated; on the AD axis they are ~170 counts apart. Measured 2026-09-04 by
 * pulling the probe: out of the ground reads AD 47-59, while the driest soil
 * all season was AD 228.
 *
 * Falls back to the percentage when soilAD is missing, so a sensor that does
 * not publish it keeps the old behaviour rather than losing the guard entirely.
 */
private boolean sampleOutOfGround(def adRaw, BigDecimal pct) {
    BigDecimal ad = safeDec(adRaw)
    if (ad != null) return ad <= numSetting(outOfGroundAD, 90)
    if (pct == null) return false
    return pct <= numSetting(outOfGroundPct, 6)
}

/** The CURRENT sample. For a historical one - an event baseline, say - call
 *  sampleOutOfGround() with that sample's own ad, not the live reading. */
private boolean looksOutOfGround(BigDecimal pct) {
    return sampleOutOfGround(state.lastAD, pct)
}

/**
 * What to write in the CSV note column for this sample, or null for none.
 *
 * Deliberately distinguishes the suspected case from the confirmed one. A row
 * that is below the threshold but inside the grace period is not yet acted on,
 * and an archive that flattened those two together would misreport when
 * learning actually stopped. No commas - this lands in a CSV field unquoted.
 */
private String outOfGroundNote() {
    if (looksOutOfGround(safeDec(state.lastPct))) {
        return state.suspectOutOfGround ? "probe-out" : "probe-out-unconfirmed"
    }
    Long hold = state.learnHoldUntilMs as Long
    if (hold != null && now() < hold) return "probe-returned-settling"
    return null
}

private void checkOutOfGround(BigDecimal pct) {
    if (looksOutOfGround(pct)) {
        if (state.lowReadingSinceMs == null) state.lowReadingSinceMs = now()
        else if ((now() - (state.lowReadingSinceMs as Long)) > outGraceMs() && !state.suspectOutOfGround) {
            state.suspectOutOfGround = true
            log.warn "${app.label}: reading has sat at or below the probe-out level " +
                     "(AD ${state.lastAD}, ${pct}%) for over ${intSetting(outGraceMin, 10)} min - " +
                     "probe may be out of the ground. Learning suspended until it recovers."
        }
    } else {
        state.lowReadingSinceMs = null
        if (state.suspectOutOfGround) {
            state.suspectOutOfGround = false
            // The probe is back, but a reinserted one reads low until soil
            // contact re-establishes - measured at 48% against a pre-pull 59%,
            // still climbing 30 min later. Learning off that dip would bank an
            // artificially dry reading as a real observation.
            state.learnHoldUntilMs = now() + returnHoldMs()
            logInfo "reading recovered (AD ${state.lastAD}) - probe looks back in the ground. " +
                    "Learning held ${intSetting(returnHoldMin, 30)} min while contact settles."
        }
    }
}

private void trackLowestSurvived(BigDecimal pct) {
    if (!canLearn()) return
    // canLearn() alone is NOT enough here. suspectOutOfGround does not latch
    // until the grace period expires, but this banks on the FIRST low sample -
    // so without a direct test a single pull drops the record to 0 immediately,
    // and it never recovers because the comparison is one-way. Display-only, so
    // the cost is a status page that lies rather than a bad watering decision.
    if (looksOutOfGround(pct)) return
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
    if (looksOutOfGround(pct)) {
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
        // Bank what was predicted so it can be scored against what actually falls.
        fcstUpsert(dayKey(now()), [
            issued  : state.forecast.fetchedIso,
            sameFcst: state.forecast.rainToday,
            prob    : state.forecast.probToday,
            et0Fcst : state.forecast.et0Today
        ])
        // Only the FIRST prediction for a date counts as the day-ahead one.
        // updated() fires runIn(30,"fetchForecastNow") on every Done press, so
        // without this an evening Done would overwrite a genuine ~24 h-ahead
        // forecast with one issued 4 h before midnight - biasing the log's
        // measured skill optimistically, with nothing in the row to detect it.
        String aheadDay = dayKey(now() + 86400000L)
        Map aheadRec = fcstLog().find { it.day == aheadDay }
        if (aheadRec == null || aheadRec.aheadFcst == null) {
            fcstUpsert(aheadDay, [
                aheadFcst  : state.forecast.rainTomorrow,
                aheadIssued: state.forecast.fetchedIso
            ])
        }
        logDebug "forecast: ${state.forecast.rain48} in over 48 h, ET0 today ${state.forecast.et0Today}"
    } catch (ex) {
        log.warn "${app.label}: forecast parse failed - ${ex.message}"
    }
}

/* ------------------------------------------- forecast accuracy log -- */

/**
 * One row per day: what was forecast, against what actually fell.
 *
 * Prompted by 2026-09-02. Open-Meteo called 0.00 in for the day and put 0.52 in
 * on tomorrow; 0.46 in fell that morning. The 48-hour total was close - the
 * daily split was inverted. That distinction decides whether a forecast may
 * ever be allowed to suppress a notification, and it cannot be settled by
 * reasoning about it, only by a season of paired forecast/actual values.
 *
 * Two errors are tracked separately because they are not the same error:
 *   sameFcst  - issued that morning for that day
 *   aheadFcst - issued ~24 h earlier for the same date
 * Costs nothing to collect and answers the question with evidence.
 */
private List fcstLog() { return (state.fcstLog ?: []) as List }

private void fcstUpsert(String day, Map fields) {
    if (!day) return
    if (fields.every { fk, fv -> fv == null }) return   // nothing to record yet
    List fl = fcstLog()
    Map rec = fl.find { it.day == day }
    if (rec == null) {
        rec = [day: day]
        fl << rec
    }
    fields.each { fk, fv -> if (fv != null) rec[fk] = fv }
    fl = fl.sort { it.day }
    while (fl.size() > 400) fl.remove(0)
    // Reassign rather than mutate in place: nested mutation inside state is not
    // reliably persisted by Hubitat.
    state.fcstLog = fl
}

/**
 * Bank the accumulated daily rain against the day it belongs to, at the moment
 * the date changes. Must be called BEFORE the running maxima are updated,
 * otherwise the new day's reset-to-zero reading is compared against yesterday's
 * maximum and yesterday's total leaks forward.
 */
private void rollRainDay() {
    String dk = dayKey(now())
    if (state.rainDayKey == dk) return
    if (state.rainDayKey) {
        fcstUpsert(state.rainDayKey as String, [
            actual : safeDec(state.rainDailyMax),
            rateMax: safeDec(state.rainRateMaxToday)
        ])
    }
    state.rainDayKey       = dk
    state.rainDailyMax     = null
    state.rainRateMaxToday = null
}

private void noteForecastActual() {
    fcstUpsert(dayKey(now()), [
        actual : safeDec(state.rainDailyMax),
        rateMax: safeDec(state.rainRateMaxToday)
    ])
}

private void flushForecastLog() {
    if (writeFiles == false) return
    List fl = fcstLog()
    if (!fl) return
    try {
        StringBuilder fb = new StringBuilder()
        fb.append("# garden-moisture-logger v${VERSION} forecast accuracy log - app=${app.label}\n")
        fb.append("# aheadFcstIn  = forecast issued about 24 h earlier for this date\n")
        fb.append("# sameDayFcstIn = forecast issued that morning for the same date\n")
        fb.append("# actualIn = observed total for the date (peak rainDaily before the midnight reset)\n")
        fb.append("date,issuedIso,aheadIssuedIso,aheadFcstIn,sameDayFcstIn,probPct,et0FcstIn,actualIn,peakRateInHr\n")
        fl.each { r ->
            fb.append("${r.day},${nz(r.issued)},${nz(r.aheadIssued)},${nz(r.aheadFcst)},${nz(r.sameFcst)},")
            fb.append("${nz(r.prob)},${nz(r.et0Fcst)},${nz(r.actual)},${nz(r.rateMax)}\n")
        }
        uploadHubFile("${filePrefix()}forecast.csv", fb.toString().getBytes("UTF-8"))
        logDebug "wrote forecast accuracy log (${fl.size()} days)"
    } catch (ex) {
        log.warn "${app.label}: forecast log write failed - ${ex.message}"
    }
}

/* ------------------------------------------------------------ file output */

/**
 * Rewrites the whole of today's file (~23 KB at 5-minute sampling), so it is
 * called via flushThrottled() from the sampler. It means a reboot
 * loses nothing and there is no append/recovery path that can corrupt history.
 */
/**
 * flush() rewrites the ENTIRE day's file each call, so write volume grows with
 * the SQUARE of the sample count: at 5-minute sampling that is 288 whole-file
 * writes of a file ending near 23 KB - about 3.3 MB/day, against 0.4 MB/day at
 * the old 15-minute rate, or roughly 1.2 GB/year onto the hub's eMMC.
 *
 * state.rows is the source of truth and survives a reboot, so the FILE is
 * allowed to lag: a skipped flush costs at most a couple of samples of file
 * content and never any recorded data. Marker rows, event closes and the daily
 * rollover all still call flush() directly, so nothing important waits.
 */
private void flushThrottled() {
    Integer fc = (state.flushCount ?: 0) + 1
    state.flushCount = fc
    // Every third sample restores the old once-per-15-minutes write rate while
    // keeping three times the data. Simulation always writes, so the suite sees
    // every row immediately.
    if (fc % 3 == 0 || simActive()) flush()
}

private void flush() {
    if (writeFiles == false) return
    List rows = state.rows
    if (!rows) return
    try {
        String fname = "${filePrefix()}${state.dayKey}.csv"
        StringBuilder sb = new StringBuilder()
        sb.append("# garden-moisture-logger v${VERSION} app=${app.label} device=${soil?.displayName}\n")
        sb.append("# day=${state.dayKey} sampleMin=${sampleMin ?: 5}\n")
        sb.append("# moisturePct is the Ecowitt 'humidity' attribute - remapped capacitance, NOT volumetric water content\n")
        sb.append("epochMs,iso,moisturePct,soilAD,battery,rainRate,rainDaily,rainEvent,raining,outdoorTempF,et0Today,fcstRain48h,seasonActive,frozen,probeOrphaned,note\n")
        rows.each { r ->
            sb.append("${r.ms},${isoOf(r.ms)},${nz(r.pct)},${nz(r.ad)},${nz(r.batt)},")
            sb.append("${nz(r.rate)},${nz(r.daily)},${nz(r.event)},${nz(r.raining)},${nz(r.tempF)},")
            sb.append("${nz(r.et0)},${nz(r.fcst48)},${r.season},${r.frozen},${nz(r.orphan)},${nz(r.note)}\n")
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
    // Outside the try: an anchors write failure must not also skip the export.
    learnSave()
}

/* ------------------------------------ learned data: export / import -- */

/**
 * J.R.'s framing, 2026-09-06: "it's an EXPORT right before moving the app."
 * Correct as the user-facing action - but the file is kept current
 * automatically as well, because the moves that lose data are the UNPLANNED
 * ones: a dead hub, a botched reinstall, a parent/child rewrite done to fix
 * something else. An export you have to remember does not cover those.
 *
 * Distinct from saveAnchors(), which stays as-is: that file is named
 * garden_<app.id>_anchors.json, and app.id CHANGES on reinstall, so the new
 * install looks for a file that does not exist and silently starts empty while
 * a season of learning sits on disk under the old id. This one is named by the
 * USER, so it survives a reinstall, a hub move, and a parent/child rewrite.
 *
 * Mirrors Bathroom Fan NextGen v2.3.0 (learnSave/learnSeed/placementLabel/
 * seedFile/seedNow) deliberately - same dialect, so the two read together.
 */
private String learnFileName() {
    String lf = (learnFile ?: "").trim()
    // Fall back to the ZONE NAME, never app.id. Falling back to app.id would
    // reintroduce the exact bug this feature exists to fix, silently, for any
    // child created by the parent or pushed via saveOrUpdateJson without the
    // page ever being opened (Hubitat commits defaultValue only on submit).
    if (!lf) lf = "garden_learn_" + ((thisName ?: app.label ?: "zone").toString().toLowerCase())
    lf = lf.replaceAll(/[^A-Za-z0-9_\-.]/, "_")
    if (!lf.toLowerCase().endsWith(".json")) lf = lf + ".json"
    // Must not collide with the app's own generated files.
    if (lf == anchorFileName() || lf.startsWith(filePrefix())) lf = "export_" + lf
    return lf
}

/** Everything the app has LEARNED, as opposed to raw samples. */
private Map learnTables() {
    return [
        fcObs          : state.fcObs ?: [],
        fcDaily        : state.fcDaily ?: [],
        implicitObs    : state.implicitObs ?: [],
        stressObs      : state.stressObs ?: [],
        dryDays        : state.dryDays ?: [],
        // The wetting-event archive: rain attribution, rainSource, follow-ups.
        // Not regenerable except from the CSVs. Was missing.
        events         : state.events ?: [],
        fcstLog        : state.fcstLog ?: [],
        lowestSurvived : state.lowestSurvived,
        seasonStartedMs: state.seasonStartedMs,
        rainDayKey     : state.rainDayKey,
        rainDailyMax   : state.rainDailyMax,
        rainRateMaxToday: state.rainRateMaxToday
    ]
}

private Boolean nothingLearned() {
    Map t = learnTables()
    return !(t.fcObs || t.fcDaily || t.implicitObs || t.stressObs || t.dryDays ||
             t.events || t.fcstLog || t.lowestSurvived != null)
}

private void learnSave(Boolean force = false) {
    if (!force && learnPersist == false) return
    // ---------------------------------------------------------------- #1 --
    // THE important guard. Without it this feature destroys its own backup in
    // precisely the window it exists for: after a reinstall, state is empty,
    // learnFile resolves to the same name as the good file, and the first
    // unattended dayRollover() overwrites a season of learning with []. The
    // user never touched anything. Never write an empty payload.
    if (nothingLearned()) {
        logDebug "export skipped - nothing learned yet, refusing to overwrite ${learnFileName()}"
        return
    }
    try {
        // A few recent (AD, pct) pairs travel with the data. They are the only
        // automatic way to detect a RECALIBRATION later: the percentage is
        // capacitance remapped between the gateway's dry-air and submerged
        // points, so if those change the same AD maps to a different pct and
        // every stored percentage anchor is void.
        List pairs = []
        (state.recent ?: []).reverse().each { rr ->
            if (pairs.size() < 5 && rr?.ad != null && rr?.pct != null) pairs << [ad: rr.ad, pct: rr.pct]
        }
        Map payload = [
            provenance: [
                app         : (app.label ?: "Garden Moisture Logger Child"),
                appId       : app.id,
                zone        : (thisName ?: ""),
                placement   : (placementLabel ?: "UNSET - placement not recorded"),
                soilDevice  : (soil?.displayName ?: "unknown"),
                rainDevice  : (rainDev?.displayName ?: "none"),
                version     : VERSION,
                writtenIso  : isoOf(now()),
                adPctPairs  : pairs,
                // The tables are only interpretable against the settings that
                // shaped them - the same observations under a different MAD or
                // percentile give a different threshold.
                settings    : [
                    madFraction     : madFraction,
                    clampFrac       : clampFrac,
                    implicitPct     : implicitPct,
                    anchorWindowDays: anchorWindowDays,
                    minFcObs        : minFcObs,
                    minImplicitObs  : minImplicitObs,
                    sampleMin       : sampleMin
                ],
                counts      : [
                    fcObs      : (state.fcObs ?: []).size(),
                    fcDaily    : (state.fcDaily ?: []).size(),
                    implicitObs: (state.implicitObs ?: []).size(),
                    stressObs  : (state.stressObs ?: []).size(),
                    dryDays    : (state.dryDays ?: []).size(),
                    events     : (state.events ?: []).size(),
                    fcstLog    : (state.fcstLog ?: []).size()
                ]
            ],
            learn: learnTables()
        ]
        String fn = learnFileName()
        uploadHubFile(fn, JsonOutput.toJson(payload).getBytes("UTF-8"))
        state.learnExportIso  = isoOf(now())
        state.learnExportFile = fn      // the name actually written, not a recomputed one
        state.learnWriteError = null
        logDebug "learned data exported to ${fn}"
    } catch (ex) {
        state.learnWriteError = ex.message
        log.warn "${app.label}: could not export learned data - ${ex.message}"
    }
}

/** One-shot import. Never automatic: silently inheriting another bed's numbers
 *  is worse than starting empty. */
private void learnSeed() {
    try {
        String sf = (seedFile ?: "").trim()
        if (!sf) { log.warn "${app.label}: import requested but no file name given"; return }
        byte[] raw = downloadHubFile(sf)
        if (!raw) { log.warn "${app.label}: seed file ${sf} is missing or empty"; return }
        Map parsed = new JsonSlurper().parseText(new String(raw, "UTF-8"))
        Map tbl = parsed?.learn as Map
        if (!tbl) { log.warn "${app.label}: ${sf} has no 'learn' section - nothing imported"; return }

        // ------------------------------------------------------------ #3 --
        // A valid-but-PARTIAL file must not blank the tables it omits.
        // `tbl.fcObs ?: []` would replace a season of data with [] for any key
        // the file happens to lack - a hand-edited file, an older version, a
        // half-written upload. Require every list, and require the right type.
        List required = ["fcObs","fcDaily","implicitObs","stressObs","dryDays","events","fcstLog"]
        List missing = required.findAll { k -> !(tbl.containsKey(k)) }
        if (missing) {
            log.warn "${app.label}: REFUSING to import ${sf} - missing ${missing}. A partial file " +
                     "would blank the tables it omits. Nothing changed."
            return
        }
        List badType = required.findAll { k -> !(tbl[k] instanceof List) }
        if (badType) {
            log.warn "${app.label}: REFUSING to import ${sf} - ${badType} are not lists. Nothing changed."
            return
        }

        Map prov = (parsed?.provenance ?: [:]) as Map
        String mine = (placementLabel ?: "")
        String theirs = (prov?.placement ?: "")
        if (mine && theirs && mine != theirs) {
            log.warn "${app.label}: SEED PLACEMENT MISMATCH - this instance is '${mine}' but the " +
                     "data was learned at '${theirs}'. Imported anyway; treat these as a starting " +
                     "guess, NOT as measurements of this probe in this hole."
        } else if (!theirs) {
            log.warn "${app.label}: seed file records no placement. Cannot tell whether these " +
                     "numbers apply to this probe position."
        }

        // ------------------------------------------------------------ #4 --
        // Calibration check. On a fresh install lastAD/lastPct are still null
        // (the first sample is 5 s away), which is EXACTLY when imports happen -
        // so say the check could not run rather than passing silently.
        BigDecimal nowAD = safeDec(state.lastAD)
        BigDecimal nowPct = safeDec(state.lastPct)
        List oldPairs = (prov?.adPctPairs ?: []) as List
        if (nowAD == null || nowPct == null) {
            log.warn "${app.label}: no live reading yet, so the calibration check could NOT run. " +
                     "If the probe was recalibrated since this file was written, every percentage " +
                     "anchor in it is void. Re-check once a sample arrives."
        } else if (oldPairs) {
            BigDecimal bestGap = null
            oldPairs.each { op ->
                BigDecimal oad = safeDec(op?.ad); BigDecimal opct = safeDec(op?.pct)
                if (oad != null && opct != null && (oad - nowAD).abs() <= 5) {
                    BigDecimal gap = (opct - nowPct).abs()
                    if (bestGap == null || gap < bestGap) bestGap = gap
                }
            }
            if (bestGap != null && bestGap > 3) {
                log.warn "${app.label}: CALIBRATION SHIFT SUSPECTED - a similar raw A/D reading " +
                         "mapped to a percentage ${bestGap} points different when this data was " +
                         "learned. Imported anyway, but if the probe was recalibrated these anchors " +
                         "are void - Clear learned data and start over."
            }
        }

        // Snapshot before overwriting, so a bad import is recoverable.
        try {
            if (!nothingLearned()) {
                Map snap = [provenance: [note: "pre-import snapshot", writtenIso: isoOf(now())],
                            learn: learnTables()]
                uploadHubFile("preimport_" + learnFileName(),
                              JsonOutput.toJson(snap).getBytes("UTF-8"))
                log.info "${app.label}: existing data snapshotted to preimport_${learnFileName()}"
            }
        } catch (ex2) {
            log.warn "${app.label}: pre-import snapshot failed (${ex2.message}) - importing anyway"
        }

        state.fcObs            = tbl.fcObs
        state.fcDaily          = tbl.fcDaily
        state.implicitObs      = tbl.implicitObs
        state.stressObs        = tbl.stressObs
        state.dryDays          = tbl.dryDays
        state.events           = tbl.events
        state.fcstLog          = tbl.fcstLog
        state.lowestSurvived   = tbl.lowestSurvived
        state.seasonStartedMs  = tbl.seasonStartedMs
        state.rainDayKey       = tbl.rainDayKey
        state.rainDailyMax     = tbl.rainDailyMax
        state.rainRateMaxToday = tbl.rainRateMaxToday
        state.learnSeededFrom = "${sf} (from ${prov?.app ?: 'unknown'}, placement " +
                                "'${theirs ?: 'unrecorded'}', written ${prov?.writtenIso ?: 'unknown'})"
        log.info "${app.label}: imported from ${sf} - ${state.fcObs.size()} FC obs, " +
                 "${state.stressObs.size()} stress obs, ${state.fcDaily.size()} daily, " +
                 "${state.events.size()} events, ${state.fcstLog.size()} forecast days. " +
                 "${state.learnSeededFrom}"
        saveAnchors()      // also re-stamps the export via learnSave()
    } catch (ex) {
        log.warn "${app.label}: import from ${seedFile} failed - ${ex.message}"
    } finally {
        // #7: cleared on EVERY path, including the blank-filename one, so a
        // later unrelated Done cannot trigger an unrequested import.
        app.updateSetting("seedNow", [type: "bool", value: false])
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
