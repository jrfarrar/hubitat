/**
 * APRS Weather Reporter
 * Sends Ecowitt weather data from Hubitat to APRS-IS
 *
 * Author: K0JRF
 * Version: 2.6
 *   - Honour the Ecowitt driver's own orphan flags (orphanedWind,
 *     orphanedTemp, orphanedRain, and a generic 'orphaned'). A sub-sensor can
 *     die while the device it belongs to keeps publishing: on 2026-08-22 the
 *     WH69's wind unit went silent at 17:39 and the array kept reporting
 *     temperature every few minutes, so device-level activity said 'healthy'
 *     while the station beacon carried a frozen wind reading for 45 minutes.
 *     The driver had set orphanedWind at 17:52. Nothing else knew.
 *   - These flags beat any timestamp heuristic and are checked first.
 *   - Staleness handling. A weather station that keeps beaconing a frozen
 *     reading is worse than one that goes quiet: the stale number looks
 *     current to everyone downstream. If the temperature source stops
 *     reporting, no packet is sent at all; if a secondary source stops, only
 *     its field is dropped.
 *   - Judged on DEVICE last activity, never on a single attribute's
 *     timestamp. Hubitat only records an event when a value changes, so a
 *     steady wind direction reads as half an hour old on a perfectly healthy
 *     sensor. Attribute timestamps are not a liveness signal.
 *   - When the age cannot be read the device is treated as FRESH. A
 *     staleness check that cannot get a timestamp must never be the reason a
 *     station stops transmitting.
 *   - Temperature can now come from its own device, like pressure and rain
 *     already could. A sensor array sitting in the sun reads several degrees
 *     high; an averaged virtual sensor is usually the better number to
 *     beacon, but it often exposes temperature alone and so cannot serve as
 *     the main weather device.
 *   - Added an explicit "I have a rain gauge" switch, off by default. With no
 *     gauge the rain fields are omitted from the packet entirely rather than
 *     sent as "r...p...", so the report does not claim a sensor that is not
 *     there. Tick the switch and the device/attribute pickers come back.
 *   - Fixed a false-failure bug: v2.1 required HTTP 204 AND a non-zero
 *     X-Packetsrcvd header. Real Tier-2 servers answer a successful submit
 *     with a bare HTTP 200 and no such header, so genuinely delivered packets
 *     were logged as failures. Any 2xx now counts as delivered unless the body
 *     is an HTML page (which means the port is a web status page, not the
 *     submit endpoint) or the server explicitly reports zero packets received.
 *   - Each failure status now carries the meaning observed in the field
 *     instead of one guess: 403 login rejected, 404 no submit endpoint here,
 *     408 nothing speaking HTTP on this port.
 *   - Warn on save when the port is set to 14580, the raw TCP port.
 *   - Default server back to rotate.aprs2.net, which does serve HTTP submit
 *     on 8080; noam.aprs2.net:8080 answers 404.
 *   - Pressure and rain can now come from a DIFFERENT device than temperature.
 *     On an Ecowitt the barometer lives on the gateway/console, not on the
 *     outdoor sensor array, so a single-device app can never report it. The
 *     driver still exposes a null 'pressure' attribute on the array, which
 *     makes the mapping look correct while it silently sends dots.
 *   - The optional "rain since midnight" field is now omitted entirely when
 *     there is no value, instead of being sent as "P...".
 *   - Delivery switched from raw-LAN HubAction to the APRS-IS HTTP send-only
 *     port. The old HubAction path never actually reached APRS-IS: it logged
 *     "packet sent" unconditionally and had no response handler, so a silent
 *     failure looked identical to success.
 *   - Real confirmation: a submission only counts as sent on HTTP 204 with a
 *     non-zero X-Packetsrcvd header. Result is shown on the setup page.
 *   - Barometric pressure and rainfall are now reported (were hard-coded dots).
 *   - Fixed: negative temperatures produced "0-5" instead of "-05".
 *   - Fixed: null humidity produced "h  " (two spaces), an invalid field.
 *   - Celsius hubs are converted to Fahrenheit for the APRS temperature field.
 *   - Added a Diagnose button that dumps the weather device's real attribute
 *     names and values to the log, so field mapping is never a guess.
 *
 * Install as a Hubitat App (Apps > Add User App)
 * Requires: Ecowitt device already configured in Hubitat
 *
 * APRS-IS HTTP submission reference: https://www.aprs-is.net/SendOnlyPorts.aspx
 */

definition(
    name: "APRS Weather Reporter",
    namespace: "k0jrf",
    author: "K0JRF",
    description: "Reports Ecowitt weather data to APRS-IS network",
    category: "Weather",
    iconUrl: "",
    iconX2Url: "",
    importUrl: "https://raw.githubusercontent.com/jrfarrar/hubitat/master/Apps/APRSWeatherReporter.groovy"
)

preferences {
    page(name: "mainPage")
}

// ---------------------------------------------------------------------------
// UI
// ---------------------------------------------------------------------------

def mainPage() {
    dynamicPage(name: "mainPage", title: "APRS Weather Reporter", install: true, uninstall: true) {

        section("Status") {
            paragraph statusText()
        }

        section("APRS-IS Settings") {
            input "callsign",   "text",   title: "Callsign (e.g. K0JRF-13)", required: true
            input "passcode",   "number", title: "APRS-IS Passcode (computed from the BASE callsign, no SSID)", required: true
            input "aprsServer", "text",   title: "APRS-IS Server", required: true, defaultValue: "rotate.aprs2.net"
            input "aprsPort",   "number", title: "APRS-IS HTTP submit port (8080 — NOT 14580, that is the raw TCP port)", required: true, defaultValue: 8080
        }

        section("Station Location") {
            input "stationLat",  "decimal", title: "Latitude (decimal, e.g. 42.031)",   required: true
            input "stationLon",  "decimal", title: "Longitude (decimal, e.g. -80.255)", required: true
            input "stationDesc", "text",    title: "Station Description", required: true, defaultValue: "Wx Station Fairview PA"
        }

        section("Weather Devices") {
            input "wxDevice", "capability.temperatureMeasurement",
                title: "Main weather device (humidity and wind — plus temperature unless overridden below)",
                required: true, submitOnChange: true
            input "tempDevice", "capability.temperatureMeasurement",
                title: "Outdoor temperature device (optional) — use an averaged/shaded sensor here if the main array reads high in the sun",
                required: false, submitOnChange: true
            input "pressureDevice", "capability.*",
                title: "Barometric pressure device (optional) — on an Ecowitt this is normally the GATEWAY/console, not the outdoor array",
                required: false, submitOnChange: true
            input "hasRainGauge", "bool",
                title: "I have a rain gauge (report rainfall)",
                defaultValue: false, submitOnChange: true
            if (hasRainGauge) {
                input "rainDevice", "capability.*",
                    title: "Rain device (optional) — leave blank if the main device reports rain",
                    required: false, submitOnChange: true
            } else {
                paragraph "No rain gauge: the rain fields are left out of the packet entirely, " +
                          "rather than transmitted as \"r...p...\". Everything else is unaffected."
            }
        }

        if (wxDevice) {
            def opts  = attributeOptions(wxDevice)
            def tOpts = attributeOptions(tempDevice ?: wxDevice)
            def pOpts = attributeOptions(pressureDevice ?: wxDevice)
            def rOpts = attributeOptions(rainDevice ?: wxDevice)
            section("Attribute Mapping") {
                paragraph "Pick the attribute on '${wxDevice.displayName}' that supplies each APRS field. " +
                          "Leave one blank to omit that field (it is sent as dots, which is valid APRS). " +
                          "Temperature, pressure and rain are listed from their own device when you selected one above. " +
                          "Use the Diagnose button below to dump every attribute and its current value to the log."
                input "attrTemp",     "enum", title: "Temperature",              options: tOpts, required: false, defaultValue: pick(tOpts, ["temperature"])
                input "attrHum",      "enum", title: "Humidity",                 options: opts, required: false, defaultValue: pick(opts, ["humidity"])
                input "attrWindDir",  "enum", title: "Wind direction (degrees)", options: opts, required: false, defaultValue: pick(opts, ["windDirection", "windDir"])
                input "attrWindSpd",  "enum", title: "Wind speed (mph)",         options: opts, required: false, defaultValue: pick(opts, ["windSpeed", "windAvg"])
                input "attrWindGust", "enum", title: "Wind gust (mph)",          options: opts, required: false, defaultValue: pick(opts, ["windGust", "gustSpeed"])
                input "attrPressure", "enum", title: "Barometric pressure",      options: pOpts, required: false, defaultValue: pick(pOpts, ["pressure", "barometricPressure", "baromRelIn", "baromRel"])
                if (hasRainGauge) {
                    input "attrRain1h",   "enum", title: "Rain, last hour",          options: rOpts, required: false, defaultValue: pick(rOpts, ["rainHourly", "hourlyRain", "rainRate"])
                    input "attrRain24h",  "enum", title: "Rain, last 24 hours",      options: rOpts, required: false, defaultValue: pick(rOpts, ["rain24", "rainDaily", "dailyRain"])
                    input "attrRainMid",  "enum", title: "Rain since midnight (optional)", options: rOpts, required: false, defaultValue: pick(rOpts, ["rainDaily", "dailyRain"])
                }
            }
            section("Units") {
                input "pressureUnit", "enum", title: "Pressure attribute is reported in",
                    options: ["auto": "Auto-detect", "inhg": "inches of mercury", "hpa": "hectopascals / millibars"],
                    required: true, defaultValue: "auto"
                if (hasRainGauge) {
                    input "rainUnit", "enum", title: "Rain attributes are reported in",
                        options: ["in": "inches", "mm": "millimetres"],
                        required: true, defaultValue: "in"
                }
            }
        }

        section("Reporting Schedule") {
            input "reportInterval", "enum", title: "Report Every",
                options: ["10": "10 Minutes", "15": "15 Minutes", "30": "30 Minutes"],
                required: true, defaultValue: "10"
        }

        section("Data Freshness") {
            input "staleCheckEnabled", "bool",
                title: "Stop reporting when a source device goes quiet",
                defaultValue: true, submitOnChange: true
            input "orphanGuard", "bool",
                title: "Trust the driver's own orphan flags (Ecowitt: orphanedWind, orphanedTemp, orphanedRain)",
                defaultValue: true
            if (staleCheckEnabled) {
                input "staleMinutes", "number",
                    title: "Consider a source stale after this many minutes with no activity",
                    required: true, defaultValue: 60
                paragraph "Measured from the device's last activity of any kind, not from one attribute's " +
                          "timestamp — Hubitat only records an event when a value changes, so a steady wind " +
                          "direction can read as hours old on a healthy sensor. Stale temperature source: no " +
                          "packet at all. Stale secondary source: only its field is dropped. If the age cannot " +
                          "be determined the device counts as fresh, so an unreadable timestamp can never " +
                          "silence the station."
            }
        }

        section("Logging") {
            input "enableLogging", "bool", title: "Enable Debug Logging", defaultValue: false
        }

        section("Manual Control") {
            input "sendNow",  "button", title: "Send Now"
            input "diagNow",  "button", title: "Diagnose Device"
        }
    }
}

private String statusText() {
    if (!state.lastAttempt) return "No send attempted yet. Save the app, then press Send Now."
    def line = "Last attempt: ${state.lastAttempt}\n"
    line += state.lastOk ? "Result: DELIVERED (packets acknowledged: ${state.lastAccepted})\n"
                         : "Result: FAILED — ${state.lastError}\n"
    if (state.lastDelivered) line += "Last confirmed delivery: ${state.lastDelivered}\n"
    if (state.lastPacket) line += "Packet: ${state.lastPacket}\n"
    line += hasRainGauge ? "Rain: reported" : "Rain: no gauge, fields omitted"
    return line
}

private List attributeOptions(dev) {
    try { return dev.supportedAttributes.collect { it.name }.unique().sort() }
    catch (e) { return [] }
}

private String pick(List opts, List candidates) {
    return candidates.find { opts.contains(it) }
}

def appButtonHandler(btn) {
    if (btn == "sendNow") { log.info "APRS: manual send triggered"; sendWeatherReport() }
    if (btn == "diagNow") { diagnose() }
}

// ---------------------------------------------------------------------------
// Lifecycle
// ---------------------------------------------------------------------------

def installed()   { log.info "APRS Weather Reporter installed"; initialize() }
def updated()     { log.info "APRS Weather Reporter updated"; unschedule(); initialize() }
def uninstalled() { unschedule(); log.info "APRS Weather Reporter uninstalled" }

def initialize() {
    def interval = (reportInterval ?: "10").toInteger()
    if      (interval == 10) runEvery10Minutes("sendWeatherReport")
    else if (interval == 15) runEvery15Minutes("sendWeatherReport")
    else if (interval == 30) runEvery30Minutes("sendWeatherReport")
    log.info "APRS Weather Reporter scheduled every ${interval} minutes"
    if ((aprsPort as Integer) == 14580) {
        log.warn "APRS: port 14580 is the raw TCP APRS-IS port and does not speak HTTP. " +
                 "Submissions will time out with HTTP 408. Set the port to 8080."
    }
}

// ---------------------------------------------------------------------------
// Diagnostics
// ---------------------------------------------------------------------------

def diagnose() {
    if (!wxDevice) { log.warn "APRS diagnose: no weather device selected"; return }
    def devs = [wxDevice, tempDevice, pressureDevice, rainDevice].findAll { it != null }.unique { it.id }
    devs.each { dev ->
        def attrs = attributeOptions(dev)
        log.info "APRS diagnose: device '${dev.displayName}' exposes ${attrs.size()} attribute(s)"
        attrs.each { a ->
            def v = null
            try { v = dev.currentValue(a) } catch (ignored) { }
            // Only the attributes carrying a value are worth reading; the rest
            // are driver placeholders and just bury the log.
            if (v != null) log.info "APRS diagnose:   ${dev.displayName}.${a} = ${v}"
        }
        def empty = attrs.findAll { a -> try { dev.currentValue(a) == null } catch (ignored) { true } }
        log.info "APRS diagnose:   (${empty.size()} attribute(s) null on this device: ${empty.join(', ')})"
        def flags = []
        ["orphaned", "orphanedTemp", "orphanedWind", "orphanedRain"].each { f ->
            try { def v = dev.currentValue(f); if (v != null) flags << "${f}=${v}" } catch (ignored) { }
        }
        if (flags) log.info "APRS diagnose:   driver orphan flags: ${flags.join(', ')}"
        def age = deviceAgeMinutes(dev)
        log.info "APRS diagnose:   last activity: " +
                 (age == null ? "UNKNOWN — the staleness check will treat this device as fresh"
                              : "${age} minute(s) ago" + (isStale(dev) ? "  *** STALE ***" : ""))
    }
    log.info "APRS diagnose: hub temperature scale = ${location.temperatureScale}"
    log.info "APRS diagnose: rain gauge = ${hasRainGauge ? 'configured' : 'NONE — rain fields omitted from the packet'}"
    log.info "APRS diagnose: packet that would be sent right now -> ${buildPacket()}"
}

// ---------------------------------------------------------------------------
// Send
// ---------------------------------------------------------------------------

def sendWeatherReport() {
    try {
        def packet = buildPacket()
        if (packet == null) return

        def login = "user ${callsign} pass ${passcode} vers HubitatAPRS 2.6"
        def body  = "${login}\r\n${packet}\r\n"

        state.lastPacket  = packet
        state.lastAttempt = new Date().format("yyyy-MM-dd HH:mm:ss z", location.timeZone)

        if (enableLogging) log.debug "APRS body:\n${body}"

        def params = [
            uri               : "http://${aprsServer}:${aprsPort}",
            path              : "/",
            headers           : [ "Accept-Type": "text/plain", "Accept": "text/plain" ],
            requestContentType: "application/octet-stream",
            contentType       : "text/plain",
            body              : body,
            timeout           : 30
        ]

        asynchttpPost("aprsResponse", params, [packet: packet])

    } catch (Exception e) {
        state.lastOk = false
        state.lastError = "exception building/submitting: ${e.message}"
        log.error "APRS Weather Reporter error: ${e.message}"
    }
}

def aprsResponse(resp, data) {
    def status = null
    try { status = resp.status } catch (ignored) { }

    if (resp.hasError()) {
        state.lastOk = false
        state.lastError = explainStatus(status, resp.getErrorMessage())
        log.error "APRS send FAILED: ${state.lastError}"
        return
    }

    // X-Packetsrcvd is optional in practice. aprsc sends it; several Tier-2
    // servers answer a good submit with a bare 200 and no header at all.
    def rcvd = null
    try { resp.headers?.each { k, v -> if (k?.toLowerCase() == "x-packetsrcvd") rcvd = v } } catch (ignored) { }
    def count = (rcvd?.toString()?.isInteger()) ? rcvd.toString().toInteger() : null

    // An HTML body means we reached a web status page, not the submit endpoint.
    def body = ""
    try { body = resp.data?.toString() ?: "" } catch (ignored) { }
    def isWebPage = body.toLowerCase().contains("<html") || body.toLowerCase().contains("<!doctype")

    if (count != null && count < 1) {
        state.lastOk = false
        state.lastError = "HTTP ${status}, X-Packetsrcvd=0 — the server parsed the submission but accepted no packets. " +
                          "That is what an unverified client sees, so check the passcode."
        log.warn "APRS send REJECTED: ${state.lastError}"
        return
    }

    if (status != null && status >= 200 && status < 300 && !isWebPage) {
        state.lastOk = true
        state.lastAccepted = (count != null) ? count : "not reported by this server"
        state.lastError = null
        state.lastDelivered = new Date().format("yyyy-MM-dd HH:mm:ss z", location.timeZone)
        if (enableLogging) log.debug "APRS response body: ${body}"
        log.info "APRS packet DELIVERED (HTTP ${status}, packets acknowledged: ${state.lastAccepted}): ${data.packet}"
        return
    }

    state.lastOk = false
    state.lastError = isWebPage
        ? "HTTP ${status} returned an HTML page — this port is a web status page, not the APRS-IS submit endpoint. Check the server and port."
        : explainStatus(status, "unexpected response")
    log.warn "APRS send NOT CONFIRMED: ${state.lastError}"
}

/** Meanings observed against live Tier-2 servers, not guesses. */
private String explainStatus(status, detail) {
    def hint
    switch (status) {
        case 403:
            hint = "the submit endpoint rejected the login. The passcode is computed from the BASE callsign with the SSID stripped."
            break
        case 404:
            hint = "this server has no HTTP submit endpoint on this port. rotate.aprs2.net:8080 does; noam.aprs2.net:8080 does not."
            break
        case 408:
            hint = "nothing answered HTTP on this port. APRS-IS HTTP submit is port 8080 — 14580 is the raw TCP port and will always time out here."
            break
        default:
            hint = detail
    }
    return "HTTP ${status}: ${hint}"
}

// ---------------------------------------------------------------------------
// Packet construction
// ---------------------------------------------------------------------------

private String buildPacket() {
    def tempSrc = tempDevice ?: wxDevice
    if (isOrphaned(tempSrc, "Temp")) {
        state.lastOk = false
        state.lastError = "temperature source '${tempSrc?.displayName}' is flagged ORPHANED by its driver. " +
                          "Nothing sent — the last value it published is frozen, and a frozen reading on the " +
                          "air reads as current to everyone downstream."
        log.warn "APRS: ${state.lastError}"
        return null
    }
    if (isStale(tempSrc)) {
        def age = deviceAgeMinutes(tempSrc)
        state.lastOk = false
        state.lastError = "temperature source '${tempSrc?.displayName}' has had no activity for ${age} minutes " +
                          "(limit ${staleMinutes}). Nothing sent — a frozen reading on the air reads as current " +
                          "to everyone downstream, so silence is the honest failure."
        log.warn "APRS: ${state.lastError}"
        return null
    }

    def tempRaw = attrVal(tempSrc, attrTemp ?: "temperature")
    if (tempRaw == null) {
        state.lastOk = false
        state.lastError = "temperature attribute returned null on " +
                          "'${(tempDevice ?: wxDevice)?.displayName}'; nothing sent"
        log.warn "APRS: temperature not available, skipping report"
        return null
    }

    // APRS temperature is always whole degrees Fahrenheit.
    def tempF = (location.temperatureScale == "C") ? (tempRaw.toBigDecimal() * 9 / 5 + 32) : tempRaw.toBigDecimal()

    def latStr = formatLatitude(stationLat as BigDecimal)
    def lonStr = formatLongitude(stationLon as BigDecimal)

    def cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
    def timestamp = String.format(Locale.US, "%02d%02d%02dz",
        cal.get(Calendar.DAY_OF_MONTH), cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))

    def pSrc = pressureDevice ?: wxDevice
    def rSrc = rainDevice ?: wxDevice
    if (isStale(wxDevice))              log.warn "APRS: '${wxDevice.displayName}' has gone quiet — humidity and wind dropped from this packet"
    if (isOrphaned(wxDevice, "Wind"))   log.warn "APRS: '${wxDevice.displayName}' wind sensor is ORPHANED — wind dropped from this packet"
    if (isOrphaned(wxDevice, "Temp"))   log.warn "APRS: '${wxDevice.displayName}' temp/humidity sensor is ORPHANED — humidity dropped from this packet"
    if (isStale(pSrc) || isOrphaned(pSrc, null)) log.warn "APRS: '${pSrc.displayName}' is unavailable — pressure dropped from this packet"

    def wx = new StringBuilder()
    wx << three(freshVal(wxDevice, attrWindDir, "Wind"), "...")          // wind direction
    wx << "/"
    wx << three(freshVal(wxDevice, attrWindSpd, "Wind"), "...")          // sustained wind speed, mph
    wx << "g" << three(freshVal(wxDevice, attrWindGust, "Wind"), "...")  // gust, mph
    wx << "t" << tempField(tempF)                     // temperature, whole degF, signed
    // With no rain gauge the r/p/P fields are omitted entirely. Sending
    // "r...p..." would claim a sensor that reports nothing; leaving them out
    // says the station does not measure rainfall. Both are valid APRS, but
    // only one is true here.
    if (hasRainGauge) {
        wx << "r" << three(rainHundredths(freshVal(rSrc, attrRain1h, "Rain")),  "...")
        wx << "p" << three(rainHundredths(freshVal(rSrc, attrRain24h, "Rain")), "...")
        def rainMid = rainHundredths(freshVal(rSrc, attrRainMid, "Rain"))
        if (rainMid != null) wx << "P" << three(rainMid, "...")
    }
    wx << "h" << humidityField(freshVal(wxDevice, attrHum, "Temp"))
    wx << "b" << pressureField(freshVal(pSrc, attrPressure))

    return "${callsign}>APRS,TCPIP*:@${timestamp}${latStr}/${lonStr}_${wx} ${stationDesc}"
}

/**
 * Minutes since this device last did anything, or null when that cannot be
 * determined. Null means UNKNOWN, and every caller must treat unknown as
 * fresh — refusing to transmit because a timestamp was unreadable would be a
 * worse failure than the one this check exists to prevent.
 */
private Long deviceAgeMinutes(dev) {
    if (!dev) return null
    def when = null
    try { when = dev.getLastActivity() } catch (ignored) { }
    if (when == null) {
        // Fall back to a fast-moving attribute only. A slow one is useless
        // here: an unchanged value records no event, so a steady reading is
        // indistinguishable from a dead sensor.
        for (String a : ["temperature", "humidity", "pressure", "illuminance"]) {
            try {
                def st = dev.currentState(a)
                if (st?.date != null) { when = st.date; break }
            } catch (ignored) { }
        }
    }
    if (when == null) return null
    try { return (long) ((now() - when.getTime()) / 60000L) }
    catch (ignored) { return null }
}

/**
 * Ecowitt drivers publish their own staleness flags — orphanedWind,
 * orphanedTemp, orphanedRain, plus a generic 'orphaned' — raised when a
 * sub-sensor stops reporting. This beats every timestamp heuristic, because a
 * sub-sensor dies while its parent device stays alive: the WH69's wind unit
 * can go silent while the same device publishes temperature every minute.
 * Prefer the specific flag, fall back to the generic, ignore both if absent.
 */
private boolean isOrphaned(dev, String kind) {
    if (!orphanGuard || !dev) return false
    def names = kind ? ["orphaned${kind}", "orphaned"] : ["orphaned"]
    for (String a : names) {
        try {
            def v = dev.currentValue(a)
            if (v != null) return v.toString().toLowerCase().contains("true")
        } catch (ignored) { }
    }
    return false
}

private boolean isStale(dev) {
    if (!staleCheckEnabled || !dev) return false
    def age = deviceAgeMinutes(dev)
    if (age == null) return false          // unknown age is never grounds for silence
    return age > ((staleMinutes ?: 60) as Long)
}

/**
 * Attribute value, or null when its source has gone stale or the driver has
 * flagged that sub-sensor as orphaned. `kind` picks the orphan flag: "Wind",
 * "Temp", "Rain", or null for the device-wide one.
 */
private def freshVal(dev, String attr, String kind = null) {
    if (isStale(dev)) return null
    if (isOrphaned(dev, kind)) return null
    return attrVal(dev, attr)
}

private def attrVal(dev, String attr) {
    if (!attr || !dev) return null
    try { return dev.currentValue(attr) }
    catch (Exception e) {
        if (enableLogging) log.debug "APRS: attribute '${attr}' not available"
        return null
    }
}

/** Three-digit unsigned field, or the supplied placeholder when unavailable. */
private String three(def v, String placeholder) {
    if (v == null) return placeholder
    try {
        def n = Math.round(v.toBigDecimal().toDouble()) as long
        if (n < 0)    n = 0
        if (n > 999)  n = 999
        return String.format(Locale.US, "%03d", n)
    } catch (Exception e) { return placeholder }
}

/**
 * APRS temperature: three characters. Non-negative values are zero-padded
 * ("065"); negative values use a leading minus and two digits ("-05").
 * The old code ran String.format("%03d", -5) and emitted "0-5".
 */
private String tempField(def v) {
    try {
        def n = Math.round(v.toBigDecimal().toDouble()) as long
        if (n >= 0)  return String.format(Locale.US, "%03d", Math.min(n, 999L))
        return String.format(Locale.US, "-%02d", Math.min(-n, 99L))
    } catch (Exception e) { return "..." }
}

/** APRS humidity: two digits, with 100% encoded as "00". Dots when unknown. */
private String humidityField(def v) {
    if (v == null) return ".."
    try {
        def h = Math.round(v.toBigDecimal().toDouble()) as long
        if (h >= 100) return "00"
        if (h < 0)    return ".."
        return String.format(Locale.US, "%02d", h)
    } catch (Exception e) { return ".." }
}

/** APRS barometric pressure: five digits, tenths of a millibar (hPa x 10). */
private String pressureField(def v) {
    if (v == null) return "....."
    try {
        def p = v.toBigDecimal().toDouble()
        def hpa
        if (pressureUnit == "inhg")      hpa = p * 33.8639
        else if (pressureUnit == "hpa")  hpa = p
        else                             hpa = (p < 100) ? p * 33.8639 : p   // auto: inHg readings are ~29.9
        def tenths = Math.round(hpa * 10) as long
        if (tenths < 0 || tenths > 99999) return "....."
        return String.format(Locale.US, "%05d", tenths)
    } catch (Exception e) { return "....." }
}

/** APRS rain fields are hundredths of an inch. */
private def rainHundredths(def v) {
    if (v == null) return null
    try {
        def r = v.toBigDecimal().toDouble()
        if (rainUnit == "mm") r = r / 25.4
        return Math.round(r * 100)
    } catch (Exception e) { return null }
}

private String formatLatitude(BigDecimal lat) {
    def hemi    = (lat >= 0) ? "N" : "S"
    def absLat  = lat.abs()
    def degrees = absLat.toInteger()
    def minutes = (absLat - degrees) * 60
    return String.format(Locale.US, "%02d%05.2f%s", degrees, minutes, hemi)
}

private String formatLongitude(BigDecimal lon) {
    def hemi    = (lon >= 0) ? "E" : "W"
    def absLon  = lon.abs()
    def degrees = absLon.toInteger()
    def minutes = (absLon - degrees) * 60
    return String.format(Locale.US, "%03d%05.2f%s", degrees, minutes, hemi)
}
