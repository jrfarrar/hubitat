/**
 * APRS Weather Reporter
 * Sends Ecowitt weather data from Hubitat to APRS-IS
 *
 * Author: K0JRF
 * Version: 1.2 - Added manual "Send Now" button
 *
 * Install as a Hubitat App (Apps > Add User App)
 * Requires: Ecowitt device already configured in Hubitat
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

def mainPage() {
    dynamicPage(name: "mainPage", title: "APRS Weather Reporter", install: true, uninstall: true) {

        section("APRS-IS Settings") {
            input "callsign",    "text",   title: "Callsign (e.g. K0JRF-13)",  required: true
            input "passcode",    "number", title: "APRS-IS Passcode",           required: true
            input "aprsServer",  "text",   title: "APRS-IS Server",             required: true, defaultValue: "rotate.aprs2.net"
            input "aprsPort",    "number", title: "APRS-IS Port",               required: true, defaultValue: 14580
        }

        section("Station Location") {
            input "stationLat",  "decimal", title: "Latitude (decimal, e.g. 42.031)",    required: true
            input "stationLon",  "decimal", title: "Longitude (decimal, e.g. -80.255)",  required: true
            input "stationDesc", "text",    title: "Station Description",                required: true, defaultValue: "Wx Station Fairview PA"
        }

        section("Ecowitt Device") {
            input "wxDevice", "capability.temperatureMeasurement", title: "Select Ecowitt Weather Device", required: true
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
            input "sendNow", "button", title: "Send Now"
        }
    }
}

def appButtonHandler(btn) {
    if (btn == "sendNow") {
        log.info "APRS: Manual send triggered"
        sendWeatherReport()
    }
}

def installed() {
    log.info "APRS Weather Reporter installed"
    initialize()
}

def updated() {
    log.info "APRS Weather Reporter updated"
    unschedule()
    initialize()
}

def uninstalled() {
    unschedule()
    log.info "APRS Weather Reporter uninstalled"
}

def initialize() {
    def interval = reportInterval.toInteger()
    if      (interval == 10) runEvery10Minutes("sendWeatherReport")
    else if (interval == 15) runEvery15Minutes("sendWeatherReport")
    else if (interval == 30) runEvery30Minutes("sendWeatherReport")
    log.info "APRS Weather Reporter scheduled every ${interval} minutes"
}

def sendWeatherReport() {
    try {
        // --- Gather sensor values ---
        def temp      = getAttrValue("temperature")
        def humidity  = getAttrValue("humidity")
        def windDir   = getAttrValue("windDirection")
        def windSpeed = getAttrValue("windSpeed")
        def windGust  = getAttrValue("windGust")

        if (temp == null) {
            log.warn "APRS: Temperature not available, skipping report"
            return
        }

        // --- Format APRS position ---
        def latStr = formatLatitude(stationLat as BigDecimal)
        def lonStr = formatLongitude(stationLon as BigDecimal)

        // --- Timestamp DDHHMMz UTC ---
        def cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        def day  = String.format("%02d", cal.get(Calendar.DAY_OF_MONTH))
        def hour = String.format("%02d", cal.get(Calendar.HOUR_OF_DAY))
        def min  = String.format("%02d", cal.get(Calendar.MINUTE))
        def timestamp = "${day}${hour}${min}z"

        // --- Format weather fields ---
        def wdirStr = (windDir   != null) ? String.format("%03d", windDir.toInteger())   : "..."
        def wspStr  = (windSpeed != null) ? String.format("%03d", windSpeed.toInteger()) : "..."
        def wgstStr = (windGust  != null) ? String.format("%03d", windGust.toInteger())  : "..."
        def tempStr = String.format("%03d", temp.toInteger())
        def humStr  = "  "
        if (humidity != null) {
            def h = humidity.toInteger()
            humStr = (h >= 100) ? "00" : String.format("%02d", h)
        }

        // No baro or rainfall — use dots as placeholders per APRS spec
        def wxData = "${wdirStr}/${wspStr}g${wgstStr}t${tempStr}r...p...h${humStr}b....."
        def packet = "${callsign}>APRS,TCPIP*:@${timestamp}${latStr}/${lonStr}_${wxData} ${stationDesc}"

        if (enableLogging) log.debug "APRS Packet: ${packet}"

        // --- Send login + packet via Hubitat raw socket ---
        def loginLine  = "user ${callsign} pass ${passcode} vers HubitatAPRS 1.1\r\n"
        def packetLine = "${packet}\r\n"

        def hubAction = new hubitat.device.HubAction(
            loginLine + packetLine,
            hubitat.device.Protocol.RAW_LAN,
            [
                destinationAddress: "${aprsServer}:${aprsPort}",
                type               : hubitat.device.HubAction.Type.LAN_TYPE_RAW,
                encoding           : hubitat.device.HubAction.Encoding.HEX_STRING,
                parseWarning       : false
            ]
        )

        sendHubCommand(hubAction)
        log.info "APRS weather packet sent: ${packet}"

    } catch (Exception e) {
        log.error "APRS Weather Reporter error: ${e.message}"
    }
}

// --- Helper: get device attribute safely ---
private def getAttrValue(String attr) {
    try {
        return wxDevice.currentValue(attr)
    } catch (Exception e) {
        if (enableLogging) log.debug "APRS: attribute '${attr}' not available"
        return null
    }
}

// --- Helper: format latitude for APRS (DDMM.hhN) ---
private String formatLatitude(BigDecimal lat) {
    def hemi    = (lat >= 0) ? "N" : "S"
    def absLat  = lat.abs()
    def degrees = absLat.toInteger()
    def minutes = (absLat - degrees) * 60
    return String.format("%02d%05.2f%s", degrees, minutes, hemi)
}

// --- Helper: format longitude for APRS (DDDMM.hhW) ---
private String formatLongitude(BigDecimal lon) {
    def hemi    = (lon >= 0) ? "E" : "W"
    def absLon  = lon.abs()
    def degrees = absLon.toInteger()
    def minutes = (absLon - degrees) * 60
    return String.format("%03d%05.2f%s", degrees, minutes, hemi)
}