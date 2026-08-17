/**
 *  Outdoor Temperature Averager
 *
 *  A Hubitat parent app that averages several temperature sensors into a single
 *  virtual temperature sensor, while:
 *    - dropping sensors that have stopped reporting (dead battery / offline), and
 *    - rejecting outliers that sit too far from the median of the live sensors
 *      (e.g. a sensor sitting in direct sun reading much higher than the rest).
 *
 *  It creates and updates a child "Virtual Temperature Sensor" device that you
 *  can use anywhere in Hubitat (dashboards, Rule Machine, thermostat control, etc).
 *
 *  Design notes
 *  ------------
 *  Staleness: a sensor is considered stale if the more recent of getLastActivity()
 *  and its temperature state timestamp is older than the configured window.
 *  getLastActivity() is used because it reflects ANY check-in from the device, so a
 *  battery that has died (device goes silent) is caught even if the last temperature
 *  value never changed.
 *
 *  Outlier rejection (deviation from median): the median of the live readings is
 *  computed, then any sensor whose reading deviates from that median by more than the
 *  configured amount is dropped. The median is robust — a single sun-baked sensor
 *  cannot pull it, so that sensor is the one that gets flagged. Outlier rejection only
 *  runs with 3+ live sensors, because with 2 sensors there is no majority to identify
 *  which one is wrong.
 *
 *  Periodic recompute: because a dead sensor sends no events, the app also recomputes
 *  on a schedule so a sensor that goes silent is eventually dropped even if nothing
 *  else triggers a recalculation.
 *
 *  Author: J.R. Farrar
 *  License: free to use and modify.
 *
 *  Revision history
 *    1.0.0 - Initial version.
 */

definition(
    name:        "Outdoor Temperature Averager",
    namespace:   "jrfarrar",
    author:      "J.R. Farrar",
    description: "Average multiple temperature sensors into one virtual sensor, dropping dead sensors and median outliers.",
    parent:      "jrfarrar:Admin tools",
    category:    "Convenience",
    iconUrl:     "",
    iconX2Url:   "",
    iconX3Url:   ""
)

preferences {
    page(name: "mainPage")
}

/* ----------------------------------------------------------------------------
 *  UI
 * -------------------------------------------------------------------------- */

def mainPage() {
    dynamicPage(name: "mainPage", title: "", install: true, uninstall: true) {

        section("<b>Name</b>") {
            input "thisName", "text", title: "Name for this app and its virtual sensor",
                defaultValue: "Outdoor Temp (Averaged)", submitOnChange: true, required: true
            if (thisName) app.updateLabel(thisName)
        }

        section("<b>Sensors to average</b>") {
            input "tempSensors", "capability.temperatureMeasurement",
                title: "Temperature sensors", multiple: true, required: true, submitOnChange: true
            if (tempSensors) {
                paragraph "Selected ${tempSensors.size()} sensor(s). Outlier rejection needs at least 3 live sensors to work."
            }
        }

        section("<b>Dead / stale sensor handling</b>") {
            input "staleMinutes", "number",
                title: "Ignore a sensor if it hasn't checked in for this many minutes",
                defaultValue: 120, required: true
        }

        section("<b>Outlier rejection (deviation from median)</b>") {
            input "outlierEnable", "bool",
                title: "Drop readings that are too far from the median of the live sensors",
                defaultValue: true, submitOnChange: true
            if (outlierEnable) {
                input "maxDeviation", "decimal",
                    title: "Maximum allowed distance from the median (in your hub's temperature units)",
                    defaultValue: 5.0, required: true
                paragraph "Example: with a max of 5, if the median of your live sensors is 78 and one sensor reads 96 (sitting in the sun), that sensor is excluded from the average."
            }
        }

        section("<b>Output</b>") {
            input "decimals", "enum", title: "Decimal places in the averaged value",
                options: ["0": "0 (whole degrees)", "1": "1", "2": "2"], defaultValue: "1", required: true
            input "refreshMinutes", "enum",
                title: "Recompute at least this often (so a silent/dead sensor gets dropped even with no new events)",
                options: ["5": "Every 5 minutes", "10": "Every 10 minutes", "15": "Every 15 minutes",
                          "30": "Every 30 minutes", "60": "Every hour"],
                defaultValue: "10", required: true
        }

        section("<b>Logging</b>") {
            input "logEnable", "bool", title: "Enable debug logging", defaultValue: true
        }
    }
}

/* ----------------------------------------------------------------------------
 *  Lifecycle
 * -------------------------------------------------------------------------- */

def installed() {
    initialize()
}

def updated() {
    unsubscribe()
    unschedule()
    initialize()
}

def uninstalled() {
    // Remove the child virtual sensor when the app is removed.
    getChildDevices().each { deleteChildDevice(it.deviceNetworkId) }
}

def initialize() {
    def child = getOrCreateChild()
    if (child && thisName && child.getLabel() != thisName) {
        child.setLabel(thisName)
    }

    subscribe(tempSensors, "temperature", "tempHandler")

    switch (refreshMinutes ?: "10") {
        case "5":  runEvery5Minutes("computeAverage");  break
        case "10": runEvery10Minutes("computeAverage"); break
        case "15": runEvery15Minutes("computeAverage"); break
        case "30": runEvery30Minutes("computeAverage"); break
        case "60": runEvery1Hour("computeAverage");     break
        default:   runEvery10Minutes("computeAverage")
    }

    // Compute once now so the value is populated immediately.
    computeAverage()
}

private getOrCreateChild() {
    def dni = "avgTemp_${app.id}"
    def child = getChildDevice(dni)
    if (!child) {
        try {
            child = addChildDevice(
                "hubitat",
                "Virtual Temperature Sensor",
                dni,
                [name: thisName ?: "Outdoor Temp (Averaged)",
                 label: thisName ?: "Outdoor Temp (Averaged)",
                 isComponent: false]
            )
            log.info "Outdoor Temperature Averager: created child virtual sensor '${child.getLabel()}'."
        } catch (e) {
            log.error "Outdoor Temperature Averager: could not create child device. " +
                      "Confirm the built-in 'Virtual Temperature Sensor' driver exists. Error: ${e.message}"
        }
    }
    return child
}

/* ----------------------------------------------------------------------------
 *  Event handling
 * -------------------------------------------------------------------------- */

def tempHandler(evt) {
    computeAverage()
}

def computeAverage() {
    def scale = location.temperatureScale        // "F" or "C"
    Long staleMs = ((staleMinutes ?: 120) as Long) * 60000L

    List live    = []      // maps: [name, temp]
    List dropped = []      // human-readable reasons, for logging

    tempSensors.each { s ->
        def raw = s.currentValue("temperature")
        Long ageMs = sensorAgeMs(s)

        if (raw == null) {
            dropped << "${s.displayName} (no reading)"
        } else if (ageMs != null && ageMs > staleMs) {
            dropped << "${s.displayName} (stale ~${Math.round(ageMs / 60000)} min)"
        } else {
            live << [name: s.displayName, temp: (raw as BigDecimal)]
        }
    }

    if (live.isEmpty()) {
        log.warn "Outdoor Temperature Averager: no live sensors — average NOT updated."
        return
    }

    List kept = live

    // Median-deviation outlier rejection (needs a majority: 3+ live sensors).
    if (outlierEnable && live.size() >= 3) {
        BigDecimal med    = median(live.collect { it.temp })
        BigDecimal maxDev = (maxDeviation ?: 5.0) as BigDecimal

        List keepers  = live.findAll { (it.temp - med).abs() <= maxDev }
        List outliers = live.findAll { (it.temp - med).abs() >  maxDev }

        outliers.each { dropped << "${it.name}=${it.temp} (outlier vs median ${med})" }

        // Safety net: the sensor(s) at the median always have deviation 0, so keepers
        // should never be empty. Guard anyway.
        kept = keepers.isEmpty() ? live : keepers
    }

    BigDecimal sum = kept.collect { it.temp }.sum() as BigDecimal
    BigDecimal avg = sum / kept.size()

    int dp = (decimals ?: "1") as Integer
    BigDecimal rounded = avg.setScale(dp, java.math.RoundingMode.HALF_UP)

    def child = getChildDevice("avgTemp_${app.id}")
    if (child) {
        child.sendEvent(
            name: "temperature",
            value: rounded,
            unit: "°${scale}",
            descriptionText: "${child.displayName} averaged temperature is ${rounded}°${scale}"
        )
    } else {
        log.warn "Outdoor Temperature Averager: child device missing — cannot publish average."
    }

    if (logEnable) {
        log.debug "Outdoor Temperature Averager: ${rounded}°${scale} from ${kept.size()} sensor(s): " +
                  "${kept.collect { it.name + '=' + it.temp }.join(', ')}"
        if (dropped) {
            log.debug "Outdoor Temperature Averager: excluded ${dropped.size()} sensor(s): ${dropped.join('; ')}"
        }
    }
}

/* ----------------------------------------------------------------------------
 *  Helpers
 * -------------------------------------------------------------------------- */

// Age (ms) since the sensor last showed any sign of life. Uses the more recent of
// its last activity and its last temperature-state timestamp. Returns null if unknown.
private Long sensorAgeMs(s) {
    Long best = null

    try {
        def st = s.currentState("temperature")
        Long t = toMs(st?.getDate())
        if (t != null) best = t
    } catch (ignored) { }

    try {
        Long la = toMs(s.getLastActivity())
        if (la != null && (best == null || la > best)) best = la
    } catch (ignored) { }

    return best == null ? null : (now() - best)
}

// Convert a Date or a timestamp String into epoch ms. Returns null if it can't.
private Long toMs(val) {
    if (val == null) return null
    if (val instanceof Date) return val.getTime()
    try {
        return Date.parse("yyyy-MM-dd HH:mm:ss", "${val}".replace("+00:00", "+0000")).getTime()
    } catch (ignored) { }
    try {
        return (new Date("${val}")).getTime()
    } catch (ignored) { }
    return null
}

// Median of a list of BigDecimals.
private BigDecimal median(List<BigDecimal> vals) {
    if (!vals) return null
    List<BigDecimal> s = vals.sort(false)
    int n = s.size()
    int mid = n.intdiv(2)
    if (n % 2 == 1) {
        return s[mid]
    }
    return (s[mid - 1] + s[mid]) / 2
}