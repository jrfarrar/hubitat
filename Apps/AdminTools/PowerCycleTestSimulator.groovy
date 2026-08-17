/**
 * Power Cycle Test Simulator v1.2
 *
 * Simulates cycling behavior for testing the Power Cycle Monitor app.
 * Controls a switch/outlet on and off with configurable timing and cycle counts.
 *
 * v1.2 - Added simulated wattage setting and setPower() calls
 * v1.1 - Fixed null pointer error on initial page load
 * v1.0 - Initial release
 *
 * Author: J.R. Farrar
 */

definition(
    name: "Power Cycle Test Simulator",
    namespace: "jrfarrar",
    author: "J.R. Farrar",
    description: "Simulate power cycling patterns for testing",
    category: "Convenience",
    iconUrl: "",
    iconX2Url: "",
    importUrl: "https://raw.githubusercontent.com/jrfarrar/hubitat/master/Apps/AdminTools/PowerCycleTestSimulator.groovy"
)

preferences {
    page(name: "mainPage")
}

def mainPage() {
    dynamicPage(name: "mainPage", title: "Power Cycle Test Simulator v1.2", install: true, uninstall: true) {
        
        section("Test Device") {
            input "testSwitch", "capability.switch", title: "Select Switch/Outlet to Control", required: true, multiple: false
            input "simulatedWatts", "number", title: "Simulated Power (watts)", required: true, defaultValue: 150, description: "Power reading when device is ON"
        }
        
        section("Cycle Configuration") {
            input "onDuration", "number", title: "ON Duration (seconds)", required: true, defaultValue: 30, description: "How long device stays ON"
            input "offDuration", "number", title: "OFF Duration (seconds)", required: true, defaultValue: 120, description: "How long device stays OFF"
            input "cycleCount", "number", title: "Number of Cycles", required: true, defaultValue: 5, description: "How many ON/OFF cycles to run"
        }
        
        section("Test Control") {
            if (state.testRunning) {
                paragraph "<div style='background-color:#fff3cd; padding:15px; border-radius:5px; border-left:4px solid #ff9800;'>" +
                         "<b>⚠️ TEST RUNNING</b><br>" +
                         "Cycle ${state.currentCycle ?: 0} of ${cycleCount}<br>" +
                         "Status: ${state.currentState ?: 'Unknown'}<br>" +
                         "Next action in ~${state.nextActionIn ?: '?'} seconds</div>"
                input "btnStop", "button", title: "⛔ Stop Test"
            } else {
                def totalTime = "?"
                if (onDuration && offDuration && cycleCount) {
                    def totalMinutes = ((onDuration + offDuration) * cycleCount / 60.0)
                    totalTime = String.format("%.1f", totalMinutes)
                }
                paragraph "<div style='background-color:#d4edda; padding:15px; border-radius:5px; border-left:4px solid #28a745;'>" +
                         "<b>✅ Ready to Start</b><br>" +
                         "Will run ${cycleCount ?: '?'} cycles<br>" +
                         "ON: ${onDuration ?: '?'}s / OFF: ${offDuration ?: '?'}s<br>" +
                         "Total time: ~${totalTime} minutes</div>"
                input "btnStart", "button", title: "▶️ Start Test Sequence"
            }
        }
        
        section("Test Log") {
            if (state.testLog) {
                paragraph "<div style='background-color:#f8f9fa; padding:10px; border-radius:5px; font-family:monospace; font-size:0.9em; max-height:200px; overflow-y:auto;'>${state.testLog}</div>"
                input "btnClearLog", "button", title: "Clear Log"
            } else {
                paragraph "<div style='color:#999;'>No test log yet. Start a test to see activity.</div>"
            }
        }
        
        section("App Settings") {
            input "logEnable", "bool", title: "Enable Debug Logging", defaultValue: false
        }
    }
}

def installed() {
    log.info "Power Cycle Test Simulator installed"
    initialize()
}

def updated() {
    log.info "Power Cycle Test Simulator updated"
    unsubscribe()
    unschedule()
    initialize()
}

def initialize() {
    state.testRunning = false
    state.currentCycle = 0
    if (!state.testLog) state.testLog = ""
}

def appButtonHandler(btn) {
    if (btn == "btnStart") {
        startTest()
    } else if (btn == "btnStop") {
        stopTest()
    } else if (btn == "btnClearLog") {
        state.testLog = ""
    }
}

def startTest() {
    logInfo "▶️ Starting test sequence: ${cycleCount} cycles"
    
    // Initialize state
    state.testRunning = true
    state.currentCycle = 0
    state.testLog = "${getTimestamp()} Test sequence started<br>"
    
    // Make sure device is OFF to start
    testSwitch.off()
    if (testSwitch.hasCommand("setPower")) {
        testSwitch.setPower(0)
    }
    logInfo "Turned device OFF to prepare for test"
    state.testLog += "${getTimestamp()} Device turned OFF (preparation)<br>"
    
    // Wait 3 seconds then start first cycle
    runIn(3, "startCycle")
    state.currentState = "Preparing..."
    state.nextActionIn = 3
}

def stopTest() {
    logInfo "⛔ Stopping test sequence"
    state.testLog += "${getTimestamp()} <b>Test manually stopped</b><br>"
    
    // Turn device OFF
    testSwitch.off()
    if (testSwitch.hasCommand("setPower")) {
        testSwitch.setPower(0)
    }
    logInfo "Turned device OFF"
    state.testLog += "${getTimestamp()} Device turned OFF<br>"
    
    // Clean up
    unschedule()
    state.testRunning = false
    state.currentCycle = 0
    state.currentState = "Stopped"
    state.nextActionIn = null
}

def startCycle() {
    if (!state.testRunning) return
    
    state.currentCycle = (state.currentCycle ?: 0) + 1
    
    if (state.currentCycle > cycleCount) {
        // Test complete
        completeTest()
        return
    }
    
    logInfo "🔴 Cycle ${state.currentCycle}/${cycleCount}: Turning device ON for ${onDuration}s"
    state.testLog += "${getTimestamp()} <b>Cycle ${state.currentCycle}/${cycleCount}</b>: Device ON (${onDuration}s)<br>"
    
    // Turn ON
    testSwitch.on()
    
    // Set power reading if device supports it
    if (testSwitch.hasCommand("setPower")) {
        testSwitch.setPower(simulatedWatts ?: 150)
        logInfo "Set power to ${simulatedWatts}W"
    }
    
    state.currentState = "ON (Cycle ${state.currentCycle})"
    state.nextActionIn = onDuration
    
    // Schedule OFF
    runIn(onDuration, "turnOff")
}

def turnOff() {
    if (!state.testRunning) return
    
    logInfo "🔵 Cycle ${state.currentCycle}/${cycleCount}: Turning device OFF for ${offDuration}s"
    state.testLog += "${getTimestamp()} Cycle ${state.currentCycle}: Device OFF (${offDuration}s)<br>"
    
    // Turn OFF
    testSwitch.off()
    
    // Set power to 0 if device supports it
    if (testSwitch.hasCommand("setPower")) {
        testSwitch.setPower(0)
        logInfo "Set power to 0W"
    }
    
    state.currentState = "OFF (Cycle ${state.currentCycle})"
    state.nextActionIn = offDuration
    
    // Schedule next cycle
    runIn(offDuration, "startCycle")
}

def completeTest() {
    logInfo "✅ Test sequence complete: ${cycleCount} cycles finished"
    state.testLog += "${getTimestamp()} <b>✅ Test sequence complete!</b><br>"
    state.testLog += "${getTimestamp()} Total cycles: ${cycleCount}<br>"
    
    // Make sure device is OFF
    testSwitch.off()
    if (testSwitch.hasCommand("setPower")) {
        testSwitch.setPower(0)
    }
    
    // Clean up
    state.testRunning = false
    state.currentCycle = 0
    state.currentState = "Complete"
    state.nextActionIn = null
}

def getTimestamp() {
    return new Date().format("HH:mm:ss")
}

def logInfo(msg) {
    if (logEnable) log.info msg
}

def logDebug(msg) {
    if (logEnable) log.debug msg
}