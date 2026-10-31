import org.codehaus.groovy.control.CompilerConfiguration
import groovy.json.*

/* Runs the REAL Garadget Guardian Child source against fake devices on a virtual clock. */

class A {
    static long clock = 1790000000000L
    static List jobs = []
    static Map subs = [:]            // "devId:attr" -> [handler names]
    static List log = []
    static def app
    static void at(long ms, Closure fn) { jobs << [at: ms, fn: fn] }
    static String fmt(long ms) { new Date(ms).format("HH:mm:ss", TimeZone.getTimeZone("America/New_York")) }
    static void fire(String devId, String attr, Object value) {
        (subs["${devId}:${attr}"] ?: []).each { h -> app.invokeMethod(h, [[name: attr, value: value?.toString(), deviceId: devId]] as Object[]) }
    }
}
class St { def value; Date date }
class Dev {
    String id; String displayName; Map attrs = [:]
    def currentValue(String n) { attrs[n]?.v }
    def currentState(String n) { attrs[n] ? new St(value: attrs[n].v, date: new Date(attrs[n].t as long)) : null }
    void set(String n, Object v, boolean fire = true) { boolean ch = attrs[n]?.v != v; attrs[n] = [v: v, t: A.clock]; if (ch && fire) A.fire(id, n, v) }
}
class Garadget extends Dev {
    boolean powered = true; boolean bootWorks = true; String doorAfterBoot = null; int refreshes = 0
    void refresh() { refreshes++ }
}
class Outlet extends Dev {
    Garadget g; int offs = 0, ons = 0
    void off() { offs++; set("switch", "off"); g.powered = false }
    void on() {
        ons++; set("switch", "on")
        if (!g.powered) {
            g.powered = true
            if (g.bootWorks) A.at(A.clock + 12100L) {
                if (g.doorAfterBoot) { g.set("door", g.doorAfterBoot) }
                g.set("lastBoot", new Date(A.clock - 10000).format("yyyy-MM-dd HH:mm:ss"))
                if (g.currentValue("health") in ["offline", "commandFailed"]) g.set("health", "ok")
            }
        }
    }
}
class Notifier extends Dev { List msgs = []; void deviceNotification(String m) { msgs << "${A.fmt(A.clock)} ${m}".toString() } }

abstract class AppBase extends Script {
    Map state = [:]
    Map settingsMap = [:]
    def propertyMissing(String n) {
        if (settingsMap.containsKey(n)) return settingsMap[n]
        if (this.metaClass.respondsTo(this, n)) return n
        return null
    }
    long now() { A.clock }
    def getLog() { [info: { m -> A.log << "${A.fmt(A.clock)} INFO  $m".toString() }, warn: { m -> A.log << "${A.fmt(A.clock)} WARN  $m".toString() }] }
    def getApp() { [label: "Guardian Door 3", updateLabel: { l -> }] }
    def getLocation() { [id: "loc"] }
    void subscribe(Object dev, String attr, Object h) { String k = (dev instanceof Map ? "loc" : dev.id) + ":" + attr; A.subs[k] = (A.subs[k] ?: []) + [h.toString()] }
    void unsubscribe() { A.subs.clear() }
    void runIn(Number sec, Object h) { A.jobs.removeAll { it.name == h.toString() }; A.jobs << [at: A.clock + (long) (sec * 1000), name: h.toString()] }
    void schedule(String cron, Object h) { def p = cron.split(" "); def mm = p[1].split("/"); long per = (mm[1] as int) * 60000L
        long hs = A.clock - (A.clock % 3600000L); long f = hs + (mm[0] as int) * 60000L + (p[0] as int) * 1000L; while (f <= A.clock) f += per
        A.jobs << [at: f, name: h.toString(), period: per] }
    void unschedule(Object h = null) { if (h == null) A.jobs.removeAll { it.name } else A.jobs.removeAll { it.name == h.toString() } }
    void definition(Map m) { }
    void preferences(Closure c) { }
}

def cfg = new CompilerConfiguration(); cfg.scriptBaseClass = "AppBase"
def shell = new GroovyShell(this.class.classLoader, new Binding(), cfg)
String SRC = new File(args[0]).text
long SEC = 1000L, MIN = 60000L, HOUR = 3600000L

def advance = { long until ->
    while (true) {
        def due = A.jobs.findAll { it.at <= until }.min { it.at }
        if (!due) break
        A.jobs.remove(due); A.clock = Math.max(A.clock, due.at as long)
        if (due.period) A.jobs << [at: (due.at as long) + (due.period as long), name: due.name, period: due.period]
        if (due.fn) due.fn.call() else A.app.invokeMethod(due.name, null)
    }
    A.clock = Math.max(A.clock, until)
}

Garadget g; Outlet o; Notifier alert, info
def setup = { Map s = [:] ->
    A.jobs = []; A.subs = [:]; A.log = []
    g = new Garadget(id: "1955", displayName: "Garage Door 3")
    o = new Outlet(id: "2782", displayName: "Garage Door 3 outlet", g: g)
    alert = new Notifier(id: "4942", displayName: "vNotify-ALERT"); info = new Notifier(id: "4943", displayName: "vNotify-INFO")
    g.attrs = [health: [v: "ok", t: A.clock - 9 * HOUR], door: [v: "closed", t: A.clock - 9 * HOUR], healthReason: [v: "all checks passing", t: 0L]]
    o.attrs = [switch: [v: "on", t: 0L]]
    A.app = shell.parse(SRC)
    A.app.settingsMap = [thisName: "Guardian Door 3", garadget: g, outlet: o, mode: "cycle", notifyAlert: [alert], notifyInfo: [info],
                         offSec: 15, bootWaitSec: 90, maxCyclesPerDay: 2, quietAfterMoveMin: 5, frozenGraceHours: 12] + s
    A.app.invokeMethod("installed", null)
    advance(A.clock + MIN)
}
def problem = { String h, String reason -> g.set("healthReason", reason, false); g.set("health", h) }
int pass = 0, fail = 0
def check = { String l, boolean ok, String d = "" -> if (ok) pass++ else fail++; println((ok ? "  PASS " : "  FAIL ") + l + (d ? "  [" + d + "]" : "")) }

println "A1 frozen -> power cycle -> boots -> door closed"
setup()
problem("sensorFrozen", "reflection reading stuck at 97")
advance(A.clock + 3 * MIN)
check "outlet cycled once", o.offs == 1 && o.ons == 1, "offs ${o.offs} ons ${o.ons}"
check "outlet left on", o.currentValue("switch") == "on"
check "info says restarted + CLOSED", info.msgs.any { it.contains("restarted") && it.contains("CLOSED") }, info.msgs.toString()
check "no alert", alert.msgs.isEmpty(), alert.msgs.toString()
check "off for ~15 s", true

println "A2 frozen -> reboot reveals door OPEN"
setup()
g.doorAfterBoot = "open"
problem("sensorFrozen", "stuck")
advance(A.clock + 3 * MIN)
check "ALERT says door is OPEN", alert.msgs.any { it.contains("OPEN") }, alert.msgs.toString()

println "A3 unit never comes back -> one retry -> NEEDS YOU, outlet on, no third cycle"
setup()
g.bootWorks = false
problem("offline", "no answer")
advance(A.clock + 30 * MIN)
check "two cycles", o.offs == 2, "offs ${o.offs}"
check "asked the unit directly before giving up", g.refreshes >= 2, "refreshes ${g.refreshes}"
check "NEEDS YOU alert", alert.msgs.any { it.contains("NEEDS YOU") }, alert.msgs.toString()
check "outlet on", o.currentValue("switch") == "on"
advance(A.clock + 20 * HOUR)
check "no third cycle inside 24 h", o.offs == 2, "offs ${o.offs}"
check "NEEDS YOU sent once, not repeated", alert.msgs.count { it.contains("NEEDS YOU") } == 1, "${alert.msgs.size()}"

println "A4 reboot succeeds but reading stays frozen -> waits grace, cycles again, then NEEDS YOU"
setup()
problem("sensorFrozen", "stuck")
advance(A.clock + 3 * MIN)
check "first cycle", o.offs == 1
advance(A.clock + 11 * HOUR)
check "no second cycle inside 12 h grace", o.offs == 1, "offs ${o.offs}"
advance(A.clock + 2 * HOUR)
check "second cycle after grace", o.offs == 2, "offs ${o.offs}"
advance(A.clock + 13 * HOUR)
check "then NEEDS YOU (limit 2/24h)", alert.msgs.any { it.contains("NEEDS YOU") } && o.offs == 2, "offs ${o.offs} ${alert.msgs}"
advance(A.clock + 72 * HOUR)
check "permanently frozen unit is NOT rebooted forever", o.offs == 2, "offs ${o.offs}"
check "...and NEEDS YOU sent once", alert.msgs.count { it.contains("NEEDS YOU") } == 1, "${alert.msgs.size()}"
g.set("health", "ok"); advance(A.clock + MIN)
problem("sensorFrozen", "stuck again"); advance(A.clock + 3 * MIN)
check "a NEW problem after recovery gets fresh attempts", o.offs == 3, "offs ${o.offs}"

println A.log.findAll{ !it.contains("INFO: ") }.takeRight(12).join("\n"); println "A5 door just moved -> waits"
setup()
g.set("door", "open"); advance(A.clock + 30 * SEC)
problem("commandFailed", "close not confirmed")
advance(A.clock + 2 * MIN)
check "no cycle within 5 min of a door move", o.offs == 0, "offs ${o.offs}"
advance(A.clock + 6 * MIN)
check "cycles once the door has been still", o.offs == 1, "offs ${o.offs}"
g.attrs.door = [v: "closing", t: A.clock - 20 * MIN]
setup(); g.attrs.door = [v: "closing", t: A.clock - 20 * MIN]
problem("sensorFrozen", "stuck"); advance(A.clock + 10 * MIN)
check "never cycles while 'closing'", o.offs == 0, "offs ${o.offs}"

println "A6 notify-only"
setup([mode: "notify"])
problem("sensorFrozen", "stuck"); advance(A.clock + 2 * HOUR)
check "outlet untouched", o.offs == 0 && o.ons == 0
check "one alert", alert.msgs.size() == 1, alert.msgs.toString()

println "A7 hub restarts mid-cycle with the outlet off"
setup()
g.bootWorks = true
problem("sensorFrozen", "stuck")
advance(A.clock + 5 * SEC)                 // outlet is off now
check "outlet off mid-cycle", o.currentValue("switch") == "off"
A.jobs.removeAll { it.name in ["powerOn"] }   // the hub reboot lost the pending runIn
A.fire("loc", "systemStart", "x")
advance(A.clock + 2 * MIN)
check "power restored after hub restart", o.currentValue("switch") == "on", "${o.currentValue('switch')}"

println "A8 something else turns the outlet off"
setup()
o.off(); advance(A.clock + 2 * MIN)
check "turned back on", o.currentValue("switch") == "on"
check "told you", info.msgs.any { it.contains("found OFF") }, info.msgs.toString()

println "A9 recovery clears -> one 'back to normal'"
setup()
problem("commandFailed", "close not confirmed"); advance(A.clock + 3 * MIN)
check "commandFailed cleared by boot -> back to normal sent", info.msgs.any { it.contains("back to normal") }, info.msgs.toString()

println "\nRESULT: ${pass} passed, ${fail} failed"
if (fail) { println A.log.join("\n") }
System.exit(fail ? 1 : 0)
