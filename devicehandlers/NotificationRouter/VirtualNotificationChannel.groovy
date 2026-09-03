/*
 *  Virtual Notification Channel
 *
 *  Author: J.R. Farrar
 *
 *  v1.0.0  2026-09-02  initial
 *
 *  A deliberately dumb mailbox.
 *
 *  Apps anywhere in the ecosystem -- including on other hubs, via Hub Mesh --
 *  call deviceNotification() on this device. It records the payload and fires
 *  a notificationText event. The "Notification Router Channel" app on the hub
 *  that OWNS this device subscribes to that event and does all the routing.
 *
 *  This driver holds NO routing configuration, on purpose, for two reasons:
 *
 *    1. Hubitat drivers cannot have device-selector inputs (only apps can), so
 *       "which devices do I forward to" physically cannot live here.
 *    2. Every calling app in the ecosystem depends on this device. A device
 *       that knows nothing is a device that cannot break.
 *
 *  Note on the notificationText attribute: capability "Notification" defines a
 *  COMMAND (deviceNotification) and no attributes at all, so there is nothing
 *  for an app to subscribe to. Hence the custom attribute. isStateChange:true
 *  is required -- without it, the same alert text twice in a row would fire the
 *  subscription only once.
 */

metadata {
    definition(
            name: "Virtual Notification Channel",
            namespace: "jrfarrar",
            author: "J.R. Farrar",
            importUrl: "https://raw.githubusercontent.com/jrfarrar/hubitat/master/devicehandlers/NotificationRouter/VirtualNotificationChannel.groovy"
    ) {
        capability "Notification"
        capability "Actuator"

        // What the router app subscribes to. Everything else is cosmetic /
        // dashboard fodder and is written back by the router after fan-out.
        attribute "notificationText", "string"
        attribute "lastSeverity", "string"
        attribute "lastTime", "string"
        attribute "lastDelivered", "string"
        attribute "lastSuppressed", "string"
        attribute "messageCount", "number"

        // Called by the router app after it has fanned a message out. Not for
        // use by anything else.
        command "routeResult", [[name: "severity", type: "STRING"],
                                [name: "delivered", type: "STRING"],
                                [name: "suppressed", type: "STRING"]]

        command "testNotify", [[name: "Message*", type: "STRING", description: "Text to inject"],
                               [name: "Severity", type: "ENUM", constraints: ["", "INFO", "LOW", "NORMAL", "HIGH", "CRITICAL"]]]
        command "resetCount"
    }

    preferences {
        input name: "maxLen", type: "number", title: "Truncate messages longer than (characters)",
                defaultValue: 900, required: true
        input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: false
        input name: "txtEnable", type: "bool", title: "Enable descriptionText logging", defaultValue: true
    }
}

def installed() {
    log.info "${device.displayName}: installed"
    sendEvent(name: "messageCount", value: 0)
    sendEvent(name: "notificationText", value: "")
}

def updated() {
    log.info "${device.displayName}: preferences updated (maxLen=${maxLen}, debug=${logEnable})"
    if (logEnable) runIn(1800, "logsOff")
}

def logsOff() {
    log.warn "${device.displayName}: debug logging disabled"
    device.updateSetting("logEnable", [value: "false", type: "bool"])
}

/*
 * THE inbound API. Every app in the ecosystem, on any hub, calls this.
 * Keep the signature and the behaviour stable forever.
 */
def deviceNotification(String text) {
    if (text == null) text = ""

    Integer limit = (maxLen ?: 900) as Integer
    String payload = text
    if (payload.length() > limit) {
        // Hubitat truncates oversized event values itself; doing it here means we
        // control where the cut lands and can say so out loud instead of silently
        // losing the tail.
        log.warn "${device.displayName}: message ${payload.length()} chars, truncated to ${limit}"
        payload = payload.substring(0, limit) + "..."
    }

    Integer n = ((device.currentValue("messageCount") ?: 0) as BigDecimal).intValue()
    sendEvent(name: "messageCount", value: n + 1)
    sendEvent(name: "lastTime", value: new Date().format("yyyy-MM-dd HH:mm:ss"))

    if (txtEnable) log.info "${device.displayName}: ${payload}"
    if (logEnable) log.debug "${device.displayName}: firing notificationText (msg #${n + 1})"

    sendEvent(name: "notificationText", value: payload, isStateChange: true,
            descriptionText: "${device.displayName} received: ${payload}")
}

// Written back by the router app so the device page and dashboards can show
// what actually happened, without the router needing state the user can see.
def routeResult(String severity, String delivered, String suppressed) {
    sendEvent(name: "lastSeverity", value: severity ?: "")
    sendEvent(name: "lastDelivered", value: delivered ?: "")
    sendEvent(name: "lastSuppressed", value: suppressed ?: "")
    if (logEnable) log.debug "${device.displayName}: routeResult sev=${severity} to=[${delivered}] suppressed=[${suppressed}]"
}

def testNotify(String message, String severity = null) {
    String t = (severity) ? "[${severity}] ${message}" : message
    log.info "${device.displayName}: TEST injection -> ${t}"
    deviceNotification(t)
}

def resetCount() {
    sendEvent(name: "messageCount", value: 0)
    log.info "${device.displayName}: message count reset"
}
