/**
 * A small Hubitat stand-in for running SmartFilterProHubitatApp.groovy off
 * the hub. It loads the real app source and stubs the platform around it:
 *
 *   - a controllable clock (now()) and a scheduler for runEvery* / runIn
 *   - state: copied per execution and saved only when the execution
 *     returns (a crash discards it), like the hub
 *   - atomicState: written immediately, values round-tripped through JSON
 *     (so a mutated read without a write-back is lost, like the hub)
 *   - a fake thermostat whose attributes tests change with or without
 *     delivering the event to the app
 *   - httpPost: Core ingest (with Core's three dedup keys: sequence_number,
 *     source_event_id, and device + event_type + equipment_status +
 *     recorded_at) and the Bubble core_token endpoint. No network is used.
 *
 * Hubitat runs Groovy 2.4; tests/run_tests.sh uses groovy-all 2.4.21.
 */
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.codehaus.groovy.control.CompilerConfiguration

import java.time.LocalDateTime
import java.time.ZoneId

class HubitatHarness {
    static final String TZ = "America/New_York"
    static final String CORE_TOKEN = "core-token-test"

    long clock
    Map stateStore = [:]
    AtomicStore atomicState = new AtomicStore()
    Map settings = [:]
    FakeLog log = new FakeLog()
    FakeThermostat dev = new FakeThermostat()
    Script app
    Binding binding

    // scheduler
    Map periodic = [:]          // name -> [interval: ms, next: ms]
    Map once = [:]              // name -> at ms
    List subscriptions = []
    static final Set IGNORED_JOBS = ["pollBubbleStatus", "checkForUpdate"] as Set

    // Core model
    List attempts = []          // every batch POSTed to Core (copies)
    List core = []              // events Core stored (after dedup)
    Set coreSeqs = [] as Set
    Set coreEventIds = [] as Set
    Set coreTimeKeys = [] as Set   // UNIQUE (device_id, event_type, equipment_status, recorded_at)
    int duplicates = 0
    Closure coreFailure = null  // { List batch -> null | "transient" | "crash" }
    boolean bubbleUp = true
    int tokenIssues = 0

    HubitatHarness(String appPath) {
        binding = new Binding()
        binding.setVariable("__harness", this)
        binding.setVariable("settings", settings)
        binding.setVariable("atomicState", atomicState)
        binding.setVariable("log", log)
        binding.setVariable("location", [timeZone: TimeZone.getTimeZone(TZ), hubs: [[id: "hub-1"]]])
        binding.setVariable("app", [id: 7, label: "SmartFilterPro"])
        binding.setVariable("state", stateStore)
        def config = new CompilerConfiguration()
        config.scriptBaseClass = HubitatAppScript.name
        app = new GroovyShell(HubitatHarness.classLoader, binding, config).parse(new File(appPath))
        app.run()   // definition / preferences / mappings
    }

    static long t(String local) {
        LocalDateTime.parse(local.replace(' ', 'T')).atZone(ZoneId.of(TZ)).toInstant().toEpochMilli()
    }

    static String iso(long ms) { new Date(ms).toInstant().toString() }

    /** Link the app, select the thermostat and install it at `atMs`. */
    void install(long atMs, Map deviceAttrs = [:]) {
        clock = atMs
        dev.attrs.putAll([thermostatOperatingState: "idle", thermostatFanMode: "auto", thermostatMode: "heat",
                          temperature: 70.0d, humidity: 40.0d, heatingSetpoint: 68.0d, coolingSetpoint: 76.0d,
                          thermostatSetpoint: 68.0d])
        dev.attrs.putAll(deviceAttrs)
        dev.attrs.keySet().each { dev.dates[it] = atMs }
        dev.lastActivityMs = atMs
        settings.thermostat = dev
        settings.enableDebugLogging = false
        settings.httpTimeout = 30
        stateStore.sfpAccessToken = "access-token-test"
        stateStore.sfpRefreshToken = "refresh-token-test"
        stateStore.sfpUserId = "user-e2e"
        stateStore.sfpHvacId = "e2e-hubitat"
        stateStore.sfpHvacName = "Hall"
        stateStore.sfpCoreToken = CORE_TOKEN
        stateStore.sfpCoreTokenExp = (long) (atMs / 1000L) + 10L * 365 * 86400
        exec { app.installed() }
    }

    /** One app execution: state is saved when it returns, discarded on a crash. */
    void exec(Closure body) {
        Map working = copyState(stateStore)
        binding.setVariable("state", working)
        try {
            body.call()
            stateStore = working
        } catch (HubCrash crash) {
            log.add("CRASH", crash.message)
        } finally {
            binding.setVariable("state", stateStore)
        }
    }

    private static Map copyState(Map src) {
        Map out = [:]
        src.each { k, v ->
            if (k == "eventBuffer") out[k] = v == null ? null : new ArrayList(v as List)
            else if (v instanceof FakeThermostat) out[k] = v
            else out[k] = AtomicStore.jsonCopy(v)
        }
        return out
    }

    // ---------------------------------------------------------- scheduler

    void every(String name, long intervalMs) { periodic[name] = [interval: intervalMs, next: clock + intervalMs] }
    void runIn(long secs, String name) { once[name] = clock + secs * 1000L }
    void unscheduleAll() { periodic.clear(); once.clear() }

    /** Run every job due up to `toMs` in time order, then set the clock to `toMs`. */
    void advanceTo(long toMs) {
        while (true) {
            def due = nextJob()
            if (due == null || due.at > toMs) break
            clock = due.at
            if (due.periodic) periodic[due.name].next = due.at + periodic[due.name].interval
            else once.remove(due.name)
            if (!(due.name in IGNORED_JOBS)) exec { app."${due.name}"() }
        }
        clock = toMs
    }

    private Map nextJob() {
        Map best = null
        periodic.each { n, j -> if (best == null || j.next < best.at || (j.next == best.at && n < best.name)) best = [name: n, at: j.next, periodic: true] }
        once.each { n, at -> if (best == null || at < best.at || (at == best.at && n < best.name)) best = [name: n, at: at, periodic: false] }
        return best
    }

    /** The hub is off from now until `untilMs`: nothing runs; schedules resume after. */
    void hubOff(long untilMs) {
        periodic.each { n, j -> while (j.next < untilMs) j.next += j.interval }
        once.clear()
        clock = untilMs
    }

    /** The hub finished booting: location systemStart. */
    void systemStart() {
        subscriptions.findAll { it[1] == "systemStart" }.each { s -> exec { app."${s[2]}"([name: "systemStart", value: "start"]) } }
    }

    // ------------------------------------------------------------ device

    /** The thermostat reports a change and the hub delivers it to the app. */
    void deviceEvent(String attr, def value) {
        dev.set(attr, value, clock)
        exec { app.handleEvent([name: attr, value: value, displayName: dev.displayName]) }
    }

    /** The attribute changes on the hub but the app never gets the event. */
    void silentChange(String attr, def value) { dev.set(attr, value, clock) }

    // -------------------------------------------------------------- http

    void httpPost(Map params, Closure callback) {
        String uri = params.uri as String
        if (uri.contains("/ingest/v1/events:batch")) {
            List batch = AtomicStore.jsonCopy(params.body) as List
            attempts << batch
            String auth = (params.headers?.Authorization ?: "") as String
            if (auth != "Bearer ${CORE_TOKEN}".toString()) {
                callback.call([status: 401, data: [ok: false, error: "invalid_token"]])
                return
            }
            String failure = coreFailure?.call(batch)
            if (failure == "crash") throw new HubCrash("hub died during the POST")
            if (failure == "transient") throw new IOException("Connection reset")
            batch.each { Map e ->
                String seq = "${e.device_id}|${e.source_vendor}|${e.sequence_number}"
                String eid = e.source_event_id ? "${e.device_id}|${e.source_event_id}" : null
                // Core's baseline schema also has UNIQUE (device_id, event_type,
                // equipment_status, recorded_at), event_type normalized to upper case.
                String timeKey = "${e.device_id}|${(e.event_type as String).toUpperCase()}|${e.equipment_status}|${java.time.Instant.parse(e.timestamp as String).toEpochMilli()}"
                if (coreSeqs.contains(seq) || (eid && coreEventIds.contains(eid)) || coreTimeKeys.contains(timeKey)) { duplicates++; return }
                coreSeqs << seq
                coreTimeKeys << timeKey
                if (eid) coreEventIds << eid
                core << e
            }
            callback.call([status: 200, data: [ok: true, inserted: []]])
            return
        }
        if (uri.endsWith("/issue_core_token_hub")) {
            if (!bubbleUp) throw new IOException("Bubble unavailable")
            tokenIssues++
            callback.call([status: 200, data: [response: [core_token: CORE_TOKEN, expires_at: (long) (clock / 1000L) + 86400L]]])
            return
        }
        throw new IOException("unexpected POST in test: ${uri}")
    }

    // ------------------------------------------------------- Core runtime

    /** Events Core stored that carry runtime, in Core's processing order. */
    List runtimePieces() {
        core.findAll { ((it.runtime_seconds ?: 0) as long) > 0 }.collect { Map e ->
            long end = java.time.Instant.parse(e.timestamp as String).toEpochMilli()
            long secs = e.runtime_seconds as long
            [status: (e.previous_status ?: e.equipment_status) as String, startMs: end - secs * 1000L, endMs: end,
             seconds: secs, checkpoint: (e.runtime_type == "CHECKPOINT"), event: e]
        }.sort { a, b -> a.endMs <=> b.endMs }
    }

    static String mode(String status) {
        String s = (status ?: "").toLowerCase()
        if (s.contains("aux")) return "auxheat"
        if (s.contains("heat")) return "heat"
        if (s.contains("cool")) return "cool"
        if (s.contains("fan")) return "fan"
        return "unknown"
    }

    /** Sessions as Core's sessionStitcher builds them from the pieces. */
    List coreSessions() {
        List sessions = []
        runtimePieces().each { Map p ->
            if (p.seconds > 86400L) return     // Core rejects it
            String m = mode(p.status)
            Map open = sessions.reverse().find { it.mode == m && it.terminated == "checkpoint" && Math.abs(it.endMs - p.startMs) <= 300000L }
            if (open) {
                open.endMs = p.endMs; open.seconds += p.seconds; open.pieces++
                open.terminated = p.checkpoint ? "checkpoint" : "posted_runtime"
            } else {
                sessions << [mode: m, startMs: p.startMs, endMs: p.endMs, seconds: p.seconds, pieces: 1,
                             terminated: p.checkpoint ? "checkpoint" : "posted_runtime"]
            }
        }
        return sessions
    }

    long totalSeconds(String m = null) { runtimePieces().findAll { m == null || mode(it.status) == m }.sum { it.seconds } ?: 0L }
}

/** Base class for the app script: the Hubitat platform methods it calls. */
abstract class HubitatAppScript extends Script {
    HubitatHarness getH() { binding.getVariable("__harness") as HubitatHarness }

    def propertyMissing(String name) { h.settings[name] }

    long now() { h.clock }
    void definition(Map m) {}
    void preferences(Closure c) {}
    void mappings(Closure c) {}
    void subscribe(Object target, String attr, Object handler) { h.subscriptions << [target, attr, handler] }
    void unsubscribe() { h.subscriptions.clear() }
    void unschedule() { h.unscheduleAll() }
    void runEvery1Minute(String n) { h.every(n, 60000L) }
    void runEvery5Minutes(String n) { h.every(n, 300000L) }
    void runEvery10Minutes(String n) { h.every(n, 600000L) }
    void runEvery15Minutes(String n) { h.every(n, 900000L) }
    void runEvery30Minutes(String n) { h.every(n, 1800000L) }
    void schedule(String cron, String n) {}
    void runIn(Number secs, String n, Map opts = null) { h.runIn(secs as long, n) }
    void httpPost(Map params, Closure c) { h.httpPost(params, c) }
    void httpGet(Map params, Closure c) { throw new IOException("no network in tests") }
    def getChildDevice(String dni) { null }
    void deleteChildDevice(String dni) {}
    String getHubUID() { "hub-1" }
}

/** atomicState: immediate writes; reads and writes are JSON copies. */
class AtomicStore {
    Map store = [:]

    static def jsonCopy(def v) {
        if (v == null) return null
        return new JsonSlurper().parseText(JsonOutput.toJson([v: v])).v
    }

    def read(Object k) { jsonCopy(store[k.toString()]) }
    void write(Object k, Object v) { store[k.toString()] = jsonCopy(v) }
    def getAt(String k) { read(k) }
    def getAt(GString k) { read(k) }
    void putAt(String k, Object v) { write(k, v) }
    void putAt(GString k, Object v) { write(k, v) }
    def remove(Object k) { store.remove(k.toString()) }
    def propertyMissing(String n) { read(n) }
    def propertyMissing(String n, def v) { write(n, v) }
}

class FakeLog {
    List lines = []
    boolean echo = System.getenv("HARNESS_LOG") != null
    void add(String level, def msg) {
        lines << "${level} ${msg}".toString()
        if (lines.size() > 4000) lines = lines.drop(1000)
        if (echo) println "  [${level}] ${msg}"
    }
    void debug(def m) { add("DEBUG", m) }
    void info(def m) { add("INFO", m) }
    void warn(def m) { add("WARN", m) }
    void error(def m) { add("ERROR", m) }
    void trace(def m) {}
}

class FakeThermostat {
    String label = "Hall Thermostat"
    String displayName = "Hall Thermostat"
    String name = "Generic Z-Wave Thermostat"
    String deviceNetworkId = "ZW-42"
    Map attrs = [:]
    Map dates = [:]
    Long lastActivityMs
    String healthStatus = "ACTIVE"
    int refreshCount = 0
    Closure onRefresh = null

    void set(String attr, def value, long atMs) {
        attrs[attr] = value
        dates[attr] = atMs
        lastActivityMs = atMs
    }

    def getId() { 42 }
    def currentValue(String n) { attrs[n] }
    def currentState(String n) { attrs.containsKey(n) ? [value: attrs[n], date: new Date(dates[n] as long)] : null }
    def getDataValue(String k) { null }
    def getStatus() { healthStatus }
    def getLastActivity() { lastActivityMs != null ? new Date(lastActivityMs as long) : null }
    boolean hasCommand(String c) { c == "refresh" }
    void refresh() { refreshCount++; onRefresh?.call() }
    def getSupportedThermostatModes() { ["heat", "cool", "auto", "off"] }
    def getSupportedThermostatFanModes() { ["auto", "on", "circulate"] }

    def propertyMissing(String n) {
        if (n.startsWith("current") && n.length() > 7) {
            String a = n.substring(7)
            return attrs[a[0].toLowerCase() + a.substring(1)]
        }
        throw new MissingPropertyException(n, FakeThermostat)
    }
}

/** Thrown by a test to kill the current execution, as a hub crash would. */
class HubCrash extends Error {
    HubCrash(String m) { super(m) }
}
