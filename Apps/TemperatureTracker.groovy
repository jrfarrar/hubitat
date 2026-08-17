/**
 *  Temperature Tracker
 *
 *  Tracks temperature readings over time from one or more selected sensors.
 *  Each sensor is tracked individually with monthly average, min, and max
 *  temperatures as well as yearly extremes. Data is stored in efficient
 *  monthly buckets.
 *
 *  Author:  J.R. (K0JRF)
 *  Date:    2026-02-23
 */

definition(
    name: "Temperature Tracker",
    namespace: "k0jrf",
    author: "J.R.",
    description: "Track temperature over time with monthly averages and yearly min/max statistics for multiple sensors",
    category: "Convenience",
    iconUrl: "",
    iconX2Url: "",
    iconX3Url: ""
)

preferences {
    page(name: "mainPage")
    page(name: "sensorDetailPage")
}

def mainPage() {
    dynamicPage(name: "mainPage", title: "Temperature Tracker", install: true, uninstall: true) {
        section("Temperature Sensors") {
            input "tempSensors", "capability.temperatureMeasurement", title: "Select Temperature Sensors", required: true, multiple: true
            input "pollInterval", "enum", title: "Additional Polling Interval (besides events)",
                options: ["None", "5 Minutes", "15 Minutes", "30 Minutes", "1 Hour"],
                defaultValue: "None", required: false
        }
        section("Settings") {
            input "tempScale", "enum", title: "Temperature Scale", options: ["F", "C"], defaultValue: "F", required: true
            input "enableLogging", "bool", title: "Enable Debug Logging", defaultValue: false
        }

        if (state.sensorData && !state.sensorData.isEmpty()) {
            section("Sensors") {
                def sensorIds = state.sensorData.keySet().sort { a, b ->
                    getSensorName(a).toLowerCase() <=> getSensorName(b).toLowerCase()
                }
                sensorIds.each { sId ->
                    def sName = getSensorName(sId)
                    def device = tempSensors?.find { it.id.toString() == sId }
                    def liveTemp = device?.currentValue("temperature")
                    def desc = liveTemp != null ? "Currently ${liveTemp}°${tempScale}" : "Tap for details"
                    href(name: "detail_${sId}", page: "sensorDetailPage",
                        title: "${sName}", description: desc,
                        params: [sensorId: sId])
                }
            }
        } else {
            section("Statistics") {
                paragraph "No data collected yet. Statistics will appear here once sensors report temperatures."
            }
        }

        section("Data Management") {
            input "resetData", "bool", title: "Reset All Data (toggle on, then tap Done)", defaultValue: false, submitOnChange: true
        }
    }
}

def sensorDetailPage(params) {
    def sensorId = params?.sensorId ?: state.lastViewedSensor
    if (sensorId) {
        state.lastViewedSensor = sensorId
    }
    def sName = getSensorName(sensorId)
    def device = tempSensors?.find { it.id.toString() == sensorId }
    def liveTemp = device?.currentValue("temperature")

    dynamicPage(name: "sensorDetailPage", title: "${sName}") {
        section("Current") {
            def currentText = liveTemp != null ? "📍 <b>Current:</b> ${liveTemp}°${tempScale}" : "No live reading available"
            def readingCount = getTotalReadings(sensorId)
            paragraph "${currentText}\n📊 <b>Total Readings:</b> ${readingCount}"
        }
        section("Yearly Extremes") {
            paragraph getSensorYearlySummary(sensorId)
        }
        section("Monthly Breakdown") {
            paragraph getSensorMonthlyDetail(sensorId)
        }
    }
}

// ==================== LIFECYCLE ====================

def installed() {
    logDebug("Temperature Tracker installed")
    initialize()
}

def updated() {
    logDebug("Temperature Tracker updated")
    unsubscribe()
    unschedule()

    if (resetData) {
        log.info "Temperature Tracker: Resetting all data"
        state.sensorData = [:]
        app.updateSetting("resetData", [type: "bool", value: false])
    }

    initialize()
}

def uninstalled() {
    logDebug("Temperature Tracker uninstalled")
    unsubscribe()
    unschedule()
}

def initialize() {
    if (state.sensorData == null) {
        state.sensorData = [:]
    }

    // Subscribe to all selected sensors
    tempSensors.each { sensor ->
        subscribe(sensor, "temperature", temperatureHandler)
    }

    // Set up optional polling
    switch (pollInterval) {
        case "5 Minutes":
            runEvery5Minutes(pollTemperatures)
            break
        case "15 Minutes":
            runEvery15Minutes(pollTemperatures)
            break
        case "30 Minutes":
            runEvery30Minutes(pollTemperatures)
            break
        case "1 Hour":
            runEvery1Hour(pollTemperatures)
            break
    }

    // Schedule monthly maintenance at midnight on the 1st
    schedule("0 0 0 1 * ?", monthlyMaintenance)

    // Clean up any sensors that have been removed from the selection
    pruneRemovedSensors()

    // Grab current readings right away
    tempSensors.each { sensor ->
        def currentTemp = sensor.currentValue("temperature")
        if (currentTemp != null) {
            recordTemperature(sensor.id.toString(), sensor.displayName, currentTemp as BigDecimal)
        }
    }

    log.info "Temperature Tracker: Initialized - monitoring ${tempSensors.size()} sensor(s)"
}

// ==================== EVENT HANDLERS ====================

def temperatureHandler(evt) {
    def sensorId = evt.device.id.toString()
    def sensorName = evt.device.displayName
    def temp = evt.value as BigDecimal
    logDebug("Temperature event from ${sensorName}: ${temp}°${tempScale}")
    recordTemperature(sensorId, sensorName, temp)
}

def pollTemperatures() {
    tempSensors.each { sensor ->
        def temp = sensor.currentValue("temperature")
        if (temp != null) {
            logDebug("Polled ${sensor.displayName}: ${temp}°${tempScale}")
            recordTemperature(sensor.id.toString(), sensor.displayName, temp as BigDecimal)
        }
    }
}

// ==================== DATA RECORDING ====================

def recordTemperature(String sensorId, String sensorName, BigDecimal temp) {
    def now = new Date()
    def monthKey = now.format("yyyy-MM")

    def allData = state.sensorData ?: [:]

    if (allData[sensorId] == null) {
        allData[sensorId] = [name: sensorName, months: [:]]
    }

    // Update sensor name in case it changed
    allData[sensorId].name = sensorName

    def months = allData[sensorId].months ?: [:]
    def bucket = months[monthKey]

    if (bucket == null) {
        bucket = [
            count: 0,
            sum: 0.0,
            min: temp,
            max: temp,
            minDate: now.format("yyyy-MM-dd HH:mm"),
            maxDate: now.format("yyyy-MM-dd HH:mm"),
            firstReading: now.format("yyyy-MM-dd HH:mm"),
            lastReading: now.format("yyyy-MM-dd HH:mm")
        ]
    }

    bucket.count = (bucket.count ?: 0) + 1
    bucket.sum = (bucket.sum ?: 0.0) + temp

    if (temp < (bucket.min as BigDecimal)) {
        bucket.min = temp
        bucket.minDate = now.format("yyyy-MM-dd HH:mm")
    }
    if (temp > (bucket.max as BigDecimal)) {
        bucket.max = temp
        bucket.maxDate = now.format("yyyy-MM-dd HH:mm")
    }
    bucket.lastReading = now.format("yyyy-MM-dd HH:mm")

    months[monthKey] = bucket
    allData[sensorId].months = months
    state.sensorData = allData

    logDebug("Recorded ${temp}° for ${sensorName} [${monthKey}] (count: ${bucket.count})")
}

// ==================== DISPLAY ====================

int getTotalReadings(String sensorId) {
    def allData = state.sensorData
    def sData = allData ? allData[sensorId] : null
    if (!sData || !sData.months) return 0
    def total = 0
    sData.months.each { key, bucket -> total += (bucket.count ?: 0) }
    return total
}

String getSensorYearlySummary(String sensorId) {
    def allData = state.sensorData
    def sData = allData ? allData[sensorId] : null
    if (!sData || !sData.months || sData.months.isEmpty()) return "No data for this sensor."

    def sb = new StringBuilder()
    def months = sData.months

    def years = months.keySet().collect { it.substring(0, 4) }.unique().sort().reverse()

    years.each { year ->
        def yearMin = null
        def yearMax = null
        def yearMinDate = ""
        def yearMaxDate = ""

        months.each { key, bucket ->
            if (key.startsWith(year)) {
                def bMin = bucket.min as BigDecimal
                def bMax = bucket.max as BigDecimal
                if (yearMin == null || bMin < yearMin) {
                    yearMin = bMin
                    yearMinDate = bucket.minDate
                }
                if (yearMax == null || bMax > yearMax) {
                    yearMax = bMax
                    yearMaxDate = bucket.maxDate
                }
            }
        }

        if (yearMin != null) {
            sb.append("<b>── ${year} ──</b>\n")
            sb.append("🔽 Min: ${formatTemp(yearMin)} on ${yearMinDate}\n")
            sb.append("🔼 Max: ${formatTemp(yearMax)} on ${yearMaxDate}\n\n")
        }
    }

    return sb.toString()
}

String getSensorMonthlyDetail(String sensorId) {
    def allData = state.sensorData
    def sData = allData ? allData[sensorId] : null
    if (!sData || !sData.months || sData.months.isEmpty()) return "No monthly data for this sensor."

    def sb = new StringBuilder()
    def months = sData.months

    def sortedKeys = months.keySet().sort().reverse()
    def years = sortedKeys.collect { it.substring(0, 4) }.unique()

    years.each { year ->
        sb.append("<b>═══════ ${year} ═══════</b>\n")
        def yearKeys = sortedKeys.findAll { it.startsWith(year) }

        yearKeys.each { key ->
            def bucket = months[key]
            def count = bucket.count ?: 0
            if (count == 0) return

            def avg = (bucket.sum as BigDecimal) / count
            def monthName = getMonthName(key)

            sb.append("\n<b>${monthName}</b> (${count} readings)\n")
            sb.append("  Avg: ${formatTemp(avg)}  ")
            sb.append("Min: ${formatTemp(bucket.min as BigDecimal)}  ")
            sb.append("Max: ${formatTemp(bucket.max as BigDecimal)}\n")
            sb.append("  Min on: ${bucket.minDate}  Max on: ${bucket.maxDate}\n")
        }
        sb.append("\n")
    }

    return sb.toString()
}

// ==================== HELPER METHODS ====================

String getSensorName(String sensorId) {
    def allData = state.sensorData
    if (allData && allData[sensorId]) {
        return allData[sensorId].name ?: "Sensor ${sensorId}"
    }
    def device = tempSensors?.find { it.id.toString() == sensorId }
    return device?.displayName ?: "Sensor ${sensorId}"
}

String getMonthName(String monthKey) {
    def months = [
        "01": "January", "02": "February", "03": "March",
        "04": "April", "05": "May", "06": "June",
        "07": "July", "08": "August", "09": "September",
        "10": "October", "11": "November", "12": "December"
    ]
    def parts = monthKey.split("-")
    return months[parts[1]] ?: monthKey
}

String formatTemp(BigDecimal temp) {
    return String.format("%.1f°%s", temp, tempScale)
}

void pruneRemovedSensors() {
    def allData = state.sensorData ?: [:]
    def activeIds = tempSensors?.collect { it.id.toString() } ?: []

    allData.keySet().toList().each { sId ->
        if (!activeIds.contains(sId)) {
            log.info "Temperature Tracker: Removing data for deselected sensor '${allData[sId].name}'"
            allData.remove(sId)
        }
    }

    state.sensorData = allData
}

def monthlyMaintenance() {
    logDebug("Monthly maintenance check")
    def cutoff = new Date() - 730
    def cutoffKey = cutoff.format("yyyy-MM")
    def allData = state.sensorData ?: [:]
    def pruned = false

    allData.each { sId, sData ->
        def months = sData.months ?: [:]
        months.keySet().toList().each { key ->
            if (key < cutoffKey) {
                log.info "Temperature Tracker: Pruning old data for ${sData.name} [${key}]"
                months.remove(key)
                pruned = true
            }
        }
        sData.months = months
    }

    if (pruned) {
        state.sensorData = allData
    }
}

void logDebug(String msg) {
    if (enableLogging) {
        log.debug "Temperature Tracker: ${msg}"
    }
}
