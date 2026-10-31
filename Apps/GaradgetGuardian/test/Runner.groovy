import groovy.json.JsonOutput
import org.codehaus.groovy.control.CompilerConfiguration

// ------------------------------------------------------------------ load real driver source
String driverPath = args[0]
String mode = args.length > 1 ? args[1] : "scenarios"
def gcl = new GroovyClassLoader(this.class.classLoader)
// HubSim compiled onto classpath
def cfg = new CompilerConfiguration(); cfg.scriptBaseClass = "HubSim"
def shell = new GroovyShell(gcl, new Binding(), cfg)
String SRC = new File(driverPath).text

def drv
def newDriver = { Map settings ->
    Sim.reset()
    Sim.settings = [doorName: "Door3", ipAddr: "192.168.13.4", ipPort: "1883", retryTime: 120, refreshStats: true, refreshTime: 15,
                    watchDogSched: true, watchDogTime: 45, staleMinutes: 30, logLevel: "2", healthChecks: true, frozenHours: 36, commandMarginSec: 30] + settings
    drv = shell.parse(SRC)
    drv.run()
    drv
}

def call = { String m, Object... a -> drv.invokeMethod(m, a) }
def deliver = { String topic, Map payload -> call("parse", JsonOutput.toJson([topic: topic, payload: JsonOutput.toJson(payload)])) }
def at = { long ms, Closure fn -> Sim.jobs << [at: ms, fn: fn, period: 0L] }
def advance = { long until ->
    while (true) {
        def due = Sim.jobs.findAll { it.at <= until }.min { it.at }
        if (!due) break
        Sim.jobs.remove(due)
        Sim.clock = Math.max(Sim.clock, due.at as long)
        if (due.period) Sim.jobs << [at: (due.at as long) + (due.period as long), name: due.name, period: due.period]
        if (due.fn) due.fn.call() else drv.invokeMethod(due.name, null)
    }
    Sim.clock = Math.max(Sim.clock, until)
}
long MIN = 60000L, HOUR = 3600000L, SEC = 1000L

// ------------------------------------------------------------------ modelled Garadget
class G {
    String door = "closed"; int base = 97; int bright = 19; boolean alive = true; boolean frozen = false
    Integer frozenSensor = null; long changedAt; boolean answers = true; boolean lies = false
    int srt = 15; int mtt = 23000; Random rnd = new Random(7); int sensorNow = 97
    String timeStr(long now) {
        long s = (now - changedAt).intdiv(1000)
        if (s < 60) return "${s}s"; long m = s.intdiv(60)
        if (m < 120) return "${m}m"; long h = m.intdiv(60)
        if (h < 48) return "${h}h"; return "${h.intdiv(24)}d"
    }
    Map status(long now) {
        if (!frozen) { if (rnd.nextDouble() < 0.45) sensorNow = Math.max(0, Math.min(100, base + rnd.nextInt(3) - 1)) }
        [status: door, time: timeStr(now), sensor: frozen ? frozenSensor : sensorNow, bright: bright, signal: -45]
    }
}
G g
def config = { [sys: "2.0.1", ver: "1.24", rdt: 1000, mtt: g.mtt, rlt: 300, rlp: 1000, srt: g.srt, nme: "Door3", mqtt: 3, mqip: "192.168.13.4", mqpt: 1883, mqus: "", mqto: 15] }
def pubStatus = { deliver("garadget/Door3/status", g.status(Sim.clock)) }
def move = { String target ->
    // physical travel: "opening"/"closing" then settled; reading crosses the threshold
    String mid = target == "open" ? "opening" : "closing"
    g.door = mid; g.changedAt = Sim.clock
    if (!g.frozen) { g.base = target == "open" ? 1 : 97; g.sensorNow = target == "open" ? 2 : 5 }
    pubStatus()
    at(Sim.clock + g.mtt) { g.door = target; g.changedAt = Sim.clock; if (!g.frozen) g.sensorNow = g.base; pubStatus() }
}
def wire = {
    Sim.onPublish = { String topic, String payload ->
        if (!g.alive || !g.answers) return
        if (topic.endsWith("/command")) {
            if (payload == "get-status") at(Sim.clock + 300) { if (g.alive) pubStatus() }
            else if (payload == "get-config") at(Sim.clock + 200) { if (g.alive) deliver("garadget/Door3/config", config()) }
            else if (payload in ["open", "close"]) {
                String target = payload == "open" ? "open" : "closed"
                if (g.lies) {            // frozen unit: claims the result, reading never crosses
                    at(Sim.clock + 500) { g.door = target; g.changedAt = Sim.clock; pubStatus() }
                } else if (g.frozen) {   // frozen unit thinks it is already there: does nothing
                } else if (g.door != target) at(Sim.clock + 200) { move(target) }
            }
        }
    }
}
def boot = { long offMs ->     // power cycle: dead for offMs, reconnects ~12 s after power returns, publishes status + config
    g.alive = false
    at(Sim.clock + offMs) { }
    at(Sim.clock + offMs + 12100) {
        g.alive = true; g.frozen = false; g.changedAt = Sim.clock - 10000L
        pubStatus(); deliver("garadget/Door3/config", config())
    }
}
def start = { Map s = [:] ->
    newDriver(s)
    g = new G(); g.changedAt = Sim.clock - 5 * HOUR
    wire()
    // seed device as an existing install: attributes already present from v1.4.5
    Sim.attrs.sensor = [value: 97, date: Sim.clock - 5 * HOUR]; Sim.attrs.door = [value: "closed", date: Sim.clock - 5 * HOUR]
    Sim.attrs.srt = [value: 15, date: Sim.clock - 100 * HOUR]; Sim.attrs.mtt = [value: 23000, date: Sim.clock - 100 * HOUR]
    Sim.state.lastMessageReceived = Sim.clock - 10 * MIN
    call("updated")       // what saving the new driver version does
    advance(Sim.clock + 2 * MIN)
}
def health = { Sim.attrs.health?.value }
def healthEvents = { Sim.events.findAll { it.name == "health" }.collect { "${Sim.fmt(it.at)} ${it.value}" } }
def evts = { String n -> Sim.events.findAll { it.name == n } }
int pass = 0, fail = 0
def check = { String label, boolean ok, String detail = "" ->
    if (ok) pass++ else fail++
    println((ok ? "  PASS " : "  FAIL ") + label + (detail ? "  [" + detail + "]" : ""))
}

if (mode == "scenarios") {
    // ---------------------------------------------------------------- S1 healthy idle week
    println "S1 healthy door, never used, 7 days"
    start()
    long t0 = Sim.clock
    advance(t0 + 7 * 24 * HOUR)
    check "health never left ok", healthEvents().every { it.endsWith(" ok") }, healthEvents().toString()
    check "health attribute is ok", health() == "ok", "${health()}"
    check "no false lastBoot", evts("lastBoot").isEmpty(), evts("lastBoot").collect { it.value }.toString()
    check "no warnings logged", !Sim.logs.any { it.contains("WARN") || it.contains("ERROR") }, Sim.logs.findAll { it.contains("WARN") }.take(2).toString()

    // ---------------------------------------------------------------- S2 freeze -> detect -> reboot -> recovers
    println "S2 sensor freezes while unit stays online"
    start()
    t0 = Sim.clock
    advance(t0 + 6 * HOUR)
    g.frozen = true; g.frozenSensor = g.sensorNow
    long frozeAt = Sim.clock
    long lastChange = (Sim.state.lastSensorChangeAt as long)
    advance(frozeAt + 35 * HOUR)
    check "still ok 35 h after freeze starts", health() == "ok", "${health()}"
    advance(frozeAt + 38 * HOUR)
    check "sensorFrozen within 36 h + 15 min of last change", health() == "sensorFrozen", "${health()}"
    def fe = evts("health").find { it.value == "sensorFrozen" }
    long lag = fe ? ((fe.at as long) - lastChange) : -1
    check "fired 36h..36h15m after last real change", fe && lag >= 36 * HOUR && lag <= 36 * HOUR + 16 * MIN, "lag ${lag / MIN} min"
    check "reason names the stuck value", (Sim.attrs.healthReason?.value ?: "").contains("stuck at"), "${Sim.attrs.healthReason?.value}"
    check "unit was never 'offline' meanwhile", !healthEvents().any { it.endsWith("offline") }
    // power cycle
    boot(15000L)
    long cyc = Sim.clock
    advance(cyc + 60 * SEC)
    check "lastBoot reported within 30 s of power-on", evts("lastBoot").any { (it.at as long) - cyc <= 15000 + 30000 }, evts("lastBoot").collect { "${Sim.fmt(it.at)} ${it.value}" }.toString()
    advance(cyc + 4 * HOUR)
    check "health back to ok once the reading moves again", health() == "ok", "${health()} ${healthEvents()}"

    // ---------------------------------------------------------------- S3 close proven by sensor
    println "S3 close command on a healthy unit"
    start()
    call("open"); advance(Sim.clock + 2 * MIN)
    check "open confirmed", (Sim.attrs.lastCommandResult?.value ?: "").contains("open") && Sim.attrs.lastCommandResult.value.contains("confirmed"), "${Sim.attrs.lastCommandResult?.value}"
    advance(Sim.clock + 20 * MIN)
    call("close"); advance(Sim.clock + 2 * MIN)
    check "close confirmed", (Sim.attrs.lastCommandResult?.value ?: "").startsWith("close") && Sim.attrs.lastCommandResult.value.contains("confirmed"), "${Sim.attrs.lastCommandResult?.value}"
    check "health ok", health() == "ok", "${health()}"
    check "door closed", Sim.attrs.door?.value == "closed"
    check "a door move is not a boot", evts("lastBoot").isEmpty(), evts("lastBoot").collect { it.value }.toString()

    // ---------------------------------------------------------------- S4 close on a frozen unit that does nothing
    println "S4 auto-close fires, frozen unit does nothing"
    start()
    call("open"); advance(Sim.clock + 2 * MIN)
    g.frozen = true; g.frozenSensor = g.sensorNow
    call("close")
    long c4 = Sim.clock
    advance(c4 + 23 * SEC + 30 * SEC + 25 * SEC)
    check "commandFailed after travel + margin + poll", health() == "commandFailed", "${health()} at +${(Sim.clock - c4) / 1000}s"
    check "door still reported open (not hidden)", Sim.attrs.door?.value == "open"

    // ---------------------------------------------------------------- S5 unit LIES: says closed, reading never crosses
    println "S5 auto-close fires, unit reports 'closed' but the reading never crosses"
    start()
    call("open"); advance(Sim.clock + 2 * MIN)
    g.lies = true; g.frozen = true; g.frozenSensor = 1
    call("close")
    long c5 = Sim.clock
    advance(c5 + 2 * MIN)
    check "status string said closed", Sim.attrs.door?.value == "closed"
    check "... but commandFailed (J.R.'s case)", health() == "commandFailed", "${health()} ${Sim.attrs.lastCommandResult?.value}"
    check "reason says not confirmed", (Sim.attrs.healthReason?.value ?: "").contains("not confirmed"), "${Sim.attrs.healthReason?.value}"
    // recovery: power cycle, then a real close
    g.lies = false
    boot(15000L); advance(Sim.clock + 2 * MIN)
    g.door = "open"; g.base = 1; g.sensorNow = 1   // after reboot the truth: door is open
    pubStatus(); advance(Sim.clock + 1 * MIN)
    call("close"); advance(Sim.clock + 2 * MIN)
    check "after reboot a proven close clears it", health() == "ok", "${health()} ${Sim.attrs.lastCommandResult?.value}"

    // ---------------------------------------------------------------- S6 offline and back
    println "S6 unit dies, comes back"
    start()
    g.alive = false
    long d6 = Sim.clock
    advance(d6 + 59 * MIN)
    check "not offline before 60 min", health() == "ok", "${health()}"
    advance(d6 + 80 * MIN)
    check "offline by ~75 min", health() == "offline", "${health()} ${healthEvents()}"
    g.alive = true
    advance(Sim.clock + 20 * MIN)
    check "back to ok after it answers", health() == "ok", "${health()}"

    // ---------------------------------------------------------------- S7 retained replay must not reset the frozen clock
    println "S7 freeze, then broker reconnects and replays retained (old, different) values"
    start()
    g.frozen = true; g.frozenSensor = g.sensorNow
    long f7 = Sim.clock
    advance(f7 + 20 * HOUR)
    long clk = Sim.state.lastSensorChangeAt as long
    call("reconnectMqtt")   // driver sets trustData=false at subscribe
    deliver("garadget/Door3/status", [status: "closed", time: "3d", sensor: 55, bright: 19, signal: -40])   // replay, different value
    advance(Sim.clock + 30 * SEC)
    check "replayed value did not move the change clock", (Sim.state.lastSensorChangeAt as long) == clk
    advance(f7 + 38 * HOUR)
    check "still detected as frozen", health() == "sensorFrozen", "${health()}"

    // ---------------------------------------------------------------- S8 upgrade mid-freeze keeps the clock
    println "S8 install v1.4.6 onto a device whose reading has not changed for 30 h"
    newDriver([:]); g = new G(); g.frozen = true; g.frozenSensor = 97; g.changedAt = Sim.clock - 30 * HOUR; wire()
    Sim.attrs.sensor = [value: 97, date: Sim.clock - 30 * HOUR]; Sim.attrs.door = [value: "closed", date: Sim.clock - 30 * HOUR]
    Sim.attrs.srt = [value: 15, date: 0L]; Sim.attrs.mtt = [value: 23000, date: 0L]
    Sim.state.lastMessageReceived = Sim.clock - 5 * MIN
    long u8 = Sim.clock
    call("updated")
    advance(u8 + 7 * HOUR)
    def fe8 = evts("health").find { it.value == "sensorFrozen" }
    check "frozen reported ~6 h after the upgrade (clock seeded from the 30-h-old event)", fe8 && (fe8.at as long) - u8 <= 6 * HOUR + 16 * MIN, fe8 ? "at +${((fe8.at as long) - u8) / MIN} min" : "never"

    // ---------------------------------------------------------------- S9 wall button: physical change at 0 s is not a boot
    println "S9 wall-button open and close"
    start()
    g.door = "open"; g.base = 1; g.sensorNow = 1; g.changedAt = Sim.clock; pubStatus()
    advance(Sim.clock + 40 * SEC); call("pollNow"); advance(Sim.clock + 5 * SEC)    // poll 40 s later: time "40s", same state
    advance(Sim.clock + 30 * MIN)
    g.door = "closed"; g.base = 97; g.sensorNow = 97; g.changedAt = Sim.clock; pubStatus()
    advance(Sim.clock + 2 * HOUR)
    check "no false lastBoot from button presses", evts("lastBoot").isEmpty(), evts("lastBoot").collect { it.value }.toString()
    check "health ok", health() == "ok", "${health()}"

    // ---------------------------------------------------------------- S10 boot where the truth differs (the 10-30 07:47 case)
    println "S10 reboot reveals the door was really open (closed -> open at 9 s, no motion)"
    start()
    advance(Sim.clock + 30 * MIN)
    g.alive = false; long c10 = Sim.clock
    at(c10 + 15000 + 12000) { g.alive = true; g.door = "open"; g.base = 1; g.sensorNow = 1; g.changedAt = Sim.clock - 9000L; pubStatus(); deliver("garadget/Door3/config", config()) }
    advance(c10 + 2 * MIN)
    check "lastBoot reported", !evts("lastBoot").isEmpty()
    check "door now open", Sim.attrs.door?.value == "open"

    // ---------------------------------------------------------------- S11 health checks off = v1.4.5 behaviour
    println "S11 healthChecks=false"
    start([healthChecks: false])
    g.frozen = true; g.frozenSensor = 97
    advance(Sim.clock + 50 * HOUR)
    check "no health problems reported when disabled", !healthEvents().any { !it.endsWith(" ok") }, healthEvents().toString()

    // ---------------------------------------------------------------- S12 command while already closed is not verified
    println "S12 close sent while already closed"
    start()
    call("close"); advance(Sim.clock + 3 * MIN)
    check "no failure for a no-op close", health() == "ok" && !Sim.state.cmdPending, "${health()}"

    println "\nRESULT: ${pass} passed, ${fail} failed"
    System.exit(fail ? 1 : 0)
}

if (mode == "replay") {
    String dev = args[3]
    def rows = new File(args[2]).readLines().drop(1).collect { it.split(",", 4) }.findAll { it[0] == dev }
                 .collect { [ms: it[1] as long, attr: it[2], v: it[3]] }
    println "Door ${dev}: ${rows.size()} events ${Sim.fmt(rows[0].ms)} -> ${Sim.fmt(rows[-1].ms)}"
    newDriver([doorName: "Door3"])
    Sim.onPublish = null                  // the driver's own polls are answered by the replayed cadence below
    Sim.clock = rows[0].ms - 60000L
    Map cur = [status: "closed", time: "1h", sensor: 97, bright: 19, signal: -45]
    long timeBaseSec = 3600L, timeSetAt = Sim.clock
    def toSec = { String t -> def m = (t =~ /^(\d+)([smhd])$/); if (!m.matches()) return 3600L; long n = m[0][1] as long; ["s":1L,"m":60L,"h":3600L,"d":86400L][m[0][2]] * n }
    def fmtT = { long sec -> if (sec < 60) return "${sec}s"; long mm = sec.intdiv(60); if (mm < 120) return "${mm}m"; long hh = mm.intdiv(60); if (hh < 48) return "${hh}h"; return "${hh.intdiv(24)}d" }
    rows.findAll { it.attr in ["srt","mtt"] }.each { Sim.attrs[it.attr] = [value: it.v as int, date: 0L] }
    if (!Sim.attrs.srt) Sim.attrs.srt = [value: 25, date: 0L]
    Sim.attrs.mtt = Sim.attrs.mtt ?: [value: 20000, date: 0L]
    Sim.state.lastMessageReceived = Sim.clock
    call("updated")
    int i = 0
    long poll = Sim.clock - (Sim.clock % (15 * MIN)) + 15 * MIN + 18000L
    long end = rows[-1].ms + 30 * MIN
    while (Sim.clock < end) {
        long nextEvt = i < rows.size() ? rows[i].ms : Long.MAX_VALUE
        long next = Math.min(nextEvt, poll)
        advance(next)
        if (next == nextEvt) {
            // apply every event in this same ms, then deliver one status if a status field changed
            boolean statusish = false
            // one real Garadget message is stored as several attribute events a few ms apart: regroup the burst
            while (i < rows.size() && rows[i].ms - nextEvt < 1500L && !(rows[i].attr == "door" && rows[i].ms != nextEvt && cur.status != null && rows[i].ms - nextEvt > 400L)) {
                def r = rows[i++]
                if (r.attr == "door") { cur.status = r.v; statusish = true }
                else if (r.attr in ["sensor","bright","signal"]) { cur[r.attr] = r.v as int; statusish = true }
                else if (r.attr == "time") { cur.time = r.v; timeBaseSec = toSec(r.v); timeSetAt = r.ms; statusish = true }
            }
            if (statusish) { advance(rows[i-1].ms); deliver("garadget/Door3/status", new LinkedHashMap(cur)) }
        } else {
            // a real unit's "time since change" keeps counting between recorded changes
            long est = timeBaseSec + (Sim.clock - timeSetAt).intdiv(1000)
            if (est >= 60 || toSec(cur.time) < est) cur.time = fmtT(est)
            deliver("garadget/Door3/status", new LinkedHashMap(cur))
            poll += 15 * MIN
        }
    }
    println "  health transitions:"; Sim.events.findAll { it.name == "health" }.each { println "    ${Sim.fmt(it.at)}  ${it.value}  ${it.descriptionText ?: ''}" }
    println "  lastBoot detections:"; Sim.events.findAll { it.name == "lastBoot" }.each { println "    ${Sim.fmt(it.at)}  boot≈${it.value}" }
    println "  warnings: " + Sim.logs.count { it.contains(" WARN ") }
}
