/**
 * Runtime checkpoint tests for SmartFilterProHubitatApp.groovy, run against
 * the real app source through tests/HubitatHarness.groovy.
 *
 *   tests/run_tests.sh                       (all tests, the app in this repo)
 *   tests/run_tests.sh path/to/App.groovy    (another copy, e.g. an older version)
 *
 * Environment:
 *   DUMP_RUN=file.json    write Core's events for the 60-hour run (for an
 *                         end-to-end check through Core's ingest + workers)
 *   UPDATE_GOLDEN=1       rewrite tests/golden/checkpoint_event.json
 *   HARNESS_LOG=1         echo the app's log
 */
import groovy.json.JsonOutput
import groovy.json.JsonSlurper

import static HubitatHarness.t
import static HubitatHarness.iso

String appPath = args ? args[0] : "SmartFilterProHubitatApp.groovy"
String testsDir = new File(getClass().protectionDomain.codeSource.location.path).parent
println "App under test: ${appPath}"

int passed = 0
List failures = []

def test = { String name, Closure body ->
    try {
        body.call()
        passed++
        println "  PASS  ${name}"
    } catch (Throwable e) {
        failures << name
        println "  FAIL  ${name}: ${e instanceof CheckFailed ? '' : e.class.simpleName + ' '}${e.message?.readLines()?.take(6)?.join(' | ')}"
        if (System.getenv("HARNESS_TRACE")) e.printStackTrace()
    }
}

class CheckFailed extends RuntimeException { CheckFailed(String m) { super(m) } }

def check = { boolean cond, String msg -> if (!cond) throw new CheckFailed(msg) }

def harness = { new HubitatHarness(appPath) }

def min = { long m -> m * 60000L }

/** Pieces are contiguous (each starts where the previous ended) within a session. */
def assertContiguous = { List pieces ->
    for (int i = 1; i < pieces.size(); i++) {
        check(pieces[i].startMs == pieces[i - 1].endMs,
              "piece ${i} starts ${iso(pieces[i].startMs)}, previous ended ${iso(pieces[i - 1].endMs)}")
    }
}

/**
 * Core processes runtime in timestamp order and skips events older than
 * what it already processed, so a runtime piece must never be stamped
 * before an event that was posted earlier.
 */
def assertRuntimeNotBackdated = { HubitatHarness h ->
    long latest = Long.MIN_VALUE
    h.attempts.flatten().each { Map e ->
        long ts = java.time.Instant.parse(e.timestamp as String).toEpochMilli()
        if (((e.runtime_seconds ?: 0) as long) > 0)
            check(ts >= latest, "runtime event at ${e.timestamp} posted after an event at ${iso(latest)}")
        latest = Math.max(latest, ts)
    }
}

def summary = { HubitatHarness h ->
    h.coreSessions().collect { "${it.mode} ${iso(it.startMs)}..${iso(it.endMs)} ${it.seconds}s/${it.pieces}" }.join("; ")
}

// ---------------------------------------------------------------------------
println "\n(a) 60-hour fan run, Mon 00:00 to Wed 12:00"
HubitatHarness runA = null
test("posts contiguous checkpoints <= 15 min plus a small END, 216000 s total, one Core session") {
    def h = harness()
    runA = h
    h.install(t("2026-10-04 23:07:00"))
    h.advanceTo(t("2026-10-05 00:00:00"))
    h.deviceEvent("thermostatFanMode", "on")
    long end = t("2026-10-07 12:00:00")
    double temp = 70.0d
    for (long at = t("2026-10-05 00:37:00"); at < end; at += min(37)) {
        h.advanceTo(at)
        temp = (temp == 70.0d) ? 70.5d : 70.0d
        h.deviceEvent("temperature", temp)
    }
    h.advanceTo(end)
    h.deviceEvent("thermostatFanMode", "auto")
    h.advanceTo(t("2026-10-07 12:30:00"))

    List pieces = h.runtimePieces()
    check(pieces.every { it.status == "Fan_only" }, "every piece is Fan_only")
    check(pieces.every { it.seconds <= 900L }, "largest piece ${pieces*.seconds.max()} s > 900 s")
    check(pieces.first().startMs == t("2026-10-05 00:00:00"), "first piece starts ${iso(pieces.first().startMs)}")
    check(pieces.last().endMs == end, "last piece ends ${iso(pieces.last().endMs)}")
    assertContiguous(pieces)
    check(h.totalSeconds() == 216000L, "total ${h.totalSeconds()} s, expected 216000")
    List ends = pieces.findAll { !it.checkpoint }
    check(ends.size() == 1 && ends[0].event.event_type == "Mode_Change" && ends[0].seconds <= 900L, "one small END: ${ends*.seconds}")
    check(ends[0].event.equipment_status == "Idle" && ends[0].event.previous_status == "Fan_only" && ends[0].event.is_active == false, "END fields")
    List cps = pieces.findAll { it.checkpoint }
    check(cps.size() >= 239, "checkpoints: ${cps.size()}")
    check(cps.every { Map p -> Map e = p.event; e.event_type == "Telemetry_Update" && e.is_active == true && e.equipment_status == "Fan_only" && e.previous_status == "Fan_only" && e.source_event_id }, "checkpoint fields")
    check(pieces*.event*.source_event_id.unique().size() == pieces.size(), "source_event_id unique per piece")
    List seqs = h.attempts.flatten()*.sequence_number
    check(seqs == seqs.sort(false) && seqs.unique(false).size() == seqs.size(), "sequence numbers increase")
    check(h.core.every { it.sequence_number != null }, "every event has a sequence_number")
    List sessions = h.coreSessions()
    check(sessions.size() == 1 && sessions[0].seconds == 216000L, "Core sessions: ${summary(h)}")
    assertRuntimeNotBackdated(h)
}

// ---------------------------------------------------------------------------
println "\n(b) a stop the app only learns of on its scheduled check"
test("ends the run at the time the hub recorded the stop") {
    def h = harness()
    h.install(t("2026-10-05 07:07:00"))
    h.advanceTo(t("2026-10-05 08:00:00"))
    h.deviceEvent("thermostatOperatingState", "heating")
    h.advanceTo(t("2026-10-05 08:30:00"))
    h.silentChange("thermostatOperatingState", "idle")          // event never delivered
    h.advanceTo(t("2026-10-05 10:00:00"))

    List pieces = h.runtimePieces()
    check(h.totalSeconds("heat") == 1800L, "heat ${h.totalSeconds('heat')} s, expected 1800 (${summary(h)})")
    check(pieces.last().endMs == t("2026-10-05 08:30:00"), "run ends ${iso(pieces.last().endMs)}")
    check(!pieces.last().checkpoint && pieces.last().event.equipment_status == "Idle", "closed by an END")
    assertContiguous(pieces)
    check(h.coreSessions().size() == 1, "one session: ${summary(h)}")
}

// ---------------------------------------------------------------------------
println "\n(c) no confirmation for over an hour"
test("closes at the last confirmation, counts nothing unconfirmed, starts a new session when confirmed again") {
    def h = harness()
    h.install(t("2026-10-04 23:07:00"))
    h.advanceTo(t("2026-10-05 00:00:00"))
    h.deviceEvent("thermostatFanMode", "on")
    h.advanceTo(t("2026-10-05 01:58:00"))
    h.deviceEvent("temperature", 71.0d)                          // last confirmation
    h.advanceTo(t("2026-10-05 02:00:00"))
    h.silentChange("deviceAlive", "false")                       // stops reporting
    h.advanceTo(t("2026-10-05 04:00:00"))
    h.silentChange("deviceAlive", "true")                        // back, fan still on
    h.advanceTo(t("2026-10-05 06:00:00"))
    h.deviceEvent("thermostatFanMode", "auto")
    h.advanceTo(t("2026-10-05 06:30:00"))

    // Runtime is reported up to the last confirmation (01:58, a temperature
    // event) when the thermostat goes offline. That piece ends on the first
    // whole second after the confirmation, so it is never stamped at or
    // before the confirming event's own Telemetry_Update (Core would drop or
    // skip it): at most 1 s past the confirmation.
    long lastConfirmed = t("2026-10-05 01:58:00")
    List sessions = h.coreSessions()
    check(sessions.size() == 2, "two sessions: ${summary(h)}")
    check(sessions[0].startMs == t("2026-10-05 00:00:00") && sessions[0].endMs > lastConfirmed && sessions[0].endMs <= lastConfirmed + 1000L,
          "first ends at the last confirmation (< 1 s past): ${summary(h)}")
    check(sessions[1].startMs == t("2026-10-05 04:07:00") && sessions[1].endMs == t("2026-10-05 06:00:00"), "second starts at the confirming check: ${summary(h)}")
    check(h.runtimePieces().every { it.endMs <= lastConfirmed + 1000L || it.startMs >= t("2026-10-05 04:07:00") }, "nothing counted while unconfirmed")
    check(h.totalSeconds() == 7081L + 6780L, "total ${h.totalSeconds()}")
    assertRuntimeNotBackdated(h)
}

// ---------------------------------------------------------------------------
println "\n(d) hub restart mid-run, back within the hour"
test("resumes from the last report") {
    def h = harness()
    h.install(t("2026-10-05 09:07:00"))
    h.advanceTo(t("2026-10-05 10:00:00"))
    h.deviceEvent("thermostatOperatingState", "heating")
    h.advanceTo(t("2026-10-05 10:40:00"))
    h.hubOff(t("2026-10-05 11:10:00"))
    h.systemStart()
    check(h.dev.refreshCount == 1, "thermostat refreshed after restart")
    h.advanceTo(t("2026-10-05 12:00:00"))
    h.deviceEvent("thermostatOperatingState", "idle")
    h.advanceTo(t("2026-10-05 12:30:00"))

    List pieces = h.runtimePieces()
    assertContiguous(pieces)
    check(h.coreSessions().size() == 1 && h.totalSeconds() == 7200L, "one 7200 s session: ${summary(h)}")
    check(pieces.every { it.seconds <= 3600L }, "pieces <= 1 h")
}

// ---------------------------------------------------------------------------
println "\n(e) hub restart after more than an hour off"
test("closes at the last confirmation; a run still going starts a new session") {
    def h = harness()
    h.install(t("2026-10-05 09:07:00"))
    h.advanceTo(t("2026-10-05 10:00:00"))
    h.deviceEvent("thermostatOperatingState", "heating")
    h.advanceTo(t("2026-10-05 10:40:00"))
    h.hubOff(t("2026-10-05 13:40:00"))
    h.systemStart()
    h.advanceTo(t("2026-10-05 14:00:00"))
    h.deviceEvent("thermostatOperatingState", "idle")
    h.advanceTo(t("2026-10-05 14:30:00"))

    List sessions = h.coreSessions()
    check(sessions.size() == 2, "two sessions: ${summary(h)}")
    check(sessions[0].endMs == t("2026-10-05 10:37:00") && sessions[0].seconds == 2220L, "first closed at the last check: ${summary(h)}")
    check(sessions[1].startMs == t("2026-10-05 13:41:00") && sessions[1].endMs == t("2026-10-05 14:00:00"), "second from the post-restart check: ${summary(h)}")
}

test("a stop while the hub was off is picked up by the refresh after restart") {
    def h = harness()
    h.install(t("2026-10-05 09:07:00"))
    h.advanceTo(t("2026-10-05 10:00:00"))
    h.deviceEvent("thermostatOperatingState", "heating")
    h.advanceTo(t("2026-10-05 10:40:00"))
    h.hubOff(t("2026-10-05 13:40:00"))
    h.dev.onRefresh = { h.dev.set("thermostatOperatingState", "idle", h.clock); h.app.handleEvent([name: "thermostatOperatingState", value: "idle", displayName: "Hall"]) }
    h.systemStart()
    h.advanceTo(t("2026-10-05 15:00:00"))

    check(h.coreSessions().size() == 1 && h.totalSeconds() == 2220L, "only the confirmed 37 min: ${summary(h)}")
}

// ---------------------------------------------------------------------------
println "\n(f) bugs fixed in this version"
test("quick active -> active switches credit each status its own time") {
    def h = harness()
    h.install(t("2026-10-05 08:07:00"))
    h.advanceTo(t("2026-10-05 09:00:00"))
    h.deviceEvent("thermostatOperatingState", "heating")
    h.advanceTo(t("2026-10-05 09:16:40"))
    h.deviceEvent("thermostatOperatingState", "cooling")
    h.advanceTo(t("2026-10-05 09:16:43"))
    h.deviceEvent("thermostatOperatingState", "fan only")
    h.advanceTo(t("2026-10-05 09:26:40"))
    h.deviceEvent("thermostatOperatingState", "idle")
    h.advanceTo(t("2026-10-05 10:00:00"))

    check(h.totalSeconds("heat") == 1000L, "heat ${h.totalSeconds('heat')}")
    check(h.totalSeconds("cool") == 3L, "cool ${h.totalSeconds('cool')}")
    check(h.totalSeconds("fan") == 597L, "fan ${h.totalSeconds('fan')}")
}

test("a session END is never dropped by the Mode_Change dedup window") {
    def h = harness()
    h.install(t("2026-10-05 06:07:00"))
    h.advanceTo(t("2026-10-05 07:00:00"))
    h.deviceEvent("thermostatFanMode", "on")
    h.advanceTo(t("2026-10-05 07:10:00"))
    // An Idle Mode_Change went out a second ago (e.g. from a concurrent handler).
    h.atomicState["lastModeChangePostType_user-e2e-42"] = "Idle"
    h.atomicState["lastModeChangePostTime_user-e2e-42"] = h.clock - 1000L
    h.deviceEvent("thermostatFanMode", "auto")
    h.advanceTo(t("2026-10-05 07:30:00"))

    check(h.totalSeconds("fan") == 600L, "fan ${h.totalSeconds('fan')} s, expected 600")
}

test("pending heat / pending cool are not running (the fan is, when set to on)") {
    def h = harness()
    h.install(t("2026-10-05 04:07:00"))
    h.advanceTo(t("2026-10-05 05:00:00"))
    h.deviceEvent("thermostatOperatingState", "pending heat")
    h.advanceTo(t("2026-10-05 05:02:00"))
    h.deviceEvent("thermostatOperatingState", "heating")
    h.advanceTo(t("2026-10-05 05:10:00"))
    h.deviceEvent("thermostatOperatingState", "idle")
    h.advanceTo(t("2026-10-05 05:20:00"))
    h.deviceEvent("thermostatFanMode", "on")
    h.deviceEvent("thermostatOperatingState", "pending cool")
    h.advanceTo(t("2026-10-05 05:25:00"))
    h.deviceEvent("thermostatFanMode", "auto")
    h.advanceTo(t("2026-10-05 05:40:00"))

    check(h.totalSeconds("heat") == 480L, "heat ${h.totalSeconds('heat')} s, expected 480")
    check(h.totalSeconds("cool") == 0L, "cool ${h.totalSeconds('cool')} s, expected 0")
    check(h.totalSeconds("fan") == 300L, "fan ${h.totalSeconds('fan')} s, expected 300")
}

test("a run in progress when the update lands is taken over and reported in pieces") {
    def h = harness()
    h.install(t("2026-10-05 05:47:00"), [thermostatOperatingState: "heating"])
    // State as version 1.0.8 left it: a heating session since 06:00, last device activity 07:50,
    // and only the old schedules (no runtime checkpoint job yet).
    h.stateStore["sessionStart_user-e2e-42"] = t("2026-10-05 06:00:00")
    h.stateStore["wasActive_user-e2e-42"] = true
    h.stateStore["lastEquipmentStatus_user-e2e-42"] = "Heating_Fan"
    h.stateStore.remove("runtimeHooksVersion")
    h.atomicState.store.keySet().removeAll { it.startsWith("rt") }
    h.periodic.remove("runtimeCheckpoint")
    h.once.clear()
    h.clock = t("2026-10-05 07:50:00"); h.dev.lastActivityMs = h.clock
    h.periodic.each { n, j -> j.next = h.clock + j.interval }
    h.advanceTo(t("2026-10-05 09:00:00"))
    h.deviceEvent("thermostatOperatingState", "idle")
    h.advanceTo(t("2026-10-05 09:30:00"))

    List pieces = h.runtimePieces()
    check(h.periodic.containsKey("runtimeCheckpoint"), "checkpoint job installed by checkDeviceHealth")
    check(pieces.every { it.seconds <= 3600L }, "pieces <= 1 h: ${pieces*.seconds}")
    check(h.totalSeconds("heat") == 10800L && h.coreSessions().size() == 1, "one 3 h session: ${summary(h)}")
    assertContiguous(pieces)
}

// ---------------------------------------------------------------------------
println "\n(g) delivery"
test("no core_token: runtime is kept in the outbox and delivered once a token is issued") {
    def h = harness()
    h.install(t("2026-10-04 23:07:00"))
    h.stateStore.sfpCoreToken = null
    h.stateStore.sfpCoreTokenExp = null
    h.bubbleUp = false
    h.advanceTo(t("2026-10-05 00:00:00"))
    h.deviceEvent("thermostatFanMode", "on")
    h.advanceTo(t("2026-10-05 00:40:00"))
    check(h.core.isEmpty(), "nothing delivered without a token")
    h.bubbleUp = true
    h.advanceTo(t("2026-10-05 01:00:00"))
    h.deviceEvent("thermostatFanMode", "auto")
    h.advanceTo(t("2026-10-05 03:00:00"))

    check(h.totalSeconds("fan") == 3600L, "fan ${h.totalSeconds('fan')} s, expected 3600 (${summary(h)})")
    check(h.core.any { it.event_type == "Mode_Change" && it.equipment_status == "Fan_only" && it.timestamp == iso(t("2026-10-05 00:00:00")) },
          "the START posted while there was no token was delivered")
    check(h.runtimePieces().count { it.checkpoint && it.endMs <= t("2026-10-05 00:40:00") } >= 2, "checkpoints made without a token were delivered")
    check(((h.atomicState.outbox ?: []) as List).isEmpty(), "outbox drained")
}

test("the hub dying mid-POST doesn't lose the runtime in it") {
    def h = harness()
    h.install(t("2026-10-04 23:07:00"))
    h.advanceTo(t("2026-10-05 00:00:00"))
    h.deviceEvent("thermostatFanMode", "on")
    boolean crashed = false
    h.coreFailure = { List batch -> (!crashed && batch.any { (it.runtime_seconds ?: 0) > 0 }) ? { crashed = true; "crash" }() : null }
    h.advanceTo(t("2026-10-05 01:00:00"))
    h.deviceEvent("thermostatFanMode", "auto")
    h.advanceTo(t("2026-10-05 01:30:00"))

    check(crashed, "a POST crashed")
    check(h.totalSeconds("fan") == 3600L, "fan ${h.totalSeconds('fan')} s, expected 3600 (${summary(h)})")
}

test("Core outage: queued pieces are delivered in order once Core is back") {
    def h = harness()
    h.install(t("2026-10-04 23:07:00"))
    h.advanceTo(t("2026-10-05 00:00:00"))
    h.deviceEvent("thermostatFanMode", "on")
    h.coreFailure = { List batch -> "transient" }
    h.advanceTo(t("2026-10-05 01:00:00"))
    h.coreFailure = null
    h.advanceTo(t("2026-10-05 02:00:00"))
    h.deviceEvent("thermostatFanMode", "auto")
    h.advanceTo(t("2026-10-05 03:00:00"))

    check(h.totalSeconds("fan") == 7200L && h.coreSessions().size() == 1, "one 7200 s session: ${summary(h)}")
}

// ---------------------------------------------------------------------------
println "\n(h) wire format"
test("checkpoint payload matches tests/golden/checkpoint_event.json") {
    check(runA != null, "needs the 60-hour run")
    Map cp = runA.core.find { it.runtime_type == "CHECKPOINT" } as Map
    check(cp != null, "a checkpoint was posted")
    File golden = new File(testsDir, "golden/checkpoint_event.json")
    // payload_raw.version is APP_VERSION; pinned as a placeholder so a release bump doesn't touch the golden.
    Map pinned = new TreeMap(cp)
    pinned.payload_raw = new TreeMap(cp.payload_raw as Map) + [version: "<APP_VERSION>"]
    String actual = JsonOutput.prettyPrint(JsonOutput.toJson(pinned)) + "\n"
    if (System.getenv("UPDATE_GOLDEN")) { golden.parentFile.mkdirs(); golden.text = actual; println "        (golden updated)" }
    check(golden.exists(), "golden file missing (run with UPDATE_GOLDEN=1)")
    def expected = new JsonSlurper().parseText(golden.text)
    def got = new JsonSlurper().parseText(actual)
    check(expected == got, "payload differs from golden:\n${actual}")
}

if (System.getenv("DUMP_RUN") && runA != null) {
    new File(System.getenv("DUMP_RUN")).text = JsonOutput.prettyPrint(JsonOutput.toJson(runA.core))
    println "\nWrote ${runA.core.size()} Core events of the 60-hour run to ${System.getenv('DUMP_RUN')}"
}

println "\n${passed} passed, ${failures.size()} failed"
if (failures) {
    failures.each { println "  - ${it}" }
    System.exit(1)
}
