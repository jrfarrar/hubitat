
/**
 *  Power Cycle Monitor v2.3
 *
 *  Monitor power meters to detect cycling patterns and stuck-on failures
 *  with historical tracking and trend analysis
 *
 *  Copyright 2025 J.R. Farrar
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 *
 *  CHANGELOG:
 *  v2.3 (2025-11-18)
 *    - Added CSV auto-logging to hub filesystem
 *    - Added monthly median snapshot tracking (last 12 months)
 *    - Added baseline calculation and anomaly detection
 *    - Added sessions per day tracking
 *    - Added stuck-ON event logging to history
 *    - Added toggle display for 6 or 12 months
 *    - Added user-configurable anomaly threshold and optional alert switch (latching)
 *    - Added dynamic hub IP detection for file downloads
 *    - Added app label prefix option (prefix + dynamic status for multiple instances)
 *    - Fixed anomaly switch to properly latch (requires manual reset)
 *    - Fixed invalid runEvery12Hours method (changed to schedule with cron)
 *
 *  v2.2 (2025-11-16)
 *    - Added "Stuck ON" detection feature
 *    - Fixed app label not updating cycle count during active alert
 *
 *  v2.1 (2025-11-16)
 *    - Added session cycle counter
 *
 *  v2.0 (2025-11-14)
 *    - Fixed BigDecimal rounding issues
 *    - Fixed null pointer exceptions on upgrade
 *    - Fixed OFF duration calculation
 *
 */

definition(
    name: "Power Cycle Monitor",
    namespace: "jrfarrar",
    author: "J.R. Farrar",
    description: "Monitor power meters to detect cycling patterns and stuck-on failures with historical tracking",
    category: "Convenience",
    iconUrl: "",
    iconX2Url: "",
    iconX3Url: ""
)

preferences {
    page(name: "mainPage")
    page(name: "toggleHistoryDisplay")
}

def mainPage() {
    dynamicPage(name: "mainPage", title: "Power Cycle Monitor v2.3", install: true, uninstall: true) {
        
        section("Device to Monitor") {
            input "powerMeter", "capability.powerMeter", title: "Select Power Meter", required: true, multiple: false
            input "wattThreshold", "number", title: "Watt Threshold (device is ON above this)", required: true, defaultValue: 100
        }
        
        section("Cycling Detection Settings") {
            input "cycleCount", "number", title: "Number of Cycles to Detect", required: true, defaultValue: 3
            input "timeWindow", "number", title: "Time Window (minutes)", required: true, defaultValue: 30
            input "offTimeout", "number", title: "Off Timeout (minutes) - Reset if device stays off this long", required: true, defaultValue: 60
            input "cycleAlertSwitch", "capability.switch", title: "Cycling Alert Switch (optional)", required: false
        }
        
        section("Stuck-ON Detection Settings") {
            input "stuckOnTimeout", "number", title: "Stuck-ON Timeout (minutes) - Alert if device stays on continuously", required: true, defaultValue: 15
            input "stuckOnAlertSwitch", "capability.switch", title: "Stuck-ON Alert Switch (optional)", required: false
        }
        
        section("History Tracking Settings") {
            input "enableHistoryTracking", "bool", title: "Enable History Tracking & Logging", defaultValue: true, submitOnChange: true
            if (enableHistoryTracking) {
                input "anomalyThreshold", "number", title: "Anomaly Alert Threshold (%)", range: "15..50", required: true, defaultValue: 25
                input "anomalySwitch", "capability.switch", title: "Anomaly Alert Switch (optional)", required: false
                paragraph "Anomaly detection compares total cycle time (ON+OFF) to baseline and alerts if it exceeds the threshold percentage."
            }
        }
        
        section("App Settings") {
            input "labelPrefix", "text", title: "App Label Prefix (optional)", required: false, submitOnChange: false
            paragraph "Label will show as: '[Prefix] - [Dynamic Status]'\nLeave blank to use power meter name as prefix.\nExample: 'Well Pump - 🔴 ALERT: 5 cycles'"
        }
        
        section("Logging") {
            input "logEnable", "bool", title: "Enable Debug Logging", defaultValue: false
        }
        
        // Display current status
        section("Current Status") {
            def status = getStatusDisplay()
            paragraph status
        }
        
        // Display current session statistics if available
        if (state.leftOnDetected || state.cycleHistory?.size() > 0) {
            section("Current Session Statistics") {
                def stats = getCurrentStatistics()
                paragraph stats
            }
        }
        
        // Display historical data if tracking is enabled
        if (enableHistoryTracking) {
            displayHistorySection()
        }
        
        section("Actions") {
            input "btnReset", "button", title: "Manual Reset"
        }
    }
}

def toggleHistoryDisplay() {
    state.showFullHistory = !state.showFullHistory
    mainPage()
}

def displayHistorySection() {
    section("═══════════════════════════════════════\nHISTORY & TRENDS\n═══════════════════════════════════════") {
        // Initialize if needed
        if (!state.monthlySnapshots) state.monthlySnapshots = []
        
        def monthsToShow = state.showFullHistory ? 12 : 6
        def hasData = state.monthlySnapshots.size() > 0
        
        if (!hasData) {
            paragraph "📊 Establishing baseline... Need at least 2 months of data for trend analysis."
            if (state.currentMonthSessions?.size() > 0) {
                paragraph "Sessions collected this month: ${state.currentMonthSessions.size()}"
            }
        } else {
            // Display baseline info
            def baselineMsg = getBaselineDisplay()
            paragraph baselineMsg
            
            // Display monthly history
            def historyMsg = getMonthlyHistoryDisplay(monthsToShow)
            paragraph historyMsg
            
            // Toggle button
            if (state.monthlySnapshots.size() > 6) {
                def buttonTitle = state.showFullHistory ? "▲ Show Less" : "▼ Show All History (12 months)"
                href name: "btnToggleHistory", title: buttonTitle, description: "Toggle history view", page: "toggleHistoryDisplay"
            }
            
            // File download link
            def fileName = getFileName()
            def fileUrl = getFileManagerUrl(fileName)
            paragraph """
<a href="${fileUrl}" target="_blank">📥 Download CSV Data</a>
<small>Access all detailed session logs</small>
"""
        }
    }
}

def getBaselineDisplay() {
    def msg = ""
    
    if (state.baselineMonthsCollected < 2) {
        msg += "⏳ Collecting baseline data... (${state.baselineMonthsCollected}/2 months)\n"
    } else {
        def baselineOn = safeToDouble(state.baselineAvgOn)
        def baselineOff = safeToDouble(state.baselineAvgOff)
        def baselineCycle = safeToDouble(state.baselineCycleTime)
        
        msg += "📈 Current Baseline: ${Math.round(baselineCycle)}s cycle "
        msg += "(${Math.round(baselineOn)}s ON / ${Math.round(baselineOff)}s OFF)\n"
        
        if (state.anomalyDetected) {
            msg += "⚠️ Status: ANOMALY DETECTED - ${state.anomalyMessage}\n"
        } else {
            msg += "✓ Status: Normal operation\n"
        }
    }
    
    // Current month stats
    def sessionsToday = state.sessionsToday ?: 0
    def sessionsThisMonth = state.sessionsThisMonth ?: 0
    def currentMonth = new Date().format("yyyy-MM")
    def daysInMonth = getDaysInMonth(currentMonth)
    def avgPerDay = "0.0"
    if (daysInMonth > 0) {
        def avg = (sessionsThisMonth as Double) / (daysInMonth as Double)
        avgPerDay = String.format('%.1f', avg)
    }
    
    msg += "Sessions Today: ${sessionsToday} | This Month: ${sessionsThisMonth} (avg ${avgPerDay}/day)\n"
    
    return msg
}

def getMonthlyHistoryDisplay(monthsToShow) {
    def msg = "\nRECENT MONTHS (Last ${monthsToShow}):\n"
    msg += "─────────────────────────────────────\n"
    
    def snapshots = state.monthlySnapshots.take(monthsToShow)
    
    if (snapshots.size() == 0) {
        msg += "No monthly data yet.\n"
    } else {
        snapshots.each { snapshot ->
            def monthYear = snapshot.monthYear ?: ""
            def cycles = snapshot.cycles ?: 0
            def avgOn = Math.round((snapshot.avgOn ?: 0) as Double)
            def avgOff = Math.round((snapshot.avgOff ?: 0) as Double)
            def runtime = snapshot.runtime ?: "0.0"  // Already formatted as string
            def sessionsPerDay = snapshot.sessionsPerDay ?: "0.0"  // Also a string
            def stuckOnEvents = snapshot.stuckOnEvents ?: 0
            def anomaly = snapshot.anomaly ?: ""
            
            msg += "${monthYear}: ${cycles} cyc | ${avgOn}s/${avgOff}s | ${runtime}m | ${sessionsPerDay}/day"
            
            if (stuckOnEvents > 0) {
                msg += " | ⚠️ ${stuckOnEvents} stuck-ON"
            }
            
            if (anomaly) {
                msg += " | ${anomaly}"
            } else {
                msg += " | ✓"
            }
            
            msg += "\n"
        }
    }
    
    return msg
}

def getStatusDisplay() {
    def status = ""
    
    // Device state
    if (state.deviceOn) {
        def onDuration = getOnDuration()
        status += "Device: ON (${onDuration})\n"
    } else {
        status += "Device: OFF\n"
    }
    
    // Alert states
    if (state.stuckOnDetected) {
        def duration = getStuckOnDuration()
        status += "🔴 STUCK-ON ALERT: Device continuously on for ${duration}\n"
    } else if (state.leftOnDetected) {
        status += "🔴 CYCLING ALERT: ${state.sessionCycleCount} cycles detected\n"
    } else {
        status += "✓ No alerts\n"
    }
    
    // Cycle info
    def cycleHistorySize = state.cycleHistory?.size() ?: 0
    if (cycleHistorySize > 0) {
        status += "Cycles in window: ${cycleHistorySize} of ${cycleCount} threshold\n"
    }
    
    return status
}

def getCurrentStatistics() {
    def stats = ""
    
    // Session cycle count
    if (state.sessionCycleCount > 0) {
        stats += "Total cycles this session: ${state.sessionCycleCount}\n"
    }
    
    // Average ON time
    if (state.onDurations && state.onDurations.size() > 0) {
        def avgOn = state.onDurations.sum() / state.onDurations.size()
        stats += "Average ON: ${String.format('%.1f', avgOn as Double)}s\n"
    }
    
    // Average OFF time
    if (state.offDurations && state.offDurations.size() > 0) {
        def avgOff = state.offDurations.sum() / state.offDurations.size()
        stats += "Average OFF: ${String.format('%.1f', avgOff as Double)}s\n"
    }
    
    // Total runtime
    if (state.lastActivity && state.cycleHistory && state.cycleHistory.size() > 0) {
        def firstCycle = state.cycleHistory[0]
        def runtime = (state.lastActivity - firstCycle) / 1000.0 / 60.0
        stats += "Session runtime: ${String.format('%.1f', runtime as Double)}m\n"
    }
    
    // Previous session data
    if (state.lastTotalCycles) {
        stats += "\nPrevious Session:\n"
        stats += "Cycles: ${state.lastTotalCycles}\n"
        stats += "Runtime: ${state.lastRuntimeDisplay}\n"
        if (state.lastAvgOn) {
            def avgOn = safeToDouble(state.lastAvgOn)
            stats += "Avg ON: ${String.format('%.1f', avgOn)}s\n"
        }
        if (state.lastAvgOff) {
            def avgOff = safeToDouble(state.lastAvgOff)
            stats += "Avg OFF: ${String.format('%.1f', avgOff)}s\n"
        }
    }
    
    return stats
}

def getOnDuration() {
    if (!state.continuousOnStart) return "unknown"
    def duration = (now() - state.continuousOnStart) / 1000.0
    if (duration < 60) {
        return "${Math.round(duration)}s"
    } else {
        return "${String.format('%.1f', duration / 60.0)}m"
    }
}

def getStuckOnDuration() {
    if (!state.continuousOnStart) return "unknown"
    def duration = (now() - state.continuousOnStart) / 1000.0 / 60.0
    return "${String.format('%.1f', duration)}m"
}

def installed() {
    logDebug("Installed with settings: ${settings}")
    initialize()
}

def updated() {
    logDebug("Updated with settings: ${settings}")
    unsubscribe()
    unschedule()
    initialize()
}

def initialize() {
    logDebug("Initializing Power Cycle Monitor")
    
    // Initialize state variables if needed
    if (state.deviceOn == null) state.deviceOn = false
    if (!state.cycleHistory) state.cycleHistory = []
    if (!state.onDurations) state.onDurations = []
    if (!state.offDurations) state.offDurations = []
    if (state.leftOnDetected == null) state.leftOnDetected = false
    if (state.stuckOnDetected == null) state.stuckOnDetected = false
    if (state.sessionCycleCount == null) state.sessionCycleCount = 0
    
    // Initialize history tracking variables
    if (enableHistoryTracking) {
        if (!state.monthlySnapshots) state.monthlySnapshots = []
        if (!state.currentMonthSessions) state.currentMonthSessions = []
        if (!state.currentMonth) state.currentMonth = new Date().format("yyyy-MM")
        if (state.baselineMonthsCollected == null) state.baselineMonthsCollected = 0
        if (state.anomalyDetected == null) state.anomalyDetected = false
        if (state.stuckOnEventCountThisMonth == null) state.stuckOnEventCountThisMonth = 0
        if (state.sessionsThisMonth == null) state.sessionsThisMonth = 0
        if (state.sessionsToday == null) state.sessionsToday = 0
        if (!state.lastSessionDate) state.lastSessionDate = new Date().format("yyyy-MM-dd")
        
        // Schedule month rollover check every 12 hours (at midnight and noon)
        schedule("0 0 */12 * * ?", "checkMonthRollover")
    }
    
    // Subscribe to power meter events
    subscribe(powerMeter, "power", powerHandler)
    
    // Schedule regular check for device off (every 5 minutes)
    runEvery5Minutes("checkDeviceOff")
    
    // Update app label
    updateAppLabel()
    
    logDebug("Initialization complete")
}

def powerHandler(evt) {
    def power = evt.value.toFloat()
    def currentTime = now()
    
    logDebug("Power event: ${power}W at ${new Date(currentTime)}")
    
    state.lastActivity = currentTime
    
    // Update sessions today counter
    def today = new Date().format("yyyy-MM-dd")
    if (state.lastSessionDate != today) {
        state.sessionsToday = 0
        state.lastSessionDate = today
    }
    
    if (power >= wattThreshold) {
        handleDeviceOn(currentTime, power)
    } else {
        handleDeviceOff(currentTime)
    }
}

def handleDeviceOn(currentTime, power) {
    // Track wattage for history
    if (!state.currentSessionWattages) state.currentSessionWattages = []
    state.currentSessionWattages.add(power)

    if (!state.deviceOn) {
        // Device just turned ON
        log.info "Device turned ON (${power}W)"
        state.deviceOn = true
        state.continuousOnStart = currentTime
        
        // Calculate OFF duration if we have a previous state change time
        if (state.lastStateChangeTime) {
            def offDuration = (currentTime - state.lastStateChangeTime) / 1000.0
            if (!state.offDurations) state.offDurations = []
            state.offDurations.add(offDuration)
            logDebug("Recorded OFF duration: ${String.format('%.1f', offDuration)}s")
        }
        
        state.lastStateChangeTime = currentTime
        
        // Cancel any stuck-ON detection if it was active
        if (state.stuckOnDetected) {
            logDebug("Device turned off then back on - clearing stuck-ON alert")
            state.stuckOnDetected = false
            state.stuckOnAlertTime = null
            if (stuckOnAlertSwitch) {
                stuckOnAlertSwitch.off()
            }
            updateAppLabel()
        }
        
        // Schedule stuck-ON check
        def timeoutMs = stuckOnTimeout * 60 * 1000
        runInMillis(timeoutMs, "checkStuckOn")
    }
    
    updateAppLabel()
}

def handleDeviceOff(currentTime) {
    if (state.deviceOn) {
        // Device just turned OFF - this completes a cycle
        log.info "Device turned OFF (0W)"
        state.deviceOn = false
        
        // Calculate ON duration
        if (state.continuousOnStart) {
            def onDuration = (currentTime - state.continuousOnStart) / 1000.0
            if (!state.onDurations) state.onDurations = []
            state.onDurations.add(onDuration)
            logDebug("Recorded ON duration: ${String.format('%.1f', onDuration)}s")
        }
        
        state.lastStateChangeTime = currentTime
        state.continuousOnStart = null
        
        // Record this cycle
        recordCycle(currentTime)
        
        // Check if we should trigger alert
        checkForAlert(currentTime)
        
        updateAppLabel()
    }
}

def recordCycle(currentTime) {
    logDebug("Recording cycle at ${new Date(currentTime)}")
    
    // Add to cycle history
    if (!state.cycleHistory) state.cycleHistory = []
    state.cycleHistory.add(currentTime)
    
    // Increment session cycle counter
    state.sessionCycleCount = (state.sessionCycleCount ?: 0) + 1
    
    // Remove cycles outside time window
    def windowMs = timeWindow * 60 * 1000
    state.cycleHistory = state.cycleHistory.findAll { it > (currentTime - windowMs) }
    
    // Calculate current averages for info log
    def avgOnMsg = ""
    def avgOffMsg = ""
    if (state.onDurations && state.onDurations.size() > 0) {
        def avgOn = state.onDurations.sum() / state.onDurations.size()
        avgOnMsg = "ON:${Math.round(avgOn)}s"
    }
    if (state.offDurations && state.offDurations.size() > 0) {
        def avgOff = state.offDurations.sum() / state.offDurations.size()
        avgOffMsg = "OFF:${String.format('%.1f', avgOff / 60.0)}m"
    }
    
    log.info "Cycle ${state.sessionCycleCount} recorded (${avgOnMsg}/${avgOffMsg}) - ${state.cycleHistory.size()} in window"
    
    // Update app label if alert is already active (fixes cycle count display bug)
    if (state.leftOnDetected) {
        updateAppLabel()
    }
}

def checkForAlert(currentTime) {
    def cycleHistorySize = state.cycleHistory?.size() ?: 0
    
    if (cycleHistorySize >= cycleCount && !state.leftOnDetected) {
        log.info "🔴 CYCLING ALERT TRIGGERED: Detected ${cycleHistorySize} cycles in ${timeWindow} minutes (threshold: ${cycleCount})"
        state.leftOnDetected = true
        
        if (cycleAlertSwitch) {
            cycleAlertSwitch.on()
            log.info "Cycling alert switch turned ON"
        }
        
        updateAppLabel()
    }
}

def checkStuckOn() {
    // Only check if device is still ON and stuck-ON not already detected
    if (state.deviceOn && !state.stuckOnDetected && state.continuousOnStart) {
        def duration = (now() - state.continuousOnStart) / 1000.0 / 60.0
        
        if (duration >= stuckOnTimeout) {
            log.info "🔴 STUCK-ON ALERT: Device has been on continuously for ${String.format('%.1f', duration)} minutes"
            state.stuckOnDetected = true
            state.stuckOnAlertTime = now()
            
            if (stuckOnAlertSwitch) {
                stuckOnAlertSwitch.on()
                log.info "Stuck-ON alert switch turned ON"
            }
            
            // Log stuck-ON event to history
            if (enableHistoryTracking) {
                recordSessionData("stuck-on")
                state.stuckOnEventCountThisMonth = (state.stuckOnEventCountThisMonth ?: 0) + 1
            }
            
            updateAppLabel()
        }
    }
}

def checkDeviceOff() {
    // This runs every 5 minutes to check if device has been off long enough to reset
    if (!state.deviceOn && !state.leftOnDetected) {
        // Device is off and no alert - nothing to check
        return
    }
    
    if (!state.lastActivity) {
        // No activity recorded yet
        return
    }
    
    def timeSinceActivity = (now() - state.lastActivity) / 1000.0 / 60.0
    
    log.info "Checking device status - ${String.format('%.1f', timeSinceActivity)} minutes since last activity"
    
    if (timeSinceActivity >= offTimeout) {
        log.info "No activity detected for ${String.format('%.1f', timeSinceActivity)} minutes. Device appears to be truly OFF."
        resetStatistics()
    }
}

def resetStatistics() {
    log.info "Session ended - Resetting statistics (${state.sessionCycleCount ?: 0} total cycles)"
    
    // Save last session data before resetting
    if (state.sessionCycleCount > 0) {
        state.lastTotalCycles = state.sessionCycleCount
        
        if (state.onDurations && state.onDurations.size() > 0) {
            state.lastAvgOn = state.onDurations.sum() / state.onDurations.size()
        }
        
        if (state.offDurations && state.offDurations.size() > 0) {
            state.lastAvgOff = state.offDurations.sum() / state.offDurations.size()
        }
        
        if (state.cycleHistory && state.cycleHistory.size() > 0) {
            def firstCycle = state.cycleHistory[0]
            def lastCycle = state.lastActivity
            def runtime = (lastCycle - firstCycle) / 1000.0 / 60.0
            state.lastRuntime = runtime
            state.lastRuntimeDisplay = String.format('%.1f', runtime) + "m"
            
            log.info "Session summary: ${state.sessionCycleCount} cycles, ${state.lastRuntimeDisplay} runtime"
        }
        
        // Record session data to history if tracking enabled
        if (enableHistoryTracking) {
            recordSessionData("normal")
        }
    }
    
    // Reset state
    state.cycleHistory = []
    state.sessionCycleCount = 0
    state.onDurations = []
    state.offDurations = []
    state.leftOnDetected = false
    state.lastStateChangeTime = null  // Critical: prevents idle time in OFF calculations
    state.currentSessionWattages = []
    
    // Turn off alert switches if they were on
    if (cycleAlertSwitch && cycleAlertSwitch.currentValue("switch") == "on") {
        cycleAlertSwitch.off()
        log.info "Cycling alert switch turned OFF"
    }
    
    // Clear anomaly alert (latching switch)
    if (state.anomalyDetected || (anomalySwitch && anomalySwitch.currentValue("switch") == "on")) {
        state.anomalyDetected = false
        state.anomalyMessage = null
        if (anomalySwitch) {
            anomalySwitch.off()
            logDebug("Cleared anomaly alert and turned off switch")
        }
    }
    
    updateAppLabel()
}

// ═══════════════════════════════════════
// HISTORY TRACKING FUNCTIONS
// ═══════════════════════════════════════

def recordSessionData(type) {
    if (!enableHistoryTracking) return
    
    logDebug("Recording session data - type: ${type}")
    
    def sessionData = [:]
    sessionData.timestamp = new Date().format("yyyy-MM-dd HH:mm:ss")
    sessionData.type = type
    
    if (type == "stuck-on") {
        // Stuck-ON event data
        sessionData.cycles = 0
        sessionData.avgOn = state.continuousOnStart ? (now() - state.continuousOnStart) / 1000.0 : 0
        sessionData.avgOff = 0
        sessionData.runtime = sessionData.avgOn / 60.0
        sessionData.duration = sessionData.runtime
    } else {
        // Normal cycling session data
        sessionData.cycles = state.sessionCycleCount ?: 0
        
        if (state.onDurations && state.onDurations.size() > 0) {
            sessionData.avgOn = state.onDurations.sum() / state.onDurations.size()
        } else {
            sessionData.avgOn = 0
        }
        
        if (state.offDurations && state.offDurations.size() > 0) {
            sessionData.avgOff = state.offDurations.sum() / state.offDurations.size()
        } else {
            sessionData.avgOff = 0
        }
        
        if (state.cycleHistory && state.cycleHistory.size() > 0) {
            def firstCycle = state.cycleHistory[0]
            def lastCycle = state.lastActivity
            sessionData.duration = (lastCycle - firstCycle) / 1000.0 / 60.0
            sessionData.runtime = sessionData.duration  // For cycling sessions, these are the same
        } else {
            sessionData.duration = 0
            sessionData.runtime = 0
        }
    }
    
    // Calculate wattage metrics
    if (state.currentSessionWattages && state.currentSessionWattages.size() > 0) {
        sessionData.avgWattage = state.currentSessionWattages.sum() / state.currentSessionWattages.size()
        sessionData.peakWattage = state.currentSessionWattages.max()
        
        // Calculate total energy (watt-hours)
        // Energy = average power * time in hours
        sessionData.totalEnergy = sessionData.avgWattage * (sessionData.runtime / 60.0)
    } else {
        sessionData.avgWattage = 0
        sessionData.peakWattage = 0
        sessionData.totalEnergy = 0
    }
    
    // Add to current month sessions for median calculation
    if (!state.currentMonthSessions) state.currentMonthSessions = []
    state.currentMonthSessions.add(sessionData)
    
    // Increment session counters
    state.sessionsThisMonth = (state.sessionsThisMonth ?: 0) + 1
    state.sessionsToday = (state.sessionsToday ?: 0) + 1
    
    // Log to CSV file
    logSessionToFile(sessionData)
    
    // Check for anomalies
    if (type == "normal" && state.baselineMonthsCollected >= 2) {
        checkForAnomalies(sessionData)
    }
    
    logDebug("Session data recorded: ${sessionData.cycles} cycles, ${String.format('%.1f', sessionData.avgOn)}s ON, ${String.format('%.1f', sessionData.avgOff)}s OFF")
}

def logSessionToFile(sessionData) {
    try {
        def fileName = getFileName()
        def csvLine = formatCsvLine(sessionData)
        
        // Try to read existing file
        def fileContent = ""
        try {
            def existingData = downloadHubFile(fileName)
            if (existingData) {
                fileContent = new String(existingData)
            }
        } catch (Exception e) {
            logDebug("File doesn't exist yet, will create new: ${e.message}")
        }
        
        // Add header if file is empty
        if (!fileContent) {
            fileContent = "Timestamp,Type,Cycles,AvgOn,AvgOff,Runtime,Duration,AvgWattage,PeakWattage,TotalEnergy\n"
        }
        
        // Append new line
        fileContent += csvLine + "\n"
        
        // Write file
        uploadHubFile(fileName, fileContent.bytes)
        logDebug("Logged to file: ${fileName}")
        
    } catch (Exception e) {
        log.error "Error logging to file: ${e.message}"
    }
}

def formatCsvLine(sessionData) {
    return "${sessionData.timestamp}," +
           "${sessionData.type}," +
           "${sessionData.cycles}," +
           "${String.format('%.1f', sessionData.avgOn)}," +
           "${String.format('%.1f', sessionData.avgOff)}," +
           "${String.format('%.2f', sessionData.runtime)}," +
           "${String.format('%.2f', sessionData.duration)}," +
           "${String.format('%.1f', sessionData.avgWattage)}," +
           "${String.format('%.1f', sessionData.peakWattage)}," +
           "${String.format('%.2f', sessionData.totalEnergy)}"
}

def checkMonthRollover() {
    def currentMonth = new Date().format("yyyy-MM")
    
    if (state.currentMonth != currentMonth) {
        logDebug("Month changed from ${state.currentMonth} to ${currentMonth} - updating snapshot")
        updateMonthlySnapshot()
        
        // Reset monthly counters
        state.currentMonth = currentMonth
        state.currentMonthSessions = []
        state.stuckOnEventCountThisMonth = 0
        state.sessionsThisMonth = 0
        
        // Start new monthly file
        // (CSV file name changes with month, so no action needed here)
    }
}

def updateMonthlySnapshot() {
    if (!state.currentMonthSessions || state.currentMonthSessions.size() == 0) {
        logDebug("No sessions to snapshot for this month")
        return
    }
    
    // Calculate median session by runtime
    def sessions = state.currentMonthSessions.findAll { it.type == "normal" }
    if (sessions.size() == 0) {
        logDebug("No normal sessions to snapshot (only stuck-ON events)")
        return
    }
    
    def sortedByRuntime = sessions.sort { it.runtime }
    def medianIndex = Math.round(sortedByRuntime.size() / 2.0) - 1
    if (medianIndex < 0) medianIndex = 0
    def medianSession = sortedByRuntime[medianIndex]
    
    // Calculate sessions per day for the month
    def daysInMonth = getDaysInMonth(state.currentMonth)
    def avg = (state.sessionsThisMonth as Double) / (daysInMonth as Double)
    def sessionsPerDay = String.format('%.1f', avg)
    
    // Create snapshot
    def snapshot = [
        monthYear: new Date().parse("yyyy-MM", state.currentMonth).format("MMM yyyy"),
        cycles: medianSession.cycles,
        avgOn: medianSession.avgOn,
        avgOff: medianSession.avgOff,
        runtime: String.format('%.1f', medianSession.runtime as Double),
        sessionsPerDay: sessionsPerDay,
        stuckOnEvents: state.stuckOnEventCountThisMonth ?: 0,
        totalSessions: state.sessionsThisMonth
    ]
    
    // Add to snapshots (keep last 12)
    if (!state.monthlySnapshots) state.monthlySnapshots = []
    state.monthlySnapshots.add(0, snapshot)  // Add to front
    if (state.monthlySnapshots.size() > 12) {
        state.monthlySnapshots = state.monthlySnapshots.take(12)
    }
    
    // Increment baseline months counter
    state.baselineMonthsCollected = (state.baselineMonthsCollected ?: 0) + 1
    
    // Recalculate baseline
    calculateBaseline()
    
    logDebug("Monthly snapshot created for ${snapshot.monthYear}: ${snapshot.cycles} cycles, ${sessionsPerDay} sessions/day")
}

def calculateBaseline() {
    if (!state.monthlySnapshots || state.monthlySnapshots.size() < 2) {
        logDebug("Not enough data for baseline (need 2+ months)")
        return
    }
    
    // Use last 2-12 months for baseline
    def snapshotsForBaseline = state.monthlySnapshots.take(12)
    
    def totalOn = 0
    def totalOff = 0
    def count = 0
    
    snapshotsForBaseline.each { snapshot ->
        totalOn += snapshot.avgOn
        totalOff += snapshot.avgOff
        count++
    }
    
    state.baselineAvgOn = totalOn / count
    state.baselineAvgOff = totalOff / count
    state.baselineCycleTime = state.baselineAvgOn + state.baselineAvgOff
    
    logDebug("Baseline calculated: ${String.format('%.1f', safeToDouble(state.baselineCycleTime))}s cycle (${String.format('%.1f', safeToDouble(state.baselineAvgOn))}s ON, ${String.format('%.1f', safeToDouble(state.baselineAvgOff))}s OFF) from ${count} months")
}

def checkForAnomalies(sessionData) {
    if (!state.baselineCycleTime || state.baselineMonthsCollected < 2) {
        logDebug("Baseline not established yet, skipping anomaly check")
        return
    }
    
    def currentCycleTime = sessionData.avgOn + sessionData.avgOff
    def baselineCycle = safeToDouble(state.baselineCycleTime)
    def baselineOn = safeToDouble(state.baselineAvgOn)
    def baselineOff = safeToDouble(state.baselineAvgOff)
    
    def deviation = Math.abs(currentCycleTime - baselineCycle) / baselineCycle * 100.0
    
    logDebug("Anomaly check: current=${String.format('%.1f', currentCycleTime as Double)}s, baseline=${String.format('%.1f', baselineCycle)}s, deviation=${String.format('%.1f', deviation as Double)}%")
    
    if (deviation >= anomalyThreshold) {
        // Anomaly detected - determine what changed
        def onDeviation = Math.abs(sessionData.avgOn - baselineOn) / baselineOn * 100.0
        def offDeviation = Math.abs(sessionData.avgOff - baselineOff) / baselineOff * 100.0
        
        def anomalyMsg = ""
        if (onDeviation > 15 && offDeviation > 15) {
            anomalyMsg = "⚠️ Both ON/OFF increased"
        } else if (onDeviation > 15) {
            anomalyMsg = currentCycleTime > baselineCycle ? "⚠️ ON time up ${String.format('%.0f', onDeviation)}%" : "⚠️ ON time down ${String.format('%.0f', onDeviation)}%"
        } else if (offDeviation > 15) {
            anomalyMsg = currentCycleTime > baselineCycle ? "⚠️ OFF time up ${String.format('%.0f', offDeviation)}%" : "⚠️ OFF time down ${String.format('%.0f', offDeviation)}%"
        } else {
            anomalyMsg = "⚠️ Cycle time ${String.format('%.0f', deviation)}% off"
        }
        
        state.anomalyDetected = true
        state.anomalyMessage = "Cycles ${String.format('%.0f', deviation)}% ${currentCycleTime > baselineCycle ? 'longer' : 'shorter'} - ${anomalyMsg}"
        
        logDebug("ANOMALY DETECTED: ${state.anomalyMessage}")
        
        // Turn on anomaly switch if configured
        if (anomalySwitch) {
            anomalySwitch.on()
            logDebug("Turned on anomaly alert switch")
        }
        
    } else {
        // No anomaly - clear state but leave switch latched for user to reset
        if (state.anomalyDetected) {
            state.anomalyDetected = false
            state.anomalyMessage = null
            logDebug("Anomaly cleared - operation returned to normal (switch remains latched)")
            // Note: anomalySwitch stays ON (latching behavior) - user must manually reset
        }
    }
}

def getDaysInMonth(monthString) {
    def date = new Date().parse("yyyy-MM", monthString)
    def cal = Calendar.getInstance()
    cal.setTime(date)
    return cal.getActualMaximum(Calendar.DAY_OF_MONTH)
}

def getFileName() {
    def deviceName = powerMeter.displayName.replaceAll(/[^a-zA-Z0-9]/, "")
    def month = new Date().format("yyyy-MM")
    return "power-cycle-${deviceName}-${month}.csv"
}

def getHubIP() {
    return location.hub.localIP ?: "192.168.13.36"
}

def getFileManagerUrl(fileName) {
    return "http://${getHubIP()}/local/${fileName}"
}

// ═══════════════════════════════════════
// UI & UTILITY FUNCTIONS
// ═══════════════════════════════════════

def updateAppLabel() {
    // Get the prefix (default to power meter name if not set or empty)
    def prefix = (labelPrefix && labelPrefix.trim()) ? labelPrefix.trim() : (powerMeter ? powerMeter.displayName : "Power Cycle Monitor")
    
    logDebug("updateAppLabel - labelPrefix setting: '${labelPrefix}', powerMeter: ${powerMeter?.displayName}, using prefix: '${prefix}'")
    
    // Build dynamic status suffix
    def status = ""
    
    if (state.stuckOnDetected) {
        def duration = getStuckOnDuration()
        status = "🔴 STUCK-ON: ${duration}"
    } else if (state.leftOnDetected) {
        status = "🔴 ALERT: ${state.sessionCycleCount} cycles"
    } else if (state.cycleHistory?.size() > 0) {
        status = "Monitoring: ${state.cycleHistory.size()} cycles"
    } else {
        status = "✓ Normal"
    }
    
    // Combine prefix + status
    def label = "${prefix} - ${status}"
    app.updateLabel(label)
    logDebug("updateAppLabel - Set label to: '${label}'")
}

def appButtonHandler(btn) {
    switch(btn) {
        case "btnReset":
            logDebug("Manual reset button pressed")
            resetStatistics()
            break
    }
}

def logDebug(msg) {
    if (logEnable) {
        log.debug msg
    }
}

def safeToDouble(value) {
    // Safely convert any value to Double, handling formatted strings from old versions
    if (value == null) return 0.0
    if (value instanceof Number) return value as Double
    
    // If it's a string, try to extract the number
    try {
        // Remove common units and formatting
        def cleaned = value.toString().replaceAll(/[^\d.-]/, '')
        return cleaned ? (cleaned as Double) : 0.0
    } catch (Exception e) {
        logDebug("Warning: Could not parse '${value}' as number, using 0.0")
        return 0.0
    }
}
