/**
 *  House Announce - the front door from Hubitat to the house-announce service (192.168.13.74)
 *
 *  A virtual speaker + notification device. Anything that can speak to a speaker or notify a device (Notifier, Rule Machine,
 *  custom apps) can use it; the SERVICE decides which rooms hear and see the message (house mode + each room's Room Lights state),
 *  makes the voice and the text card, and plays them. Record: C:\CLAUDE\HomeConnector\announce\README.md
 *
 *  - speak(text, volume)            volume 0-100 is passed on (optional)
 *  - deviceNotification(text)       a leading [INFO] / [WARNING] / [CRITICAL] tag sets the severity and is removed
 *  - announce(text, severity, spokenText, targets, volume)   everything settable; targets = comma list of device names, empty = let the rooms decide
 *  - replay()                       says the last message again
 *
 *  The hub is trusted by its address (devices.json "trusted_callers" on the service) - no key is stored on the hub.
 *
 *  v0.2.0  2026-11-21  production name; "ignore the sender's volume" (J.R.: each speaker's own setting wins); a leading "!" is dropped
 *  v0.1.0  2026-11-21  first version, dev hub only (J.R. + Claude, chat "Follow Me announcement hub research")
 */
import groovy.json.JsonOutput

metadata {
    definition(name: "House Announce", namespace: "jrfarrar", author: "J.R. Farrar") {
        capability "Actuator"
        capability "SpeechSynthesis"
        capability "Notification"
        command "announce", [[name: "Text*", type: "STRING", description: "The words shown (and spoken, unless Spoken text is given)"],
                             [name: "Severity", type: "ENUM", constraints: ["info", "warning", "critical"]],
                             [name: "Spoken text", type: "STRING", description: "How to SAY it when that differs from the words shown"],
                             [name: "Targets", type: "STRING", description: "Device names, comma separated. Empty = the rooms in use"],
                             [name: "Volume", type: "NUMBER", description: "0-100. Empty = each speaker's own setting"]]
        command "replay"
        attribute "lastMessage", "string"
        attribute "lastResult", "string"
    }
    preferences {
        input name: "server", type: "text", title: "Service address", defaultValue: "http://192.168.13.74:8080", required: true
        input name: "defaultSeverity", type: "enum", title: "Severity when the message does not say", options: ["info", "warning", "critical"], defaultValue: "info"
        input name: "source", type: "text", title: "Source shown on the card", defaultValue: "House"
        input name: "targets", type: "text", title: "Always send to these devices (comma separated). Empty = the rooms in use", required: false
        input name: "ignoreVolume", type: "bool", title: "Ignore the volume a sender gives - each speaker uses its own setting in the service", defaultValue: true
        input name: "dryRun", type: "bool", title: "Dry run: the service only reports who WOULD get it - no sound, no picture", defaultValue: false
        input name: "testOnSave", type: "bool", title: "Send the test message below once when these preferences are saved", defaultValue: false
        input name: "testText", type: "text", title: "Test message", defaultValue: "Test from Hubitat.", required: false
        input name: "logEnable", type: "bool", title: "Debug logging", defaultValue: false
    }
}

def installed() { sendEvent(name: "lastResult", value: "installed") }

def updated() {
    if (testOnSave) {
        device.updateSetting("testOnSave", [value: "false", type: "bool"])
        send(testText ?: "Test from Hubitat.", null, null, null, null)
    }
}

def speak(text, volume = null, voice = null) { send(text, null, null, null, (ignoreVolume == false) ? volume : null) }

def deviceNotification(text) { send(text, null, null, null, null) }

def announce(text, severity = null, spokenText = null, targetList = null, volume = null) { send(text, severity, spokenText, targetList, volume) }   // an explicit volume here is always honoured

def replay() { post("/replay", [:], "replay") }

private send(text, severity, spokenText, targetList, volume) {
    String t = (text ?: "").toString().trim()
    while (t.startsWith("!")) t = t.substring(1).trim()          // the old trick against a clipped first word - not needed, the chime wakes the speaker
    def m = (t =~ /^\[(INFO|WARNING|CRITICAL)\]\s*/)
    if (m.find()) {                                   // the Notification Router's inline tag
        if (!severity) severity = m.group(1).toLowerCase()
        t = t.substring(m.end())
    }
    if (!t) { sendEvent(name: "lastResult", value: "nothing to say"); return }
    Map body = [text: t, severity: (severity ?: defaultSeverity ?: "info"), source: (source ?: "House")]
    if (spokenText) body.speech = spokenText.toString()
    String tl = (targetList ?: targets ?: "").toString().trim()
    if (tl) body.targets = tl.split(",").collect { it.trim() }.findAll { it }
    if (volume != null && volume.toString().trim() != "") body.volume = volume as BigDecimal
    if (dryRun) body.dry = true
    sendEvent(name: "lastMessage", value: t, isStateChange: true)
    post("/say", body, t)
}

private post(String path, Map body, String what) {
    if (logEnable) log.debug "House Announce -> ${path} ${body}"
    try {
        asynchttpPost("posted", [uri: (server ?: "http://192.168.13.74:8080") + path, requestContentType: "application/json", contentType: "application/json",
                                 body: JsonOutput.toJson(body), timeout: 20], [what: what])
    } catch (e) {
        log.warn "House Announce: could not send (${e.message})"
        sendEvent(name: "lastResult", value: "could not send: ${e.message}", isStateChange: true)
    }
}

def posted(resp, data) {
    String res
    if (resp.hasError()) {
        res = "service error ${resp.getStatus()}: ${resp.getErrorMessage()}"
        log.warn "House Announce: ${res} (${data?.what})"
    } else {
        def j = resp.getJson()
        if (j?.dropped) res = "dropped: ${j.dropped}"
        else if (j?.error) res = "refused: ${j.error}"
        else res = "#${j?.seq} -> ${j?.targets ? j.targets.join(', ') : 'nobody'}" + (j?.targets ? "" : " (${j?.why?.collect { k, v -> "${k}: ${v}" }?.join('; ')})")
        if (logEnable) log.debug "House Announce: ${res}"
    }
    sendEvent(name: "lastResult", value: res.take(1000), isStateChange: true)
}
