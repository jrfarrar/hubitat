/**
 * Power Cycle Monitor v2.41 (Smart Dashboard)
 *
 * Monitor power meters to detect cycling patterns and stuck-on failures
 * with historical tracking and trend analysis.
 *
 * v2.41 Changes — EQUIPMENT HEALTH DETECTION REWRITE:
 * - FIX: the anomaly switch was never turned off by any code path except the
 *   "Reset All Historical Data" button, while finishSession() silently cleared
 *   state.anomalyDetected. The switch latched ON and the UI showed no reason why.
 *   The latch is now deliberate and carries its reason, timestamp and value.
 * - FIX: anomaly detection compared a SINGLE session's (avgOn + avgOff) to the
 *   baseline. avgOff is set by household water demand, not by equipment, and on a
 *   3-cycle session it has only 2 samples. Measured over 10 months / 2662 cycles on
 *   a real well: session avgOff CV = 41%, tested against a 25% threshold. Every one
 *   of the 6 alerts fired on a 3-cycle session; 0 of 44 sessions with 4+ cycles ever
 *   tripped. It was detecting how often someone flushed a toilet.
 * - NEW: health is now judged on a trailing CYCLE-WEIGHTED window (default 100
 *   cycles, ~10 days) of avgOn, which is the equipment signal: correlation with
 *   session size r = -0.08, i.e. independent of demand. Back-test over the same
 *   10 months: old rule 24 false-alarm episodes, new rule 0.
 * - NEW: two distinct failure modes, reported by direction:
 *     avgOn RISING  -> pump/well weakening (worn impeller, dropping water level)
 *     cycle period COLLAPSING -> pressure tank losing air charge (short-cycling)
 *   Short-cycling is checked first; it destroys pumps in days, wear takes months.
 * - FIX: updateMonthlySnapshot() stored the MEDIAN SESSION's avgOn/avgOff/cycles as
 *   the month's figures. A single 15s-quantised session stood in for the whole month
 *   and fed the baseline. Measured error vs the true cycle-weighted monthly mean
 *   ranged -16.4% to +13.1%. Now cycle-weighted across all sessions in the month.
 * - NOTE ON RESOLUTION: the IoTaWatt parent driver polls on an interval (15s here),
 *   so each ON period is measured on that grid. With a ~27s ON period that is a
 *   per-cycle sd of ~6s, which alone accounts for essentially all the session-level
 *   scatter. It averages out over the window; it does not over a single session.
 *   This is why the window is expressed in CYCLES, not sessions or days.
 *
 * v2.40 Changes:
 * - FIX: Stuck-ON switch now clears when the pump actually stops (in handleDeviceOff),
 *   instead of staying latched until the next ON cycle. Prevents the stuck-on
 *   notification from firing/persisting for hours after the pump has already shut off.
 *
 * v2.39 Changes:
 * - FIX: Runtime calculation now correctly uses (cycles - 1) for OFF periods
 *   (3 cycles = 3 ON periods but only 2 OFF periods between them)
 *
 * v2.38 Changes:
 * - IMPROVED: Recent Sessions now shows date with time (M/d h:mm a format)
 *
 * v2.37 Changes:
 * - IMPROVED: Session end timing now much more accurate
 * - Changed heartbeat to run every 1 minute (was 5 minutes)
 * - Added immediate timeout check on every device OFF event
 * - Added scheduled check at exact timeout time for precision
 * - Session ends within ~1 minute of timeout instead of up to 5 minutes late
 * - FIX: Corrected subscription method reference format
 *
 * v2.36 Changes:
 * - FEATURE: Split reset functionality into two clearly labeled buttons
 * - NEW: "Clear Recent Sessions Table" button (below Recent Sessions section)
 * - NEW: "Reset All Historical Data" button (at bottom with warnings)
 * - UI: Clear descriptions explain exactly what each reset button does
 * - SAFETY: Historical reset now includes warning message before action
 */

import java.math.RoundingMode

definition(
        name: "Power Cycle Monitor",
        namespace: "jrfarrar",
        author: "J.R. Farrar",
        description: "Monitor power meters to detect cycling patterns and stuck-on failures with historical tracking",
        category: "Convenience",
        iconUrl: "",
        iconX2Url: "",
        iconX3Url: "",
        importUrl: "https://raw.githubusercontent.com/jrfarrar/hubitat/master/Apps/AdminTools/PowerCycleMonitor.groovy"
)

preferences {
    page(name: "mainPage")
    page(name: "toggleHistoryDisplay")
}

def mainPage() {
    dynamicPage(name: "mainPage", title: "Power Cycle Monitor v2.40", install: true, uninstall: true) {

        // 1. Device to Monitor
        section(getFormat("header-green", "Device to Monitor")) {
            input "powerMeter", "capability.powerMeter", title: "Select Power Meter", required: true, multiple: false
            input "wattThreshold", "number", title: "Watt Threshold (ON > this)", required: true, defaultValue: 100, description: "Watts"
        }

        // 2. Cycling Detection (Usage Monitor)
        section(getFormat("header-green", "Cycling Detection (Usage Monitor)")) {
            input "cycleCount", "number", title: "Cycles to Log/Alert", required: true, defaultValue: 3, description: "Sessions with fewer cycles will be ignored"
            input "timeWindow", "number", title: "Time Window (minutes)", required: true, defaultValue: 30
            input "offTimeout", "number", title: "Reset triggers if OFF for (min)", required: true, defaultValue: 60
            input "cycleAlertSwitch", "capability.switch", title: "Active Usage Switch (Optional)", required: false, description: "Turns ON when cycling detected"
        }

        // 3. Stuck-ON Detection (Failure Monitor)
        section(getFormat("header-green", "Stuck-ON Detection (Failure Monitor)")) {
            input "stuckOnTimeout", "number", title: "Alert if ON longer than (min)", required: true, defaultValue: 15
            input "stuckOnAlertSwitch", "capability.switch", title: "Stuck-ON Switch (Optional)", required: false, description: "Turns ON when stuck detected"
        }

        // 4. Current Session (always visible)
        section(getFormat("header-green", "Current Session")) {
            paragraph getSmartDashboardHtml()
        }

        // 5. Recent Sessions (always visible)
        section(getFormat("header-green", "Recent Sessions")) {
            if (!state.recentSessions) state.recentSessions = []

            if (state.recentSessions.size() == 0) {
                paragraph "<div style='background-color:#f0f0f0; padding:10px; border-radius:5px; margin-top:-10px;'><b>No session history yet...</b><br>Completed sessions will be shown here.</div>"
            } else {
                paragraph getHistoryTableHtml()
            }
            
            // Reset Recent Sessions button with description
            paragraph "<hr style='margin-top:15px; margin-bottom:10px;'>"
            input "btnResetRecent", "button", title: "Clear Recent Sessions Table"
            paragraph "<small style='color:#666;'>Clears only the table above and ends the current active session. All historical data (Monthly History, Baseline, CSV files) is preserved.</small>"
        }

        // 6. Historical Analysis and Anomaly Detection (master section for optional features)
        section(getFormat("header-green", "Historical Analysis and Anomaly Detection")) {
            input "enableHistoryTracking", "bool", title: "Enable Historical Analysis", defaultValue: true, submitOnChange: true
            
            if (settings.enableHistoryTracking) {
                // Equipment Health Detection
                paragraph "<div style='font-weight:bold; margin-top:15px; margin-bottom:10px; font-size:1.1em;'>Equipment Health Detection</div>"
                paragraph getWellHealthHtml()

                input "healthWindowCycles", "number", title: "Health Window (cycles)", range: "30..400", required: true, defaultValue: 100,
                        description: "Averaged over this many recent cycles. Smaller = faster but noisier."
                input "anomalyThreshold", "number", title: "Pump Wear Threshold (% change in avg ON time)", range: "5..50", required: true, defaultValue: 15
                input "shortCycleThreshold", "number", title: "Short-Cycle Threshold (% drop in cycle period)", range: "20..70", required: true, defaultValue: 40,
                        description: "Pressure tank losing its air charge"
                input "anomalySwitch", "capability.switch", title: "Health Alert Switch (Latching)", required: false

                paragraph "<hr style='margin-top:15px; margin-bottom:10px;'>"
                input "btnAckHealth", "button", title: "Acknowledge & Clear Health Alert"
                paragraph "<small style='color:#666;'>Clears the latched alert above and turns the alert switch off. The latch is deliberate: it stays on until you clear it, so an alert cannot be missed while you are away. Historical data and baseline are not affected.</small>"

                // Baseline Management
                paragraph "<div style='font-weight:bold; margin-top:15px; margin-bottom:10px; font-size:1.1em;'>Baseline Management</div>"
                input "lockBaseline", "bool", title: "Lock Current Baseline", defaultValue: false, submitOnChange: true
                paragraph getBaselineDisplayHtml()

                input "btnRebuildHistory", "button", title: "Rebuild Monthly History &amp; Baseline from CSV"
                paragraph "<small style='color:#666;'>Recomputes every monthly snapshot from this app's own CSV logs using the corrected cycle-weighted average. Snapshots written before v2.41 stored a single median session's numbers instead of the month's, so the baseline they produced was built from nine single sessions. Safe to run: reads the CSVs, does not modify them.${state.rebuildResult ? "<br><b>Last run:</b> ${state.rebuildResult}" : ''}</small>"
            }
        }

        // 7. Monthly History (only if Historical Analysis enabled)
        if (settings.enableHistoryTracking) {
            section(getFormat("header-green", "Monthly History")) {
                if (!state.monthlySnapshots) state.monthlySnapshots = []

                def monthsToShow = state.showFullHistory ? 12 : 6

                if (state.monthlySnapshots.size() == 0) {
                    paragraph "<div style='background-color:#f0f0f0; padding:10px; border-radius:5px; margin-top:-10px;'><b>Establishing Baseline...</b><br>Need at least 2 months of data for trend analysis.<br>Sessions this month: ${state.sessionsThisMonth ?: 0}</div>"
                } else {
                    paragraph getMonthlyHistoryTableHtml(monthsToShow)

                    // Toggle button for 6/12 months
                    if (state.monthlySnapshots.size() > 6) {
                        def btnText = state.showFullHistory ? "Show Less" : "Show 12 Months"
                        href name: "btnToggleHistory", title: btnText, description: "", page: "toggleHistoryDisplay"
                    }
                }
            }
            
            // 8. CSV Logging (only if Historical Analysis enabled)
            section(getFormat("header-green", "CSV Logging")) {
                input "enableCsvLogging", "bool", title: "Enable CSV File Logging", defaultValue: true, submitOnChange: true
                
                // CSV download link - RIGHT UNDER the CSV logging switch
                if (settings.enableCsvLogging) {
                    def fileName = getFileName()
                    def fileUrl = getFileManagerUrl(fileName)
                    paragraph "<div style='text-align:left; margin-top:5px;'><a href='${fileUrl}' target='_blank' style='font-weight:bold; text-decoration:none;'>📥 Download Full History (CSV)</a></div>"
                }
            }
        }

        // 9. Reset Historical Data
        section(getFormat("header-green", "⚠️ Reset Historical Data")) {
            paragraph "<div style='background-color:#fff3cd; padding:10px; border-radius:5px; border-left:4px solid #ff9800;'><b>Warning:</b> This action will permanently delete all historical tracking data including Monthly History, Baseline calculations, and monthly snapshots. CSV log files will remain on disk but can be manually deleted.</div>"
            input "btnResetHistory", "button", title: "⚠️ Reset All Historical Data"
            paragraph "<small style='color:#666;'>This will clear: Monthly History table, Baseline data, all monthly snapshots, and monthly counters. This action cannot be undone. Recent Sessions and CSV files are not affected.</small>"
        }

        // 10. App Settings
        section(getFormat("header-green", "App Settings")) {
            input "labelPrefix", "text", title: "App Label Prefix", required: false
            input "logEnable", "bool", title: "Enable Debug Logging", defaultValue: false
            input "btnRefresh", "button", title: "🔄 Refresh Subscriptions"
        }
    }
}

def toggleHistoryDisplay() {
    state.showFullHistory = !state.showFullHistory
    mainPage()
}

// ----------------------------------------------------------------------------
//   DISPLAY HELPERS (SMART DASHBOARD)
// ----------------------------------------------------------------------------

def getSmartDashboardHtml() {
    def status = getSystemStatus()
    def row = ""

    if (state.deviceOn || state.sessionCycleCount > 0) {
        def cycles = state.sessionCycleCount
        def avgOn = getAverage(state.onDurations)
        def avgOff = (state.sessionCycleCount > 1) ? "${String.format('%.1f', getAverage(state.offDurations))}s" : "N/A"
        def runtime = state.deviceOn ? getOnDuration() : "${String.format('%.1f', (avgOn * cycles) / 60.0)}m"
        
        row = "<tr><td style='color:${status.color};'>Live</td><td>${cycles}</td><td>${String.format('%.1f', avgOn)}s</td><td>${avgOff}</td><td>${runtime}</td></tr>"
    } else {
        if (state.lastSessionStats) {
            def stats = state.lastSessionStats
            row = "<tr><td>${stats.time}</td><td>${stats.cycles}</td><td>${stats.avgOn}s</td><td>${stats.avgOff}s</td><td>${stats.runtime}m</td></tr>"
        } else {
            return "<div style='background-color:#f0f0f0; padding:10px; border-radius:5px; margin-top:-10px;'><b>Waiting for first run...</b></div>"
        }
    }

    return """
    <table style='width:100%; font-size:0.9em; border-collapse:collapse; margin-top:-10px;' border='1' bordercolor='#ddd'>
        <tr style='background-color:#f5f5f5; font-weight:bold;'>
            <td>Time</td><td>Cycles</td><td>Avg On</td><td>Avg Off</td><td>Runtime</td>
        </tr>
        ${row}
    </table>
    """
}

def getBaselineDisplayHtml() {
    def bCycle = state.baselineCycleTime ? "${safeToBigDecimal(state.baselineCycleTime).setScale(1, RoundingMode.HALF_UP)}s" : "N/A"
    def bOn = state.baselineAvgOn ? "${safeToBigDecimal(state.baselineAvgOn).setScale(0, RoundingMode.HALF_UP)}s" : "N/A"
    def bOff = state.baselineAvgOff ? "${safeToBigDecimal(state.baselineAvgOff).setScale(0, RoundingMode.HALF_UP)}s" : "N/A"
    def statusText = lockBaseline ? "(Locked)" : "(Dynamic)"

    return "Current Baseline ${statusText}: ${bCycle} Cycle (${bOn} ON / ${bOff} OFF)"
}

def getWellHealthHtml() {
    def alert = state.healthAlert
    def html = ""

    // 1. The latched alert, if any -- always shown first, with its reason.
    if (alert) {
        html += "<div style='background-color:#fdecea; padding:10px; border-radius:5px; border-left:4px solid #cc0000; margin-bottom:10px;'>" +
                "<b style='color:#cc0000;'>&#9888; ${alert.mode}</b><br>" +
                "${alert.msg}<br>" +
                "<small style='color:#666;'>Latched ${alert.atStr} &middot; still latched until acknowledged</small>" +
                "</div>"
    }

    // 2. Live window state -- so "why is nothing happening" is always answerable.
    def w = computeHealthWindow(state.healthWindow ?: [], (healthWindowCycles ?: 100) as Integer)
    def bOn = safeToBigDecimal(state.baselineAvgOn)
    def bCyc = safeToBigDecimal(state.baselineCycleTime)

    if (!w.ready) {
        html += "<div style='background-color:#f0f0f0; padding:10px; border-radius:5px;'>" +
                "<b>Collecting data...</b><br>${w.cycles} of ${healthWindowCycles ?: 100} cycles " +
                "(${w.used} sessions). No health judgement until the window is full.</div>"
        return html
    }
    if (bOn <= 0 || bCyc <= 0) {
        html += "<div style='background-color:#f0f0f0; padding:10px; border-radius:5px;'>" +
                "<b>Waiting for baseline.</b><br>Monthly snapshots are needed before health can be " +
                "judged &mdash; or press <i>Rebuild Monthly History &amp; Baseline from CSV</i> below to " +
                "build one immediately from existing logs. Window is ready (${w.cycles} cycles).</div>"
        return html
    }

    def wearPct = ((w.on - bOn) / bOn) * 100.0
    def period = w.on + w.off
    def dropPct = ((bCyc - period) / bCyc) * 100.0
    def wearLimit = safeToBigDecimal(anomalyThreshold ?: 15)
    def dropLimit = safeToBigDecimal(shortCycleThreshold ?: 40)

    def okColor = (alert) ? "#e67e22" : "#27ae60"
    def verdict = (alert) ? "Currently within limits (alert above is latched from earlier)" : "Healthy"

    html += "<div style='background-color:#f7f7f7; padding:10px; border-radius:5px;'>" +
            "<b style='color:${okColor};'>${verdict}</b><br>" +
            "<table style='width:100%; font-size:0.9em; border-collapse:collapse; margin-top:8px;' border='1' bordercolor='#ddd'>" +
            "<tr style='background-color:#eee; font-weight:bold;'><td>Metric</td><td>Now</td><td>Baseline</td><td>Change</td><td>Limit</td></tr>" +
            "<tr><td>Avg ON (pump strength)</td><td>${String.format('%.2f', w.on)}s</td><td>${String.format('%.2f', bOn)}s</td>" +
            "<td>${String.format('%+.1f', wearPct)}%</td><td>&plusmn;${wearLimit}%</td></tr>" +
            "<tr><td>Cycle period (tank charge)</td><td>${String.format('%.1f', period)}s</td><td>${String.format('%.1f', bCyc)}s</td>" +
            "<td>${String.format('%+.1f', -dropPct)}%</td><td>-${dropLimit}%</td></tr>" +
            "</table>" +
            "<small style='color:#666;'>Averaged over the last ${w.cycles} cycles (${w.used} sessions). " +
            "Avg ON is the equipment signal; Avg OFF is household demand and is deliberately not alerted on.</small>" +
            "</div>"
    return html
}

def getHistoryTableHtml() {
    def rows = ""
    state.recentSessions.each { sess ->
        rows += "<tr>"
        rows += "<td>${sess.time}</td>"
        rows += "<td>${sess.cycles}</td>"
        rows += "<td>${sess.avgOn}s</td>"
        rows += "<td>${sess.avgOff}s</td>"
        rows += "<td>${sess.runtime}m</td>"
        rows += "</tr>"
    }

    return """
    <table style='width:100%; font-size:0.9em; border-collapse:collapse; margin-top:-10px;' border='1' bordercolor='#ddd'>
        <tr style='background-color:#f5f5f5; font-weight:bold;'>
            <td>Time</td><td>Cycles</td><td>Avg On</td><td>Avg Off</td><td>Runtime</td>
        </tr>
        ${rows}
    </table>
    """
}

def getMonthlyHistoryTableHtml(limit) {
    def rows = ""
    def list = state.monthlySnapshots.take(limit)

    list.each { snap ->
        def warn = (snap.stuckOnEvents > 0) ? "!" : ""
        def anom = (snap.anomaly) ? "!" : ""
        def bg = (snap.stuckOnEvents > 0 || snap.anomaly) ? "#fff0f0" : "#ffffff"

        rows += "<tr style='background-color:${bg}'>"
        rows += "<td>${snap.monthYear}</td>"
        rows += "<td>${snap.cycles}</td>"
        rows += "<td>${Math.round(safeToBigDecimal(snap.avgOn))}s</td>"
        rows += "<td>${Math.round(safeToBigDecimal(snap.avgOff))}s</td>"
        rows += "<td>${snap.sessionsPerDay}</td>"
        rows += "<td>${warn}${anom}</td>"
        rows += "</tr>"
    }

    return """
    <table style='width:100%; font-size:0.9em; border-collapse:collapse; margin-top:-10px;' border='1' bordercolor='#ddd'>
        <tr style='background-color:#f5f5f5; font-weight:bold;'>
            <td>Month</td><td>Cyc/mo</td><td>On</td><td>Off</td><td>/Day</td><td>Alert</td>
        </tr>
        ${rows}
    </table>
    """
}


// ----------------------------------------------------------------------------
//   CORE LOGIC & HANDLERS
// ----------------------------------------------------------------------------

def installed() {
    initialize()
}

def updated() {
    unsubscribe()
    unschedule()
    initialize()
}

def initialize() {
    logDebug "Initializing..."

    if (state.deviceOn == null) state.deviceOn = false
    if (!state.cycleHistory) state.cycleHistory = []
    if (!state.onDurations) state.onDurations = []
    if (!state.offDurations) state.offDurations = []
    if (state.sessionCycleCount == null) state.sessionCycleCount = 0
    if (!state.recentSessions) state.recentSessions = []

    if (enableHistoryTracking) {
        if (!state.monthlySnapshots) state.monthlySnapshots = []
        if (!state.currentMonthSessions) state.currentMonthSessions = []
        if (!state.currentMonth) state.currentMonth = new Date().format("yyyy-MM")
        if (state.healthWindow == null) state.healthWindow = []

        schedule("0 0 1 1 * ?", "checkMonthRollover") // Run yearly, but we check monthly
    }

    subscribe(powerMeter, "power", "powerHandler")
    runEvery1Minute("heartbeat")  // Check every minute for better timeout responsiveness
    updateAppLabel()
}

def powerHandler(evt) {
    def power = safeToBigDecimal(evt.value)
    def currentTime = now()

    state.lastActivity = currentTime
    checkDayRollover()

    if (power >= wattThreshold) {
        handleDeviceOn(currentTime, power)
    } else {
        handleDeviceOff(currentTime)
    }
}

def handleDeviceOn(currentTime, power) {
    if (!state.currentSessionWattages) state.currentSessionWattages = []
    state.currentSessionWattages.add(power)

    if (!state.deviceOn) {
        log.info "${powerMeter} turned ON (power: ${power}W)"
        state.deviceOn = true
        state.continuousOnStart = currentTime

        // Pressure Tank Logic (Ignore long idle)
        if (state.lastStateChangeTime) {
            def offSeconds = (currentTime - state.lastStateChangeTime) / 1000.0
            def thresholdSeconds = offTimeout * 60

            if (offSeconds < thresholdSeconds) {
                state.offDurations.add(offSeconds)
                logDebug "OFF Duration: ${formatDuration(offSeconds)} (Recorded)"
            } else {
                logDebug "OFF Duration: ${formatDuration(offSeconds)} (Ignored - exceeded idle threshold)"
                if (state.sessionCycleCount == 0) {
                    state.onDurations = []
                    state.offDurations = []
                }
            }
        }

        state.lastStateChangeTime = currentTime

        if (state.stuckOnDetected) {
            log.warn "Stuck-ON Cleared: Device cycled off/on."
            state.stuckOnDetected = false
            if (stuckOnAlertSwitch) stuckOnAlertSwitch.off()
        }

        runInMillis((stuckOnTimeout * 60 * 1000).toInteger(), "checkStuckOn")
        updateAppLabel()
    }
}

def handleDeviceOff(currentTime) {
    if (state.deviceOn) {
        def onSeconds = (currentTime - state.continuousOnStart) / 1000.0
        def avgWatts = getAverage(state.currentSessionWattages)
        def peakWatts = state.currentSessionWattages.max()

        log.info "${powerMeter} turned OFF. Last run: ${formatDuration(onSeconds)}, Avg Power: ${avgWatts.toInteger()}W, Peak: ${peakWatts.toInteger()}W"
        state.deviceOn = false

        // Clear Stuck-ON as soon as the pump actually stops (don't wait for the next ON cycle)
        if (state.stuckOnDetected) {
            log.warn "Stuck-ON Cleared: pump stopped."
            state.stuckOnDetected = false
            if (stuckOnAlertSwitch) stuckOnAlertSwitch.off()
        }

        if (state.continuousOnStart) {
            state.onDurations.add(onSeconds)
        }

        state.lastStateChangeTime = currentTime
        state.continuousOnStart = null

        recordCycle(currentTime)
        checkForCyclingAlert()
        checkDeviceOffReset()  // Check immediately if session should end
        
        // Schedule a check at exactly the timeout time for precision
        def timeoutSeconds = offTimeout * 60
        runIn(timeoutSeconds, "checkDeviceOffReset")
        
        updateAppLabel()
    }
}

def recordCycle(currentTime) {
    if (!state.cycleHistory) state.cycleHistory = []
    state.cycleHistory.add(currentTime)
    state.sessionCycleCount = (state.sessionCycleCount ?: 0) + 1

    def windowMs = timeWindow * 60 * 1000
    state.cycleHistory = state.cycleHistory.findAll { it > (currentTime - windowMs) }

    if (state.sessionCycleCount == 1) {
        log.info "--- NEW SESSION STARTED ---"
    }

    log.info "Cycle #${state.sessionCycleCount} | Window: ${state.cycleHistory.size()}/${cycleCount}"
}

def checkForCyclingAlert() {
    if (state.cycleHistory.size() >= cycleCount && !state.leftOnDetected) {
        log.info "CYCLING DETECTED: ${state.cycleHistory.size()} cycles detected in ${timeWindow} min (Usage Monitor)"
        state.leftOnDetected = true
        if (cycleAlertSwitch) cycleAlertSwitch.on()
    }
}

def checkStuckOn() {
    if (!state.deviceOn || !state.continuousOnStart) return

    def onDurationMinutes = (now() - state.continuousOnStart) / 1000.0 / 60.0

    if (onDurationMinutes >= stuckOnTimeout && !state.stuckOnDetected) {
        log.warn "STUCK-ON ALERT: Device ON for ${String.format('%.1f', onDurationMinutes)} min"
        state.stuckOnDetected = true

        if (stuckOnAlertSwitch) stuckOnAlertSwitch.on()

        if (enableHistoryTracking) {
            recordSessionData("stuck-on")
        }

        updateAppLabel()
    }
}

def heartbeat() {
    checkDeviceOffReset()
    if (state.deviceOn && !state.stuckOnDetected) {
        checkStuckOn()
    }
}

def checkDeviceOffReset() {
    if (state.deviceOn) return
    if (!state.lastActivity) return

    def minsSinceActivity = (now() - state.lastActivity) / 1000.0 / 60.0

    if (minsSinceActivity >= offTimeout && (state.sessionCycleCount > 0 || state.leftOnDetected)) {
        log.info "--- SESSION ENDED (${String.format('%.1f', minsSinceActivity)}m idle) ---"
        finishSession()
    }
}

def finishSession() {
    // 1. Capture Last Session Stats for Dashboard (Memory Only)
    if (state.sessionCycleCount > 0) {
        def avgOn = getAverage(state.onDurations)
        def avgOff = getAverage(state.offDurations)
        // Runtime = (ON time × cycles) + (OFF time × (cycles - 1))
        // Because 3 cycles have 3 ON periods but only 2 OFF periods between them
        def offPeriods = Math.max(0, state.sessionCycleCount - 1)
        def totalSeconds = (avgOn * state.sessionCycleCount) + (avgOff * offPeriods)

        def session = [
                time: new Date().format("M/d h:mm a"),
                cycles: state.sessionCycleCount,
                avgOn: String.format('%.1f', avgOn),
                avgOff: String.format('%.1f', avgOff),
                runtime: String.format('%.1f', totalSeconds / 60.0)
        ]
        state.lastSessionStats = session

        if (!state.recentSessions) state.recentSessions = []
        state.recentSessions.add(0, session)
        if (state.recentSessions.size() > 10) state.recentSessions = state.recentSessions.take(10)
    }

    // 2. Log to CSV only if it meets threshold
    if (enableHistoryTracking) {
        if (state.sessionCycleCount >= cycleCount) {
            recordSessionData("normal")
        } else {
            logDebug "Ignored session with ${state.sessionCycleCount} cycles (Threshold: ${cycleCount})"
        }
    }

    // 3. Reset
    state.cycleHistory = []
    state.sessionCycleCount = 0
    state.onDurations = []
    state.offDurations = []
    state.leftOnDetected = false
    state.currentSessionWattages = []

    if (cycleAlertSwitch && cycleAlertSwitch.currentValue("switch") == "on") {
        cycleAlertSwitch.off()
    }

    // NOTE (v2.41): this used to clear state.anomalyDetected here, which erased the
    // on-screen reason for an alert while leaving the alert SWITCH latched on. The
    // result was a switch that read ON with the app reporting "System Idle" and no
    // explanation anywhere. The health latch now lives in state.healthAlert and is
    // cleared only by acknowledgement -- deliberately not here.

    updateAppLabel()
}

// ----------------------------------------------------------------------------
//   DATA LOGGING & BASELINE
// ----------------------------------------------------------------------------

def recordSessionData(type) {
    def data = [:]
    data.timestamp = new Date().format("yyyy-MM-dd HH:mm:ss")
    data.type = type

    if (type == "stuck-on") {
        data.cycles = 0
        data.avgOn = state.continuousOnStart ? (now() - state.continuousOnStart) / 1000.0 : 0.0
        data.avgOff = 0.0
        data.runtime = data.avgOn / 60.0
        state.stuckOnEventCountThisMonth = (state.stuckOnEventCountThisMonth ?: 0) + 1
    } else {
        data.cycles = state.sessionCycleCount ?: 0
        data.avgOn = getAverage(state.onDurations)
        data.avgOff = getAverage(state.offDurations)

        // Runtime = (ON time × cycles) + (OFF time × (cycles - 1))
        def offPeriods = Math.max(0, data.cycles - 1)
        def totalSeconds = (data.avgOn * data.cycles) + (data.avgOff * offPeriods)
        data.runtime = totalSeconds / 60.0
    }

    if (!state.currentMonthSessions) state.currentMonthSessions = []
    state.currentMonthSessions.add(data)
    state.sessionsThisMonth = (state.sessionsThisMonth ?: 0) + 1

    if (settings.enableCsvLogging) {
        logSessionToFile(data)
    }

    // Always feed the rolling window, even before a baseline exists, so the window is
    // already full the moment the baseline becomes usable. evaluateWellHealth() guards
    // its own preconditions and names whichever guard suppressed a judgement.
    if (type == "normal") {
        evaluateWellHealth(data)
    }
}

def logSessionToFile(data) {
    def fileName = getFileName()
    def line = "${data.timestamp},${data.type},${data.cycles}," +
            "${String.format('%.1f', safeToBigDecimal(data.avgOn))}," +
            "${String.format('%.1f', safeToBigDecimal(data.avgOff))}," +
            "${String.format('%.2f', safeToBigDecimal(data.runtime))}"

    def fileContent = ""
    try {
        def existingBytes = downloadHubFile(fileName)
        if (existingBytes) {
            fileContent = new String(existingBytes)
        }
    } catch (e) {
        logDebug "Creating new log file: ${fileName}"
    }

    if (!fileContent) {
        fileContent = "Time,Type,Cycles,AvgOn,AvgOff,SessionDurationMin\n"
    }

    try {
        fileContent += "${line}\n"
        uploadHubFile(fileName, fileContent.bytes)
        logDebug "Saved session to CSV"
    } catch (e) {
        log.error "Failed to write CSV: ${e.message}"
    }
}

def checkMonthRollover() {
    def nowMonth = new Date().format("yyyy-MM")
    if (state.currentMonth != nowMonth) {
        log.info "Month Rollover: ${state.currentMonth} -> ${nowMonth}"
        updateMonthlySnapshot()
        state.currentMonth = nowMonth
        state.currentMonthSessions = []
        state.stuckOnEventCountThisMonth = 0
        state.sessionsThisMonth = 0
    }
}

def updateMonthlySnapshot() {
    if (!state.currentMonthSessions) return

    def validSessions = state.currentMonthSessions.findAll { it.type == "normal" }
    if (validSessions.size() == 0) return

    // v2.41: this used to take the MEDIAN SESSION by runtime and store that one
    // session's avgOn/avgOff/cycles as the whole month's figures -- so a single
    // 15s-quantised session represented the month AND fed the baseline. Measured
    // against the true cycle-weighted monthly mean over 10 real months, the stored
    // value was off by -16.4% to +13.1%. Now weighted across every session, by cycles,
    // which is the correct estimator: a 12-cycle session carries 4x the information
    // of a 3-cycle one.
    def totCycles = 0.0
    def sumOn = 0.0
    def sumOff = 0.0
    validSessions.each { s ->
        def c = safeToBigDecimal(s.cycles)
        if (c > 0) {
            totCycles += c
            sumOn += safeToBigDecimal(s.avgOn) * c
            sumOff += safeToBigDecimal(s.avgOff) * c
        }
    }
    if (totCycles <= 0) return

    def snap = [
            monthYear: new Date().parse("yyyy-MM", state.currentMonth).format("MMM yyyy"),
            cycles: totCycles as Integer,
            avgOn: (sumOn / totCycles),
            avgOff: (sumOff / totCycles),
            stuckOnEvents: state.stuckOnEventCountThisMonth ?: 0,
            sessionsPerDay: String.format('%.1f', (state.sessionsThisMonth / 30.0)),
            anomaly: (state.healthAlert != null)
    ]

    state.monthlySnapshots.add(0, snap)
    if (state.monthlySnapshots.size() > 12) state.monthlySnapshots = state.monthlySnapshots.take(12)

    state.baselineMonthsCollected = (state.baselineMonthsCollected ?: 0) + 1
    calculateBaseline()
}

def calculateBaseline() {
    if (settings.lockBaseline) {
        log.info "Baseline is LOCKED. Skipping update."
        return
    }

    def list = state.monthlySnapshots.take(12)
    if (list.size() < 2) return

    def totOn = 0.0
    def totOff = 0.0
    list.each {
        totOn += safeToBigDecimal(it.avgOn)
        totOff += safeToBigDecimal(it.avgOff)
    }

    state.baselineAvgOn = totOn / list.size()
    state.baselineAvgOff = totOff / list.size()
    state.baselineCycleTime = state.baselineAvgOn + state.baselineAvgOff

    log.info "Baseline Updated: ${state.baselineCycleTime}s Cycle"
}

// ----------------------------------------------------------------------------
//   EQUIPMENT HEALTH DETECTION
//
//   Judged on a trailing CYCLE-WEIGHTED window, never on a single session.
//   avgOn  = time to refill the tank's drawdown  -> pump / well / lift  (EQUIPMENT)
//   avgOff = time for the house to drain it      -> demand              (NOT equipment)
//   Only avgOn and the cycle period are alerted on.
// ----------------------------------------------------------------------------

// Accumulate the trailing `windowCycles` cycles, weighting each session by its
// cycle count. Returns [ready:false, ...] while still filling -- never a verdict.
def computeHealthWindow(win, windowCycles) {
    def accC = 0.0
    def accOn = 0.0
    def accOff = 0.0
    def used = 0

    if (win) {
        for (int i = 0; i < win.size(); i++) {
            if (accC >= windowCycles) break
            def s = win[i]
            def c = safeToBigDecimal(s?.c)
            if (c <= 0) continue
            accC += c
            accOn += safeToBigDecimal(s?.on) * c
            accOff += safeToBigDecimal(s?.off) * c
            used++
        }
    }

    if (accC < windowCycles) {
        return [ready: false, cycles: accC, used: used]
    }
    return [ready: true, cycles: accC, used: used, on: (accOn / accC), off: (accOff / accC)]
}

def evaluateWellHealth(data) {
    // 1. Push this session onto the rolling window (newest first).
    def win = state.healthWindow ?: []
    win.add(0, [c: (data.cycles ?: 0), on: safeToBigDecimal(data.avgOn), off: safeToBigDecimal(data.avgOff)])

    // Keep enough sessions to always cover the window with headroom, then stop.
    def keep = 80
    if (win.size() > keep) win = win.take(keep)
    state.healthWindow = win

    def windowCycles = (healthWindowCycles ?: 100) as Integer
    def w = computeHealthWindow(win, windowCycles)

    // 2. Guards. Each one names ITSELF -- a suppressed judgement must be diagnosable,
    //    because months of silently-skipped evaluation looks identical to months of health.
    if (!w.ready) {
        state.healthStatus = "collecting: ${w.cycles}/${windowCycles} cycles"
        logDebug "Health check skipped [GUARD: window filling] ${w.cycles}/${windowCycles} cycles"
        return
    }
    def bOn = safeToBigDecimal(state.baselineAvgOn)
    def bCyc = safeToBigDecimal(state.baselineCycleTime)
    if (bOn <= 0) {
        state.healthStatus = "no baseline avgOn yet"
        log.info "Health check skipped [GUARD: baselineAvgOn missing/zero]"
        return
    }
    if (bCyc <= 0) {
        state.healthStatus = "no baseline cycle time yet"
        log.info "Health check skipped [GUARD: baselineCycleTime missing/zero]"
        return
    }

    // 3. Judge.
    def wearPct = ((w.on - bOn) / bOn) * 100.0
    def period = w.on + w.off
    def dropPct = ((bCyc - period) / bCyc) * 100.0
    def wearLimit = safeToBigDecimal(anomalyThreshold ?: 15)
    def dropLimit = safeToBigDecimal(shortCycleThreshold ?: 40)

    state.healthAvgOn = w.on
    state.healthPeriod = period
    state.healthWearPct = wearPct
    state.healthDropPct = dropPct

    // Short-cycling first: it is the urgent one. A tank that has lost its air charge
    // will cycle a pump to death in days, where wear takes months.
    if (dropPct >= dropLimit) {
        raiseHealthAlert("SHORT-CYCLING (pressure tank)",
                "Cycle period fell ${String.format('%.0f', dropPct)}% " +
                "(${String.format('%.0f', period)}s vs ${String.format('%.0f', bCyc)}s baseline) " +
                "over the last ${w.cycles} cycles. Typically the pressure tank losing its air charge. " +
                "Check tank pre-charge before the pump is damaged.")
        return
    }

    // A DEVELOPING waterlog trips the avgOn test before the period test, because avgOn
    // is a third of the period and so crosses its own threshold first. Without this
    // branch the first alert a user sees says "check the pressure switch" when the real
    // fault is the tank -- the harness caught exactly that (T4). Losing drawdown shrinks
    // the ON and OFF halves TOGETHER, so a falling avgOn accompanied by a falling period
    // is the tank signature, not a pressure-switch one. Same mode string as above, so
    // this escalates the message in place rather than latching a second time.
    if (wearPct <= -wearLimit && dropPct >= wearLimit) {
        raiseHealthAlert("SHORT-CYCLING (pressure tank)",
                "Cycle period is falling (${String.format('%.0f', dropPct)}% down, " +
                "${String.format('%.0f', period)}s vs ${String.format('%.0f', bCyc)}s baseline) " +
                "with average ON time down ${String.format('%.1f', wearPct.abs())}% " +
                "over the last ${w.cycles} cycles. Both halves of the cycle shrinking together " +
                "means the tank is losing drawdown volume. Check tank pre-charge.")
        return
    }

    if (wearPct >= wearLimit) {
        raiseHealthAlert("PUMP WEAKENING",
                "Average ON time rose ${String.format('%.1f', wearPct)}% " +
                "(${String.format('%.1f', w.on)}s vs ${String.format('%.1f', bOn)}s baseline) " +
                "over the last ${w.cycles} cycles, with demand unchanged. " +
                "Consistent with a worn pump, a dropping water level, or a leaking check valve.")
        return
    }
    // avgOn down but the cycle PERIOD holding steady -- drawdown is unchanged, so this
    // is not the tank. Pressure switch, or the meter's reporting interval changed.
    if (wearPct <= -wearLimit) {
        raiseHealthAlert("RUN TIME DROPPED",
                "Average ON time fell ${String.format('%.1f', wearPct.abs())}% " +
                "(${String.format('%.1f', w.on)}s vs ${String.format('%.1f', bOn)}s baseline) " +
                "over the last ${w.cycles} cycles, but the cycle period is steady " +
                "(${String.format('%+.0f', -dropPct)}%). Drawdown looks unchanged, so this is more " +
                "likely the pressure switch or a change in meter reporting interval than the tank.")
        return
    }

    state.healthStatus = "ok (avgOn ${String.format('%+.1f', wearPct)}%, period ${String.format('%+.1f', -dropPct)}%)"
    state.anomalyDetected = false
}

// The latch. It is intentional that nothing here clears the switch: an unattended
// alarm that clears itself is an alarm nobody sees. Cleared only by acknowledgement.
def raiseHealthAlert(mode, msg) {
    state.healthStatus = msg
    state.anomalyDetected = true
    state.anomalyMessage = msg

    // Already latched for this same mode -- refresh the text, don't re-log or re-command.
    if (state.healthAlert && state.healthAlert.mode == mode) {
        state.healthAlert.msg = msg
        return
    }

    state.healthAlert = [
            mode : mode,
            msg  : msg,
            at   : now(),
            atStr: new Date().format("M/d/yyyy h:mm a")
    ]
    log.warn "WELL HEALTH ALERT -- ${mode}: ${msg}"
    if (anomalySwitch) anomalySwitch.on()
    updateAppLabel()
}

def clearHealthAlert(reason) {
    if (!state.healthAlert && !state.anomalyDetected) {
        log.info "No health alert to clear."
        return
    }
    log.info "Health alert cleared (${reason}): ${state.healthAlert?.mode ?: 'none'}"
    state.healthAlert = null
    state.anomalyDetected = false
    state.anomalyMessage = null
    if (anomalySwitch && anomalySwitch.currentValue("switch") == "on") {
        anomalySwitch.off()
    }
    updateAppLabel()
}

// Rebuild every monthly snapshot from this app's own CSV logs, using the corrected
// cycle-weighted estimator. Needed after v2.41 because snapshots written by <=v2.40
// hold a single median session's numbers, and the baseline is the mean of those.
// Reads only files this app wrote. Does not touch the CSVs themselves.
def rebuildHistoryFromCsv() {
    def rebuilt = []
    def missing = []
    def cal = new Date()

    // Walk back 14 months from the current month.
    for (int back = 0; back < 14; back++) {
        def d = new Date(now() - (back * 30L * 24L * 60L * 60L * 1000L))
        def monthStr = d.format("yyyy-MM")
        if (rebuilt.find { it.monthKey == monthStr }) continue

        def fname = "power-cycle-${powerMeter.displayName.replaceAll(/[^a-zA-Z0-9]/, '')}-${monthStr}.csv"
        def text = null
        try {
            def bytes = downloadHubFile(fname)
            if (bytes) text = new String(bytes)
        } catch (e) {
            missing.add(monthStr)
            continue
        }
        if (!text) { missing.add(monthStr); continue }

        def totCycles = 0.0
        def sumOn = 0.0
        def sumOff = 0.0
        def sessions = 0
        def stuckCount = 0

        text.split("\n").each { line ->
            def p = line.trim().split(",")
            if (p.size() < 5) return
            if (p[0] == "Time") return
            def type = p[1]
            if (type == "stuck-on") { stuckCount++; return }
            def c = safeToBigDecimal(p[2])
            if (c <= 0) return
            totCycles += c
            sumOn += safeToBigDecimal(p[3]) * c
            sumOff += safeToBigDecimal(p[4]) * c
            sessions++
        }

        if (totCycles <= 0) { missing.add(monthStr); continue }

        rebuilt.add([
                monthKey     : monthStr,
                monthYear    : Date.parse("yyyy-MM", monthStr).format("MMM yyyy"),
                cycles       : totCycles as Integer,
                avgOn        : (sumOn / totCycles),
                avgOff       : (sumOff / totCycles),
                stuckOnEvents: stuckCount,
                sessionsPerDay: String.format('%.1f', (sessions / 30.0)),
                anomaly      : false
        ])
    }

    if (rebuilt.size() < 2) {
        log.warn "Rebuild aborted [GUARD: only ${rebuilt.size()} month(s) of CSV found]. Missing: ${missing}"
        state.rebuildResult = "FAILED - only ${rebuilt.size()} month(s) readable"
        return
    }

    // Newest first, drop the partial current month from the baseline if it is thin.
    rebuilt.sort { a, b -> b.monthKey <=> a.monthKey }
    state.monthlySnapshots = rebuilt.take(12)
    state.baselineMonthsCollected = state.monthlySnapshots.size()

    // Recompute the baseline the same way calculateBaseline() does, but weighted by
    // each month's cycle count so a thin month cannot swing it.
    def tc = 0.0
    def so = 0.0
    def sf = 0.0
    state.monthlySnapshots.each { m ->
        def c = safeToBigDecimal(m.cycles)
        tc += c
        so += safeToBigDecimal(m.avgOn) * c
        sf += safeToBigDecimal(m.avgOff) * c
    }
    state.baselineAvgOn = so / tc
    state.baselineAvgOff = sf / tc
    state.baselineCycleTime = state.baselineAvgOn + state.baselineAvgOff

    def msg = "Rebuilt ${state.monthlySnapshots.size()} months from CSV (${tc as Integer} cycles). " +
            "Baseline: ${String.format('%.2f', state.baselineAvgOn)}s ON / " +
            "${String.format('%.2f', state.baselineAvgOff)}s OFF / " +
            "${String.format('%.2f', state.baselineCycleTime)}s cycle."
    log.info msg
    state.rebuildResult = msg
}

// ----------------------------------------------------------------------------
//   UTILITIES
// ----------------------------------------------------------------------------

def getSystemStatus() {
    if (state.stuckOnDetected) {
        return [text: "STUCK ON ALERT", color: "#cc0000"]
    } else if (state.healthAlert) {
        // Surfaced in the app label so a latched health alert is visible from the app
        // list without opening the app. This is the piece that was missing: the switch
        // was on and every screen said "System Idle".
        return [text: state.healthAlert.mode, color: "#cc0000"]
    } else if (state.leftOnDetected) {
        return [text: "Cycling Detected", color: "#e67e22"]
    } else if (state.deviceOn) {
        return [text: "Pump Running", color: "#27ae60"]
    }
    return [text: "System Idle", color: "#666"]
}

def updateAppLabel() {
    def prefix = labelPrefix ?: powerMeter.displayName
    def status = getSystemStatus()
    app.updateLabel("${prefix} - ${status.text}")
}

def appButtonHandler(btn) {
    if (btn == "btnAckHealth") {
        clearHealthAlert("acknowledged from app UI")
    } else if (btn == "btnRebuildHistory") {
        rebuildHistoryFromCsv()
    } else if (btn == "btnResetRecent") {
        log.info "Clearing Recent Sessions table and ending current session"
        state.recentSessions = []
        finishSession()
    } else if (btn == "btnResetHistory") {
        log.warn "⚠️ RESETTING ALL HISTORICAL DATA - This cannot be undone!"
        
        // Clear all historical tracking data
        state.monthlySnapshots = []
        state.currentMonthSessions = []
        state.baselineAvgOn = null
        state.baselineAvgOff = null
        state.baselineCycleTime = null
        state.baselineMonthsCollected = 0
        state.stuckOnEventCountThisMonth = 0
        state.sessionsThisMonth = 0
        state.sessionsToday = 0
        state.anomalyDetected = false
        state.anomalyMessage = null
        state.showFullHistory = false
        state.healthWindow = []
        state.healthStatus = null
        state.healthAvgOn = null
        state.healthPeriod = null
        state.healthWearPct = null
        state.healthDropPct = null

        clearHealthAlert("historical data reset")

        log.info "All historical data has been reset. CSV files remain on disk."
    }
}

def checkDayRollover() {
    def today = new Date().format("yyyy-MM-dd")
    if (state.lastSessionDate != today) {
        state.sessionsToday = 0
        state.lastSessionDate = today
    }
}

def safeToBigDecimal(val) {
    if (val == null) return BigDecimal.ZERO
    try {
        if (val instanceof BigDecimal) return val
        def clean = val.toString().replaceAll("[^\\d.-]", "")
        return clean ? new BigDecimal(clean) : BigDecimal.ZERO
    } catch (e) {
        return BigDecimal.ZERO
    }
}

def getAverage(list) {
    if (!list || list.size() == 0) return BigDecimal.ZERO
    def sum = list.collect { safeToBigDecimal(it) }.sum()
    return (sum / new BigDecimal(list.size()))
}

def getFileName() {
    def cleanName = powerMeter.displayName.replaceAll(/[^a-zA-Z0-9]/, "")
    def dateStr = new Date().format("yyyy-MM")
    return "power-cycle-${cleanName}-${dateStr}.csv"
}

def getFileManagerUrl(filename) {
    return "/local/${filename}"
}

def getOnDuration() {
    if (!state.continuousOnStart) return ""
    def sec = (now() - state.continuousOnStart) / 1000
    return formatDuration(sec)
}

def formatDuration(seconds) {
    def s = safeToBigDecimal(seconds)
    if (s < 60) return "${s.setScale(0, RoundingMode.HALF_UP)}s"
    return "${(s/60).setScale(1, RoundingMode.HALF_UP)}m"
}

def logDebug(msg) {
    if (logEnable) log.debug msg
}

def getFormat(type, text="") {
    if (type == "header-green") return "<div style='color:#fff;font-weight:bold;background:#81BC00;border:1px solid;box-shadow:2px 3px #A9A9A9;padding:5px'>${text}</div>"
}