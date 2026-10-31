import groovy.json.JsonOutput
import groovy.json.JsonSlurper

/** Minimal Hubitat platform simulation for running a real driver source file. */
class Sim {
    static long clock = 1790000000000L
    static List<Map> jobs = []             // [at, name, periodMs, args]
    static Map<String, Map> attrs = [:]    // name -> [value, date]
    static List<Map> events = []           // every sendEvent
    static List<String> logs = []
    static List<Map> published = []
    static Closure onPublish = null        // the modelled Garadget
    static Map settings = [:]
    static Map data = [:]
    static StateMap state = new StateMap()
    static boolean mqttConnected = true
    static String label = "Garage Door 3"

    static void reset() {
        jobs = []; attrs = [:]; events = []; logs = []; published = []; state = new StateMap(); data = [:]
        mqttConnected = true
    }
    static String fmt(long ms) { new Date(ms).format("MM-dd HH:mm:ss", TimeZone.getTimeZone("America/New_York")) }
}

/** Hubitat state is persisted as JSON: values come back as fresh copies with JSON number types. */
class StateMap extends LinkedHashMap {
    Object put(Object k, Object v) { super.put(k, v == null ? null : new JsonSlurper().parseText(JsonOutput.toJson([x: v])).x) }
    Object get(Object k) { def v = super.get(k); v == null ? null : new JsonSlurper().parseText(JsonOutput.toJson([x: v])).x }
    void setProperty(String k, Object v) { put(k, v) }
    Object getProperty(String k) { get(k) }
}

class FakeState { def value; Date date }

class FakeDevice {
    String getLabel() { Sim.label }
    String getName() { "Garadget MQTT" }
    String getDeviceNetworkId() { "SIM-DNI" }
    static Set UNDECLARED = ["rdt","mtt","rlt","rlp","srt","mqip","mqpt","mqto"] as Set
    def currentValue(String n) { UNDECLARED.contains(n) ? null : Sim.attrs[n]?.value }
    def currentState(String n) { def a = Sim.attrs[n]; a == null ? null : new FakeState(value: a.value, date: new Date(a.date as long)) }
    void updateSetting(String n, Map m) { Sim.settings[n] = m.value }
    String getDataValue(String n) { Sim.data[n] }
    void updateDataValue(String n, String v) { Sim.data[n] = v }
}

class FakeLog {
    void debug(m) { Sim.logs << "${Sim.fmt(Sim.clock)} DEBUG $m".toString() }
    void info(m)  { Sim.logs << "${Sim.fmt(Sim.clock)} INFO  $m".toString() }
    void warn(m)  { Sim.logs << "${Sim.fmt(Sim.clock)} WARN  $m".toString() }
    void error(m) { Sim.logs << "${Sim.fmt(Sim.clock)} ERROR $m".toString() }
}

class FakeMqtt {
    void connect(String broker, String client, u, p) { Sim.mqttConnected = true }
    void disconnect() { Sim.mqttConnected = false }
    boolean isConnected() { Sim.mqttConnected }
    void subscribe(String t) { Sim.published << [at: Sim.clock, topic: "SUBSCRIBE", payload: t] }
    void publish(String topic, String payload) {
        Sim.published << [at: Sim.clock, topic: topic, payload: payload]
        if (Sim.onPublish) Sim.onPublish.call(topic, payload)
    }
    Map parseMessage(String d) { new JsonSlurper().parseText(d) as Map }
}

abstract class HubSim extends Script {
    def getState() { Sim.state }
    def getDevice() { new FakeDevice() }
    def getLog() { new FakeLog() }
    def getLocation() { [timeZone: TimeZone.getTimeZone("America/New_York")] }
    def getInterfaces() { [mqtt: new FakeMqtt()] }
    def getSettings() { Sim.settings }

    // unset settings read as null, a bare method name (runIn(15, pollNow)) reads as that name
    def propertyMissing(String n) {
        if (Sim.settings.containsKey(n)) return Sim.settings[n]
        if (this.metaClass.respondsTo(this, n)) return n
        return null
    }

    long now() { Sim.clock }
    void pauseExecution(long ms) { }
    def parseJson(String s) { new JsonSlurper().parseText(s) }
    void metadata(Closure c) { }

    void sendEvent(Map m) {
        Sim.events << ([at: Sim.clock] + m)
        Sim.attrs[m.name] = [value: m.value, date: Sim.clock]
    }
    void runIn(Number sec, Object handler, Map opts = [:]) {
        String h = handler.toString()
        Sim.jobs.removeAll { it.name == h && !it.period }   // runIn overwrites by default
        Sim.jobs << [at: Sim.clock + (long) (sec * 1000), name: h, period: 0L]
    }
    void schedule(String cron, Object handler) {
        def p = cron.split(" ")
        int s = p[0] as int
        def mm = p[1].split("/")
        int off = mm[0] as int, every = mm[1] as int
        long periodMs = every * 60000L
        long hourStart = Sim.clock - (Sim.clock % 3600000L)
        long first = hourStart + off * 60000L + s * 1000L
        while (first <= Sim.clock) first += periodMs
        Sim.jobs << [at: first, name: handler.toString(), period: periodMs]
    }
    void unschedule(Object h = null) {
        // only the driver's own jobs: simulated device replies (fn jobs) are not the driver's to cancel
        if (h == null) Sim.jobs.removeAll { it.name } else Sim.jobs.removeAll { it.name == h.toString() }
    }
}
