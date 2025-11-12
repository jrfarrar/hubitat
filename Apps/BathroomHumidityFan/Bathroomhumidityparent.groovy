/*
 *	Bathroom Humidity Fan (Parent)
 *
 *  Parent app for managing multiple bathroom humidity fan instances
 *  and creating a combined average humidity sensor.
 * 
 *  CORRECTED VERSION - v1.1.46
 *  - Fixed division by zero risk
 *  - Fixed integer division bug
 *  - Added comprehensive error handling
 *  - Added null safety throughout
 *  - Improved button handler logic
 *  - Added validation for sensor values
 */

def setVersion(){
	state.version = "1.1.46" // Version number to match child app
	state.InternalName = "BathroomHumidityFan"
}

definition(
    name: "Bathroom Humidity Fan",
    namespace: "jrfarrar",
    singleInstance: true,
    author: "J.R. Farrar",
    description: "Control a fan (switch) based on relative humidity. - Parent",
    category: "Convenience",
    iconUrl: "",
    iconX2Url: "",
    iconX3Url: "",
    importUrl: "https://raw.githubusercontent.com/jrfarrar/hubitat/refs/heads/master/Apps/BathroomHumidityFan/Bathroomhumidityparent.groovy")

preferences {
	page(name: "mainPage")
}

def mainPage() {
	return dynamicPage(name: "mainPage", title: "", install: true, uninstall: true) {
        if(!state.ShfInstalled) {
            section("Hit Done to install Bathroom Humidity Fan App") {
        	}
        }
        else {
        	section("<b>Create a new Bathroom Humidity Fan Instance.</b>") {
            	app(name: "childApps", appName: "Bathroom Humidity Fan Child", namespace: "Craig.Romei", 
                    title: "New Bathroom Humidity Fan Instance", multiple: true)
        	}
            section("<b>Create a combined humidity sensor.</b>") {
			    input "humidSensors", "capability.relativeHumidityMeasurement", 
                      title: "Select Humidity Sensors", submitOnChange: true, required: false, multiple: true
                input name: "Create", type: "button", title: state.createCombinedSensorButtonName, 
                      submitOnChange:true, width: 5
			    if(humidSensors) {
                    paragraph "Current average is ${averageHumid()}%"
                }
		    }
    	}
    }
}

def installed() {
    log.info "Bathroom Humidity Fan Parent installed"
    state.ShfInstalled = true
    initializeState()
	initialize()
}

def updated() {
    log.info "Bathroom Humidity Fan Parent updated"
    initializeState()
	initialize()
}

// NEW: Centralized state initialization
private void initializeState() {
    if (state.ShfInstalled == null) state.ShfInstalled = false
    if (state.created == null) state.created = false
}

def initialize() {
    log.debug "Initializing Bathroom Humidity Fan Parent"
    
    try {
        def deviceId = "AverageHumid_${app.id}"
        def avgDevice = getChildDevice(deviceId)
        
        // Update average humidity device if it exists
        if (avgDevice) {
            def avg = averageHumid()
            if (avg != null) {
                avgDevice.setHumidity(avg)
                log.debug "Set average humidity device to ${avg}%"
            }
        }
        
        // Subscribe to sensor events
        unsubscribe()  // Clear old subscriptions first
        if (humidSensors) {
            subscribe(humidSensors, "humidity", handler)
            log.debug "Subscribed to ${humidSensors.size()} humidity sensors"
        }
        
        setCreateCombinedSensorButtonName()
    } catch (Exception e) {
        log.error "Error in initialize: ${e.message}"
    }
}

// FIXED: Safe average calculation with null checks and error handling
def averageHumid() {
    // Check if sensors configured
    if (!humidSensors || humidSensors.size() == 0) {
        log.warn "averageHumid: No humidity sensors configured"
        return 0.0
    }
    
    try {
        def total = 0.0  // FIXED: Float from start to prevent integer division
        def validSensors = 0
        
        // Sum all valid sensor readings
        humidSensors.each { sensor ->
            def humidity = sensor?.currentHumidity
            
            // Validate sensor reading
            if (humidity != null) {
                // Check if value is in reasonable range
                if (humidity >= 0 && humidity <= 100) {
                    total += humidity
                    validSensors++
                } else {
                    log.warn "Sensor ${sensor.displayName} returned out-of-range value: ${humidity}%"
                }
            } else {
                log.warn "Sensor ${sensor?.displayName} returned null humidity"
            }
        }
        
        // Check if we have any valid readings
        if (validSensors == 0) {
            log.warn "averageHumid: No sensors returned valid humidity values"
            return 0.0
        }
        
        // Calculate average - safe division with Float
        def average = (total / validSensors).toDouble().round(1)
        log.debug "Average humidity: ${average}% from ${validSensors} sensors"
        
        return average
        
    } catch (Exception e) {
        log.error "Error calculating average humidity: ${e.message}"
        return 0.0
    }
}

// FIXED: Safe handler with error handling and no duplicate calls
def handler(evt) {
    log.debug "Humidity sensor event from ${evt.displayName}: ${evt.value}%"
    
    try {
        // Calculate new average
        def avg = averageHumid()
        
        // Update average device if it exists
        def deviceId = "AverageHumid_${app.id}"
        def avgDevice = getChildDevice(deviceId)
        
        if (avgDevice && avg != null) {
            avgDevice.setHumidity(avg)
            log.debug "Updated average humidity device to ${avg}%"
        } else if (!avgDevice) {
            log.debug "Average humidity device not found, skipping update"
        }
    } catch (Exception e) {
        log.error "Error in humidity handler: ${e.message}"
    }
}

// FIXED: Improved button name logic
def setCreateCombinedSensorButtonName() {
    def deviceId = "AverageHumid_${app.id}"
    
    if (getChildDevice(deviceId)) {
        state.createCombinedSensorButtonName = "Delete Combined Humidity Sensor"
    } else {
        state.createCombinedSensorButtonName = "Create Combined Humidity Sensor"
    }
}

// FIXED: Simplified button handler with error handling
def appButtonHandler(btn) {
    log.debug "Button pressed: ${btn}"
    
    def deviceId = "AverageHumid_${app.id}"
    def device = getChildDevice(deviceId)
    
    if (!device) {
        // Create the average humidity device
        log.info "Creating average humidity sensor"
        
        try {
            addChildDevice(
                "hubitat", 
                "Virtual Humidity Sensor", 
                deviceId, 
                null, 
                [label: "Average Humidity Sensor", name: "Average Humidity Sensor"]
            )
            
            log.info "Successfully created average humidity sensor"
            
            // Set initial value
            def newDevice = getChildDevice(deviceId)
            if (newDevice) {
                def avg = averageHumid()
                if (avg != null) {
                    newDevice.setHumidity(avg)
                    log.debug "Set initial humidity to ${avg}%"
                }
            }
            
        } catch (Exception e) {
            log.error "Failed to create average humidity device: ${e.message}"
            log.error "This may happen if the device already exists but Hubitat lost track of it"
            log.error "Try manually deleting the device from the Devices page"
        }
        
    } else {
        // Delete the average humidity device
        log.info "Deleting average humidity sensor"
        
        try {
            deleteChildDevice(deviceId)
            log.info "Successfully deleted average humidity sensor"
            
        } catch (Exception e) {
            log.error "Failed to delete average humidity device: ${e.message}"
            log.error "The device may be in use by a child app or rule"
            log.error "Remove it from any apps/rules first, then try again"
        }
    }
    
    // Update button name
    setCreateCombinedSensorButtonName()
}

// Uninstall cleanup
def uninstalled() {
    log.info "Uninstalling Bathroom Humidity Fan Parent"
    
    try {
        def deviceId = "AverageHumid_${app.id}"
        def device = getChildDevice(deviceId)
        
        if (device) {
            deleteChildDevice(deviceId)
            log.info "Deleted average humidity sensor during uninstall"
        }
    } catch (Exception e) {
        log.warn "Could not delete average humidity device during uninstall: ${e.message}"
    }
}
