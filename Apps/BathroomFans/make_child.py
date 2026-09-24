"""Derive BathroomFanChild.groovy from BathroomFanNG.groovy.

The CHILD is today's standalone app with three kinds of change and NO others:
  1. a `parent:` declaration and a new definition name (two apps cannot share a name on a hub)
  2. production values -- the ones measured into the master over 37 runs -- as the code DEFAULTS,
     in both the input defaultValue and the cfg*() null fallback, so a fresh child needs only its
     devices, a name and its files
  3. version 3.0.0

The CORE DECISION LOGIC block is asserted byte-identical to the source. Every replacement must
match exactly once or the script refuses to write. Re-runnable: always derives from the standalone.
"""
import re, sys

SRC = 'BathroomFanNG.groovy'
DST = 'BathroomFanChild.groovy'
src = open(SRC, encoding='utf-8').read()
out = src

EDITS = [
    # --- definition -------------------------------------------------------------------------
    ('name: "Bathroom Fan NextGen",\n    namespace: "jrfarrar",',
     'name: "Bathroom Fan",\n    namespace: "jrfarrar",\n    parent: "jrfarrar:Bathroom Fans",'),
    ('description: "Delayed-max humidity trigger, dew-point drying, learns seasonal run length.",',
     'description: "One bathroom: excess-over-house trigger with local confirmation, bounded turn-off target, run log. Child of Bathroom Fans.",'),
    ('String APP_VERSION() { return "2.8.2" }', 'String APP_VERSION() { return "3.0.0" }'),
    ('importUrl: "https://raw.githubusercontent.com/jrfarrar/hubitat/master/Apps/BathroomFans/BathroomFanNG.groovy",',
     'importUrl: "https://raw.githubusercontent.com/jrfarrar/hubitat/master/Apps/BathroomFans/BathroomFanChild.groovy",'),
    # --- input defaults -> production values ---------------------------------------------------
    ('"the real fan, so this can run in parallel with your existing app",\n                  defaultValue: true, submitOnChange: true',
     '"the real fan, so this can run in parallel with your existing app",\n                  defaultValue: false, submitOnChange: true'),
    ('"excess": "EXCESS over the house  (bathroom dew - house dew)"],\n                  defaultValue: "rh", required: true, submitOnChange: true',
     '"excess": "EXCESS over the house  (bathroom dew - house dew)"],\n                  defaultValue: "excess", required: true, submitOnChange: true'),
    ("\"(${(triggerMetric == 'dp') ? 'F of dew point' : '%RH'})\",\n                  defaultValue: 9, required: true",
     "\"(${(triggerMetric == 'dp') ? 'F of dew point' : '%RH'})\",\n                  defaultValue: 2.5, required: true"),
    ('input "dryMaxMin", "number", title: "Give up waiting to dry after (minutes)",\n                  defaultValue: 45, required: true',
     'input "dryMaxMin", "number", title: "Give up waiting to dry after (minutes)",\n                  defaultValue: 90, required: true'),
    ("\"'turn the fan off after N minutes' rule.\",\n                  defaultValue: 50, required: true",
     "\"'turn the fan off after N minutes' rule.\",\n                  defaultValue: 95, required: true"),
    ('"3": "Turn it off after a fixed time, unless humidity takes over"],\n                  defaultValue: "3", required: true',
     '"3": "Turn it off after a fixed time, unless humidity takes over"],\n                  defaultValue: "2", required: true'),
    ('input "manualOffMinutes", "number", title: "Manual run length (minutes)",\n                  defaultValue: 30, required: true',
     'input "manualOffMinutes", "number", title: "Manual run length (minutes)",\n                  defaultValue: 20, required: true'),
    ('title: "Dry when dew point is within this many F of its pre-shower value",\n                  defaultValue: 2.5, required: true',
     'title: "Dry when dew point is within this many F of its pre-shower value",\n                  defaultValue: 3.5, required: true'),
    ('title: "Warn when the sensor has sent <b>no events of any kind</b> for (minutes)",\n                  defaultValue: 90, required: true',
     'title: "Warn when the sensor has sent <b>no events of any kind</b> for (minutes)",\n                  defaultValue: 75, required: true'),
    # --- cfg null-fallbacks, same values ----------------------------------------------------------
    ('private BigDecimal cfgRise()   { return ((riseThreshold  != null ? riseThreshold  : 9)  as BigDecimal) }',
     'private BigDecimal cfgRise()   { return ((riseThreshold  != null ? riseThreshold  : 2.5) as BigDecimal) }'),
    ('private int cfgDryMax()        { return ((dryMaxMin      != null ? dryMaxMin      : 45) as int) }',
     'private int cfgDryMax()        { return ((dryMaxMin      != null ? dryMaxMin      : 90) as int) }'),
    ('private int cfgMaxRun()        { return ((maxRunMin      != null ? maxRunMin      : 50) as int) }',
     'private int cfgMaxRun()        { return ((maxRunMin      != null ? maxRunMin      : 95) as int) }'),
    ('private int cfgManualMin()     { return ((manualOffMinutes != null ? manualOffMinutes : 30) as int) }',
     'private int cfgManualMin()     { return ((manualOffMinutes != null ? manualOffMinutes : 20) as int) }'),
    ('private int cfgStaleMin()      { return ((sensorStaleMin != null ? sensorStaleMin : 90) as int) }',
     'private int cfgStaleMin()      { return ((sensorStaleMin != null ? sensorStaleMin : 75) as int) }'),
    ('private int cfgDryMargin10()   { return (((dryMarginF != null ? dryMarginF : 2.5) as BigDecimal) * 10) as int }',
     'private int cfgDryMargin10()   { return (((dryMarginF != null ? dryMarginF : 3.5) as BigDecimal) * 10) as int }'),
    ('private String  manualMode() { return (manualControlMode != null ? manualControlMode : "3") as String }',
     'private String  manualMode() { return (manualControlMode != null ? manualControlMode : "2") as String }'),
]

for old, new in EDITS:
    n = out.count(old)
    if n != 1:
        print('REFUSING: %d matches (need 1) for:\n   %s' % (n, old[:90].replace('\n', '\\n')))
        sys.exit(1)
    out = out.replace(old, new)

# Header note, right after the definition block's closing paren.
HDR = '''
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
'''
anchor = '    singleThreaded: true\n)\n'
if out.count(anchor) != 1:
    print('REFUSING: definition anchor not unique'); sys.exit(1)
out = out.replace(anchor, anchor + HDR)

# CORE block must be untouched.
def core(s):
    a = s.index('// ===== CORE DECISION LOGIC'); b = s.index('// ===== END CORE DECISION LOGIC')
    return s[a:b]
if core(src) != core(out):
    print('REFUSING: CORE DECISION LOGIC block changed'); sys.exit(1)

open(DST, 'w', encoding='utf-8', newline='\n').write(out)
print('wrote %s: %d chars (source %d), %d edits, CORE block byte-identical (%d chars)'
      % (DST, len(out), len(src), len(EDITS), len(core(src))))
