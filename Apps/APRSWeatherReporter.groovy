/**
 * APRS Weather Reporter
 * Sends Ecowitt weather data from Hubitat to APRS-IS
 *
 * Author: K0JRF
 * Version: 2.0
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
            input "aprsServer", "text",   title: "APRS-IS Server", required: true, defaultValue: "noam.aprs2.net"
            input "aprsPort",   "number", title: "APRS-IS HTTP submit port", required: true, defaultValue: 8080
        }

        section("Station Location") {
            input "stationLat",  "decimal", title: "Latitude (decimal, e.g. 42.031)",   required: true
            input "stationLon",  "decimal", title: "Longitude (decimal, e.g. -80.255)", required: true
            input "stationDesc", "text",    title: "Station Description", required: true, defaultValue: "Wx Station Fairview PA"
        }

        section("Ecowitt Device") {
            input "wxDevice", "capability.temperatureMeasurement", title: "Select Ecowitt Weather Device",
                required: true, submitOnChange: true
        }

        if (wxDevice) {
            def opts = attributeOptions()
            section("Attribute Mapping") {
                paragraph "Pick the attribute on '${wxDevice.displayName}' that supplies each APRS field. " +
                          "Leave one blank to omit that field (it is sent as dots, which is valid APRS). " +
                          "Use the Diagnose button below to dump every attribute and its current value to the log."
                input "attrTemp",     "enum", title: "Temperature",              options: opts, required: false, defaultValue: pick(opts, ["temperature"])
                input "attrHum",      "enum", title: "Humidity",                 options: opts, required: false, defaultValue: pick(opts, ["humidity"])
                input "attrWindDir",  "enum", title: "Wind direction (degrees)", options: opts, required: false, defaultValue: pick(opts, ["windDirection", "windDir"])
                input "attrWindSpd",  "enum", title: "Wind speed (mph)",         options: opts, required: false, defaultValue: pick(opts, ["windSpeed", "windAvg"])
                input "attrWindGust", "enum", title: "Wind gust (mph)",          options: opts, required: false, defaultValue: pick(opts, ["windGust", "gustSpeed"])
                input "attrPressure", "enum", title: "Barometric pressure",      options: opts, required: false, defaultValue: pick(opts, ["pressure", "barometricPressure", "baromRelIn", "baromRel"])
                input "attrRain1h",   "enum", title: "Rain, last hour",          options: opts, required: false, defaultValue: pick(opts, ["rainHourly", "hourlyRain", "rainRate"])
                input "attrRain24h",  "enum", title: "Rain, last 24 hours",      options: opts, required: false, defaultValue: pick(opts, ["rain24", "rainDaily", "dailyRain"])
                input "attrRainMid",  "enum", title: "Rain since midnight (optional)", options: opts, required: false, defaultValue: pick(opts, ["rainDaily", "dailyRain"])
            }
            section("Units") {
                input "pressureUnit", "enum", title: "Pressure attribute is reported in",
                    options: ["auto": "Auto-detect", "inhg": "inches of mercury", "hpa": "hectopascals / millibars"],
                    required: true, defaultValue: "auto"
                input "rainUnit", "enum", title: "Rain attributes are reported in",
                    options: ["in": "inches", "mm": "millimetres"],
                    required: true, defaultValue: "in"
            }
        }

        section("Reporting Schedule") {
            input "reportInterval", "enum", title: "Report Every",
                options: ["10": "10 Minutes", "15": "15 Minutes", "30": "30 Minutes"],
                required: true, defaultValue: "10"
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
    line += state.lastOk ? "Result: DELIVERED — server acknowledged ${state.lastAccepted} packet(s)\n"
                         : "Result: FAILED — ${state.lastError}\n"
    if (state.lastPacket) line += "Packet: ${state.lastPacket}"
    return line
}

private List attributeOptions() {
    try { return wxDevice.supportedAttributes.collect { it.name }.unique().sort() }
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
}

// ---------------------------------------------------------------------------
// Diagnostics
// ---------------------------------------------------------------------------

def diagnose() {
    if (!wxDevice) { log.warn "APRS diagnose: no weather device selected"; return }
    def attrs = attributeOptions()
    log.info "APRS diagnose: device '${wxDevice.displayName}' exposes ${attrs.size()} attribute(s)"
    attrs.each { a ->
        def v = null
        try { v = wxDevice.currentValue(a) } catch (ignored) { }
        log.info "APRS diagnose:   ${a} = ${v}"
    }
    log.info "APRS diagnose: hub temperature scale = ${location.temperatureScale}"
    log.info "APRS diagnose: packet that would be sent right now -> ${buildPacket()}"
}

// ---------------------------------------------------------------------------
// Send
// ---------------------------------------------------------------------------

def sendWeatherReport() {
    try {
        def packet = buildPacket()
        if (packet == null) return

        def login = "user ${callsign} pass ${passcode} vers HubitatAPRS 2.0"
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
        state.lastError = "HTTP ${status} ${resp.getErrorMessage()}"
        log.error "APRS send FAILED: ${state.lastError}"
        return
    }

    // Per the APRS-IS spec a successful submission is 204 with X-Packetsrcvd.
    def rcvd = null
    try { resp.headers?.each { k, v -> if (k?.toLowerCase() == "x-packetsrcvd") rcvd = v } } catch (ignored) { }

    if (status == 204 && rcvd?.toString()?.isInteger() && rcvd.toString().toInteger() > 0) {
        state.lastOk = true
        state.lastAccepted = rcvd
        state.lastError = null
        log.info "APRS packet DELIVERED (server accepted ${rcvd}): ${data.packet}"
    } else {
        state.lastOk = false
        state.lastError = "HTTP ${status}, X-Packetsrcvd=${rcvd}. Server did not confirm the packet. " +
                          "A 204 with X-Packetsrcvd=0 usually means the passcode is wrong (unverified clients are silently dropped)."
        log.warn "APRS send NOT CONFIRMED: ${state.lastError}"
    }
}

// ---------------------------------------------------------------------------
// Packet construction
// ---------------------------------------------------------------------------

private String buildPacket() {
    def tempRaw = attrVal(attrTemp ?: "temperature")
    if (tempRaw == null) {
        state.lastOk = false
        state.lastError = "temperature attribute returned null; nothing sent"
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

    def wx = new StringBuilder()
    wx << three(attrVal(attrWindDir), "...")          // wind direction
    wx << "/"
    wx << three(attrVal(attrWindSpd), "...")          // sustained wind speed, mph
    wx << "g" << three(attrVal(attrWindGust), "...")  // gust, mph
    wx << "t" << tempField(tempF)                     // temperature, whole degF, signed
    wx << "r" << three(rainHundredths(attrVal(attrRain1h)),  "...")
    wx << "p" << three(rainHundredths(attrVal(attrRain24h)), "...")
    if (attrRainMid) wx << "P" << three(rainHundredths(attrVal(attrRainMid)), "...")
    wx << "h" << humidityField(attrVal(attrHum))
    wx << "b" << pressureField(attrVal(attrPressure))

    return "${callsign}>APRS,TCPIP*:@${timestamp}${latStr}/${lonStr}_${wx} ${stationDesc}"
}

private def attrVal(String attr) {
    if (!attr) return null
    try { return wxDevice.currentValue(attr) }
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
