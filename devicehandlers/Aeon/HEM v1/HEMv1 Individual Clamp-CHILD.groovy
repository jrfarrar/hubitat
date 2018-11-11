/*
 *  AEON HEM V1 Child Device Driver
 */
metadata {
    definition (name: "AEON HEM V1 (Child)", namespace: "jrfarrar", author: "jrfarrar") {
        capability "Energy Meter"
		capability "Power Meter"
		capability "Refresh"
		capability "Sensor"
        
        attribute "power", "string"
        attribute "energy", "string"
    }
}
