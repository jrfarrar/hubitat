/*
 *	Notification Router Channel (Child)
 *
 *	Author: J.R. Farrar
 *
 *	v1.0.0  2026-09-02  initial
 *
 *	One instance == one notification channel.
 *
 *	Creates a "Virtual Notification Channel" device on this hub, subscribes to it,
 *	and fans anything that arrives out to whatever targets are configured here.
 *	Share the created device via Hub Mesh and every app on every hub can post to
 *	it without knowing or caring who ends up hearing about it.
 *
 *	The routing decision lives in one pure function (decideTarget) inside the
 *	ROUTING BLOCK below. That block is spliced verbatim into the test harness by
 *	_diag/build_nr_harness.py, so the harness exercises the shipped code rather
 *	than a copy of it that can drift.
 */

definition(
    name: "Notification Router Channel",
    namespace: "jrfarrar",
    author: "J.R. Farrar",
    description: "One notification channel: a meshable inbox device plus its routing rules",
    parent: "jrfarrar:Notification Router",
    iconUrl: "",
    iconX2Url: "",
    iconX3Url: "",
    importUrl: "https://raw.githubusercontent.com/jrfarrar/hubitat/master/Apps/NotificationRouter/NotificationRouterChannel.groovy"
)

preferences {
    page(name: "pageConfig")
    page(name: "pageTargets")
    page(name: "pageHistory")
}

// ============================================================================
//  PAGES
// ============================================================================

def pageConfig() {
    dynamicPage(name: "pageConfig", title: "", install: true, uninstall: true, refreshInterval: 0) {

        section(getFormat("header-green", "CHANNEL")) {
            paragraph "Apps anywhere in the ecosystem post here. This app decides who hears it."
            input(name: "channelName", type: "text", title: "Channel name (e.g. ALERT, HOUSE, GARDEN)",
                    required: true, submitOnChange: true)
            input(name: "defaultSeverity", type: "enum", title: "Default severity for messages with no tag",
                    options: sevOptions(), defaultValue: "NORMAL", required: true)
            input(name: "stripTag", type: "bool", title: "Strip the [SEVERITY] tag from delivered text",
                    defaultValue: true)
            paragraph "<small>A sender can override severity by prefixing the message: " +
                    "<code>[CRITICAL] well pump stuck on</code> or <code>[4] low battery</code>. " +
                    "An unrecognised bracket is left alone and treated as part of the message.</small>"
            if (channelName) {
                def d = getChildDevice(channelDni())
                paragraph d ? "Inbox device: <b>${d.displayName}</b><br>" +
                        "<small>Share this device in Settings &rarr; Hub Mesh so other hubs can post to it.</small>"
                        : "<small>The inbox device is created when you press Done.</small>"
            }
        }

        section(getFormat("header-green", "TARGETS")) {
            input(name: "notifyDevices", type: "capability.notification", title: "Notification / push devices",
                    multiple: true, required: false, submitOnChange: true)
            input(name: "speechDevices", type: "capability.speechSynthesis", title: "Speakers (TTS)",
                    multiple: true, required: false, submitOnChange: true)
            input(name: "switchDevices", type: "capability.switch", title: "Switches to flip",
                    multiple: true, required: false, submitOnChange: true)
            if (collectTargets()) {
                href "pageTargets", title: "Per-target rules", description: targetsSummary()
            } else {
                paragraph "<small>Pick at least one target, then per-target rules appear here.</small>"
            }
        }

        section(getFormat("header-green", "TEST")) {
            input(name: "testText", type: "text", title: "Test message", required: false,
                    defaultValue: "[NORMAL] test message from the router")
            input(name: "btnDry", type: "button", title: "DRY RUN (decide, send nothing)")
            input(name: "btnSend", type: "button", title: "SEND FOR REAL")
            if (state.lastTest) paragraph "<pre style='font-size:0.8em'>${state.lastTest}</pre>"
        }

        section(getFormat("header-green", "HISTORY")) {
            href "pageHistory", title: "Recent messages", description: "${state.msgCount ?: 0} handled since install"
            input(name: "histSize", type: "number", title: "Keep this many in history", defaultValue: 50, required: true)
        }

        section(getFormat("header-green", "LOGGING")) {
            input(name: "logLevel", title: "IDE logging level", multiple: false, required: true,
                    type: "enum", options: getLogLevels(), submitOnChange: false, defaultValue: "1")
        }

        section(getFormat("header-green", "APP NAME")) {
            input(name: "thisName", type: "text", title: "App Name", submitOnChange: true)
            if (thisName) app.updateLabel("$thisName")
            else if (channelName) app.updateSetting("thisName", "Notify Channel - ${channelName}")
        }
    }
}

def pageTargets() {
    dynamicPage(name: "pageTargets", title: getFormat("title", "Per-target rules"), install: false, uninstall: false) {
        def targets = collectTargets()
        if (!targets) {
            section { paragraph "No targets selected." }
            return
        }
        section {
            paragraph "<small>Every rule below is per-target. A target only receives a message if it " +
                    "clears all of them. 'Always deliver at or above' punches through quiet hours, " +
                    "de-duplication and rate limiting -- but never through 'disabled' or an excluded keyword.</small>"
        }
        targets.each { t ->
            String k = t.key
            section(getFormat("header-green", "${typeLabel(t.type)}: ${t.name}")) {
                if (isRouterChannelDevice(t.dev)) {
                    paragraph "<b style='color:#FF0000'>This is a router channel device. It will be skipped " +
                            "(loop guard) -- a channel routing into a channel would loop forever.</b>"
                }
                input(name: "${k}_enable", type: "bool", title: "Enabled", defaultValue: true, submitOnChange: true)
                input(name: "${k}_minSev", type: "enum", title: "Only at or above severity",
                        options: sevOptions(), defaultValue: "INFO", required: true)
                input(name: "${k}_bypassSev", type: "enum", title: "Always deliver at or above",
                        options: sevOptions() + ["never": "never bypass"], defaultValue: "CRITICAL", required: true)

                input(name: "${k}_quietMode", type: "enum", title: "Quiet hours",
                        options: ["off": "none", "global": "use parent defaults", "custom": "custom"],
                        defaultValue: "off", required: true, submitOnChange: true)
                if (settings["${k}_quietMode"] == "custom") {
                    input(name: "${k}_qStart", type: "time", title: "Quiet from", required: false)
                    input(name: "${k}_qEnd", type: "time", title: "Quiet until", required: false)
                }

                input(name: "${k}_incl", type: "text", title: "Only if text contains (comma separated, blank = any)", required: false)
                input(name: "${k}_excl", type: "text", title: "Never if text contains (comma separated)", required: false)

                input(name: "${k}_dedupe", type: "number", title: "Suppress identical text within (minutes, 0 = off)",
                        defaultValue: 0, required: false)
                input(name: "${k}_rateMax", type: "number", title: "Max messages per window (0 = unlimited)",
                        defaultValue: 0, required: false)
                input(name: "${k}_rateWin", type: "number", title: "Rate window (minutes)", defaultValue: 60, required: false)

                input(name: "${k}_tpl", type: "text", title: "Message template", defaultValue: "%text%", required: false)
                paragraph "<small>Tokens: <code>%text% %channel% %severity% %time% %date%</code></small>"

                if (t.type == "S") {
                    input(name: "${k}_vol", type: "number", title: "Set volume before speaking (blank = leave alone)", required: false)
                }
                if (t.type == "W") {
                    input(name: "${k}_swMode", type: "enum", title: "Action",
                            options: ["on": "turn on", "off": "turn off", "onFor": "turn on, then off after N seconds"],
                            defaultValue: "on", required: true, submitOnChange: true)
                    if (settings["${k}_swMode"] == "onFor") {
                        input(name: "${k}_swDur", type: "number", title: "Seconds before turning back off",
                                defaultValue: 60, required: true)
                    }
                }
            }
        }
    }
}

def pageHistory() {
    dynamicPage(name: "pageHistory", title: getFormat("title", "Recent messages"), install: false, uninstall: false) {
        section {
            def h = state.history ?: []
            if (!h) {
                paragraph "Nothing yet."
            } else {
                def sb = "<pre style='font-size:0.8em'>"
                h.each { e ->
                    sb += "${e.t}  [${e.sev}]  ${e.text}\n"
                    sb += "        sent: ${e.ok ? e.ok.join(', ') : '(nobody)'}\n"
                    if (e.sup) e.sup.each { s -> sb += "        skip: ${s}\n" }
                }
                paragraph sb + "</pre>"
            }
        }
        section {
            input(name: "btnClearHist", type: "button", title: "CLEAR HISTORY")
        }
    }
}

def targetsSummary() {
    def t = collectTargets()
    if (!t) return "none"
    def off = t.findAll { settings["${it.key}_enable"] == false }.size()
    return "${t.size()} target(s)" + (off ? ", ${off} disabled" : "")
}

// ============================================================================
//  LIFECYCLE
// ============================================================================

def installed() {
    infolog "installed"
    initialize()
}

def updated() {
    infolog "updated"
    initialize()
}

def initialize() {
    unsubscribe()
    unschedule()
    if (state.lastSent == null) state.lastSent = [:]
    if (state.rateLog == null) state.rateLog = [:]
    if (state.history == null) state.history = []

    def dev = getOrCreateChannelDevice()
    if (dev) {
        subscribe(dev, "notificationText", notificationHandler)
        infolog "subscribed to ${dev.displayName}"
    } else {
        log.error "Notification Router Channel: could not create or find the inbox device"
    }
    updateAppLabel()
}

def uninstalled() {
    unschedule()
    unsubscribe()
    getChildDevices().each {
        infolog "removing child device ${it.displayName}"
        deleteChildDevice(it.deviceNetworkId)
    }
}

def channelDni() { return "NR-${app.id}-CH" }

def channelLabel() {
    return channelName ? "vNotify-${channelName}" : "vNotify-Channel-${app.id}"
}

/*
 * isComponent MUST be false. Component child devices are hidden from the device
 * list, cannot be edited, and -- the part that matters here -- are not offered
 * in Hub Mesh, which would make the whole design pointless.
 */
def getOrCreateChannelDevice() {
    def d = getChildDevice(channelDni())
    if (!d) {
        try {
            d = addChildDevice("jrfarrar", "Virtual Notification Channel", channelDni(),
                    [name: "Virtual Notification Channel", label: channelLabel(), isComponent: false])
            infolog "created inbox device ${d.displayName}"
        } catch (e) {
            log.error "Notification Router Channel: failed to create inbox device -- is the " +
                    "'Virtual Notification Channel' driver installed? (${e.message})"
            return null
        }
    } else if (channelName && d.label != channelLabel()) {
        d.setLabel(channelLabel())
        infolog "renamed inbox device to ${channelLabel()}"
    }
    return d
}

def updateAppLabel() {
    if (!thisName) return
    def n = collectTargets().size()
    def colour = n ? "green" : "#FF0000"
    app.updateLabel("$thisName <span style=\"color:${colour};\">(${n} target${n == 1 ? '' : 's'})</span>")
}

def appButtonHandler(btn) {
    if (btn == "btnDry") runTest(true)
    else if (btn == "btnSend") runTest(false)
    else if (btn == "btnClearHist") { state.history = []; state.msgCount = 0 }
}

def runTest(boolean dryRun) {
    def results = handleMessage(testText ?: "test message", dryRun)
    def sb = (dryRun ? "DRY RUN -- nothing was sent\n" : "SENT FOR REAL\n")
    results.each { r ->
        sb += "  [${r.ok ? 'SENT' : 'skip'}] ${r.name}${r.ok ? '' : '  <- ' + r.reason}\n"
    }
    if (!results) sb += "  (no targets configured)\n"
    state.lastTest = sb
}

// ============================================================================
//  THE MESSAGE PATH
// ============================================================================

def notificationHandler(evt) {
    String raw = (evt?.value == null) ? "" : evt.value.toString()
    if (raw.trim() == "") {
        debuglog "empty notification received, ignored"
        return
    }
    handleMessage(raw, false)
}

/*
 * Builds the message, asks decideTarget about every target, delivers to the ones
 * that pass, and records what happened. Returns the per-target result list so the
 * test buttons can show it.
 */
def handleMessage(String raw, boolean dryRun) {
    def nowD = new Date()
    long nowMs = nowD.getTime()
    Integer nowMins = minsOfDay(nowD)

    def parsed = parseSeverity(raw, defaultSeverity ?: "NORMAL", (stripTag == null) ? true : stripTag)
    def msg = [
            text   : parsed.text,
            sev    : parsed.sev,
            sevName: parsed.sevName,
            channel: (channelName ?: app.label ?: "channel"),
            timeStr: nowD.format("h:mm a", location.timeZone),
            dateStr: nowD.format("yyyy-MM-dd", location.timeZone)
    ]

    def g = getGlobals()
    def targets = collectTargets()
    def results = []

    targets.each { t ->
        def cfg = targetConfig(t, g)
        def ctx = [
                nowMins         : nowMins,
                nowMs           : nowMs,
                lastText        : state.lastSent?.get(t.key)?.text,
                lastAtMs        : state.lastSent?.get(t.key)?.at,
                rateHits        : (state.rateLog?.get(t.key) ?: []),
                globalDisabled  : (g.disabled == true),
                globalMuted     : (g.muted == true),
                globalMinRank   : sevRank(g.minSev ?: "INFO"),
                globalBypassRank: sevRank(g.bypassSev ?: "CRITICAL"),
                muteName        : g.muteSwitchName
        ]

        def d
        try {
            d = decideTarget(cfg, msg, ctx)
        } catch (e) {
            log.error "Notification Router Channel: decideTarget threw for ${t.name}: ${e.message}"
            results.add([key: t.key, name: t.name, ok: false, reason: "decision error: ${e.message}"])
            return
        }

        if (!d.deliver) {
            debuglog "skip ${t.name}: ${d.reason}"
            results.add([key: t.key, name: t.name, ok: false, reason: d.reason])
            return
        }

        if (dryRun) {
            results.add([key: t.key, name: t.name, ok: true, reason: "would send: ${d.text}"])
            return
        }

        // One dead target must not swallow the rest of the fan-out.
        try {
            deliverTo(t, d.text)
            noteSent(t.key, msg.text, nowMs)
            results.add([key: t.key, name: t.name, ok: true, reason: d.reason])
            debuglog "sent to ${t.name}"
        } catch (e) {
            log.warn "Notification Router Channel: delivery to ${t.name} failed: ${e.message}"
            results.add([key: t.key, name: t.name, ok: false, reason: "delivery failed: ${e.message}"])
        }
    }

    if (!dryRun) {
        recordHistory(msg, results)
        writeBackToDevice(msg, results)
    }
    return results
}

def deliverTo(Map t, String text) {
    switch (t.type) {
        case "N":
            t.dev.deviceNotification(text)
            break
        case "S":
            def v = settings["${t.key}_vol"]
            if (v != null) {
                try { t.dev.setVolume(v as Integer) }
                catch (e) { debuglog "${t.name} has no setVolume, ignoring volume setting" }
            }
            t.dev.speak(text)
            break
        case "W":
            String mode = settings["${t.key}_swMode"] ?: "on"
            if (mode == "off") {
                t.dev.off()
            } else {
                t.dev.on()
                if (mode == "onFor") {
                    Integer secs = (settings["${t.key}_swDur"] ?: 60) as Integer
                    // overwrite:false so two alerts in quick succession do not cancel
                    // each other's auto-off.
                    runIn(secs, "autoOff", [data: [dni: t.dev.deviceNetworkId], overwrite: false])
                }
            }
            break
        default:
            throw new Exception("unknown target type ${t.type}")
    }
}

def autoOff(data) {
    def dni = data?.dni
    if (!dni) return
    def d = (switchDevices ?: []).find { it.deviceNetworkId == dni }
    if (d) {
        d.off()
        debuglog "auto-off ${d}"
    }
}

def noteSent(String key, String text, long ms) {
    if (state.lastSent == null) state.lastSent = [:]
    state.lastSent[key] = [text: text, at: ms]

    if (state.rateLog == null) state.rateLog = [:]
    def l = (state.rateLog[key] ?: []) as List
    l.add(ms)
    long cutoff = ms - (24L * 3600000L)      // nothing older than a day can matter
    l = l.findAll { (it as Long) > cutoff }
    if (l.size() > 200) l = l[(l.size() - 200)..(l.size() - 1)]
    state.rateLog[key] = l
}

def recordHistory(Map msg, List results) {
    if (state.history == null) state.history = []
    def ok = results.findAll { it.ok }.collect { it.name }
    def sup = results.findAll { !it.ok }.collect { "${it.name}: ${it.reason}" }
    String shortText = msg.text.length() > 160 ? msg.text.substring(0, 160) + "..." : msg.text

    state.history.add(0, [t: new Date().format("MM-dd HH:mm:ss", location.timeZone),
                          sev: msg.sevName, text: shortText, ok: ok, sup: sup])

    Integer cap = (histSize ?: 50) as Integer
    if (cap < 1) cap = 1
    if (state.history.size() > cap) state.history = state.history[0..(cap - 1)]
    state.msgCount = (state.msgCount ?: 0) + 1

    if (!ok) log.warn "Notification Router Channel [${msg.channel}]: '${shortText}' reached NOBODY (${sup.size()} target(s) suppressed)"
    else infolog "[${msg.sevName}] '${shortText}' -> ${ok.join(', ')}"
}

def writeBackToDevice(Map msg, List results) {
    def d = getChildDevice(channelDni())
    if (!d) return
    def ok = results.findAll { it.ok }.collect { it.name }
    def sup = results.findAll { !it.ok }.size()
    try {
        d.routeResult(msg.sevName, ok ? ok.join(", ") : "(nobody)", sup ? "${sup} suppressed" : "")
    } catch (e) {
        debuglog "routeResult writeback failed: ${e.message}"
    }
}

// ============================================================================
//  CONFIG PLUMBING  (impure: reads settings, devices, the clock)
// ============================================================================

def collectTargets() {
    def out = []
    (notifyDevices ?: []).each { out.add([key: "N_${it.id}", type: "N", dev: it, name: "${it}"]) }
    (speechDevices ?: []).each { out.add([key: "S_${it.id}", type: "S", dev: it, name: "${it}"]) }
    (switchDevices ?: []).each { out.add([key: "W_${it.id}", type: "W", dev: it, name: "${it}"]) }
    return out
}

def typeLabel(String t) {
    if (t == "N") return "Notification"
    if (t == "S") return "Speaker"
    if (t == "W") return "Switch"
    return t
}

def targetConfig(Map t, Map g) {
    String k = t.key
    String quietMode = settings["${k}_quietMode"] ?: "off"
    Integer qs = null
    Integer qe = null
    if (quietMode == "custom") {
        qs = timeToMins(settings["${k}_qStart"])
        qe = timeToMins(settings["${k}_qEnd"])
    } else if (quietMode == "global") {
        qs = timeToMins(g?.defQuietStart)
        qe = timeToMins(g?.defQuietEnd)
    }

    return [
            enabled       : (settings["${k}_enable"] == null) ? true : (settings["${k}_enable"] == true),
            minRank       : sevRank(settings["${k}_minSev"] ?: "INFO"),
            bypassRank    : sevRank(settings["${k}_bypassSev"] ?: "CRITICAL"),
            qStartMins    : qs,
            qEndMins      : qe,
            incl          : settings["${k}_incl"],
            excl          : settings["${k}_excl"],
            dedupeMin     : settings["${k}_dedupe"],
            rateMax       : settings["${k}_rateMax"],
            rateWinMin    : settings["${k}_rateWin"],
            tpl           : settings["${k}_tpl"],
            isRouterDevice: isRouterChannelDevice(t.dev)
    ]
}

def getGlobals() {
    try {
        return parent.getGlobalConfig() ?: [:]
    } catch (e) {
        log.warn "Notification Router Channel: could not read parent globals (${e.message}), continuing without them"
        return [:]
    }
}

/*
 * Loop guard. A router channel device selected as a target would re-enter this
 * app forever. Checked two ways because a Hub Mesh linked device gets a different
 * DNI than the source device.
 */
def isRouterChannelDevice(dev) {
    if (dev == null) return false
    try {
        if (dev.deviceNetworkId?.toString()?.startsWith("NR-")) return true
    } catch (e) { }
    try {
        if (dev.getTypeName() == "Virtual Notification Channel") return true
    } catch (e) { }
    return false
}

def minsOfDay(Date d) {
    return (d.format("HH", location.timeZone) as Integer) * 60 + (d.format("mm", location.timeZone) as Integer)
}

def timeToMins(t) {
    if (!t) return null
    try {
        def d = timeToday(t, location.timeZone)
        return (d.format("HH", location.timeZone) as Integer) * 60 + (d.format("mm", location.timeZone) as Integer)
    } catch (e) {
        debuglog "could not parse time '${t}': ${e.message}"
        return null
    }
}

def sevOptions() {
    return ["INFO": "INFO", "LOW": "LOW", "NORMAL": "NORMAL", "HIGH": "HIGH", "CRITICAL": "CRITICAL"]
}

// Read by the parent app's status table.
def getChannelStatus() {
    def h = (state.history ?: [])
    def last = h ? "${h[0].t} [${h[0].sev}] ${h[0].text}" : "-"
    return [name: (channelName ?: app.label), targets: collectTargets().size(), last: last]
}

// ============================================================================
//  ROUTING BLOCK START
// ============================================================================
//  Everything between the START and END markers is PURE: no state, no settings,
//  no device objects, no clock, no logging. build_nr_harness.py enforces that
//  mechanically and splices this block verbatim into the harness, so the harness
//  tests the shipped decision code rather than a paraphrase of it that can drift.
//
//  If you need something from the outside world in here, pass it in via cfg or
//  ctx. Reaching out is what makes routing logic untestable.
// ============================================================================

def sevRank(String name) {
    switch ((name ?: "").trim().toUpperCase()) {
        case "INFO": return 1
        case "LOW": return 2
        case "NORMAL": return 3
        case "HIGH": return 4
        case "CRITICAL": return 5
        case "NEVER": return 99      // "never bypass" -- nothing can reach rank 99
        default: return 0            // unknown name: treated as no floor
    }
}

def sevName(Integer rank) {
    switch (rank) {
        case 1: return "INFO"
        case 2: return "LOW"
        case 3: return "NORMAL"
        case 4: return "HIGH"
        case 5: return "CRITICAL"
        case 99: return "never"
        default: return "NORMAL"
    }
}

/*
 * "[CRITICAL] pump stuck" -> sev 5, text "pump stuck"
 * "[4] low battery"       -> sev 4
 * "pump stuck"            -> channel default, untagged
 * "[Kitchen] door open"   -> channel default, text UNCHANGED. An unrecognised
 *                            bracket is somebody's message, not a severity tag,
 *                            and silently eating it would be a data-loss bug.
 */
def parseSeverity(String raw, String channelDefault, boolean stripTag) {
    String text = (raw == null) ? "" : raw
    Integer defRank = sevRank(channelDefault)
    if (defRank < 1 || defRank > 5) defRank = 3

    String t = text.trim()
    if (t.startsWith("[")) {
        int close = t.indexOf("]")
        if (close > 1 && close <= 12) {
            String tag = t.substring(1, close).trim()
            Integer r = 0
            if (tag.isInteger()) {
                r = tag.toInteger()
            } else {
                r = sevRank(tag)
            }
            if (r >= 1 && r <= 5) {
                String rest = t.substring(close + 1).trim()
                return [sev: r, sevName: sevName(r), text: (stripTag ? rest : text), tagged: true]
            }
        }
    }
    return [sev: defRank, sevName: sevName(defRank), text: text, tagged: false]
}

// Minute-of-day window. Handles windows that wrap midnight, which is the common
// case for quiet hours and the one that is easy to get wrong.
def inQuietHours(Integer nowMins, Integer startMins, Integer endMins) {
    if (nowMins == null || startMins == null || endMins == null) return false
    if (startMins == endMins) return false                              // zero-length window is "off"
    if (startMins < endMins) return (nowMins >= startMins && nowMins < endMins)
    return (nowMins >= startMins || nowMins < endMins)                  // wraps midnight
}

def splitCsv(String s) {
    if (s == null) return []
    def out = []
    s.split(",").each { p ->
        String v = p.trim().toLowerCase()
        if (v) out.add(v)
    }
    return out
}

// Exclude always beats include.
def keywordVerdict(String text, String inclCsv, String exclCsv) {
    String hay = (text == null) ? "" : text.toLowerCase()
    def ex = splitCsv(exclCsv)
    for (int i = 0; i < ex.size(); i++) {
        if (hay.contains(ex[i])) return [ok: false, reason: "excluded by keyword '${ex[i]}'"]
    }
    def inc = splitCsv(inclCsv)
    if (inc.size() > 0) {
        for (int i = 0; i < inc.size(); i++) {
            if (hay.contains(inc[i])) return [ok: true, reason: null]
        }
        return [ok: false, reason: "no required keyword matched"]
    }
    return [ok: true, reason: null]
}

def renderTemplate(String tpl, Map msg) {
    String t = (tpl == null || tpl.trim() == "") ? "%text%" : tpl
    return t.replace("%text%", (msg?.text == null ? "" : msg.text.toString()))
            .replace("%channel%", (msg?.channel == null ? "" : msg.channel.toString()))
            .replace("%severity%", (msg?.sevName == null ? "" : msg.sevName.toString()))
            .replace("%time%", (msg?.timeStr == null ? "" : msg.timeStr.toString()))
            .replace("%date%", (msg?.dateStr == null ? "" : msg.dateStr.toString()))
}

/*
 * The entire routing decision for one target, as one pure function.
 *
 *   cfg : per-target config, already flattened out of settings
 *         [enabled, minRank, bypassRank, qStartMins, qEndMins, incl, excl,
 *          dedupeMin, rateMax, rateWinMin, tpl, isRouterDevice]
 *   msg : [text, sev, sevName, channel, timeStr, dateStr]
 *   ctx : [nowMins, nowMs, lastText, lastAtMs, rateHits,
 *          globalDisabled, globalMuted, globalMinRank, globalBypassRank, muteName]
 *
 * returns [deliver:boolean, reason:String, text:String]
 *
 * reason is ALWAYS non-null and is distinct per path. "It didn't send and I don't
 * know why" is the failure mode that makes a router like this untrustworthy, so
 * the harness asserts uniqueness of these strings.
 */
def decideTarget(Map cfg, Map msg, Map ctx) {
    Integer sev = (msg?.sev == null) ? 3 : (msg.sev as Integer)
    Long nowMsL = (ctx?.nowMs == null) ? 0L : (ctx.nowMs as Long)

    if (ctx?.globalDisabled == true) {
        return [deliver: false, reason: "all routing disabled at parent", text: null]
    }
    if (cfg?.enabled == false) {
        return [deliver: false, reason: "target disabled", text: null]
    }
    if (cfg?.isRouterDevice == true) {
        return [deliver: false, reason: "loop guard: target is a router channel device", text: null]
    }

    Integer gMin = (ctx?.globalMinRank == null) ? 1 : (ctx.globalMinRank as Integer)
    if (sev < gMin) {
        return [deliver: false, reason: "below global severity floor (${sevName(gMin)})", text: null]
    }

    Integer tMin = (cfg?.minRank == null) ? 1 : (cfg.minRank as Integer)
    if (sev < tMin) {
        return [deliver: false, reason: "below target severity floor (${sevName(tMin)})", text: null]
    }

    def kw = keywordVerdict(msg?.text, cfg?.incl, cfg?.excl)
    if (!kw.ok) {
        return [deliver: false, reason: kw.reason, text: null]
    }

    // Global mute is governed by the GLOBAL bypass severity, never by a target's.
    // A target must not be able to talk its way past a house-wide mute.
    Integer gBypass = (ctx?.globalBypassRank == null) ? 99 : (ctx.globalBypassRank as Integer)
    if (ctx?.globalMuted == true && sev < gBypass) {
        return [deliver: false, reason: "muted by ${ctx?.muteName ?: 'mute switch'}", text: null]
    }

    // The target's own bypass covers the three "be polite" rules below.
    Integer bypass = (cfg?.bypassRank == null) ? 99 : (cfg.bypassRank as Integer)
    boolean emergency = (sev >= bypass)

    if (!emergency) {
        if (inQuietHours(ctx?.nowMins as Integer, cfg?.qStartMins as Integer, cfg?.qEndMins as Integer)) {
            return [deliver: false, reason: "quiet hours", text: null]
        }

        Integer dedupe = (cfg?.dedupeMin == null) ? 0 : (cfg.dedupeMin as Integer)
        if (dedupe > 0 && ctx?.lastText != null && ctx?.lastAtMs != null) {
            long age = nowMsL - (ctx.lastAtMs as Long)
            if (ctx.lastText == msg?.text && age < (dedupe * 60000L)) {
                return [deliver: false, reason: "duplicate within ${dedupe} min", text: null]
            }
        }

        Integer rateMax = (cfg?.rateMax == null) ? 0 : (cfg.rateMax as Integer)
        Integer rateWin = (cfg?.rateWinMin == null) ? 60 : (cfg.rateWinMin as Integer)
        if (rateWin < 1) rateWin = 1
        if (rateMax > 0) {
            long cutoff = nowMsL - (rateWin * 60000L)
            int hits = 0
            (ctx?.rateHits ?: []).each { h -> if ((h as Long) > cutoff) hits++ }
            if (hits >= rateMax) {
                return [deliver: false, reason: "rate limited (${rateMax} per ${rateWin} min)", text: null]
            }
        }
    }

    return [deliver: true,
            reason : (emergency ? "delivered (severity bypass)" : "delivered"),
            text   : renderTemplate(cfg?.tpl, msg)]
}

// ============================================================================
//  ROUTING BLOCK END
// ============================================================================

def getFormat(type, myText = "") {			// Modified from @Stephack Code
    if (type == "header-green") return "<div style='color:#ffffff;font-weight: bold;background-color:#81BC00;border: 1px solid;box-shadow: 2px 3px #A9A9A9'>${myText}</div>"
    if (type == "line") return "<hr style='background-color:#1A77C9; height: 1px; border: 0;'>"
    if (type == "title") return "<h2 style='color:#1A77C9;font-weight: bold'>${myText}</h2>"
    if (type == "title2") return "<div style='color:#1A77C9;font-weight: bold'>${myText}</div>"
}

def debuglog(statement) {
    def logL = 0
    if (logLevel) logL = logLevel.toInteger()
    if (logL == 0) { return }
    else if (logL >= 2) { log.debug("$thisName: " + statement) }
}

def infolog(statement) {
    def logL = 0
    if (logLevel) logL = logLevel.toInteger()
    if (logL == 0) { return }
    else if (logL >= 1) { log.info("$thisName: " + statement) }
}

def getLogLevels() {
    return [["0": "None"], ["1": "Running"], ["2": "NeedHelp"]]
}
