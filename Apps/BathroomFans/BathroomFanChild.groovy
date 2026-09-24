/**
 *  Bathroom Fan NextGen  --  v2.0.0
 *
 *  Humidity fan control that cannot be fooled by a slow ambient ramp, cannot
 *  cycle, and LEARNS how long the room actually takes to dry in each season.
 *
 *  Successor to Bathroom Humidity Fan v1.1.47 (Craig Romei original, heavily
 *  fixed) and Bathroom Fan Guard v1.2.0 (the interim delayed-max detector).
 *  Design notes: C:\CLAUDE\Hubitat\NextGen\REQUIREMENTS.md
 *
 *  TRIGGER -- delayed-max baseline
 *    baseline = HIGHEST humidity in a window that ENDS `lag` min ago, `span` long
 *    ON when rh >= baseline + rise.  A slow attic/weather ramp drags the lagged
 *    baseline up with it and never opens the gap; a shower outruns it.
 *    Validated on real hub data: production app 25 ON commands -> this one 0.
 *
 *  TURN-OFF -- dew point back to pre-shower, then hold
 *    Dew point is ABSOLUTE moisture: it falls only while water is actually
 *    leaving the surfaces, and %RH is untrustworthy here because the fan moves
 *    air temperature. Dry when dew point returns to within `dryMarginF` of the
 *    PRE-shower dew point (taken from the same lagged window as the baseline),
 *    held for `stallConfirmMin`. Fallback for when ambient rose during the run:
 *    slope decayed to a fraction of this run's own peak AND most of the
 *    excursion given back. Bounded by dryMinMin / dryMaxMin / maxRunMin.
 *
 *  LEARNING
 *    Every completed automatic run records its length against a condition
 *    bucket keyed on the PRE-shower dew point (5F bins). EMA, alpha 0.3.
 *    The learned value is a PRIOR and a report -- deliberately NOT a floor,
 *    because a floor taken from the learned mean is a ratchet the EMA could
 *    never come back down from. Runs ended by a cap, by hand, or without a
 *    dew point are NOT observations of drying and never feed the table.
 *
 *  Harness: fanng_core.py + sim_ng.py. The CORE block below is mirrored
 *  line-for-line in fanng_core.py -- change one, change the other.
 *
 *  NO ATTIC DATA. Standing constraint (J.R. 2026-09-04): the attic sensor
 *  measures attic air and must not gate, trigger or time anything here.
 *
 *  Built with Claude for J.R. Farrar.
 */

definition(
    name: "Bathroom Fan",
    namespace: "jrfarrar",
    parent: "jrfarrar:Bathroom Fans",
    author: "J.R. Farrar",
    description: "One bathroom: excess-over-house trigger with local confirmation, bounded turn-off target, run log. Child of Bathroom Fans.",
    category: "Convenience",
    iconUrl: "", iconX2Url: "",
    importUrl: "https://raw.githubusercontent.com/jrfarrar/hubitat/master/Apps/BathroomFans/BathroomFanChild.groovy",
    singleThreaded: true
)

/*  v3.0.0  2026-09-24 - CHILD OF "Bathroom Fans"
 *
 *  This file is DERIVED from BathroomFanNG.groovy by make_child.py. Do not hand-edit the core here;
 *  edit the standalone, re-run make_child.py, and the script asserts the CORE DECISION LOGIC block
 *  is byte-identical. Three differences from the standalone, and no others:
 *    1. `parent: "jrfarrar:Bathroom Fans"` and the definition name "Bathroom Fan".
 *    2. The defaults ARE the production values measured into the master bathroom over 37 runs
 *       (riseThreshold 2.5 excess, fanOnDelay 0, dryMarginF 3.5, excessFloorF 3.5, dryMaxMin 90,
 *       maxRunMin 95, manual mode 2 / 20 min, sensorStaleMin 75, shadowMode off). A fresh child
 *       therefore needs only: its sensor, its fan, the house reference, the outdoor sensors, a name,
 *       and (for a migrated room) learnFile + seedFile.
 *    3. Version 3.0.0.
 *  The parent owns NOTHING (J.R., 2026-09-24): every sensor, reference and setting lives here.
 */

preferences {
    page(name: "mainPage")
    page(name: "learnPage")
    page(name: "advancedPage")
}

String APP_VERSION() { return "3.0.0" }

/*  v2.4.0  2026-09-06 — RUN LOG, so the learner's key can be chosen with evidence
 *
 *  The learned table keeps only an EMA. If the BUCKET KEY is the wrong variable, the underlying
 *  observations are already gone and a season is wasted.
 *
 *  That is a live risk, not a hypothetical. Over 10 days on this exact system, fan behaviour
 *  tracked outdoor TEMP at r=+0.90 and outdoor DEW at +0.77 — while HOUSE dew, which is the
 *  learner's PRIMARY key, came in at +0.06 with a standard deviation under 1 F, because the AC
 *  decouples the interior. A key that barely varies cannot discriminate anything.
 *
 *  So every completed run now writes one CSV row: duration, how it ended, whether it fed the
 *  learner, pre/peak dew, trigger baseline, resting excess, and the indoor/outdoor conditions
 *  captured AT TRIGGER. The learned table becomes a DERIVED summary that can be recomputed
 *  offline under any keying and re-imported through the seed file.
 *
 *  Runs the learner REJECTS are logged too — a run cut short by a cap or ended by hand is not an
 *  observation of drying time, but it is evidence about when this app struggles, which is the
 *  actual open question. `fed_learner` distinguishes them.
 *
 *  J.R.'s standing observation this is meant to serve: *"this has never been an issue [in mild
 *  weather], my old code ran just fine. It's only been on these really hot and humid days that
 *  I've seen the code break... there is absolutely something that is correlating with the outside
 *  weather and the fan running."* Those days may not recur until next summer. Collect properly now.
 */

/*  v2.3.0  2026-09-06 — THE LEARNED TABLE BECOMES PORTABLE
 *
 *  The learned drying times are the only thing this app accumulates that cannot be rebuilt
 *  quickly — a season of observations. Held only in app state, they die on any of:
 *    - a parent/child rewrite (a child app declares `parent:` and cannot be installed standalone,
 *      so graduating a standalone trial means a FRESH install)
 *    - moving between hubs, e.g. a dev-hub trial handing off to production
 *    - any reinstall
 *
 *  J.R. had accepted "it will have to relearn" as the cost of going parent/child. It does not.
 *  The table is now written to File Manager after every completed run — once per shower, so the
 *  I/O is nothing — and can be imported by a one-shot switch.
 *
 *  PROVENANCE TRAVELS WITH THE NUMBERS. A table is only meaningful for the room, sensor and
 *  placement it was learned in. The file records app, sensor, metric and a placement label, and
 *  a seed with a mismatched placement logs loudly rather than silently inheriting numbers that do
 *  not apply. J.R. moved the master Sonoff on 2026-09-05 and every threshold derived before that
 *  became void — that is exactly the failure this guard is for.
 *
 *  Seeding is deliberately a one-shot switch, never automatic: silently inheriting the OTHER
 *  bathroom's drying times would be worse than starting empty.
 */

/*  v2.2.0  2026-09-06 — EXCESS OVER THE HOUSE
 *
 *  J.R. reframed the problem, and it is the right frame: *"thinking about it as 'a shower' is
 *  probably not the right context. What we are really seeking to do is keep the humidity down in a
 *  room where it's higher than the rest of the house... 1 shower, 2 showers, 3 showers in a row
 *  doesn't matter."*
 *
 *  So there is now a third trigger metric, `excess` = dew(bathroom) − dew(house), used for BOTH
 *  the trigger and the turn-off. It never counts events; it runs while the room is wetter than the
 *  house and stops when it is not.
 *
 *  SELF-CALIBRATING. The app stores no offset. The resting excess is re-derived continuously from
 *  the same lagged window, so the constant part — sensor bias, ceiling-vs-waist height, one room
 *  simply being drier — cancels and may drift for months with nothing to reconfigure. It is not
 *  "bathroom should equal house"; it is "their difference should be whatever it has recently
 *  been." Slow change is followed, fast change is the signal.
 *
 *  WHY, measured. 156 quiet samples on 2026-09-05/06:
 *      dew excess (bath − house):  sd 1.77 F,  cool→warm drift +1.61,  r vs outdoor dew +0.12
 *      RH  excess (bath − house):  sd 3.47  ,  cool→warm drift +4.44,  r vs outdoor dew +0.46
 *  The RH relationship wanders ~4x more with outdoor conditions. That drift IS the high heat and
 *  humidity failure: same fixed offset, different day, different effective trigger point. Caveat —
 *  outdoor range in that sample was only 57–72 F, so +4.44 is a LOWER bound.
 *
 *  It also fixes a turn-off failure the dew-point target cannot: if the whole house got wetter,
 *  the room may never return to its own pre-shower dew point, and the app had to lean on a slope
 *  fallback. The excess comes back down regardless.
 *
 *  LEARNING now keys on indoor AND outdoor dew point — 5 F indoor bins, 10 F outdoor bins,
 *  deliberately coarser outside because a 5x5 table fragments into cells that never fill. Lookup
 *  falls back to the indoor-only bucket, then to the cold-start prior.
 *
 *  EARLY GUARD. J.R.: *"off too early, still not there; wait too long and it runs longer than
 *  needed."* If the dry condition appears at under 60% of the learned time, the confirmation hold
 *  doubles. This only ever LENGTHENS a hold — the learned value stays a prior and never becomes a
 *  floor, because a floor taken from the learned mean is a ratchet the EMA could never come back
 *  down from.
 *
 *  REBOUND NOW FEEDS THE LEARNER — and this fixes a real bug. J.R. explained why the drop timeout
 *  exists at all: these fans move enough air to crash the humidity AT THE SENSOR while the
 *  surfaces are still evaporating. The sensor reads dry, the fan stops early, the room rebounds,
 *  the fan kicks on again. The learner was recording that too-short run as a successful drying
 *  time — teaching itself an even shorter time next round, compounding every occurrence. A
 *  rebound is definitive evidence the run was too short, so it now re-records that bucket 30%
 *  longer, moving the EMA up. Still a nudge, never a floor.
 *
 *  Note what excess does NOT fix: it is measured at the same sensor, in the same moving air, so it
 *  is fooled by a fast-drying fan exactly as dew point is. The confirmation hold is the defence
 *  against that, and it is orthogonal to the choice of metric. Excess fixes the humid-day TARGET
 *  problem; the hold fixes the wet-surfaces problem. Both are needed.
 *
 *  FAILURE SURFACE, stated plainly: excess needs TWO healthy sensors where dew point needs one. A
 *  stale or dead reference is caught by refStaleMin and falls back to dew-point mode rather than
 *  acting on a corrupted difference.
 *
 *  ADOPTS A MANUAL RUN. J.R.: "someone walks in and turns on the fan BEFORE they get into the
 *  shower." That used to leave the fan on a blunt 30-minute manual cutoff with no pre-shower
 *  reference and no learning — and that cutoff could fire mid-shower. If a shower is now detected
 *  while a manual run is in progress, the app takes the run over: pre-shower reference from the
 *  lagged window (still valid, it predates the rise), manual cutoff cancelled, proper turn-off.
 *  Still gated on the trigger, so a fan run for a smell is left alone exactly as before.
 *
 *  MANUAL OVERRIDE AS TRAINING DATA, and it must be PHYSICAL. Device 833 is also switched by
 *  Google Home and observed by MODE-MORNING; a digital "on" from another app would be misread as
 *  a human correcting us. Only a hand on the wall switch counts.
 */

/*  v2.1.0  2026-09-05 — three changes, all from measurement on a real 3-shower morning.
 *
 *  1. TRIGGER METRIC IS NOW SELECTABLE (%RH or dew point). This app already turns OFF on dew
 *     point, arguing "%RH is untrustworthy here because the fan moves air temperature" — and then
 *     triggered on %RH. Measured 2026-09-05: a real shower raised RH 8.6 against a threshold of 9
 *     (missed by 0.4) while dew point rose 4.1 F. Added as an OPTION rather than a replacement so
 *     the two can be compared on real showers instead of argued about. Note the threshold changes
 *     units with the mode — 9 is meaningless in dew-point mode.
 *
 *  2. COOLDOWN DEFAULT 12 -> 0. J.R.: "that's bad and a no go." That morning had three showers,
 *     two of them 2.5 minutes apart. A 12-minute cooldown gives the second one no fan at all. His
 *     BHF has no cooldown and handles the case correctly by simply never switching off between
 *     them. Cycling is already prevented by the delayed-max trigger, the dew-point turn-off and
 *     the dry-hold.
 *
 *  3. DRY-HOLD DEFAULT 4 -> 10 min, matching BHF's `humidityDropTimeout`. A renewed rise resets
 *     the hold, and that reset is what carries a fan through back-to-back showers. At 4 minutes
 *     NG would stop six minutes earlier than the app that demonstrably works.
 *
 *  Standing instruction behind all three: do not regress from BHF's back-to-back behaviour.
 */

// ===========================================================================
// ===== PAGES -- RENDER ONLY.  No unschedule(), no subscribe(), no state
// ===== mutation, no device commands. v1.1.47's mainPage() called updated()
// ===== via setPauseButtonName() and silently killed the max-run watchdog.
// ===========================================================================

def mainPage() {
    dynamicPage(name: "mainPage", title: "", install: true, uninstall: true) {

        section {
            paragraph "<b>Bathroom Fan NextGen v${APP_VERSION()}</b>"
            // STANDING RULE (J.R.): every instance must be renameable.
            input "thisName", "text", title: "<b>Name for this instance</b>",
                  submitOnChange: true, required: true, defaultValue: "Bathroom Fan NextGen"
        }

        section("<b>Devices</b>") {
            input "humiditySensor", "capability.relativeHumidityMeasurement",
                  title: "Humidity sensor${sensorDecoration()}", required: true, submitOnChange: true
            input "tempSensor", "capability.temperatureMeasurement",
                  title: "Temperature sensor (for dew point -- normally the same device)${tempDecoration()}",
                  required: false, submitOnChange: true
            input "shadowMode", "bool",
                  title: "<b>SHADOW MODE</b> -- drive a virtual switch this app creates instead of " +
                         "the real fan, so this can run in parallel with your existing app",
                  defaultValue: false, submitOnChange: true
            if (!isShadow()) {
                input "fanSwitch", "capability.switch", title: "Fan switch (real)", required: true
            } else {
                input "shadowName", "text", title: "Name for the virtual test switch",
                      defaultValue: "_TESTFANSWITCH", submitOnChange: true
                paragraph "Shadow switch: <i>${shadowSwitch()?.displayName ?: 'created when you press Done'}</i>"
            }
        }

        section("<b>Trigger</b>") {
            // 2026-09-05: added so RH and dew point can be COMPARED on real showers rather than
            // argued about. This app already turns OFF on dew point, on the stated grounds that
            // "%RH is untrustworthy here because the fan moves air temperature" -- yet it triggers
            // on %RH. Measured that morning: a real shower raised RH by 8.6 against a threshold of
            // 9 (missed by 0.4) while dew point rose 4.1 F, which the SensorPair logger caught at
            // a 2.5 F threshold with zero false positives overnight.
            input "triggerMetric", "enum",
                  title: "What does the rise threshold measure?",
                  options: ["rh"    : "%RH  (original)",
                            "dp"    : "Dew point F  (matches the turn-off logic)",
                            "excess": "EXCESS over the house  (bathroom dew - house dew)"],
                  defaultValue: "excess", required: true, submitOnChange: true
            if (triggerMetric == "excess") {
                paragraph "<b>Excess mode.</b> The control variable is how much wetter this room " +
                          "is than the rest of the house, in absolute moisture. It is NOT " +
                          "'bathroom equals house' - the resting difference (height, sensor bias, " +
                          "different room) is measured continuously from the same lagged window " +
                          "and the threshold applies to departures from THAT. Nothing to " +
                          "calibrate and no stored baseline to go stale.<br><br>" +
                          "Measured 2026-09-05/06 across 156 quiet samples: the dew-point excess " +
                          "held sd <b>1.77 F</b> and barely tracked outdoor conditions " +
                          "(r=+0.12), while the same comparison in %RH had sd <b>3.47</b> and " +
                          "r=+0.46. That drift is the high-heat/humidity failure."
                input "refHumiditySensor", "capability.relativeHumidityMeasurement",
                      title: "House reference - humidity (a room the bathroom cannot affect)",
                      required: false, submitOnChange: true
                input "refTempSensor", "capability.temperatureMeasurement",
                      title: "House reference - temperature",
                      required: false, submitOnChange: true
                input "refStaleMin", "number",
                      title: "Treat the reference as unusable if it has not reported for (minutes)",
                      defaultValue: 60, required: true
                input "outdoorHumiditySensor", "capability.relativeHumidityMeasurement",
                      title: "Outdoor humidity (OPTIONAL - only sharpens the learned drying time)",
                      required: false
                input "outdoorTempSensor", "capability.temperatureMeasurement",
                      title: "Outdoor temperature (OPTIONAL)", required: false
                paragraph "Outdoor readings never gate or trigger anything. They only add a second " +
                          "key to the learned drying table, so 'how long does this room take to " +
                          "dry' can differ between a cool dry day and a hot humid one."
                paragraph "Excess needs BOTH sensors. The dew-point mode depends only on the " +
                          "bathroom sensor, so it has less to go wrong; if the reference goes " +
                          "stale this mode falls back to plain dew point rather than acting on a " +
                          "corrupted difference."
            }
            input "riseThreshold", "decimal",
                  title: "Rise above the lagged baseline that means 'shower' " +
                         "(${(triggerMetric == 'dp') ? 'F of dew point' : '%RH'})",
                  defaultValue: 2.5, required: true
            if (triggerMetric == "dp") {
                paragraph "<b>Dew-point mode.</b> The threshold now means degrees F, not %RH - " +
                          "9 would be unreachable. Real measured shower rises: <b>4.1 F</b> " +
                          "(2026-09-05, 6.7 min). The logger uses <b>2.5 F</b>, which produced no " +
                          "false positives across a quiet night. Set this accordingly."
            }
            input "fanOnDelay", "number",
                  title: "Wait this many seconds before acting (debounces a hand-wash blip)",
                  defaultValue: 0, required: false
        }

        section("<b>Drying</b>") {
            paragraph "The fan runs until the dew point returns to its pre-shower level. " +
                      "These are the safety bounds, not the normal control."
            input "dryMinMin", "number", title: "Never run less than (minutes)",
                  defaultValue: 8, required: true
            input "dryMaxMin", "number", title: "Give up waiting to dry after (minutes)",
                  defaultValue: 90, required: true
            input "maxRunMin", "number",
                  title: "<b>Backstop</b> — hard maximum run time (minutes). Applies to EVERY run " +
                         "this app can see, including one switched on at the wall, and regardless " +
                         "of the manual-control setting. This replaces an external " +
                         "'turn the fan off after N minutes' rule.",
                  defaultValue: 95, required: true
            // DEFAULT CHANGED 12 -> 0 on 2026-09-05. J.R.: "that's bad and a no go."
            // Three showers that morning, two of them 2.5 minutes apart. A 12-minute cooldown
            // means a second shower starting inside that window gets NO FAN AT ALL. His BHF has
            // no cooldown -- it simply never turns off between them -- and it handles this case
            // correctly, which is the behaviour not to regress from. Cycling is already prevented
            // by the delayed-max trigger, dew-point turn-off and the dry-hold; a cooldown is not
            // the tool for it.
            input "minOffMin", "number",
                  title: "Cooldown -- cannot re-trigger within (minutes). <b>0 = off, and 0 is " +
                         "the right answer here</b>: back-to-back showers are normal in this " +
                         "house and a cooldown suppresses the second one entirely.",
                  defaultValue: 0, required: true
        }

        section("<b>Manual control</b>") {
            input "manualControlMode", "enum", title: "When the fan is switched on by hand",
                  options: ["1": "Leave it alone entirely",
                            "2": "Turn it off after a fixed time",
                            "3": "Turn it off after a fixed time, unless humidity takes over"],
                  defaultValue: "2", required: true
            input "manualOffMinutes", "number", title: "Manual run length (minutes)",
                  defaultValue: 20, required: true
            input "deviceActivation", "capability.switch",
                  title: "Also start a run when any of these switch on (motion rules, voice, dashboards)",
                  multiple: true, required: false
        }

        section("<b>Restrictions</b>") {
            input "disabledSwitch", "capability.switch",
                  title: "Hardware disable -- app is inert while any of these is ON",
                  multiple: true, required: false
            input "modes", "mode", title: "Only run in these modes (blank = all)",
                  multiple: true, required: false
            input "days", "enum", title: "Only run on these days (blank = all)",
                  options: ["Monday","Tuesday","Wednesday","Thursday","Friday","Saturday","Sunday"],
                  multiple: true, required: false
            input "fromTime", "time", title: "Only between -- start", required: false
            input "toTime",   "time", title: "Only between -- end",   required: false
        }

        section("<b>Status</b>") {
            paragraph statusText()
            input "pauseButton", "button", title: state.paused ? "Resume" : "Pause"
        }

        section {
            href "learnPage", title: "Learned drying times", description: learnSummary()
            href "advancedPage", title: "Advanced tuning", description: "Baseline window, dew-point margins, logging"
        }
    }
}

def learnPage() {
    dynamicPage(name: "learnPage", title: "Learned drying times") {
        section {
            paragraph "The app records how long each completed automatic run took, bucketed by the " +
                      "dew point of the room BEFORE the shower -- which is what makes it seasonal. " +
                      "Runs cut short by a cap, ended by hand, or taken without a temperature " +
                      "reading are excluded: they are not observations of how long drying takes."
            paragraph learnTable()
        }
        section {
            input "resetLearnButton", "button", title: "Reset learned table"
            paragraph "<small>Cold start uses ${cfgDryFallback()} min as the prior until a bucket has data.</small>"
        }
    }
}

def advancedPage() {
    dynamicPage(name: "advancedPage", title: "Advanced tuning") {
        section("Baseline window") {
            paragraph "<small>Retention is always lag + span + 18 min. If retention did not exceed " +
                      "lag + span, pruning would eat the far edge of the window and the baseline " +
                      "would silently become unavailable forever.</small>"
            input "baselineLagMin", "number", title: "Baseline window ends this many minutes ago",
                  defaultValue: 12, required: true
            input "baselineSpanMin", "number", title: "Baseline window length (minutes)",
                  defaultValue: 10, required: true
        }
        section("<b>Learned table — portability</b>") {
            paragraph "The learned drying times live in app state, which does not survive being " +
                      "rebuilt as a parent/child app, moved between hubs, or reinstalled. " +
                      "Persisting them to File Manager makes all three survivable - and lets a " +
                      "trial on the dev hub hand its knowledge to production instead of starting " +
                      "over."
            input "learnPersist", "bool",
                  title: "Save the learned table to File Manager after every completed run",
                  defaultValue: true, submitOnChange: true
            input "learnFile", "text",
                  title: "File name (blank = fanng_learn_&lt;app id&gt;.json)",
                  required: false, submitOnChange: true
            input "placementLabel", "text",
                  title: "Placement label - what/where the sensor is",
                  required: false, submitOnChange: true
            paragraph "<b>Placement matters.</b> A table learned with the sensor in one position " +
                      "is not valid after it moves - J.R. moved the master Sonoff on 2026-09-05 " +
                      "and every threshold derived before that became void. The label is stamped " +
                      "into the file so a seed can refuse, or at least warn, on a mismatch."
            input "seedFile", "text",
                  title: "Seed FROM this file (leave blank unless importing)",
                  required: false, submitOnChange: true
            input "seedNow", "bool",
                  title: "Import it on the next Done  (one-shot, clears itself)",
                  defaultValue: false, submitOnChange: true
            paragraph learnFileStatus()
        }

        section("Dew-point drying") {
            input "dryMarginF", "decimal",
                  title: "Dry when dew point is within this many F of its pre-shower value",
                  defaultValue: 3.5, required: true
            input "excessFloorF", "decimal",
                  title: "<b>EXCESS mode floor</b> - also treat the room as dry once it is within " +
                         "this many F of the house, even if that is looser than the line above",
                  defaultValue: 3.5, required: true
            paragraph "<small><b>The fan cannot dry the room below its makeup air.</b> An exhaust " +
                      "fan replaces room air with air pulled from the rest of the house, so a " +
                      "target below \"level with the house\" is arithmetic it cannot satisfy. " +
                      "Measured 2026-09-12: the house sat at 65-66 F dew while this bathroom sat " +
                      "at 58.7, resting excess -7.2, target -3.7 - the app spent <b>80.9 min</b> " +
                      "asking the room to end up seven degrees drier than the house. Confirmed " +
                      "not a sensor fault; a second reference gave the same -7.2.<br><br>" +
                      "The turn-off target is <b>whichever of the two is easier to reach</b>, so " +
                      "on ordinary days the resting line still governs and this never binds. " +
                      "Across the 11 logged runs, floor 3.5 leaves five completely unchanged and " +
                      "takes runs finishing inside the cap from 8/11 to 10/11.</small>"
            // DEFAULT CHANGED 4 -> 10 on 2026-09-05, to match BHF's `humidityDropTimeout`.
            // Once the room reads dry, wait this long with it CONTINUOUSLY dry before switching
            // off; if it stops being dry the timer resets. That reset is what carries a fan
            // through back-to-back showers. At 4 minutes NG would cut off six minutes earlier
            // than the app J.R. says works well -- a regression against proven behaviour, and
            // combined with the old cooldown it could have stopped mid-event and then refused to
            // restart.
            input "stallConfirmMin", "number",
                  title: "Hold the dry condition this long before stopping (minutes). " +
                         "Equivalent to BHF's <code>humidityDropTimeout</code>; a renewed rise " +
                         "resets it, which is how back-to-back showers stay on one run.",
                  defaultValue: 10, required: true
            input "dropFrac", "decimal",
                  title: "Fallback: fraction of the dew-point excursion that must be given back",
                  defaultValue: 0.7, required: true
        }
        section("Logging") {
            input "logLevel", "enum", title: "Logging",
                  options: ["0": "Off", "1": "Info", "2": "Debug", "3": "Trace"],
                  defaultValue: "1", required: true
            paragraph "<small>Debug and Trace switch themselves off after 30 minutes.</small>"
        }
        section("Sensor health") {
            input "sensorStaleMin", "number",
                  title: "Warn when the sensor has sent <b>no events of any kind</b> for (minutes)",
                  defaultValue: 75, required: true
            paragraph "<small>This is a <b>device</b> liveness test, not a humidity test. Any " +
                      "attribute arriving - humidity, temperature, battery, lastCheckin - proves " +
                      "the radio was heard from. It used to watch humidity alone, which on a " +
                      "change-driven sensor cannot tell a stable room from a dead battery: " +
                      "measured over 7 days the median humidity gap was 3.3 min but the longest " +
                      "was <b>127.8</b>, and it cried wolf three times about a sensor that was " +
                      "still checking in the whole time.</small>"
            input "humidityStaleMin", "number",
                  title: "Also warn if the device is alive but has not reported <b>humidity</b> " +
                         "for (minutes)",
                  defaultValue: 240, required: true
            paragraph "<small>Catches the opposite failure: a driver that stops parsing humidity " +
                      "while the radio keeps checking in would look perfectly healthy above. Keep " +
                      "this well clear of 128 min or it becomes the old false alarm again.</small>"
            input "lowBatteryPct", "number", title: "Warn below this battery %",
                  defaultValue: 20, required: true
            input "notifyDevice", "capability.notification",
                  title: "Send sensor warnings to", multiple: true, required: false
        }
    }
}

// ===========================================================================
// ===== CONFIG ACCESSORS
// ===== `!= null` rather than elvis throughout: elvis treats a legitimate 0
// ===== as "unset", which silently discarded a 0-second fanOnDelay in v1.
// ===========================================================================

private long MINMS() { return 60L * 1000L }

private BigDecimal cfgRise()   { return ((riseThreshold  != null ? riseThreshold  : 2.5) as BigDecimal) }
private int cfgLag()           { return ((baselineLagMin != null ? baselineLagMin : 12) as int) }
private int cfgSpan()          { return ((baselineSpanMin!= null ? baselineSpanMin: 10) as int) }
private int cfgKeep()          { return cfgLag() + cfgSpan() + 18 }
private int cfgOnDelay()       { return ((fanOnDelay     != null ? fanOnDelay     : 0)  as int) }
private int cfgDryMin()        { return ((dryMinMin      != null ? dryMinMin      : 8)  as int) }
private int cfgDryMax()        { return ((dryMaxMin      != null ? dryMaxMin      : 90) as int) }
private int cfgMaxRun()        { return ((maxRunMin      != null ? maxRunMin      : 95) as int) }
// Fallback changed 12 -> 0 with the input default. Changing only the input's defaultValue would
// have been cosmetic: an instance that already saved 12 keeps 12, and a null still resolved to 12.
private int cfgMinOff()        { return ((minOffMin      != null ? minOffMin      : 0)  as int) }
private int cfgStallConfirm()  { return ((stallConfirmMin!= null ? stallConfirmMin: 10) as int) }
private int cfgManualMin()     { return ((manualOffMinutes != null ? manualOffMinutes : 20) as int) }
private int cfgStaleMin()      { return ((sensorStaleMin != null ? sensorStaleMin : 75) as int) }
// Humidity-attribute backstop. Longest legitimate humidity gap measured on the Sonoff over 7 days
// was 127.8 min (device alive throughout), so 240 leaves headroom and still catches a wedge.
private int cfgHumStaleMin()   { return ((humidityStaleMin != null ? humidityStaleMin : 240) as int) }
private int cfgLowBatt()       { return ((lowBatteryPct  != null ? lowBatteryPct  : 20) as int) }
private int cfgDryFallback()   { return 18 }
private int cfgDryMargin10()   { return (((dryMarginF != null ? dryMarginF : 3.5) as BigDecimal) * 10) as int }
// Excess-mode target floor, tenths F. See the FLOOR comment in the excess turn-off block.
private int cfgExcessFloor10() { return (((excessFloorF != null ? excessFloorF : 3.5) as BigDecimal) * 10) as int }
private BigDecimal cfgDropFrac(){ return ((dropFrac     != null ? dropFrac      : 0.7) as BigDecimal) }

private int MIN_BASELINE_SAMPLES() { return 2 }
private int SAT_CEILING10()   { return 950 }
private BigDecimal STALL_FRAC()  { return 0.12 as BigDecimal }
private BigDecimal STALL_NOISE10(){ return 1.0 as BigDecimal }
private BigDecimal LEARN_ALPHA() { return 0.3 as BigDecimal }
// Minimum dew-point excursion (tenths F) for a run to count as an observation of drying time.
// Measured separation across 21 runs: noise <= 3.1 F, real showers >= 6.9 F. See the EXCURSION
// GATE comment in applyBookkeeping() for the 2026-09-13 cycling incident that forced this.
private int MIN_LEARN_EXCURSION10() { return 50 }
// Early guard: "far sooner than learned" = under 60% of the learned time; hold 2x longer then.
private BigDecimal EARLY_FRAC()      { return 0.6 as BigDecimal }
private BigDecimal EARLY_HOLD_MULT() { return 2.0 as BigDecimal }
// A rebound proves the last run was too short: re-record it 30% longer so the EMA moves UP.
private BigDecimal REBOUND_STRETCH() { return 1.3 as BigDecimal }
private int BUCKET_BIN_F()    { return 5 }

private boolean isShadow()   { return (shadowMode == null || shadowMode) }
private int     logLvl()     { return ((logLevel != null ? logLevel : "1") as String) as Integer }
private String  manualMode() { return (manualControlMode != null ? manualControlMode : "2") as String }

// ===========================================================================
// ===== LIFECYCLE
// ===========================================================================

def installed() { initialize() }
def updated()   { unsubscribe(); unschedule(); initialize() }
def uninstalled() { getChildDevices()?.each { deleteChildDevice(it.deviceNetworkId) } }

def initialize() {
    // Back-fill idiom: only set what is missing, so upgrades are painless.
    if (state.samples == null)      state.samples      = []
    if (state.learn == null)        state.learn        = [:]
    if (state.log == null)          state.log          = []
    if (state.seq == null)          state.seq          = 0L
    if (state.paused == null)       state.paused       = false
    if (state.autoOn == null)       state.autoOn       = false
    if (state.onAtMs == null)       state.onAtMs       = 0L
    if (state.offAtMs == null)      state.offAtMs      = 0L
    if (state.stallSinceMs == null) state.stallSinceMs = 0L
    if (state.peakSlope10 == null)  state.peakSlope10  = 0.0

    // One-shot learned-table import, before anything else can write to the table.
    if (seedNow == true) learnSeed()

    if (isShadow()) ensureShadow()

    // --- reconcile from observed reality, not from stale state (REQ 6.3) ---
    def tgt = target()
    state.fanOn = (tgt?.currentValue("switch") == "on")
    if (!state.fanOn) {
        state.autoOn = false
        clearRunContext()
    }

    // Guard every subscribe: on a first install initialize() can run before the
    // required inputs are set, and subscribing to null throws.
    if (humiditySensor) subscribe(humiditySensor, "humidity", "humidityHandler")
    if (tempSensor)     subscribe(tempSensor, "temperature", "temperatureHandler")
    if (tgt) subscribe(tgt, "switch", "switchHandler")
    if (disabledSwitch)    subscribe(disabledSwitch, "switch", "disabledHandler")
    if (deviceActivation)  subscribe(deviceActivation, "switch.on", "activationHandler")
    // v1.1.47 had a complete modeChangeHandler and never subscribed to mode.
    subscribe(location, "mode", "modeChangeHandler")

    // Sampler + backstop. Load-bearing: fills the baseline window when a
    // selective-reporting sensor goes quiet, and drives the dry test when no
    // events arrive. Without it the baseline was null 71.7% of one night.
    runEvery5Minutes("periodicCheck")

    // Timers are rebuilt from absolute timestamps, so unschedule() above and
    // hub restarts are both non-events.
    // Re-arm the backstop for ANY run in progress, not just one we started.
    // Gating this on autoOn meant a hub restart during a manual run silently
    // dropped the only timer protecting it.
    if (state.fanOn) {
        long started = (state.onAtMs ?: 0L) as Long
        long left = started ? (cfgMaxRun() * MINMS()) - (nowMs() - started) : cfgMaxRun() * MINMS()
        runIn(Math.max(60L, (left / 1000L)) as Integer, "endMaxRun", [overwrite: true])
        // v2.8.2: a MANUAL run in progress also gets its cutoff re-armed. updated() unschedules
        // everything, and until now only the hard cap came back -- so a Done pressed during a
        // hand-started run silently replaced the 20-minute manual cutoff with the 85-minute cap.
        // Only possible since v2.8.1, which is when manual runs started stamping onAtMs.
        if (!(state.autoOn == true) && manualMode() != "1" && started) {
            long mLeft = (cfgManualMin() * MINMS()) - (nowMs() - started)
            runIn(Math.max(30L, (mLeft / 1000L)) as Integer, "endManualRun", [overwrite: true])
        }
    }
    if (logLvl() >= 2) runIn(1800, "logsOff")

    updateLabel()
    writeStatus(null, "initialized v${APP_VERSION()}", "init", null)
    logInfo "initialized v${APP_VERSION()} shadow=${isShadow()} fanOn=${state.fanOn}"
}

Long nowMs() { return now() }

private void clearRunContext() {
    state.triggerBaseline = null
    state.trigDp10   = null
    state.preDp10    = null
    state.peakDp10   = null
    state.peakSlope10 = 0.0
    state.stallSinceMs = 0L
    // NOTE: lastLearnBucket / lastLearnMin are deliberately NOT cleared here. They must outlive
    // the run so a rebound in the following minutes can still correct what that run taught.
    state.adoptedRun = false
    state.runBucket  = null
    state.runOutBucket = null
    state.restExcess10 = null
    state.runMetric  = null
    state.earlyGuard = null
    state.runDegraded = false
    state.pendingLearn = false
}

// ===========================================================================
// ===== TARGET DEVICE
// ===========================================================================

private String shadowDni() { return "FanNGShadow_${app.id}" }
private def shadowSwitch() { return getChildDevice(shadowDni()) }

private def ensureShadow() {
    def d = shadowSwitch()
    if (!d) {
        d = addChildDevice("hubitat", "Virtual Switch", shadowDni(),
                           [label: (shadowName ?: "_TESTFANSWITCH"),
                            name: "FanNG Test Switch", isComponent: false])
        logInfo "created shadow switch ${d?.displayName}"
    }
    return d
}

private def target() { return isShadow() ? shadowSwitch() : fanSwitch }

// ===========================================================================
// ===== HANDLERS -- one handler name per intent, so unschedule() can never
// ===== cancel the wrong timer (v1.1.47's manual and auto turn-off shared
// ===== `turnOffFan`, so every On branch killed the manual cutoff).
// ===========================================================================

def humidityHandler(evt) {
    Integer rh10 = parse10(evt.value)
    if (rh10 == null) { logWarn "unparseable humidity '${evt.value}'"; return }
    // Only the humidity handler may touch humidity-derived state, and the
    // timestamp comes from the EVENT, not from now(). v1.1.47's rate was
    // corrupted because a compare-sensor event reset the clock.
    state.lastRhEventMs = (evt.date ? evt.date.time : nowMs())
    if (cfgOnDelay() > 0 && !state.fanOn) {
        // AUDIT: must NOT be overwrite:true. A rising shower produces events
        // faster than the delay, so re-arming on every event would push the
        // timer out indefinitely and the app would never act.
        state.pendingRh10 = rh10
        if (!(state.onDelayArmed == true)) {
            state.onDelayArmed = true
            runIn(cfgOnDelay(), "onDelayElapsed", [overwrite: false])
        }
        return
    }
    step(rh10, "event")
}

def onDelayElapsed() {
    state.onDelayArmed = false
    // Re-read rather than trusting the value that armed the timer: if the blip
    // was a hand-wash, humidity has already fallen back and we must not act on
    // the stale peak. That is the entire point of the delay.
    Integer rh10 = currentRh10()
    if (rh10 == null && state.pendingRh10 != null) rh10 = state.pendingRh10 as Integer
    if (rh10 != null) step(rh10, "delay")
}

def temperatureHandler(evt) {
    // Temperature alone does not drive a decision; it is read at sample time.
    // Recording it here only keeps sensorAge honest for a split sensor.
    state.lastTempEventMs = (evt.date ? evt.date.time : nowMs())
}

def periodicCheck() {
    // SAMPLER. Feeds the last-known reading in with a fresh timestamp so the
    // baseline window stays populated when a selective-reporting sensor goes
    // quiet, and so the dry test still runs with no events arriving.
    Integer rh10 = currentRh10()
    if (rh10 == null) { writeStatus(null, "sampler: no humidity value", "sampler", null); return }
    checkSensorHealth()
    step(rh10, "sampler")
}

def switchHandler(evt) {
    // Suppress the echo of our own command; one that never echoes expires,
    // rather than latching the app out of sync forever.
    if (evt.value == state.suppressValue && nowMs() < ((state.suppressUntilMs ?: 0L) as Long)) {
        state.suppressUntilMs = 0L
        return
    }
    externalSwitch(evt.value, nowMs())

    // ---------------------------------------------------------------------------------------
    // MANUAL OVERRIDE = a labelled training example (v2.2.0, from a live failure 2026-09-06).
    //
    // J.R. found the room at 72% RH with the fan off, and switched it back on by hand. Nothing
    // in the app caught that, and the REBOUND watch never would have: the room was not getting
    // wetter, it simply was not dry yet. Rebound detects "stopped, then re-wetted". This is
    // "stopped while still wet" -- a different failure needing a different signal.
    //
    // A human reaching for the switch shortly after an automatic turn-off is the strongest
    // ground truth available: it means the run was too short. Treat it as such and correct the
    // learner upward, exactly as a rebound does.
    // ---------------------------------------------------------------------------------------
    //
    // PHYSICAL ONLY. Device 833 is also switched by MODE-MORNING-Wake Up Early for Shower and by
    // Google Home; a digital "on" from either would be misread as a human correcting us, and
    // would corrupt the learner in the opposite direction from the bug this fixes. Only a hand on
    // the wall switch counts. (PROJECT.md method lesson: check type=physical vs digital before
    // attributing a switch event to anyone.)
    if (evt.value == "on" && !(state.autoOn == true) && evt.type == "physical") {
        long since = nowMs() - ((state.offAtMs ?: 0L) as Long)
        if ((state.offAtMs ?: 0L) > 0 && since <= (MANUAL_OVERRIDE_WINDOW_MIN() * MINMS())) {
            state.note = "MANUAL override ${r1(since / (MINMS() as double))} min after auto-off - " +
                         "the run was too short"
            logWarn "${appName()}: ${state.note}"
            correctLearnerUpward("manual override")
        }
    }

    if (evt.value == "on") {
        // AUDIT: previously this armed ONLY the manual cutoff, which
        // manualControlMode "1" disables entirely -- so a fan switched on at
        // the wall could run forever. That is the exact v1.1.47 bug and the
        // reason an external 45-min rule was needed. Arm the hard backstop too.
        armManualCutoff()
        armBackstop()
    }
    logDebug "external switch=${evt.value} -> fanOn=${state.fanOn} autoOn=${state.autoOn}"
    writeStatus(null, "external switch=${evt.value}", "switch", null)
    updateLabel()
}

def activationHandler(evt) {
    if (blocked()) { logDebug "activation ignored: ${blockedReason()}"; return }
    if (state.fanOn) return
    logInfo "activation by ${evt.displayName}"
    // auto:false -- an activation switch is a manual start. Setting autoOn
    // after the fact (as the first draft did) left endMaxRun armed against a
    // flag it would then find false, and read as an auto run for one instant.
    command("ON", "activated by ${evt.displayName}", "activation", false)
}

def endMaxRunGuard() { }   // reserved; keeps handler names one-per-intent

def disabledHandler(evt) {
    boolean off = anyOn(disabledSwitch)
    writeStatus(null, "disable switch ${off ? 'ON - app inert' : 'cleared'}", "disable", null)
    if (off && state.fanOn && state.autoOn) command("OFF", "disable switch turned on", "disable")
    updateLabel()
}

def modeChangeHandler(evt) {
    if (state.fanOn && state.autoOn && !modeOk()) {
        command("OFF", "mode changed to ${evt.value} - outside allowed modes", "mode")
    }
    writeStatus(null, "mode=${evt.value} ok=${modeOk()}", "mode", null)
}

// Hard cap applies to ANY run we are aware of, auto or manual. Gating it on
// autoOn (as v1.1.47 did) is how a manual run could last forever.
def endMaxRun()      { if (state.fanOn) command("OFF", "hard max ${cfgMaxRun()} min reached", "timer") }
def endManualRun()   { if (state.fanOn && !state.autoOn) command("OFF", "manual run ${cfgManualMin()} min elapsed", "timer") }
def logsOff()        { app.updateSetting("logLevel", [value: "1", type: "enum"]); logInfo "verbose logging off" }

private void armManualCutoff() {
    if (manualMode() == "1") return
    runIn(cfgManualMin() * 60, "endManualRun", [overwrite: true])
}

// THE BACKSTOP. Armed for EVERY run this app can see -- ours, an activation
// switch, or a hand on the wall -- and independent of manualControlMode, so
// "leave manual runs alone" still cannot leave the fan running forever.
// This is what replaces an external "turn the fan off after N minutes" rule.
private void armBackstop() {
    runIn(cfgMaxRun() * 60, "endMaxRun", [overwrite: true])
}

def appButtonHandler(String btn) {
    if (btn == "pauseButton") {
        state.paused = !(state.paused as Boolean)
        if (state.paused && state.fanOn && state.autoOn) command("OFF", "paused", "button")
        logInfo "paused=${state.paused}"
    } else if (btn == "resetLearnButton") {
        state.learn = [:]
        logInfo "learned table reset"
    }
    runIn(2, "updateLabel")
}

private Integer currentRh10() { return parse10(humiditySensor?.currentValue("humidity")) }

private Integer currentTemp10() {
    def d = tempSensor ?: humiditySensor
    return parse10(d?.currentValue("temperature"))
}

private Integer parse10(v) {
    try {
        if (v == null) return null
        BigDecimal b = v.toString().replace("%", "").replace("°F", "").trim().toBigDecimal()
        return Math.round((b as double) * 10.0d) as Integer
    } catch (e) { return null }
}

// ---- the one place that runs a decision and acts on it --------------------
private void step(Integer rh10, String source) {
    if (blocked()) { writeStatus(rh10, blockedReason(), source, null); return }
    Integer t10 = currentTemp10()
    Map res = decide(nowMs(), rh10, t10, source)
    if (res.action == null) {
        writeStatus(rh10, res.reason, source, null)
        logDebug "${source}: ${res.reason}"
        return
    }
    command(res.action, res.reason, source)
}

// ===========================================================================
// ===== CORE DECISION LOGIC
// ===== Mirrored line-for-line in fanng_core.py. Pure apart from the `state`
// ===== reads/writes Hubitat forces on us: no device access, no scheduling,
// ===== no commands. Everything it needs arrives as an argument.
// ===========================================================================

private Integer dewPoint10(Integer temp10, Integer rh10) {
    if (temp10 == null || rh10 == null) return null
    double rh = rh10 / 10.0d
    if (rh <= 0) return null
    if (rh > 100) rh = 100.0d
    double tc = ((temp10 / 10.0d) - 32.0d) / 1.8d
    double a = 17.62d, b = 243.12d
    double gamma = Math.log(rh / 100.0d) + a * tc / (b + tc)
    if (a - gamma == 0) return null
    double dpc = b * gamma / (a - gamma)
    return Math.round((dpc * 1.8d + 32.0d) * 10.0d) as Integer
}

private void prune(long nowT) {
    long cutoff = nowT - cfgKeep() * MINMS()
    state.samples = state.samples.findAll { (it[0] as Long) >= cutoff }
}

private Integer baselineOf(long nowT) {
    if (!state.samples) return null
    long hi = nowT - cfgLag() * MINMS()
    long lo = hi - cfgSpan() * MINMS()
    def vals = state.samples.findAll { (it[0] as Long) >= lo && (it[0] as Long) <= hi }
                            .collect { it[1] as Integer }
    if (vals.size() < MIN_BASELINE_SAMPLES()) return null
    return vals.max()
}

// ---------------------------------------------------------------------------------------------
// EXCESS over the house  (v2.2.0)
//
// excess = dew(bathroom) - dew(house reference), in tenths F.
//
// SELF-CALIBRATING, and this is the point: the app never stores an offset. The resting excess is
// re-derived continuously from the same lagged window, so the constant part -- sensor bias,
// ceiling-vs-waist height, one room being drier than another -- cancels automatically and can
// drift over months without anything needing to be reconfigured. It is not "bathroom should equal
// house"; it is "their difference should be whatever it has recently been." Slow change is
// followed; fast change is the signal.
// ---------------------------------------------------------------------------------------------

/** Reference dew point in tenths, or null if the reference is missing, incomplete or STALE.
 *  Stale matters: excess needs two sensors, so a dead reference would silently corrupt the
 *  control variable rather than obviously break it. */
private Integer refDp10() {
    if (!refHumiditySensor || !refTempSensor) return null
    def h = refHumiditySensor.currentValue("humidity")
    def t = refTempSensor.currentValue("temperature")
    if (h == null || t == null) return null
    Long la = null
    try { la = refHumiditySensor.getLastActivity()?.getTime() } catch (ignored) { la = null }
    if (la != null) {
        int mins = ((refStaleMin != null ? refStaleMin : 60) as int)
        if ((now() - la) > (mins * MINMS())) return null
    }
    return dewPoint10((t as BigDecimal) * 10 as Integer, (h as BigDecimal) * 10 as Integer)
}

private Integer excessOf(Integer dp10) {
    if (dp10 == null) return null
    Integer r = refDp10()
    if (r == null) return null
    return dp10 - r
}

/** Delayed max of the EXCESS over the lagged window. Same machinery as the other baselines --
 *  only the column differs -- so the immunity to a slow ambient ramp is preserved exactly. */
private Integer excessBaselineOf(long nowT) {
    if (!state.samples) return null
    long hi = nowT - cfgLag() * MINMS()
    long lo = hi - cfgSpan() * MINMS()
    def vals = state.samples.findAll {
        (it[0] as Long) >= lo && (it[0] as Long) <= hi && it.size() > 3 && it[3] != null
    }.collect { it[3] as Integer }
    if (vals.size() < MIN_BASELINE_SAMPLES()) return null
    return vals.max()
}

/** Resting excess for the turn-off target: the LOW end of the same window, i.e. what the room
 *  looks like relative to the house when nothing is happening. */
private Integer restingExcessOf(long nowT) {
    if (!state.samples) return null
    long hi = nowT - cfgLag() * MINMS()
    long lo = hi - cfgSpan() * MINMS()
    def vals = state.samples.findAll {
        (it[0] as Long) >= lo && (it[0] as Long) <= hi && it.size() > 3 && it[3] != null
    }.collect { it[3] as Integer }
    if (vals.size() < MIN_BASELINE_SAMPLES()) return null
    return vals.min()
}

private Integer dpBaselineOf(long nowT) {
    // The delayed-max baseline computed on DEW POINT instead of %RH, for triggerMetric "dp".
    // Same window, same max-of-window logic -- only the column differs, so the slow-ambient-ramp
    // immunity that makes the delayed max work is preserved exactly.
    if (!state.samples) return null
    long hi = nowT - cfgLag() * MINMS()
    long lo = hi - cfgSpan() * MINMS()
    def vals = state.samples.findAll { (it[0] as Long) >= lo && (it[0] as Long) <= hi && it[2] != null }
                            .collect { it[2] as Integer }
    if (vals.size() < MIN_BASELINE_SAMPLES()) return null
    return vals.max()
}

private Integer preDpOf(long nowT) {
    // Lowest dew point in the SAME lagged window the baseline came from: what
    // the room was before this shower. This is the target the fan aims at.
    if (!state.samples) return null
    long hi = nowT - cfgLag() * MINMS()
    long lo = hi - cfgSpan() * MINMS()
    def vals = state.samples.findAll { (it[0] as Long) >= lo && (it[0] as Long) <= hi && it[2] != null }
                            .collect { it[2] as Integer }
    if (!vals) return null
    return vals.min()
}

private BigDecimal dpSlope10(long nowT) {
    // Least-squares slope, tenths F per minute, over the last stallConfirm
    // window x1.5. Falls back to RH when dew point is unavailable; the run is
    // marked degraded in that case so it cannot pollute the learned table.
    long lo = nowT - (cfgStallConfirm() * 3L / 2L) * MINMS()
    def pts = state.samples.findAll { (it[0] as Long) >= lo }
    if (pts.size() < 3) return null
    boolean useDp = pts.every { it[2] != null }
    List xs = [], ys = []
    pts.each {
        xs << (((it[0] as Long) - lo) / (MINMS() as double))
        ys << ((useDp ? it[2] : it[1]) as double)
    }
    int n = xs.size()
    double mx = (xs.sum() as double) / n
    double my = (ys.sum() as double) / n
    double den = 0.0d, num = 0.0d
    for (int i = 0; i < n; i++) {
        num += (xs[i] - mx) * (ys[i] - my)
        den += (xs[i] - mx) * (xs[i] - mx)
    }
    if (den == 0) return null
    return (num / den) as BigDecimal
}

// ---- learner --------------------------------------------------------------

private String bucketKey(Integer dp10) {
    if (dp10 == null) return "dpNA"
    int b = BUCKET_BIN_F()
    return "dp" + ((Math.floor((dp10 / 10.0d) / b) as int) * b)
}

/** OUTDOOR condition bucket (v2.2.0), 10 F bins -- deliberately coarser than the indoor 5 F bins.
 *  A 2-D table fragments fast: 5x5 would spread a season's runs across dozens of near-empty cells
 *  and learn nothing. Coarse outdoor bins keep the cell count low enough to actually fill. */
private Integer outdoorDp10() {
    if (!outdoorHumiditySensor || !outdoorTempSensor) return null
    def h = outdoorHumiditySensor.currentValue("humidity")
    def t = outdoorTempSensor.currentValue("temperature")
    if (h == null || t == null) return null
    return dewPoint10((t as BigDecimal) * 10 as Integer, (h as BigDecimal) * 10 as Integer)
}

private String outBucketKey() {
    Integer o = outdoorDp10()
    if (o == null) return null           // null, not "outNA": no outdoor data means fall back
    int b = 10                           // to the indoor-only bucket rather than invent a cell
    return "out" + ((Math.floor((o / 10.0d) / b) as int) * b)
}

/** Learned drying time with a HIERARCHICAL fallback: the specific indoor+outdoor cell if it has
 *  data, else the indoor-only bucket, else the cold-start prior. Without the fallback the 2-D
 *  table would be useless until every cell had been visited. */
private BigDecimal learnedMin(String bucket) {
    return learnedMin(bucket, state.runOutBucket as String)
}

private BigDecimal learnedMin(String bucket, String outBucket) {
    if (outBucket) {
        def rec2 = state.learn ? state.learn[bucket + "_" + outBucket] : null
        if (rec2 && ((rec2.n ?: 0) as Integer) >= 2) return ((rec2.ema10 as Integer) / 10.0) as BigDecimal
    }
    def rec = state.learn ? state.learn[bucket] : null
    if (!rec || ((rec.n ?: 0) as Integer) < 1) return (cfgDryFallback() as BigDecimal)
    return ((rec.ema10 as Integer) / 10.0) as BigDecimal
}

/**
 *  The last run was too short. Push the learned time for that bucket back UP.
 *
 *  Two signals reach here, and they catch DIFFERENT failures:
 *    - "rebound"          : the fan stopped and the room got wetter again (surfaces still
 *                           evaporating -- the reason J.R.'s drop timeout exists at all)
 *    - "manual override"  : a human switched the fan back on shortly after an automatic off,
 *                           which catches "stopped while still wet" -- a case the rebound test
 *                           cannot see, because the room never re-wets, it just never dried.
 *
 *  The correction is relative to what the table ALREADY believed, not to the too-short run.
 *  Stretching a 12-minute run by 30% gives 15.6, which is still far below a 24.6-minute EMA and
 *  would drag the average DOWN again -- the harness caught precisely that mistake. So solve for
 *  the observation that lands the EMA at prevEma * stretch:
 *        x = (target - emaNow * (1 - alpha)) / alpha
 *
 *  Still an EMA nudge, never a floor: later genuine runs pull it back down.
 */
private void correctLearnerUpward(String why) {
    // NO LONGER WRITES TO THE TABLE (v2.8.0). The signal is real -- the room re-wetted, or a human
    // switched the fan back on, after an automatic off -- and the right response is for the fan to
    // come back on, which the normal trigger path already does (minOffMin is 0). Routing it into
    // the learned table instead was the indirection that inflated the learner both times it
    // happened: 2026-09-13 (eight noise runs correcting each other upward) and 2026-09-15 (a real
    // 73.7-min run's rebound pushing dp55 from 35.5 to 63.2 in one step). Kept as an observation:
    // logged, counted, visible in state. The learner is instrumentation now, not a controller.
    if (!state.lastLearnBucket) return
    state.reboundCount = ((state.reboundCount ?: 0) as Integer) + 1
    state.lastRebound = "${why} after ${state.lastLearnBucket} run of ${r1(state.lastLearnMin as BigDecimal)} min"
    logInfo "${appName()}: ${why} - previous run ended with the room still wet " +
            "(${state.lastLearnBucket}, ${r1(state.lastLearnMin as BigDecimal)} min). Observed, not learned."
    state.lastLearnBucket = null
}

private int MANUAL_OVERRIDE_WINDOW_MIN() { return 20 }

// ===========================================================================
// ===== LEARNED-TABLE PERSISTENCE  (v2.3.0)
// =====
// ===== The table is a dozen small entries and it is the only thing this app
// ===== accumulates that cannot be rebuilt quickly -- a season of drying
// ===== observations. In app state it dies on: a parent/child rewrite (a
// ===== child app cannot be installed standalone, so graduating means a fresh
// ===== install), a move between hubs, or any reinstall.
// =====
// ===== Written once per COMPLETED run -- once per shower -- so the I/O is
// ===== negligible. Seeding is deliberately a one-shot switch rather than
// ===== automatic: silently inheriting another room's drying times would be
// ===== worse than starting empty.
// ===========================================================================

private String learnFileName() {
    String f = (learnFile ?: "").trim()
    if (!f) f = "fanng_learn_${app.id}.json"
    f = f.replaceAll(/[^A-Za-z0-9_\-.]/, "_")
    if (!f.toLowerCase().endsWith(".json")) f = f + ".json"
    return f
}

private String learnFileStatus() {
    if (learnPersist == false) return "<i>Persistence off - the table lives only in app state.</i>"
    Map lm = (state.learn ?: [:])
    def sb = new StringBuilder()
    sb << "Writing to <code>${learnFileName()}</code> &mdash; readable at "
    sb << "<code>http://&lt;hub&gt;/local/${learnFileName()}</code><br>"
    sb << "Buckets held: <b>${lm.size()}</b>"
    if (state.learnSeededFrom) sb << "<br>Seeded from <code>${state.learnSeededFrom}</code>"
    if (state.learnWriteError) sb << "<br><span style='color:red'>Last write failed: ${state.learnWriteError}</span>"
    return sb.toString()
}

/** Provenance travels with the numbers. A table is only meaningful for the room, sensor and
 *  placement it was learned in, so anything importing it can check rather than assume. */
private void learnSave() {
    if (learnPersist == false) return
    try {
        Map payload = [
            provenance: [
                app        : (app.label ?: appName()),
                appId      : app.id,
                metric     : (triggerMetric ?: "rh"),
                sensor     : (humiditySensor?.displayName ?: "unknown"),
                placement  : (placementLabel ?: "UNSET - placement not recorded"),
                writtenIso : new Date().format("yyyy-MM-dd'T'HH:mm:ss", location.timeZone),
                buckets    : (state.learn ?: [:]).size()
            ],
            learn: (state.learn ?: [:])
        ]
        uploadHubFile(learnFileName(), groovy.json.JsonOutput.toJson(payload).getBytes("UTF-8"))
        state.learnWriteError = null
    } catch (e) {
        state.learnWriteError = e.message
        logWarn "${appName()}: could not save the learned table: ${e.message}"
    }
}

/** One-shot import. Refuses nothing outright, but says loudly when the provenance does not match
 *  -- a table learned at a different sensor position is not valid here, and silence would hide it. */
private void learnSeed() {
    String f = (seedFile ?: "").trim()
    if (!f) return
    try {
        byte[] b = downloadHubFile(f)
        if (!b) { logWarn "${appName()}: seed file ${f} is empty or missing"; return }
        def parsed = parseJson(new String(b, "UTF-8"))
        def table = parsed?.learn
        if (!(table instanceof Map) || table.isEmpty()) {
            logWarn "${appName()}: seed file ${f} has no usable learn table"
            return
        }
        def p = parsed?.provenance ?: [:]
        String mine  = (placementLabel ?: "")
        String theirs = (p?.placement ?: "")
        if (mine && theirs && mine != theirs) {
            logWarn "${appName()}: SEED PLACEMENT MISMATCH - this instance is '${mine}' but the " +
                    "table was learned at '${theirs}'. Imported anyway; treat the numbers as a " +
                    "starting guess, not as measurements of THIS position."
        }
        state.learn = table
        state.learnSeededFrom = "${f} (from ${p?.app ?: 'unknown'}, placement '${theirs ?: 'unrecorded'}', " +
                               "written ${p?.writtenIso ?: 'unknown'})"
        log.info "${appName()}: seeded ${table.size()} learned buckets from ${f} - ${state.learnSeededFrom}"
        learnSave()      // re-stamp with THIS instance's provenance
    } catch (e) {
        logWarn "${appName()}: seed from ${f} failed: ${e.message}"
    } finally {
        app.updateSetting("seedNow", [type: "bool", value: false])
    }
}

// ===========================================================================
// ===== RUN LOG  (v2.4.0)
// =====
// ===== The learned table keeps only an EMA. If the BUCKET KEY turns out to be
// ===== the wrong variable, the underlying observations are already gone and
// ===== the season is wasted.
// =====
// ===== Prior evidence says that is a live risk: over 10 days, fan behaviour
// ===== tracked outdoor TEMP at r=+0.90 and outdoor DEW at +0.77, while HOUSE
// ===== dew -- which is the learner's primary key -- came in at +0.06, with a
// ===== standard deviation under 1 F because the AC decouples the interior.
// ===== A key that barely varies cannot discriminate.
// =====
// ===== So write the raw facts of every completed run. The learned table then
// ===== becomes a DERIVED summary that can be recomputed offline under any
// ===== keying and re-imported through the seed file. That converts "pick the
// ===== right key now" into "decide when the data says so."
// ===========================================================================

private String runLogName() {
    String f = (learnFile ?: "").trim()
    String base = f ? f.replaceAll(/(?i)\.json$/, "") : "fanng_learn_${app.id}"
    return (base + "_runs.csv").replaceAll(/[^A-Za-z0-9_\-.]/, "_")
}

private void runLogAppend(long endMs, BigDecimal driedMin, String endReason, boolean learned) {
    if (learnPersist == false) return
    String hdr = "end_iso,start_iso,duration_min,metric,end_reason,fed_learner,adopted," +
                 "pre_dp_f,peak_dp_f,trigger_baseline,rest_excess_f," +
                 "indoor_dew_f,outdoor_temp_f,outdoor_dew_f," +
                 "bucket_indoor,bucket_outdoor,early_guard,placement\n"
    try {
        def f2 = { Integer t -> t == null ? "" : fmt1(t) }
        String row = "${new Date(endMs).format("yyyy-MM-dd'T'HH:mm:ss", location.timeZone)}," +
            "${state.onAtMs ? new Date(state.onAtMs as Long).format("yyyy-MM-dd'T'HH:mm:ss", location.timeZone) : ''}," +
            "${r1(driedMin)},${state.runMetric ?: (triggerMetric ?: 'rh')},${endReason},${learned}," +
            "${state.adoptedRun == true}," +
            "${f2(state.preDp10 as Integer)},${f2(state.peakDp10 as Integer)}," +
            "${f2(state.triggerBaseline as Integer)},${f2(state.restExcess10 as Integer)}," +
            "${f2(state.runIndoorDp10 as Integer)},${f2(state.runOutdoorT10 as Integer)}," +
            "${f2(state.runOutdoorDp10 as Integer)}," +
            "${state.runBucket ?: ''},${state.runOutBucket ?: ''}," +
            "${state.earlyGuard ? 'yes' : 'no'}," +
            "${(placementLabel ?: '').replaceAll(/[^A-Za-z0-9 _.\-]/, ' ')}\n"

        String cur = null
        try { byte[] b = downloadHubFile(runLogName()); cur = b ? new String(b, "UTF-8") : null }
        catch (ignored) { cur = null }
        String out = (cur == null || cur.trim().isEmpty()) ? (hdr + row) : (cur + row)
        uploadHubFile(runLogName(), out.getBytes("UTF-8"))
    } catch (e) {
        logWarn "${appName()}: run log write failed: ${e.message}"
    }
}

private void learnRecord(String bucket, BigDecimal driedMin) {
    // Never below dryMinMin -- a bucket must not learn a dangerous value.
    BigDecimal d = driedMin.max(cfgDryMin() as BigDecimal)
    Map lm = (state.learn ?: [:])
    def rec = lm[bucket]
    if (!rec) {
        lm[bucket] = [n: 1, ema10: Math.round((d as double) * 10.0d) as Integer]
    } else {
        BigDecimal a = LEARN_ALPHA()
        BigDecimal ema = (((rec.ema10 as Integer) / 10.0) as BigDecimal) * (1.0 - a) + d * a
        ema = ema.max(cfgDryMin() as BigDecimal)
        rec.ema10 = Math.round((ema as double) * 10.0d) as Integer
        rec.n = ((rec.n ?: 0) as Integer) + 1
        lm[bucket] = rec
    }
    state.learn = lm
    logInfo "learned: ${bucket} now ${learnedMin(bucket)} min over ${lm[bucket].n} runs"
    learnSave()   // persist after every change, so nothing is lost to a rebuild or a hub move
}

// ---- off tests ------------------------------------------------------------

private Map offTests(long nowT, Integer rh10, Integer dp10) {
    long onFor = nowT - ((state.onAtMs ?: 0L) as Long)

    if (onFor >= cfgMaxRun() * MINMS())
        return [action: "OFF", reason: "hard max ${cfgMaxRun()} min reached", learn: false]

    BigDecimal dried = (onFor / (MINMS() as double)) as BigDecimal

    if (dried >= cfgDryMax())
        return [action: "OFF", reason: "gave up waiting to dry after ${cfgDryMax()} min", learn: false]

    if (dried < cfgDryMin()) {
        state.stallSinceMs = 0L
        return [action: null, reason: "running ${r1(dried)} of >=${cfgDryMin()} min floor", learn: false]
    }

    BigDecimal slope = dpSlope10(nowT)
    if (slope == null)
        return [action: null, reason: "drying ${r1(dried)} min - not enough data for slope", learn: false]

    if (slope < 0 && slope.abs() > (state.peakSlope10 as BigDecimal))
        state.peakSlope10 = slope.abs()

    Integer pre = state.preDp10 != null ? (state.preDp10 as Integer) : null
    boolean dry = false
    String why = null

    // EXCESS turn-off (v2.2.0). "Run until the room is back near the house."
    //
    // This is the whole reason excess mode exists for the OFF decision too, not just the trigger.
    // The dew-point target below aims at the room's own pre-shower value, which on a humid day the
    // room may never reach because the entire house got wetter -- the app then leans on a slope
    // fallback. Excess sidesteps that: if the house rose too, the excess comes back down even
    // though absolute dew point does not.
    if (state.runMetric == "excess") {
        Integer ex = excessOf(dp10)
        Integer rest = state.restExcess10 != null ? (state.restExcess10 as Integer) : null
        if (ex != null && rest != null) {
            // FLOOR (v2.6.0). A fan cannot dry a room below its MAKEUP AIR -- it replaces room air
            // with air drawn from the rest of the house. So a target below "level with the house"
            // is not a stretch goal, it is arithmetic the fan cannot satisfy.
            //
            // Measured 2026-09-12: the house sat at 65-66 F dew (outdoor 68.1) while the bathroom
            // sat at 58.7, so resting excess was -7.2 and the target -3.7. The app spent 80.9 min
            // asking the room to end up SEVEN degrees drier than the house, pulling 65 F dew air
            // into it the whole time. Room dew went 58.8 -> 71.4 -> 63.9 and never came back.
            // Confirmed not a sensor fault: House Thermostat gave the identical -7.2.
            //
            // Floor swept over all 11 logged runs: none 8/11 finish, 2.5 -> 8, 3.0 -> 9,
            // 3.5 -> 10, 4.0 -> 10. Five runs are untouched at any of these because their resting
            // excess already clears the floor. Default 3.5.
            Integer restTgt = rest + cfgDryMargin10()
            Integer floorT  = cfgExcessFloor10()
            Integer tgt     = Math.max(restTgt, floorT)
            if (ex <= tgt) {
                dry = true
                why = (tgt == floorT && floorT > restTgt)
                    ? "excess ${fmt1(ex)}F within ${fmt1(floorT)}F of the house " +
                      "(floor; resting ${fmt1(rest)} would have wanted ${fmt1(restTgt)})"
                    : "excess ${fmt1(ex)}F back to resting ${fmt1(rest)} +${fmt1(cfgDryMargin10())}"
            }
        } else if (ex == null) {
            // Reference died mid-run. Fall through to the dew-point test rather than hang.
            state.note = "house reference lost mid-run - using dew-point target for turn-off"
        }
    }

    if (!dry && dp10 != null && pre != null) {
        Integer tgt = pre + cfgDryMargin10()
        if (dp10 <= tgt) {
            dry = true
            why = "dew point ${fmt1(dp10)}F back to pre-shower ${fmt1(pre)} +${fmt1(cfgDryMargin10())}"
        } else {
            // Ambient rose during the run, so the room can never get back to
            // `pre`. Accept a decayed slope, but only once most of the
            // excursion has actually been given back.
            Integer peak = state.peakDp10 != null ? (state.peakDp10 as Integer) : dp10
            int excursion = peak - pre
            int givenBack = peak - dp10
            BigDecimal thresh = STALL_NOISE10().max(STALL_FRAC() * (state.peakSlope10 as BigDecimal))
            if (excursion > 0 && givenBack >= (cfgDropFrac() * excursion) && slope.abs() <= thresh) {
                dry = true
                why = "stalled: slope ${r2(slope)} <= ${r2(thresh)}, gave back ${fmt1(givenBack)} of ${fmt1(excursion)}F"
            }
        }
    }

    if (!dry) {
        state.stallSinceMs = 0L
        if (dp10 == null || pre == null)
            return [action: null, reason: "drying ${r1(dried)} min - no dew point reference", learn: false]
        return [action: null, reason: "drying ${r1(dried)} min - dp ${fmt1(dp10)}, need ${fmt1(pre + cfgDryMargin10())}", learn: false]
    }

    if (!((state.stallSinceMs ?: 0L) as Long)) state.stallSinceMs = nowT
    BigDecimal held = ((nowT - ((state.stallSinceMs) as Long)) / (MINMS() as double)) as BigDecimal

    // EARLY GUARD REMOVED (v2.8.0). v2.2.0 doubled the confirmation hold when the dry condition
    // appeared "far sooner than learned". Measured over 27 runs on 2026-09-16: it fired FIVE times,
    // all five on the 09-13 reference-noise day, and never once on a real shower. It was
    // second-guessing a direct measurement of the room with a prediction from history -- and that
    // prediction had been corrupted twice in ten days. The confirmation hold below already covers
    // "stopped too early", and it measures the room. `state.earlyGuard` is kept null so the run-log
    // column stays valid.
    BigDecimal needHold = cfgStallConfirm() as BigDecimal
    state.earlyGuard = null

    if (held >= needHold)
        return [action: "OFF", reason: "dried after ${r1(dried)} min - ${why}", learn: true]
    return [action: null,
            reason: "drying ${r1(dried)} min - ${why}, holding ${r1(held)} of ${r1(needHold)}",
            learn: false]
}

// ---- decide ---------------------------------------------------------------

private Map decide(long nowT, Integer rh10, Integer temp10, String source) {
    Integer dp10 = dewPoint10(temp10, rh10)

    // 4th column added v2.2.0: excess over the house. Older 3-element samples are tolerated
    // everywhere by an it.size() > 3 guard, so an upgrade does not need the buffer cleared.
    Integer exc10 = excessOf(dp10)
    List s = new ArrayList((state.samples ?: []))
    s << [nowT, rh10, dp10, exc10]
    s.sort { it[0] as Long }
    state.samples = s
    prune(nowT)

    Integer base = baselineOf(nowT)
    state.lastBaseline = base

    // Trigger metric resolved HERE, before any of the run-state branches, because the manual-run
    // adoption path below needs it too. Excess falls back to dew point if the reference is
    // missing or stale rather than acting on a corrupted difference.
    String metric = (triggerMetric ?: "rh")
    if (metric == "excess" && excessOf(dp10) == null) {
        metric = "dp"
        state.note = "house reference missing or stale - excess mode fell back to dew point"
    }
    boolean useDp = (metric == "dp")
    boolean useEx = (metric == "excess")

    // rebound watch: OBSERVATION ONLY, never controls (REQUIREMENTS 10.4)
    long rUntil = (state.reboundUntilMs ?: 0L) as Long
    if (rUntil && nowT <= rUntil) {
        if (dp10 != null && state.offDp10 != null && (dp10 - (state.offDp10 as Integer)) >= 15) {
            state.note = "REBOUND +${fmt1(dp10 - (state.offDp10 as Integer))}F after turn-off - room was still wet"
            state.reboundUntilMs = 0L
            logWarn state.note
            pushLog(nowT, "REBOUND", rh10, state.note)

            // v2.2.0 -- FEED THE LEARNER. J.R. explained why the drop timeout exists: these fans
            // move enough air to crash the humidity AT THE SENSOR while the surfaces are still
            // evaporating, so the sensor reads "dry", the fan stops early, and the room rebounds.
            // The learner was recording that short run as a successful drying time and averaging
            // it in -- teaching itself an even shorter time next round. A ratchet in the WRONG
            // direction, and it compounds every time it happens.
            //
            // A rebound is the definitive evidence the run was too short, so correct the
            // observation upward instead of leaving the bad one standing. Still an EMA nudge,
            // never a floor.
            correctLearnerUpward("rebound")
        }
    } else if (rUntil) {
        state.reboundUntilMs = 0L
    }

    if (state.fanOn && state.autoOn) {
        if (dp10 != null && (state.peakDp10 == null || dp10 > (state.peakDp10 as Integer)))
            state.peakDp10 = dp10
        Map r = offTests(nowT, rh10, dp10)
        state.pendingLearn = (r.learn == true)
        if (r.action == "OFF") state.lastOffReason = (r.reason ?: "").take(80)
        return r
    }

    // ------------------------------------------------------------------------------------------
    // ADOPT A MANUAL RUN (v2.2.0). J.R.: "it's possible someone walks in and turns on the fan
    // BEFORE they get into the shower."
    //
    // Previously this returned "hands off", so the fan sat on a fixed manual cutoff (30 min) with
    // no pre-shower reference and no learning -- and that cutoff could fire mid-shower. If a
    // shower is detected while a manual run is in progress, take the run over: capture the
    // pre-shower reference from the LAGGED window (still valid, it predates the rise), cancel the
    // blunt manual cutoff, and manage the turn-off properly.
    //
    // Deliberately still gated on the trigger. Someone running the fan for a smell gets left
    // alone, exactly as before.
    // ------------------------------------------------------------------------------------------
    if (state.fanOn && !(state.autoOn == true)) {
        Integer aVal  = useEx ? excessOf(dp10) : (useDp ? dp10 : rh10)
        Integer aBase = useEx ? excessBaselineOf(nowT) : (useDp ? dpBaselineOf(nowT) : base)
        // Same local confirmation as the main trigger (v2.8.0) -- a manual run must not be
        // "adopted" as a shower on reference noise either.
        boolean aLocalOk = true
        if (useEx) {
            Integer lb = dpBaselineOf(nowT)
            aLocalOk = (dp10 != null && lb != null && dp10 >= lb + (cfgRise() * 10))
        }
        if (aVal != null && aBase != null && aVal >= aBase + (cfgRise() * 10) && aLocalOk) {
            state.triggerBaseline = aBase
            state.trigDp10  = dp10
            state.preDp10   = preDpOf(nowT)
            state.peakDp10  = dp10
            state.peakSlope10 = 0.0
            state.restExcess10 = restingExcessOf(nowT)
            state.runMetric = metric
            state.runBucket = bucketKey(state.preDp10 as Integer)
            state.runOutBucket = outBucketKey()
            state.runDegraded = (dp10 == null || state.preDp10 == null)
            state.autoOn = true
            state.adoptedRun = true
            unschedule("endManualRun")          // the blunt cutoff must not fire mid-shower
            armBackstop()
            logInfo "${appName()}: ADOPTED a manual run - shower detected while the fan was " +
                    "already on (${metric} ${fmt1(aVal)} >= ${fmt1(aBase)} + ${cfgRise()})"
            return [action: null, reason: "adopted manual run - now managing the turn-off"]
        }
        return [action: null, reason: "on, but not by us - hands off"]
    }
    if (state.fanOn) return [action: null, reason: "on, but not by us - hands off"]

    long offAt = (state.offAtMs ?: 0L) as Long
    if (offAt > 0) {
        long sinceOff = nowT - offAt
        if (sinceOff < cfgMinOff() * MINMS())
            return [action: null, reason: "cooldown: ${(sinceOff / MINMS()) as int} of ${cfgMinOff()} min"]
    }

    if (base == null)
        return [action: null, reason: "no baseline yet (need ${MIN_BASELINE_SAMPLES()} samples in window)"]

    // TRIGGER METRIC (2026-09-05). In "dp" mode the same delayed-max machinery runs on dew point
    // instead of %RH -- consistent with this app's own turn-off logic and with the SensorPair
    // logger. baselineOf()/dpBaselineOf() both read the same sample buffer, so nothing else
    // changes; only which column is compared.
    Integer trigVal  = useEx ? excessOf(dp10) : (useDp ? dp10 : rh10)
    Integer trigBase = useEx ? excessBaselineOf(nowT) : (useDp ? dpBaselineOf(nowT) : base)
    if ((useDp || useEx) && (trigVal == null || trigBase == null))
        return [action: null, reason: "${metric} trigger selected but no baseline yet"]

    // LOCAL CONFIRMATION (v2.8.0) -- in excess mode the trigger must ALSO see the bathroom's OWN
    // dew point rise above its own delayed-max baseline. Two failure modes point opposite ways:
    //
    //   * REFERENCE NOISE: the bathroom sits still and the house reference wanders, so `excess`
    //     rises with nothing happening in the room. 2026-09-13: eight fan runs in six hours.
    //   * HOUSE-WIDE EVENT: cooking, laundry, a door open on a humid day -- the bathroom rises
    //     WITH the house. A purely local rule fires; excess correctly does not.
    //
    // Requiring both rejects both. A shower raises the room AND the room-vs-house difference; the
    // two false cases each raise only one. Replayed over the whole record (11 days, 17 events,
    // trigger_replay.py): excess alone 8 false fires, local alone 2, BOTH 0 -- and 0 missed.
    // The rate-of-change idea was tested in the same replay and rejected: real showers peak
    // anywhere from 0.04 to 1.15 F/min while quiet noise reaches 2.5, so no rate threshold
    // separates them (the change-driven sensor makes dew step, and a short-window rate on steps
    // is spiky). Same threshold (cfgRise) for both, because that is what was tested.
    boolean localOk = true
    Integer localBase = null
    if (useEx) {
        localBase = dpBaselineOf(nowT)
        localOk = (dp10 != null && localBase != null && dp10 >= localBase + (cfgRise() * 10))
    }

    if (trigVal >= trigBase + (cfgRise() * 10) && !localOk) {
        state.trigVeto = "excess ${fmt1(trigVal)} >= ${fmt1(trigBase)}+${cfgRise()} but room dew " +
                         "${fmt1(dp10)} < own baseline ${fmt1(localBase)}+${cfgRise()} - reference moved, room did not"
        return [action: null, reason: "idle: ${state.trigVeto}"]
    }

    if (trigVal >= trigBase + (cfgRise() * 10)) {
        state.trigVeto = null
        state.triggerBaseline = trigBase
        state.trigDp10 = dp10
        state.preDp10  = preDpOf(nowT)
        state.peakDp10 = dp10
        state.peakSlope10 = 0.0
        // Bucket on the PRE-shower dew point -- the seasonal condition -- not
        // on the trigger reading, which the shower has already inflated.
        state.runBucket = bucketKey(state.preDp10 as Integer)
        state.runOutBucket = outBucketKey()
        state.restExcess10 = restingExcessOf(nowT)
        state.runMetric = metric
        // Conditions AT TRIGGER, captured for the run log. The whole point is to be able to
        // re-key the learner later against whichever of these actually predicts drying time.
        state.runIndoorDp10  = refDp10()
        state.runOutdoorDp10 = outdoorDp10()
        state.runOutdoorT10  = (outdoorTempSensor?.currentValue("temperature") != null)
                               ? (((outdoorTempSensor.currentValue("temperature")) as BigDecimal) * 10) as Integer
                               : null
        state.runDegraded = (dp10 == null || state.preDp10 == null)
        return [action: "ON",
                reason: "rise: ${metric} ${fmt1(trigVal)} >= baseline " +
                        "${fmt1(trigBase)} + ${cfgRise()}"]
    }

    if (rh10 >= SAT_CEILING10())
        return [action: null, reason: "SATURATED at ${fmt1(rh10)} - shower detection is blind"]

    return [action: null, reason: "idle: rh ${fmt1(rh10)} vs baseline ${fmt1(base)}"]
}

private void externalSwitch(String value, long nowT) {
    if (value == "on") {
        if (!state.fanOn) {
            state.fanOn = true; state.autoOn = false
            // v2.8.1. A hand-started run must stamp its OWN start. Before this, `onAtMs` kept the
            // previous automatic run's value, so when the manual cutoff fired 20 min later the
            // run log computed the duration from that morning's shower -- row 31, 2026-09-18:
            // "877.4 min", blank excursion, and the previous run's end reason copied verbatim.
            // First manual run in 32; the path had simply never executed. Learner untouched
            // (fed_learner=false), but the run log is the project's primary artefact.
            state.onAtMs = nowT
            state.lastOffReason = null
            clearRunContext()
        }
    } else {
        state.fanOn = false
        if (state.autoOn) { state.autoOn = false; state.offAtMs = nowT }
        clearRunContext()
        unschedule("endMaxRun")
    }
}

// ===========================================================================
// ===== END CORE DECISION LOGIC
// ===========================================================================

// ===========================================================================
// ===== COMMAND -> VERIFY -> ESCALATE
// ===== Hubitat device commands are async and essentially never throw, so a
// ===== try/catch guards a failure that cannot happen. The real failure is a
// ===== command that produces no switch event. Verify catches that.
// ===========================================================================

private void command(String action, String reason, String source, boolean auto = true) {
    long t = nowMs()
    def tgt = target()
    if (!tgt) {
        logError "no target switch configured"
        writeStatus(null, "ERROR: no target switch", source, null)
        return
    }
    logInfo "turning ${action} ${tgt.displayName} - ${reason}"

    state.suppressValue   = (action == "ON") ? "on" : "off"
    state.suppressUntilMs = t + 10000L
    state.intent          = (action == "ON") ? "on" : "off"
    state.intentAtMs      = t
    if (action == "ON") { tgt.on() } else { tgt.off() }

    // v2.8.1: every OFF records its reason here BEFORE bookkeeping writes the run log. The dry-test
    // path already did this; the manual cutoff, the hard cap, pause and disable did not, so their
    // rows inherited whatever the previous run left behind.
    if (action == "OFF") state.lastOffReason = (reason ?: "").take(80)

    applyBookkeeping(action, t, auto)
    writeStatus(null, "${action}: ${reason}", source, action)
    runIn(15, "verifyFan", [overwrite: true])
    updateLabel()
}

def verifyFan() {
    def tgt = target()
    if (!tgt || !state.intent) return
    String want = state.intent as String
    String got  = tgt.currentValue("switch") as String
    if (got == want) {
        state.cmdRetries = 0
        state.intent = null
        // AUDIT: clear the error on success. v1.1.47 never reconciled
        // state.disabled with reality, so a stale flag persisted forever.
        if (state.err) { logInfo "fan responded - clearing error"; state.err = null }
        return
    }
    int tries = ((state.cmdRetries ?: 0) as Integer) + 1
    state.cmdRetries = tries
    if (tries <= 2) {
        logWarn "fan did not respond (wanted ${want}, is ${got}) - retry ${tries}"
        if (want == "on") { tgt.on() } else { tgt.off() }
        runIn(15, "verifyFan", [overwrite: true])
    } else {
        state.err = "fan did not respond to ${want} after ${tries} attempts"
        logError state.err
        pushLog(nowMs(), "ERROR", null, state.err)
        notify("${appName()}: ${state.err}")
        state.intent = null
        state.cmdRetries = 0
    }
}

private void applyBookkeeping(String action, long t, boolean auto = true) {
    if (action == "ON") {
        state.fanOn  = true
        state.autoOn = auto
        state.onAtMs = t
        state.stallSinceMs = 0L
        state.reboundUntilMs = 0L
        state.note = null
        state.pendingLearn = false
        if (auto) {
            unschedule("endManualRun")
        } else {
            // A run we did not start on humidity is a manual run: it gets the
            // manual cutoff, never the humidity turn-off, and never feeds the
            // learner.
            armManualCutoff()
        }
        armBackstop()
    } else if (action == "OFF") {
        // RUN LOG FIRST, and log EVERY run -- including ones the learner rejects. A run cut
        // short by a cap, ended by hand, or taken without a dew point is not an observation of
        // drying time, but it IS evidence about when this app struggles, which is the open
        // question. `fed_learner` records which kind it was.
        // EXCURSION GATE (v2.7.0). A run that never saw a real moisture excursion is not an
        // observation of DRYING TIME, whatever it did with the fan.
        //
        // 2026-09-13, measured: the fan fired EIGHT times between 11:41 and 17:46 on sensor noise
        // -- bathroom dew held a 57.1-60.8 band while the house reference wandered 56.1-60.0
        // independently, and `excess` bounced -1.0..+3.3 with nobody in the room. All eight logged
        // fed_learner=true, and because each was followed by another trigger 13-40 min later the
        // rebound correction read every one as "stopped too early" and pushed the table UP:
        // dp55 36.9 -> 58.4 min, and a new dp55_out70 bucket at 65.9 min built from pure noise.
        // That then changed real behaviour -- the early guard doubles the hold below 60% of
        // learned, so at 58.4 nearly every genuine run started tripping it.
        //
        // The discriminator is clean and, unlike the trigger-side numbers, is measured from values
        // THIS APP recorded rather than reconstructed: across 21 runs, non-shower excursions top
        // out at 3.1 F and real showers bottom out at 6.9 F. Nothing lands between. 5.0 F.
        //
        // Deliberately NOT a setting: it is a correctness guard on what counts as an observation,
        // not a tuning knob. Change it with a push and say why.
        Integer pk10 = (state.peakDp10 != null) ? (state.peakDp10 as Integer) : null
        Integer pr10 = (state.preDp10  != null) ? (state.preDp10  as Integer) : null
        Integer excur10 = (pk10 != null && pr10 != null) ? (pk10 - pr10) : null
        boolean bigEnough = (excur10 != null && excur10 >= MIN_LEARN_EXCURSION10())
        if ((state.pendingLearn == true) && !(state.runDegraded == true) && !bigEnough) {
            state.learnSkip = "excursion ${excur10 == null ? 'unknown' : fmt1(excur10)}F < " +
                              "${fmt1(MIN_LEARN_EXCURSION10())}F - not a shower, not an observation"
            logWarn "${appName()}: learner SKIPPED - ${state.learnSkip}"
        } else if (bigEnough) {
            state.learnSkip = null
        }

        if (state.onAtMs) {
            BigDecimal ranMin = ((t - (state.onAtMs as Long)) / (MINMS() as double)) as BigDecimal
            boolean fed = ((state.pendingLearn == true) && !(state.runDegraded == true) && bigEnough)
            runLogAppend(t, ranMin, (state.lastOffReason ?: "unknown") as String, fed)
        }
        // Fold the run into the learner BEFORE the context is cleared.
        // NOTE the `bigEnough` term also keeps `lastLearnBucket` unset for a rejected run, so
        // `correctLearnerUpward()` cannot fire against it either -- which is what broke the
        // 09-13 feedback loop, where eight noise runs corrected each other upward in sequence.
        if ((state.pendingLearn == true) && state.onAtMs && !(state.runDegraded == true) && bigEnough) {
            BigDecimal dried = ((t - (state.onAtMs as Long)) / (MINMS() as double)) as BigDecimal
            // Record BOTH keys: the indoor-only bucket keeps filling as the reliable fallback,
            // and the indoor+outdoor cell sharpens once it has enough observations of its own.
            // Capture what the table believed BEFORE this run is folded in -- a rebound correction
            // has to aim at that, not at the run that turned out to be too short.
            BigDecimal prevEma = learnedMin(state.runBucket as String, null)
            learnRecord(state.runBucket as String, dried)
            if (state.runOutBucket)
                learnRecord((state.runBucket as String) + "_" + (state.runOutBucket as String), dried)
            state.lastLearnBucket    = state.runBucket
            state.lastLearnOutBucket = state.runOutBucket
            state.lastLearnMin       = dried
            state.lastLearnPrevMin   = prevEma
        }
        def last = state.samples ? state.samples[-1] : null
        state.offDp10 = (last && last[2] != null) ? (last[2] as Integer) : null
        state.reboundUntilMs = t + 25L * MINMS()
        state.fanOn  = false
        state.autoOn = false
        state.offAtMs = t
        clearRunContext()
        unschedule("endMaxRun")
    }
}

// ===========================================================================
// ===== GATES
// ===========================================================================

private boolean anyOn(devs) { return devs?.any { it.currentValue("switch") == "on" } }

private boolean modeOk() { return (!modes || modes.contains(location.mode)) }

private boolean daysOk() {
    if (!days) return true
    def df = new java.text.SimpleDateFormat("EEEE")
    if (location.timeZone) df.setTimeZone(location.timeZone)
    return days.contains(df.format(new Date()))
}

private boolean timeOk() {
    // Midnight-wrap handled: a window like 22:00-06:00 is a union, not an
    // empty intersection. Lifted from v1.1.47's getTimeOk(), which was correct.
    if (!fromTime || !toTime) return true
    def now = new Date()
    def s = timeToday(fromTime, location.timeZone)
    def e = timeToday(toTime,   location.timeZone)
    if (s.before(e)) return timeOfDayIsBetween(s, e, now, location.timeZone)
    return !timeOfDayIsBetween(e, s, now, location.timeZone)
}

private boolean blocked() {
    return (state.paused == true) || anyOn(disabledSwitch) || !modeOk() || !daysOk() || !timeOk()
}

private String blockedReason() {
    if (state.paused == true)   return "PAUSED"
    if (anyOn(disabledSwitch))  return "DISABLED by switch"
    if (!modeOk())              return "mode ${location.mode} not allowed"
    if (!daysOk())              return "day not allowed"
    if (!timeOk())              return "outside allowed hours"
    return "-"
}

// ===========================================================================
// ===== SENSOR HEALTH -- a dead sensor must be visible from outside, not
// ===== manifest as "the fan stopped working".
// ===========================================================================

// Attributes whose timestamp does NOT prove the radio was heard from. Hub Mesh and driver
// bookkeeping update for their own reasons -- `hubMeshDisabled` moves when mesh config changes,
// `driver` when the driver is saved -- so counting them as liveness would mask a dead sensor.
// `batteryLastReplaced` is user-entered. Everything else a driver publishes is real traffic.
private List<String> PLUMBING_ATTRS() {
    // `presence` added v2.8.2. It was left OFF this list on 2026-09-10 because its timestamp never
    // moves on a mesh link (the value is always "present") -- harmless. But the Sonoff driver has
    // a watchdog: when it has NOT heard from the radio for 3 h it flips presence to "not present"
    // and re-announces it every 2 min. Those are hub-generated events about the radio's ABSENCE,
    // and the liveness scan counted them as the radio being heard from. Seen 2026-09-17: sensor
    // dark 07:42-18:51, STALE correctly at 09:14, then at 11:44 an RHSTALE that said "alive (last
    // event 21 min ago)" about a dead device. Any attribute the hub can update without hearing
    // from the device is not evidence of liveness -- that is the rule this list encodes.
    return ["hubMeshDisabled", "driver", "restoredCounter", "notPresentCounter",
            "batteryLastReplaced", "healthStatus", "deviceCommandTime", "presence"]
}

/** Age in minutes of the NEWEST real event from the sensor, across every attribute it publishes.
 *
 *  MEASURED 2026-09-10, and the reason this exists. The old check watched the humidity attribute
 *  alone. The Sonoff SNZB-02P is CHANGE-DRIVEN, so a stable room produces silence that is
 *  indistinguishable from a dead sensor if humidity is all you look at: over 7 days the median gap
 *  was 3.3 min but the max was 127.8, and it warned three times about a device that was demonstrably
 *  alive -- still sending lastCheckin, temperature, even battery throughout every one of those
 *  windows. Liveness is a property of the DEVICE, not of one attribute.
 *
 *  Reads timestamps live rather than from subscriptions, because the app only subscribes to
 *  humidity and temperature and would never see `lastCheckin` go by.
 *
 *  HUB MESH: this app runs on the control hub and is pointed at the LINK device, not the native
 *  one. Verified that the link's per-attribute dates do track the source -- link `lastCheckin`
 *  matched the native hub's to the second. But `presence` does NOT work as a heartbeat here: its
 *  value never changes, so its timestamp sat 5.8 days old while the device checked in every 30 min.
 *  Do not "improve" this by keying on presence.
 */
private Long sensorQuietMin() {
    def d = humiditySensor
    if (!d) return null
    long newest = 0L
    List<String> deny = PLUMBING_ATTRS()

    // BEST SOURCE, verified across Hub Mesh 2026-09-10. `lastActivity` advances on every message
    // RECEIVED, including a repeat of a value that did not change -- so it is strictly better than
    // any per-attribute date, which only moves on a change. Measured on the link device 4947:
    // lastActivity 2.9 min vs newest attribute date 5.5 min, against a native newest event of
    // 2.9 min. It tracked the source hub exactly on all four meshed sensors tested.
    // Kept alongside the attribute scan rather than replacing it: if a driver returns null here
    // the scan still carries the measurement, and taking the newer of the two can only help.
    try {
        Long la = d.getLastActivity()?.getTime()
        if (la != null && la > newest) newest = la
    } catch (ignored) { }

    try {
        d.supportedAttributes?.each { a ->
            String n = a?.name
            if (!n || deny.contains(n)) return
            def st = d.currentState(n)
            if (st?.date) {
                long ms = st.date.time
                if (ms > newest) newest = ms
            }
        }
    } catch (e) {
        // A driver that refuses supportedAttributes must not take the health check -- or the
        // sampler that calls it -- down with it. Whatever lastActivity gave us still stands.
        logDebug "sensorQuietMin: attribute scan failed (${e.message}); falling back"
    }
    long rh = (state.lastRhEventMs ?: 0L) as Long
    if (rh > newest) newest = rh
    if (newest <= 0L) return null
    return (nowMs() - newest) / MINMS()
}

private void checkSensorHealth() {
    // 1. IS THE DEVICE ALIVE AT ALL? This is the question that matters, and the only one that
    //    should ever say "sensor". Any attribute arriving proves the radio was heard from.
    Long quiet = sensorQuietMin()
    if (quiet != null) {
        if (quiet >= cfgStaleMin() && !(state.staleNotified == true)) {
            state.staleNotified = true
            String m = "${appName()}: ${humiditySensor?.displayName} has sent no events of any " +
                       "kind for ${quiet} min"
            logWarn m; notify(m); pushLog(nowMs(), "STALE", null, m)
        } else if (quiet < cfgStaleMin()) {
            state.staleNotified = false
        }
    }

    // 2. IS THE HUMIDITY ATTRIBUTE ITSELF WEDGED? A live radio whose humidity has stopped updating
    //    is a real and separate failure -- a driver that stopped parsing it would look perfectly
    //    healthy to check 1. Bounded far above the 127.8 min longest legitimate gap ever measured,
    //    so ordinary change-driven quiet never trips it.
    long lastRh = (state.lastRhEventMs ?: 0L) as Long
    if (lastRh > 0 && quiet != null && quiet < cfgStaleMin()) {
        long rhAge = (nowMs() - lastRh) / MINMS()
        if (rhAge >= cfgHumStaleMin() && !(state.rhStaleNotified == true)) {
            state.rhStaleNotified = true
            String m = "${appName()}: ${humiditySensor?.displayName} is alive (last event " +
                       "${quiet} min ago) but has not reported HUMIDITY for ${rhAge} min"
            logWarn m; notify(m); pushLog(nowMs(), "RHSTALE", null, m)
        } else if (rhAge < cfgHumStaleMin()) {
            state.rhStaleNotified = false
        }
    }

    def b = humiditySensor?.currentValue("battery")
    if (b != null) {
        int pct = b as Integer
        if (pct <= cfgLowBatt() && !(state.battNotified == true)) {
            state.battNotified = true
            String m = "${appName()}: humidity sensor battery ${pct}%"
            logWarn m; notify(m); pushLog(nowMs(), "BATTERY", null, m)
        } else if (pct > cfgLowBatt() + 5) {
            state.battNotified = false
        }
    }
}

private void notify(String msg) { notifyDevice?.each { it.deviceNotification(msg) } }

// ===========================================================================
// ===== OBSERVABILITY -- state IS the public API. The MCP gateway can read
// ===== app state but NOT app logs, so anything only in the log is invisible
// ===== to automated analysis.
// ===========================================================================

/** The published status map, callable by a parent app. Added for the "Bathroom Fans" container
 *  (2026-09-24): a parent cannot read a child's `state` directly, so the child hands it over. */
Map publicStatus() { return (state.st ?: [:]) }

private void writeStatus(Integer rh10, String reason, String source, String transition) {
    long t = nowMs()
    state.seq = ((state.seq ?: 0L) as Long) + 1L
    long lastEv = (state.lastRhEventMs ?: 0L) as Long
    Integer rh = (rh10 != null) ? rh10 : (state.samples ? (state.samples[-1][1] as Integer) : null)

    String mode
    if (state.paused == true)              mode = "PAUSED"
    else if (anyOn(disabledSwitch))        mode = "DISABLED"
    else if (!modeOk() || !daysOk() || !timeOk()) mode = "RESTRICTED"
    else if (state.fanOn && state.autoOn)  mode = "RUNNING_AUTO"
    else if (state.fanOn)                  mode = "RUNNING_MANUAL"
    else if (rh != null && rh >= SAT_CEILING10()) mode = "SATURATED"
    else if (((state.offAtMs ?: 0L) as Long) > 0 && t - ((state.offAtMs) as Long) < cfgMinOff() * MINMS())
                                           mode = "COOLDOWN"
    else if (state.lastBaseline == null)   mode = "NO_BASELINE"
    else                                   mode = "IDLE"

    String nextAction = null; Long nextAt = null
    if (state.fanOn && state.autoOn && state.onAtMs) {
        nextAction = "endMaxRun"; nextAt = ((state.onAtMs as Long) + cfgMaxRun() * MINMS())
    } else if (state.fanOn) {
        nextAction = "endManualRun"
    }

    state.st = [
        v: APP_VERSION(), seq: state.seq, at: t,
        mode: mode, since: (state.fanOn ? state.onAtMs : state.offAtMs),
        reason: reason ?: "-", source: source ?: "-",
        rh: rh, dp: (state.samples && state.samples[-1][2] != null) ? state.samples[-1][2] : null,
        baseline: state.lastBaseline,
        preDp: state.preDp10, peakDp: state.peakDp10, trigBase: state.triggerBaseline,
        fanOn: state.fanOn, autoOn: state.autoOn,
        bucket: state.runBucket, learned: state.runBucket ? learnedMin(state.runBucket as String) : null,
        nextAction: nextAction, nextAt: nextAt,
        gates: [modeOk: modeOk(), daysOk: daysOk(), timeOk: timeOk(),
                paused: (state.paused == true), disabled: anyOn(disabledSwitch)],
        // sensorAgeSec is HUMIDITY age; deviceQuietMin is whole-device liveness. They diverge
        // routinely on a change-driven sensor and only the second one means "possibly dead".
        sensorAgeSec: lastEv > 0 ? ((t - lastEv) / 1000L) as Long : null,
        deviceQuietMin: sensorQuietMin(),
        degraded: (state.runDegraded == true),
        shadow: isShadow(), note: state.note, err: state.err
    ]

    if (transition) pushLog(t, transition, rh, reason)
}

private void pushLog(long t, String ev, Integer rh10, String why) {
    List lg = new ArrayList((state.log ?: []))
    lg << [t: t, ev: ev, rh: rh10, base: state.lastBaseline, why: why]
    while (lg.size() > 30) lg.remove(0)
    state.log = lg
}

private String appName() { return (thisName ?: "Bathroom Fan NextGen") }

void updateLabel() {
    String suffix
    if (state.paused == true)                 suffix = " <span style='color:orange'>(Paused)</span>"
    else if (anyOn(disabledSwitch))           suffix = " <span style='color:red'>(Disabled)</span>"
    else if (state.fanOn && state.autoOn)     suffix = " <span style='color:green'>(Drying)</span>"
    else if (state.fanOn)                     suffix = " <span style='color:blue'>(Manual)</span>"
    else                                      suffix = " <span style='color:gray'>(Idle)</span>"
    app.updateLabel(appName() + suffix)
}

// Rounding helpers. Deliberately Math.round rather than BigDecimal.setScale
// with a java.math.RoundingMode constant: the Hubitat sandbox screens by AST
// and blocks some java.* references outright (it blocks .getClass()), so this
// avoids betting the whole app on one import being allowed.
private BigDecimal r1(BigDecimal x) { return (Math.round((x as double) * 10.0d) / 10.0d) as BigDecimal }
private BigDecimal r2(BigDecimal x) { return (Math.round((x as double) * 100.0d) / 100.0d) as BigDecimal }

private String fmt1(Integer tenths) {
    if (tenths == null) return "-"
    return ((tenths as double) / 10.0d).toString()
}

private String fmtT(t) {
    if (!t) return "-"
    try { return new Date(t as Long).format("MM-dd HH:mm:ss", location.timeZone) } catch (e) { return "${t}" }
}

private String sensorDecoration() {
    if (!humiditySensor) return ""
    def h = humiditySensor.currentValue("humidity")
    def b = humiditySensor.currentValue("battery")
    return "<br><small>now: ${h ?: '?'}%${b != null ? ", battery ${b}%" : ''}</small>"
}

private String tempDecoration() {
    def d = tempSensor ?: humiditySensor
    if (!d) return ""
    def t = d.currentValue("temperature")
    return t != null ? "<br><small>now: ${t}F</small>" : ""
}

String statusText() {
    def st = state.st
    if (!st) return "Not started yet."
    List l = []
    l << "<b>${st.mode}</b> since ${fmtT(st.since)}   (seq ${st.seq})"
    l << "rh ${fmt1(st.rh as Integer)}%   dew ${fmt1(st.dp as Integer)}F   baseline ${fmt1(st.baseline as Integer)}"
    if (st.fanOn && st.autoOn) l << "pre-shower dew ${fmt1(st.preDp as Integer)}F -> target ${fmt1(((st.preDp ?: 0) as Integer) + cfgDryMargin10())}F   bucket ${st.bucket} (learned ${st.learned} min)"
    l << "last: ${st.reason}"
    l << "sensor age ${st.sensorAgeSec ?: '-'}s   ${isShadow() ? 'SHADOW' : 'LIVE'} -> ${target()?.displayName ?: 'none'}"
    if (st.note) l << "<span style='color:orange'>${st.note}</span>"
    if (st.err)  l << "<span style='color:red'>${st.err}</span>"
    return l.join("<br>")
}

private String learnSummary() {
    if (!state.learn) return "nothing learned yet"
    return state.learn.collect { k, v -> "${k}: ${((v.ema10 as Integer)/10.0)}m" }.join(", ")
}

private String learnTable() {
    if (!state.learn) return "<i>No completed automatic runs recorded yet. Cold start uses ${cfgDryFallback()} min.</i>"
    List rows = ["<table><tr><th>pre-shower dew point</th><th>runs</th><th>learned length</th></tr>"]
    state.learn.sort { it.key }.each { k, v ->
        // "dpNA" is a real bucket (a run with no dew point). Parsing it as an
        // Integer would throw and take the whole page down.
        String lo = (k as String).replace("dp", "")
        String label = lo.isInteger() ? "${lo}-${(lo as Integer) + BUCKET_BIN_F()}F" : "no dew point"
        rows << "<tr><td>${label}</td><td>${v.n}</td><td>${((v.ema10 as Integer)/10.0)} min</td></tr>"
    }
    rows << "</table>"
    return rows.join("")
}

private void logError(msg) { log.error "${appName()}: ${msg}" }
private void logWarn(msg)  { log.warn  "${appName()}: ${msg}" }
private void logInfo(msg)  { if (logLvl() >= 1) log.info  "${appName()}: ${msg}" }
private void logDebug(msg) { if (logLvl() >= 2) log.debug "${appName()}: ${msg}" }
private void logTrace(msg) { if (logLvl() >= 3) log.trace "${appName()}: ${msg}" }
