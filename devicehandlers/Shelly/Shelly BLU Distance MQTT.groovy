/**
 * Shelly BLU Distance MQTT Device Handler
 *
 * Modified from BI MQTT Motion by J.R. Farrar
 * Modified for Shelly BLU Distance sensor via BTHome format
 *
 * Expects MQTT topic format: BLE/{MAC_ADDRESS}
 * Expects BTHome JSON payload with service_data.distance
 *
 * 1.0.0 - 11/08/25 - Initial Release for Shelly BLU Distance
 * 1.1.0 - 11/08/25 - Added optional inches display
 * 1.2.0 - 11/08/25 - Added threshold-based switch capability
 * 1.3.0 - 11/24/25 - Added time delay requirement for threshold activation
 * 1.4.0 - 11/24/25 - Added Logic Inversion (Above/Below) and Quieted RSSI/LastUpdate chatter
 * 1.4.1 - 11/24/25 - Minor refinements: timer update throttling, improved descriptions, RSSI init
 * 1.4.2 - 11/24/25 - Added suppression of INFO logs for unchanged distance/battery values
 * 1.4.3 - 11/30/25 - Fixed cron expression error for watchDogTime values > 59 minutes
 * 1.4.4 - 11/30/25 - Added battery hysteresis to smooth out sensor noise/wild swings
 * 1.5.0 - 12/01/25 - Added configurable distance smoothing modes (Stable Reading, Time-Based Average, Outlier Rejection)
 * 1.6.0 - 01/07/26 - Added sensor health monitoring (detects when BLU sensor stops reporting)
 * 1.6.1 - 02/04/26 - Fixed connection recovery: watchdog survives reboots, detects stale connections,
 *                      non-blocking reconnect, explicit disconnect before reconnect
 * 1.6.2 - 02/05/26 - Fixed retained message false-positive: stale MQTT retained messages no longer
 *                      reset health status to online. Added lastMessageReceived attribute.
 *                      Added PresenceSensor capability (present/not present mirrors online/offline).
 */

metadata {
    definition (name: "Shelly BLU Distance MQTT", 
                namespace: "jrfarrar", 
                author: "J.R. Farrar",
                importUrl: "") {
        capability "Initialize"
        capability "Refresh"
        capability "Configuration"
        capability "Sensor"
        capability "Switch"
        capability "PresenceSensor"
        
        attribute "distance", "number"
        attribute "distanceCm", "number"
        attribute "distanceInch", "number"
        attribute "battery", "number"
        attribute "rssi", "number"
        attribute "lastUpdate", "string"
        attribute "thresholdTimer", "string"
        attribute "rawDistance", "number"
        attribute "smoothingStatus", "string"
        attribute "healthStatus", "string"  // online, offline, unknown
        attribute "presence", "enum", ["present", "not present"]  // mirrors healthStatus for notifications
        attribute "lastMessageReceived", "string"  // timestamp of last fresh MQTT message
        
        command "on"
        command "off"
        command "resetThresholdTimer"
        command "clearSmoothingBuffer"
    }

    preferences {
        section("MQTT Connection Settings") {
            input name: "bluAddress", type: "text", title: "BLE Device MAC Address", required: false, description: "Optional: MAC address to filter (e.g., f8:44:77:1c:e3:08). Leave blank to accept all BLE devices."
            input name: "showInches", type: "bool", title: "Display distance in inches (in addition to cm)?", defaultValue: false, required: true
            input name: "ipAddr", type: "text", title: "IP Address of MQTT broker", required: true
            input name: "ipPort", type: "text", title: "Port # of MQTT broker", defaultValue: "1883", required: true
            input name: "username", type: "text", title: "MQTT Username:", description: "(blank if none)", required: false
            input name: "password", type: "password", title: "MQTT Password:", description: "(blank if none)", required: false
            input name: "retryTime", type: "number", title: "Number of seconds between retries to connect if broker goes down", defaultValue: 300, required: true
            input name: "watchDogSched", type: "bool", title: "Check for connection to MQTT broker on a schedule?", defaultValue: false, required: true
            input name: "watchDogTime", type: "number", title: "Check MQTT broker connection interval", defaultValue: 15, range: "1..240", required: true, description: "1-59 = every X minutes, 60-240 = converted to hourly (e.g., 120 = every 2 hours)"
            input name: "staleMinutes", type: "number", title: "Minutes before marking sensor offline", defaultValue: 15, range: "5..120", required: true, description: "If no messages received within this time, sensor marked offline and reconnect attempted"
        }
        section("Threshold Switch Settings") {
            input name: "enableThreshold", type: "bool", title: "Enable switch based on distance threshold?", defaultValue: false, required: true
            input name: "triggerType", type: "enum", title: "Trigger Condition", options: ["Distance > Threshold", "Distance < Threshold"], defaultValue: "Distance > Threshold", required: true, description: "Choose when the switch turns ON"
            input name: "thresholdValue", type: "number", title: "Threshold value", defaultValue: 50, required: false, description: "Switch turns ON when trigger condition is met"
            input name: "thresholdUnit", type: "enum", title: "Threshold unit", options: ["mm", "cm", "in"], defaultValue: "cm", required: false
            input name: "enableTimeDelay", type: "bool", title: "Require time delay before turning ON?", defaultValue: false, required: true, description: "When enabled, condition must be met for specified time"
            input name: "timeDelayMinutes", type: "number", title: "Minutes condition met before ON", defaultValue: 5, required: false
        }
        section("Sensor Filtering") {
            input name: "batteryHysteresis", type: "number", title: "Battery change threshold (%)", defaultValue: 10, range: "1..25", required: true, description: "Only report battery when it changes by more than this amount (reduces noise)"
            input name: "rssiHysteresis", type: "number", title: "RSSI change threshold (dBm)", defaultValue: 5, range: "1..20", required: true, description: "Only report RSSI when it changes by more than this amount"
        }
        section("Distance Smoothing") {
            input name: "smoothingMode", type: "enum", title: "Smoothing Mode", options: [
                "none": "None (instant updates)", 
                "stable": "Stable Reading (require consecutive similar readings)",
                "average": "Time-Based Average (average over time window)",
                "outlier": "Outlier Rejection (ignore readings far from trend)"
            ], defaultValue: "none", required: true, description: "Choose how to handle sensor noise"
            
            // Stable Reading options
            input name: "stableReadingsRequired", type: "number", title: "Consecutive readings required (Stable mode)", defaultValue: 3, range: "2..10", required: false, description: "Number of similar readings before updating"
            input name: "stableTolerance", type: "number", title: "Tolerance in mm (Stable mode)", defaultValue: 10, range: "1..100", required: false, description: "How close readings must be to count as 'similar'"
            
            // Time-Based Average options
            input name: "averageWindowHours", type: "number", title: "Averaging window in hours (Average mode)", defaultValue: 24, range: "1..168", required: false, description: "Time window for averaging (1-168 hours)"
            input name: "averageUpdateInterval", type: "enum", title: "Update interval (Average mode)", options: [
                "1": "Every hour",
                "6": "Every 6 hours",
                "12": "Every 12 hours",
                "24": "Once daily"
            ], defaultValue: "24", required: false
            
            // Outlier Rejection options
            input name: "outlierThreshold", type: "number", title: "Outlier threshold in mm (Outlier mode)", defaultValue: 50, range: "10..500", required: false, description: "Ignore readings that differ from recent average by more than this"
            input name: "outlierBufferSize", type: "number", title: "Buffer size for trend (Outlier mode)", defaultValue: 5, range: "3..20", required: false, description: "Number of readings to establish trend"
        }
        section("Logging") {
            input name: "logLevel", title: "IDE logging level", multiple: false, required: true, type: "enum", options: getLogLevels(), submitOnChange: false, defaultValue: "1"
        }
    }
}

def setVersion(){
    state.name = "Shelly BLU Distance MQTT"
    state.version = "1.6.2 - Shelly BLU Distance MQTT Device Handler"   
}

void installed() {
    log.warn "installed..."
    setVersion()
    sendEvent(name: "switch", value: "off")
    sendEvent(name: "thresholdTimer", value: "inactive")
    sendEvent(name: "rssi", value: -100, unit: "dBm")
    sendEvent(name: "smoothingStatus", value: "idle")
    sendEvent(name: "healthStatus", value: "unknown")
    state.thresholdExceededTime = null
    state.lastRssi = -100
    state.lastBattery = null
    state.lastTimerUpdate = 0
    state.lastMessageReceived = 0
    state.reconnectAttempts = 0
    initializeSmoothingState()
}

void initializeSmoothingState() {
    state.smoothingBuffer = []
    state.stableReadingCount = 0
    state.lastStableValue = null
    state.stableConfirmed = false
    state.averageReadings = []  // List of maps: [value: mm, timestamp: epoch]
    state.lastAverageUpdate = 0
}

// Parse incoming device messages to generate events
void parse(String description) {
    topicFull = interfaces.mqtt.parseMessage(description).topic
    debuglog "TOPIC FULL: " + topicFull
    
    def topic = topicFull.split('/')
    def message = interfaces.mqtt.parseMessage(description).payload
    debuglog "MESSAGE: " + message
    
    if (!message) {
        debuglog "Empty payload"
        return
    }
    
    // Expect topic format: BLE/{MAC_ADDRESS}
    if (topic[0] == "BLE" && topic.size() >= 2) {
        def deviceMac = topic[1]
        debuglog "BLE Device MAC: ${deviceMac}"
        
        // If bluAddress filter is set, only process matching device
        if (bluAddress && deviceMac != bluAddress) {
            debuglog "Skipping device ${deviceMac} - doesn't match filter ${bluAddress}"
            return
        }
        
        try {
            // Parse JSON payload from BLE device
            def jsonVal = parseJson(message)
            debuglog "Parsed JSON: " + jsonVal
            
            // Parse BTHome service_data structure
            if (jsonVal.service_data) {
                parseBTHomeData(jsonVal)
            } else {
                debuglog "No service_data found in message"
            }
            
        } catch(e) {
            log.warn "Error parsing JSON from BLE topic: ${e.message}"
            debuglog "Raw message: ${message}"
        }
    } else {
        debuglog "Unhandled topic format: ${topicFull}"
    }
}

// Parse BTHome sensor data
void parseBTHomeData(bleData) {
    // Check if this is a retained/stale message by examining the Shelly timestamp
    boolean isFreshMessage = true
    if (bleData.timestamp) {
        def messageAge = now() - (bleData.timestamp as Long)
        def staleThreshold = (staleMinutes ?: 15) * 60 * 1000
        def shellyTime = new Date(bleData.timestamp as Long)
        
        if (messageAge > staleThreshold) {
            // This is a retained/stale message - don't treat as fresh data
            isFreshMessage = false
            def ageMinutes = Math.round(messageAge / 60000)
            debuglog "Stale/retained message detected (age: ${ageMinutes} min, from ${shellyTime.format("yyyy-MM-dd HH:mm:ss", location.timeZone)}) - processing data but not updating health status"
        } else {
            debuglog "Shelly timestamp: ${shellyTime.format("yyyy-MM-dd HH:mm:ss", location.timeZone)}, latency: ${messageAge}ms"
        }
    }
    
    if (isFreshMessage) {
        // Track message receipt for health monitoring (only for fresh messages)
        state.lastMessageReceived = now()
        
        // Update the visible attribute (throttled to reduce DB writes)
        def lastAttrUpdate = state.lastMsgAttrUpdate ?: 0
        if (now() - lastAttrUpdate > 60000) {
            def timestamp = new Date().format("yyyy-MM-dd HH:mm:ss", location.timeZone)
            sendEvent(name: "lastMessageReceived", value: timestamp)
            state.lastMsgAttrUpdate = now()
        }
        
        // Reset reconnect attempts on successful fresh message receipt
        state.reconnectAttempts = 0
        
        // Update health status to online since we received a fresh message
        if (device.currentValue("healthStatus") != "online") {
            infolog "Sensor is now reporting - marking online"
            sendEvent(name: "healthStatus", value: "online")
            sendEvent(name: "presence", value: "present")
        }
    }
    
    debuglog "Parsing BTHome data: " + bleData
    
    def serviceData = bleData.service_data
    boolean dataChanged = false
    
    // Parse distance data
    if (serviceData.distance != null) {
        def rawDistanceMm = serviceData.distance
        
        // Always update raw distance attribute for debugging
        sendEvent(name: "rawDistance", value: rawDistanceMm, unit: "mm")
        
        // Apply smoothing and get the processed distance
        def processedDistance = applySmoothing(rawDistanceMm)
        
        if (processedDistance != null) {
            def distanceMm = processedDistance
            // Convert mm to cm - use BigDecimal to avoid scientific notation
            def distanceCm = new BigDecimal(distanceMm / 10.0).setScale(1, BigDecimal.ROUND_HALF_UP)
            
            // Check if value changed from last time (to suppress repetitive INFO logs)
            def currentDist = device.currentValue("distance")
            boolean distValChanged = (currentDist == null || currentDist.toInteger() != distanceMm.toInteger())

            // Log and send events
            if (showInches) {
                // Convert mm to inches (1 inch = 25.4mm)
                def distanceInch = new BigDecimal(distanceMm / 25.4).setScale(2, BigDecimal.ROUND_HALF_UP)
                if (distValChanged) infolog "Distance changed: ${distanceCm} cm (${distanceInch} in, ${distanceMm} mm)"
                sendEvent(name: "distanceInch", value: distanceInch, unit: "in")
            } else {
                if (distValChanged) infolog "Distance changed: ${distanceCm} cm (${distanceMm} mm)"
            }
            
            sendEvent(name: "distance", value: distanceMm, unit: "mm")
            sendEvent(name: "distanceCm", value: distanceCm, unit: "cm")
            dataChanged = true
            
            // Check threshold and update switch state
            if (enableThreshold) {
                evaluateThreshold(distanceMm, distanceCm)
            }
        } else {
            debuglog "Smoothing returned null - waiting for stable reading"
        }
    }
    
    // Parse battery level with hysteresis to reduce noise from sensor fluctuations
    if (serviceData.battery != null) {
        def currentBattery = serviceData.battery.toInteger()
        def lastBattery = state.lastBattery != null ? state.lastBattery.toInteger() : -100
        def battThreshold = batteryHysteresis != null ? batteryHysteresis.toInteger() : 10
        
        // Only update if battery changed by more than threshold to reduce noise
        if (Math.abs(currentBattery - lastBattery) > battThreshold) {
            if (lastBattery == -100) {
                infolog "Battery initial reading: ${currentBattery}%"
            } else {
                infolog "Battery changed: ${currentBattery}% (was ${lastBattery}%)"
            }
            sendEvent(name: "battery", value: currentBattery, unit: "%")
            state.lastBattery = currentBattery
            dataChanged = true
        } else {
            debuglog "Battery ignored (within ${battThreshold}% hysteresis): ${currentBattery}% (last reported: ${lastBattery}%)"
        }
    }
    
    // Parse RSSI (signal strength) from root level with hysteresis
    // Only update if value changes by more than threshold to reduce database noise
    if (bleData.rssi != null) {
        def currentRssi = bleData.rssi.toInteger()
        def lastRssi = state.lastRssi != null ? state.lastRssi.toInteger() : -100
        def rssiThreshold = rssiHysteresis != null ? rssiHysteresis.toInteger() : 5
        
        if (Math.abs(currentRssi - lastRssi) > rssiThreshold) {
            debuglog "RSSI changed significantly (${lastRssi} -> ${currentRssi} dBm) - updating"
            sendEvent(name: "rssi", value: currentRssi, unit: "dBm")
            state.lastRssi = currentRssi
            // Note: We do NOT set dataChanged = true here. 
            // We don't want to update LastUpdate timestamp just because signal fluctuated.
        } else {
            debuglog "RSSI ignored (within ${rssiThreshold}dBm hysteresis): ${currentRssi} dBm"
        }
    }
    
    // Update last update timestamp ONLY if actual data (distance/battery) changed
    if (dataChanged) {
        def now = new Date().format("yyyy-MM-dd HH:mm:ss", location.timeZone)
        sendEvent(name: "lastUpdate", value: now)
    }
}

/**
 * Apply smoothing based on selected mode
 * Returns processed distance in mm, or null if not ready to update
 */
def applySmoothing(rawMm) {
    def mode = smoothingMode ?: "none"
    
    switch(mode) {
        case "stable":
            return applySmoothingStable(rawMm)
        case "average":
            return applySmoothingAverage(rawMm)
        case "outlier":
            return applySmoothingOutlier(rawMm)
        default:
            // No smoothing - return raw value
            sendEvent(name: "smoothingStatus", value: "disabled")
            return rawMm
    }
}

/**
 * Stable Reading Mode
 * Requires N consecutive readings within tolerance before updating
 */
def applySmoothingStable(rawMm) {
    def required = (stableReadingsRequired ?: 3).toInteger()
    def tolerance = (stableTolerance ?: 10).toInteger()
    
    // Initialize state if needed
    if (state.lastStableValue == null) {
        state.lastStableValue = rawMm
        state.stableReadingCount = 1
        state.stableConfirmed = false
        sendEvent(name: "smoothingStatus", value: "initializing (1/${required})")
        debuglog "Stable mode: First reading ${rawMm}mm"
        return rawMm  // Accept first reading to initialize
    }
    
    // Check if this reading is within tolerance of the last stable candidate
    def lastCandidate = state.smoothingBuffer?.size() > 0 ? state.smoothingBuffer[-1] : state.lastStableValue
    
    if (Math.abs(rawMm - lastCandidate) <= tolerance) {
        // Reading is similar - increment count
        state.stableReadingCount = (state.stableReadingCount ?: 0) + 1
        
        // Add to buffer
        if (state.smoothingBuffer == null) state.smoothingBuffer = []
        state.smoothingBuffer.add(rawMm)
        
        // Keep buffer size reasonable
        while (state.smoothingBuffer.size() > required) {
            state.smoothingBuffer.remove(0)
        }
        
        debuglog "Stable mode: Reading ${rawMm}mm matches candidate ${lastCandidate}mm (${state.stableReadingCount}/${required})"
        
        if (state.stableReadingCount >= required) {
            // We have enough stable readings - calculate average of buffer and update
            def avgValue = Math.round(state.smoothingBuffer.sum() / state.smoothingBuffer.size()).toInteger()
            
            // Only log confirmation message when we first reach stability OR value changes
            if (!state.stableConfirmed || state.lastStableValue != avgValue) {
                infolog "Stable mode: Confirmed value ${avgValue}mm after ${required} consistent readings"
                state.stableConfirmed = true
            }
            
            state.lastStableValue = avgValue
            sendEvent(name: "smoothingStatus", value: "stable (${required} readings)")
            return avgValue
        } else {
            sendEvent(name: "smoothingStatus", value: "collecting (${state.stableReadingCount}/${required})")
            return null  // Not ready yet
        }
    } else {
        // Reading differs significantly - reset and start new candidate
        debuglog "Stable mode: Reading ${rawMm}mm differs from candidate ${lastCandidate}mm by ${Math.abs(rawMm - lastCandidate)}mm (tolerance: ${tolerance}mm) - resetting"
        state.smoothingBuffer = [rawMm]
        state.stableReadingCount = 1
        state.stableConfirmed = false  // Reset confirmation flag
        sendEvent(name: "smoothingStatus", value: "reset - new candidate ${rawMm}mm (1/${required})")
        return null  // Don't update yet
    }
}

/**
 * Time-Based Average Mode
 * Averages readings over a time window, updates at configured interval
 */
def applySmoothingAverage(rawMm) {
    def windowHours = (averageWindowHours ?: 24).toInteger()
    def updateIntervalHours = (averageUpdateInterval ?: "24").toInteger()
    def windowMs = windowHours * 3600000
    def updateIntervalMs = updateIntervalHours * 3600000
    def currentTime = now()
    
    // Initialize state if needed
    if (state.averageReadings == null) state.averageReadings = []
    if (state.lastAverageUpdate == null) state.lastAverageUpdate = 0
    
    // Add current reading with timestamp
    state.averageReadings.add([value: rawMm, timestamp: currentTime])
    
    // Remove readings outside the window
    def cutoffTime = currentTime - windowMs
    state.averageReadings = state.averageReadings.findAll { it.timestamp >= cutoffTime }
    
    debuglog "Average mode: Added reading ${rawMm}mm. Buffer has ${state.averageReadings.size()} readings in last ${windowHours}h"
    
    // Check if it's time to update
    def timeSinceLastUpdate = currentTime - state.lastAverageUpdate
    
    if (state.lastAverageUpdate == 0) {
        // First reading - initialize
        state.lastAverageUpdate = currentTime
        sendEvent(name: "smoothingStatus", value: "averaging (${state.averageReadings.size()} readings)")
        return rawMm  // Return first value to initialize
    }
    
    if (timeSinceLastUpdate >= updateIntervalMs) {
        // Time to calculate and report average
        if (state.averageReadings.size() > 0) {
            def sum = state.averageReadings.sum { it.value }
            def avgValue = Math.round(sum / state.averageReadings.size()).toInteger()
            state.lastAverageUpdate = currentTime
            sendEvent(name: "smoothingStatus", value: "averaged ${state.averageReadings.size()} readings over ${windowHours}h")
            infolog "Average mode: Reporting average ${avgValue}mm from ${state.averageReadings.size()} readings"
            return avgValue
        }
    }
    
    sendEvent(name: "smoothingStatus", value: "collecting (${state.averageReadings.size()} readings, next update in ${Math.round((updateIntervalMs - timeSinceLastUpdate) / 3600000)}h)")
    return null  // Not time to update yet
}

/**
 * Outlier Rejection Mode
 * Ignores readings that deviate significantly from recent trend
 */
def applySmoothingOutlier(rawMm) {
    def threshold = (outlierThreshold ?: 50).toInteger()
    def bufferSize = (outlierBufferSize ?: 5).toInteger()
    
    // Initialize state if needed
    if (state.smoothingBuffer == null) state.smoothingBuffer = []
    
    // If buffer is empty or too small, accept reading and add to buffer
    if (state.smoothingBuffer.size() < 2) {
        state.smoothingBuffer.add(rawMm)
        sendEvent(name: "smoothingStatus", value: "building trend (${state.smoothingBuffer.size()}/${bufferSize})")
        debuglog "Outlier mode: Building buffer (${state.smoothingBuffer.size()}/${bufferSize}), accepting ${rawMm}mm"
        return rawMm
    }
    
    // Calculate current trend (average of buffer)
    def trendAvg = Math.round(state.smoothingBuffer.sum() / state.smoothingBuffer.size()).toInteger()
    def deviation = Math.abs(rawMm - trendAvg)
    
    if (deviation > threshold) {
        // This is an outlier - reject it
        sendEvent(name: "smoothingStatus", value: "rejected outlier ${rawMm}mm (trend: ${trendAvg}mm, deviation: ${deviation}mm)")
        infolog "Outlier mode: Rejected ${rawMm}mm - deviates ${deviation}mm from trend ${trendAvg}mm (threshold: ${threshold}mm)"
        return null  // Don't update
    }
    
    // Reading is within acceptable range - add to buffer
    state.smoothingBuffer.add(rawMm)
    
    // Keep buffer at configured size
    while (state.smoothingBuffer.size() > bufferSize) {
        state.smoothingBuffer.remove(0)
    }
    
    sendEvent(name: "smoothingStatus", value: "accepted (trend: ${trendAvg}mm)")
    debuglog "Outlier mode: Accepted ${rawMm}mm (deviation: ${deviation}mm within threshold ${threshold}mm)"
    return rawMm
}

void clearSmoothingBuffer() {
    infolog "Clearing smoothing buffer"
    initializeSmoothingState()
    sendEvent(name: "smoothingStatus", value: "buffer cleared")
}

/**
 * Check if the sensor has stopped reporting and attempt recovery
 * Called on a schedule to detect stale/offline sensors
 * Also acts as a secondary connection check independent of the watchdog
 */
void checkSensorHealth() {
    def staleThreshold = (staleMinutes ?: 15) * 60 * 1000
    def lastMsg = state.lastMessageReceived ?: 0
    def elapsed = now() - lastMsg
    
    if (lastMsg == 0) {
        // Never received a message
        if (device.currentValue("healthStatus") != "unknown") {
            sendEvent(name: "healthStatus", value: "unknown")
        }
        debuglog "Sensor health: unknown (no messages ever received)"
    } else if (elapsed > staleThreshold) {
        // Stale - no recent messages
        def minsSince = Math.round(elapsed / 60000)
        if (device.currentValue("healthStatus") != "offline") {
            log.warn "${device.displayName} has not reported in ${minsSince} minutes - marking offline"
            sendEvent(name: "healthStatus", value: "offline")
            sendEvent(name: "presence", value: "not present")
        } else {
            debuglog "Sensor still offline - last message ${minsSince} minutes ago"
        }
        
        // Connection may be stale even if isConnected() says true - force reconnect
        infolog "No data in ${minsSince} minutes - forcing MQTT reconnect"
        reconnectMqtt()
    } else {
        // Healthy
        if (device.currentValue("healthStatus") != "online") {
            infolog "Sensor is reporting normally - marking online"
            sendEvent(name: "healthStatus", value: "online")
            sendEvent(name: "presence", value: "present")
        }
        debuglog "Sensor health: online (last message ${Math.round(elapsed / 60000)} minutes ago)"
    }
}

void refresh(){
    watchDog()
    setVersion()
    infolog "Refresh called - waiting for next MQTT update..."
}

void evaluateThreshold(distanceMm, distanceCm) {
    if (!thresholdValue) {
        debuglog "No threshold value set"
        return
    }
    
    // Convert distance to the threshold unit for comparison
    def compareValue
    switch(thresholdUnit) {
        case "mm":
            compareValue = distanceMm
            break
        case "cm":
            compareValue = distanceCm
            break
        case "in":
            compareValue = new BigDecimal(distanceMm / 25.4).setScale(2, BigDecimal.ROUND_HALF_UP)
            break
        default:
            compareValue = distanceCm
    }
    
    def currentSwitch = device.currentValue("switch") ?: "off"
    
    // Check trigger condition based on preference
    boolean thresholdMet = false
    
    if (triggerType == "Distance < Threshold") {
        // ON when Short (e.g., Car Present)
        if (compareValue < thresholdValue) thresholdMet = true
    } else {
        // ON when Long (Default - e.g., Salt Empty)
        if (compareValue > thresholdValue) thresholdMet = true
    }
    
    // Logic Execution
    if (thresholdMet) {
        // Condition is Met
        if (enableTimeDelay && timeDelayMinutes != null && timeDelayMinutes > 0) {
            // Time delay is enabled - check if we need to start or continue timer
            if (state.thresholdExceededTime == null) {
                // First time exceeding threshold - start the timer
                state.thresholdExceededTime = now()
                state.lastTimerUpdate = now()
                def timeStr = new Date().format("HH:mm:ss", location.timeZone)
                sendEvent(name: "thresholdTimer", value: "waiting (started ${timeStr})")
                infolog "Trigger condition met (${compareValue}${thresholdUnit}) - timer started, waiting ${timeDelayMinutes} minute(s)"
            } else {
                // Timer already running - check if enough time has elapsed
                def elapsedMillis = now() - state.thresholdExceededTime
                def elapsedMinutes = String.format("%.1f", elapsedMillis / 60000.0)
                
                debuglog "Timer running: ${elapsedMinutes} of ${timeDelayMinutes} minutes elapsed"
                
                if (elapsedMillis >= (timeDelayMinutes * 60000)) {
                    // Enough time has passed - turn switch ON
                    if (currentSwitch != "on") {
                        infolog "Condition met for ${elapsedMinutes} minute(s) - turning switch ON"
                        sendEvent(name: "switch", value: "on")
                        sendEvent(name: "thresholdTimer", value: "triggered")
                    }
                } else {
                    // Still waiting - update status only every 30 seconds to reduce chatter
                    def timeSinceLastUpdate = now() - (state.lastTimerUpdate ?: 0)
                    if (timeSinceLastUpdate >= 30000) {
                        def timeStr = new Date(state.thresholdExceededTime).format("HH:mm:ss", location.timeZone)
                        sendEvent(name: "thresholdTimer", value: "waiting ${elapsedMinutes}/${timeDelayMinutes} min (started ${timeStr})")
                        state.lastTimerUpdate = now()
                    }
                }
            }
        } else {
            // Time delay disabled - turn ON immediately
            if (currentSwitch != "on") {
                infolog "Trigger condition met (${compareValue}${thresholdUnit}) - turning switch ON"
                sendEvent(name: "switch", value: "on")
                sendEvent(name: "thresholdTimer", value: "inactive")
            }
        }
    } else {
        // Condition NOT met - turn OFF and reset timer
        if (state.thresholdExceededTime != null) {
            infolog "Condition no longer met - resetting timer"
            state.thresholdExceededTime = null
            state.lastTimerUpdate = 0
            sendEvent(name: "thresholdTimer", value: "reset")
        }
        
        if (currentSwitch != "off") {
            infolog "Condition no longer met (${compareValue}${thresholdUnit}) - turning switch OFF"
            sendEvent(name: "switch", value: "off")
            sendEvent(name: "thresholdTimer", value: "inactive")
        }
    }
}

// These commands are for Switch capability - they update state but warn that it's normally threshold-controlled
void on() {
    infolog "Manual ON command received"
    sendEvent(name: "switch", value: "on")
    if (enableThreshold) {
        log.warn "Note: Switch is configured to be controlled by distance threshold"
    }
}

void off() {
    infolog "Manual OFF command received"
    sendEvent(name: "switch", value: "off")
    if (enableThreshold) {
        log.warn "Note: Switch is configured to be controlled by distance threshold"
    }
}

void resetThresholdTimer() {
    infolog "Manual reset of threshold timer"
    state.thresholdExceededTime = null
    state.lastTimerUpdate = 0
    sendEvent(name: "thresholdTimer", value: "reset")
}

void updated() {
    infolog "updated..."
    
    // Initialize switch state if threshold is enabled
    if (enableThreshold) {
        if (device.currentValue("switch") == null) {
            sendEvent(name: "switch", value: "off")
            infolog "Initialized switch state to OFF"
        }
    }
    
    // Reset timer if time delay settings changed
    if (enableTimeDelay) {
        state.thresholdExceededTime = null
        state.lastTimerUpdate = 0
        sendEvent(name: "thresholdTimer", value: "inactive")
        debuglog "Timer reset due to settings update"
    }
    
    // Reset smoothing state when settings change
    initializeSmoothingState()
    sendEvent(name: "smoothingStatus", value: "reset - settings changed")
    
    // Reset reconnect counter
    state.reconnectAttempts = 0
    
    configure()
    unschedule()
    pauseExecution(1000)
    
    // Set up all schedules (watchdog, health check, average mode)
    setupSchedules()
}

/**
 * Central schedule setup - called from both updated() and initialize()
 * Ensures watchdog and health check survive hub reboots
 */
void setupSchedules() {
    // Schedule the watchdog to run in case the broker restarts
    if (watchDogSched) {
        if (watchDogTime <= 59) {
            // Minute-based scheduling for intervals under 60 minutes
            debuglog "Setting schedule to check MQTT broker connection every ${watchDogTime} minute(s)"
            schedule("44 0/${watchDogTime} * ? * *", watchDog)
        } else {
            // Hour-based scheduling for intervals 60 minutes or greater
            def hours = Math.round(watchDogTime / 60.0).toInteger()
            if (hours < 1) hours = 1
            if (hours > 23) hours = 23
            debuglog "Setting schedule to check MQTT broker connection every ${hours} hour(s)"
            schedule("44 7 0/${hours} ? * *", watchDog)
        }
    }
    
    // Schedule average mode updates if needed
    if (smoothingMode == "average") {
        def intervalHours = (averageUpdateInterval ?: "24").toInteger()
        debuglog "Scheduling average update every ${intervalHours} hour(s)"
        schedule("0 0 0/${intervalHours} ? * *", triggerAverageUpdate)
    }
    
    // Schedule sensor health check every 5 minutes
    runEvery5Minutes(checkSensorHealth)
    debuglog "Scheduled sensor health check every 5 minutes"
}

void triggerAverageUpdate() {
    // Force an average calculation and update
    if (smoothingMode == "average" && state.averageReadings?.size() > 0) {
        def sum = state.averageReadings.sum { it.value }
        def avgValue = Math.round(sum / state.averageReadings.size()).toInteger()
        state.lastAverageUpdate = now()
        
        def distanceMm = avgValue
        def distanceCm = new BigDecimal(distanceMm / 10.0).setScale(1, BigDecimal.ROUND_HALF_UP)
        
        infolog "Scheduled average update: ${avgValue}mm from ${state.averageReadings.size()} readings"
        sendEvent(name: "distance", value: distanceMm, unit: "mm")
        sendEvent(name: "distanceCm", value: distanceCm, unit: "cm")
        
        if (showInches) {
            def distanceInch = new BigDecimal(distanceMm / 25.4).setScale(2, BigDecimal.ROUND_HALF_UP)
            sendEvent(name: "distanceInch", value: distanceInch, unit: "in")
        }
        
        sendEvent(name: "smoothingStatus", value: "scheduled update - averaged ${state.averageReadings.size()} readings")
        
        def now = new Date().format("yyyy-MM-dd HH:mm:ss", location.timeZone)
        sendEvent(name: "lastUpdate", value: now)
        
        // Evaluate threshold with new average
        if (enableThreshold) {
            evaluateThreshold(distanceMm, distanceCm)
        }
    }
}

void uninstalled() {
    infolog "Disconnecting from mqtt..."
    interfaces.mqtt.disconnect()
    unschedule()
}

void initialize() {
    infolog "initialize..."
    
    // Initialize switch state if threshold is enabled
    if (enableThreshold && device.currentValue("switch") == null) {
        sendEvent(name: "switch", value: "off")
        infolog "Initialized switch state to OFF"
    }
    
    // Initialize timer attribute
    if (device.currentValue("thresholdTimer") == null) {
        sendEvent(name: "thresholdTimer", value: "inactive")
    }
    
    // Initialize RSSI attribute
    if (device.currentValue("rssi") == null) {
        sendEvent(name: "rssi", value: -100, unit: "dBm")
    }
    
    // Initialize smoothing status
    if (device.currentValue("smoothingStatus") == null) {
        sendEvent(name: "smoothingStatus", value: "idle")
    }
    
    // Initialize health status and presence
    if (device.currentValue("healthStatus") == null) {
        sendEvent(name: "healthStatus", value: "unknown")
    }
    if (device.currentValue("presence") == null) {
        sendEvent(name: "presence", value: "not present")
    }
    
    // Initialize last message received attribute
    if (device.currentValue("lastMessageReceived") == null) {
        sendEvent(name: "lastMessageReceived", value: "never")
    }
    
    // Initialize state variables for hysteresis
    if (state.lastBattery == null) state.lastBattery = -100
    if (state.lastRssi == null) state.lastRssi = -100
    if (state.lastMessageReceived == null) state.lastMessageReceived = 0
    if (state.reconnectAttempts == null) state.reconnectAttempts = 0
    
    // Initialize smoothing state
    if (state.smoothingBuffer == null) initializeSmoothingState()
    
    // Connect to MQTT broker
    connectMqtt()
    
    // Set up schedules - critical for surviving hub reboots
    // unschedule first to avoid duplicates since initialize() can be called multiple times
    unschedule()
    pauseExecution(500)
    setupSchedules()
    
    // If logs are in "Need Help" turn down to "Running" after an hour
    logL = logLevel.toInteger()
    if (logL == 2) runIn(3600, logsOff)
}

/**
 * Connect to MQTT broker (subscribe to topics)
 * Separated from initialize() so it can be called independently for reconnection
 */
void connectMqtt() {
    try {
        // Open connection
        def mqttInt = interfaces.mqtt
        mqttbroker = "tcp://" + ipAddr + ":" + ipPort
        mqttclientname = "Hubitat MQTT BLE Distance " + device.deviceNetworkId
        mqttInt.connect(mqttbroker, mqttclientname, username, password)
        
        // Give it a chance to start
        pauseExecution(1000)
        infolog "Connection established..."
        
        // Subscribe to BLE topics
        // If bluAddress is specified, subscribe to that specific device
        // Otherwise subscribe to all BLE devices with wildcard
        if (bluAddress) {
            mqttInt.subscribe("BLE/${bluAddress}")
            infolog "Subscribed to topic: BLE/${bluAddress}"
        } else {
            mqttInt.subscribe("BLE/#")
            infolog "Subscribed to topic: BLE/# (all BLE devices)"
        }
        
    } catch(e) {
        log.warn "${device.label?device.label:device.name}: MQTT connect error: ${e.message}"
    }
}

/**
 * Force disconnect and reconnect to MQTT broker
 * Used when connection is suspected stale (isConnected() lies about half-open TCP connections)
 */
void reconnectMqtt() {
    state.reconnectAttempts = (state.reconnectAttempts ?: 0) + 1
    infolog "Reconnect attempt #${state.reconnectAttempts} - disconnecting first..."
    
    try {
        interfaces.mqtt.disconnect()
    } catch(e) {
        debuglog "Disconnect error (expected if already disconnected): ${e.message}"
    }
    
    pauseExecution(2000)
    
    infolog "Reconnecting to MQTT broker..."
    connectMqtt()
}

void configure(){
    infolog "configure..."
    watchDog()
}

/**
 * Watchdog - checks MQTT connection health
 * Now checks both isConnected() AND whether we've received data recently
 */
def watchDog() {
    debuglog "Watchdog: Checking MQTT status"
    
    def connected = interfaces.mqtt.isConnected()
    def lastMsg = state.lastMessageReceived ?: 0
    def elapsed = lastMsg > 0 ? now() - lastMsg : -1
    def staleThreshold = (staleMinutes ?: 15) * 60 * 1000
    
    if (!connected) {
        // Definitely disconnected - reconnect
        log.warn "Watchdog: MQTT disconnected - reconnecting"
        reconnectMqtt()
    } else if (lastMsg > 0 && elapsed > staleThreshold) {
        // Connected but no data - likely a ghost/stale connection
        def minsSince = Math.round(elapsed / 60000)
        log.warn "Watchdog: MQTT reports connected but no data in ${minsSince} minutes - forcing reconnect"
        reconnectMqtt()
    } else {
        debuglog "Watchdog: MQTT OK (connected: ${connected}, last data: ${elapsed > 0 ? Math.round(elapsed / 60000) + ' min ago' : 'never'})"
    }
}

void mqttClientStatus(String message) {
    log.warn "${device.label?device.label:device.name}: **** MQTT status: ${message} ****"
    if (message.contains("Connection lost") || message.contains("Client is not connected")) {
        // Schedule a reconnect attempt instead of blocking
        infolog "Connection lost detected - scheduling reconnect in ${retryTime} seconds"
        runIn(retryTime ?: 300, reconnectMqtt)
    }
}
    
// Logging below here
def logsOff(){
    log.warn "Debug logging disabled"
    device.updateSetting("logLevel", [value: "1", type: "enum"])
}

def debuglog(statement) {   
    def logL = 0
    if (logLevel) logL = logLevel.toInteger()
    if (logL == 0) {return} // bail
    else if (logL >= 2) {
        log.debug("${device.label?device.label:device.name}: " + statement)
    }
}

def infolog(statement) {        
    def logL = 0
    if (logLevel) logL = logLevel.toInteger()
    if (logL == 0) {return} // bail
    else if (logL >= 1) {
        log.info("${device.label?device.label:device.name}: " + statement)
    }
}

def getLogLevels(){
    return [["0":"None"],["1":"Running"],["2":"NeedHelp"]]
}
