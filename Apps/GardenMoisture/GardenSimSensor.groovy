/**
 *  Garden Sim Sensor  (VIRTUAL DRIVER - TESTING ONLY)
 *
 *  Copyright 2026 J.R. Farrar
 *
 *  Stands in for the whole sensor set the Garden Moisture Logger consumes, in
 *  one virtual device:
 *    - WH51 soil probe        -> humidity, soilAD, battery
 *    - WS90 rain gauge        -> rainRate, rainEvent, rainDaily, raining
 *    - outdoor temperature    -> temperature
 *
 *  It deliberately mirrors the Ecowitt RF Sensor driver's quirks so the logger
 *  is tested against what it will actually meet in production:
 *    - soil moisture is published on "humidity", NOT a soilMoisture attribute
 *    - "raining" is the STRING "true"/"false", not a boolean
 *    - humidity is an INTEGER, so slow changes arrive as a staircase and the
 *      finer movement only shows up in soilAD
 *
 *  Capabilities are chosen so a single instance satisfies all three device
 *  inputs on the logger child (soil / rainDev / tempDev).
 *
 *  NOT FOR PRODUCTION USE. This is a test fixture.
 *
 *  v0.1.0  2026-08-31  Initial release.
 *  v0.1.1  2026-08-31  humidity and soilAD now fire with isStateChange so
 *                      repeated values still produce events.
 *  v0.1.2  2026-08-31  Added simBoundary / markBoundary so the scenario runner
 *                      can tell the logger to reset between tests, without
 *                      either app reaching into the other's state.
 */

metadata {
    definition(name: "Garden Sim Sensor", namespace: "jrfarrar", author: "J.R. Farrar",
               importUrl: "https://raw.githubusercontent.com/jrfarrar/hubitat/master/Apps/GardenMoisture/GardenSimSensor.groovy") {
        capability "Sensor"
        capability "Relative Humidity Measurement"   // soil moisture lands here
        capability "Temperature Measurement"
        capability "Battery"

        attribute "soilAD",    "number"
        attribute "rainRate",  "number"
        attribute "rainEvent", "number"
        attribute "rainDaily", "number"
        attribute "raining",   "string"   // "true" / "false" - string, like Ecowitt
        attribute "simBoundary", "string"  // pulsed between scenarios; see markBoundary

        command "setHumidity",  [[name: "percent*", type: "NUMBER"]]
        command "setSoilAD",    [[name: "mv*",      type: "NUMBER"]]
        command "setBattery",   [[name: "percent*", type: "NUMBER"]]
        command "setTemperature", [[name: "degF*",  type: "NUMBER"]]
        command "setRaining",   [[name: "trueOrFalse*", type: "ENUM", constraints: ["true", "false"]]]
        command "setRainRate",  [[name: "inPerHr*", type: "NUMBER"]]
        command "setRainEvent", [[name: "inches*",  type: "NUMBER"]]
        command "setRainDaily", [[name: "inches*",  type: "NUMBER"]]

        // Convenience: set soil moisture and let the driver derive a plausible
        // A/D, so scenarios do not have to track both by hand.
        command "setSoil", [[name: "percent*", type: "NUMBER"]]

        command "resetAll"

        // Pulsed by the scenario runner between tests. The logger subscribes to
        // it and resets its learned + volatile state, but ONLY while its own
        // simulation speed-up is above 1 - so this can never wipe a production
        // install even if the command is sent by accident.
        command "markBoundary"
    }

    preferences {
        input "adDry",  "number", title: "Raw A/D at 0% (dry rail)",  defaultValue: 30,  required: true
        input "adWet",  "number", title: "Raw A/D at 100% (wet rail)", defaultValue: 500, required: true
        input "adJitter", "decimal", title: "A/D jitter to add (mv) - keeps the stale detector honest",
              defaultValue: 2, required: true
        input "logEnable", "bool", title: "Debug logging", defaultValue: true
    }
}

def installed() {
    resetAll()
}

def updated() {
    if (logEnable) log.debug "${device.displayName}: preferences updated"
}

def parse(String description) { }

/* ------------------------------------------------------------- commands -- */

def setHumidity(percent) {
    // Integer, exactly as the Ecowitt driver reports soil moisture.
    Integer v = Math.round((percent as BigDecimal).doubleValue()) as Integer
    if (v < 0) v = 0
    if (v > 100) v = 100
    // isStateChange so a repeated value still fires. Hubitat suppresses
    // duplicate-value events by default, which would silently swallow the
    // priming readings the sim runner uses to flush the detector between runs.
    sendEvent(name: "humidity", value: v, unit: "%", isStateChange: true)
    if (logEnable) log.debug "${device.displayName}: humidity = ${v}%"
}

def setSoilAD(mv) {
    BigDecimal v = mv as BigDecimal
    sendEvent(name: "soilAD", value: v, unit: "mv", isStateChange: true)
    if (logEnable) log.debug "${device.displayName}: soilAD = ${v} mv"
}

/**
 * Sets both, deriving A/D from the percentage across the configured rails and
 * adding a little jitter. The jitter matters: the logger's stale detector only
 * fires when moisture AND A/D are both frozen, so a sim that held A/D perfectly
 * constant would trip it spuriously on every flat stretch.
 */
def setSoil(percent) {
    BigDecimal pct = percent as BigDecimal
    setHumidity(pct)

    BigDecimal dry = (adDry  != null ? adDry  : 30) as BigDecimal
    BigDecimal wet = (adWet  != null ? adWet  : 500) as BigDecimal
    BigDecimal jit = (adJitter != null ? adJitter : 2) as BigDecimal

    double frac = pct.doubleValue() / 100.0d
    double ad = dry.doubleValue() + (frac * (wet.doubleValue() - dry.doubleValue()))
    if (jit.doubleValue() > 0) {
        ad += (new Random().nextDouble() - 0.5d) * 2.0d * jit.doubleValue()
    }
    setSoilAD(new BigDecimal(String.format(java.util.Locale.US, "%.0f", ad)))
}

def setBattery(percent) {
    sendEvent(name: "battery", value: (percent as Integer), unit: "%")
}

def setTemperature(degF) {
    sendEvent(name: "temperature", value: (degF as BigDecimal), unit: "F")
    if (logEnable) log.debug "${device.displayName}: temperature = ${degF} F"
}

def setRaining(trueOrFalse) {
    String v = (trueOrFalse?.toString() == "true") ? "true" : "false"
    sendEvent(name: "raining", value: v)     // STRING, like the Ecowitt driver
    if (logEnable) log.debug "${device.displayName}: raining = ${v}"
}

def setRainRate(inPerHr)  { sendEvent(name: "rainRate",  value: (inPerHr as BigDecimal), unit: "in/h") }
def setRainEvent(inches)  { sendEvent(name: "rainEvent", value: (inches  as BigDecimal), unit: "in") }
def setRainDaily(inches)  { sendEvent(name: "rainDaily", value: (inches  as BigDecimal), unit: "in") }

def markBoundary() {
    sendEvent(name: "simBoundary", value: now().toString(), isStateChange: true)
    log.info "${device.displayName}: scenario boundary marked"
}

def resetAll() {
    setSoil(35)
    setBattery(100)
    setTemperature(70)
    setRaining("false")
    setRainRate(0)
    setRainEvent(0)
    setRainDaily(0)
    log.info "${device.displayName}: reset to a neutral starting state"
}
