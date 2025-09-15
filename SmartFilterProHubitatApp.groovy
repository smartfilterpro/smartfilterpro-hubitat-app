/**
 *  SmartFilterPro Thermostat Bridge (Hubitat)
 *  - Link with email/password → Bubble returns access_token/refresh_token/user_id/hvac(s)
 *  - Auto-refresh tokens on real 401s OR Bubble “soft 401”
 *  - Pre-emptive (skew) refresh before expiry
 *  - Optional local thermostat selection (skip allowed)
 *  - Poll ha_therm_status every 20 minutes
 *  - Expose Reset button + Status as child devices for Hubitat Dashboard
 *  - Status page with last payload
 *  - ADDED: isReachable, ConnectivityStatusChanged, last* snapshot on session end
 *
 *  © 2025 Eric Hanfman — Apache 2.0
 */

import groovy.transform.Field

/* ============================== CONSTANTS ============================== */

@Field static final String DEFAULT_LOGIN_URL     = "https://smartfilterpro-scaling.bubbleapps.io/version-test/api/1.1/wf/ha_password_login"
// MUST be lowercase ‘hubitat’ per requirement
@Field static final String DEFAULT_TELEMETRY_URL = "https://smartfilterpro-scaling.bubbleapps.io/version-test/api/1.1/wf/hubitat"
@Field static final String DEFAULT_STATUS_URL    = "https://smartfilterpro-scaling.bubbleapps.io/version-test/api/1.1/wf/ha_therm_status"
@Field static final String DEFAULT_RESET_URL     = "https://smartfilterpro-scaling.bubbleapps.io/version-test/api/1.1/wf/ha_reset_filter"
@Field static final String DEFAULT_REFRESH_URL   = "https://smartfilterpro-scaling.bubbleapps.io/version-test/api/1.1/wf/ha_refresh_token"

@Field static final Integer DEFAULT_HTTP_TIMEOUT = 30
@Field static final Integer TOKEN_SKEW_SECONDS  = 60

@Field static final Set ACTIVE_STATES = ["heating","cooling","fan only"] as Set

// ADDED: Connectivity defaults
@Field static final Integer DEFAULT_STALE_MINUTES = 15
@Field static final Integer DEFAULT_SCAN_MINUTES  = 1

/* ============================== METADATA ============================== */

definition (
    name: "SmartFilterPro Thermostat Bridge",
    namespace: "smartfilterpro",
    author: "Eric Hanfman",
    description: "Sends thermostat state & runtime to SmartFilterPro (Bubble) and polls status.",
    category: "Convenience",
    iconUrl: "",
    iconX2Url: ""
)

preferences {
    page name: "mainPage"
    page name: "linkPage"
    page name: "selectHvacPage"
    page name: "statusPage"
}

/* ============================== UI PAGES ============================== */

def mainPage() {
    dynamicPage(name: "mainPage", title: "SmartFilterPro", install: true, uninstall: true) {
        section("Hubitat Thermostat (optional)") {
            input "thermostat", "capability.thermostat",
                title: "Select Thermostat (optional)", required: false, submitOnChange: true
            paragraph "If skipped, telemetry from Hubitat is disabled. Cloud polling still works."
        }

        section("Account Link") {
            if (state.sfpAccessToken && state.sfpUserId) {
                String hvName = state.sfpHvacName ?: "(not selected)"
                paragraph "Linked ✅  Thermostat: ${hvName}"
                href name: "relinkHref", title: "Re-link / Switch HVAC", page: "linkPage",
                     description: "Update account or choose a different HVAC"
            } else {
                input "loginEmail",    "email",    title: "Email",    required: true,  submitOnChange: true
                input "loginPassword", "password", title: "Password", required: true,  submitOnChange: true

                def linkUrl = "${appEndpointBase()}/login?access_token=${getOrCreateAppToken()}"
                logLink("/login?access_token=${getOrCreateAppToken()}")
                href name: "doLoginInline", title: "Link Account",
                     style: "external", description: "Tap to authenticate now (opens new tab)",
                     url: linkUrl, state: "complete"

                if (state.lastLoginError) paragraph "Last error: ${state.lastLoginError}"
                href name: "openLinkPage", title: "Open linking sub-page",
                     page: "linkPage", description: "Alternative linking flow"
            }
        }

        section("Options") {
            input "enableDebugLogging", "bool", title: "Enable Debug Logging", defaultValue: true
            input "enableRetryLogic", "bool", title: "Enable HTTP Retry Logic", defaultValue: true
            input "httpTimeout", "number", title: "HTTP Timeout (seconds)",
                 defaultValue: DEFAULT_HTTP_TIMEOUT, range: "5..120"
        }

        section("Runtime Tracking") {
            input "maxRuntimeHours", "number",
                title: "Maximum Runtime Hours (cap a single session)",
                defaultValue: 24, range: "1..168"
        }

        section("Connectivity (Reachability)") {
            input "reachabilityStaleMinutes", "number",
                title: "Mark device unreachable after N minutes without any events",
                defaultValue: DEFAULT_STALE_MINUTES, range: "1..180"
            input "connectivityScanMinutes", "number",
                title: "Scan frequency (minutes)",
                defaultValue: DEFAULT_SCAN_MINUTES, range: "1..30"
            input "publishConnectivity", "bool",
                title: "Send ConnectivityStatusChanged events to Bubble",
                defaultValue: true
        }

        section("Session Maintenance & Stats") {
            input "cleanupFrequency", "enum", title: "Cleanup Frequency",
                defaultValue: "1 Hour",
                options: ["30 Minutes","1 Hour","2 Hours","6 Hours","12 Hours"]
            input "enableSessionStats", "bool", title: "Log Session Stats Every 15 Minutes", defaultValue: false
            input "resetAllSessions", "bool", title: "Reset All Sessions on Save", defaultValue: false
        }

        section("Status") {
            href name: "statusHref", title: "View Last Polled Status",
                 page: "statusPage", description: "Shows the last ha_therm_status payload"
        }
    }
}

def linkPage() {
    dynamicPage(name: "linkPage", title: "Link SmartFilterPro", install: false, uninstall: false) {
        section("Sign In") {
            input "loginEmail",    "email",    title: "Email",    required: true, submitOnChange: true
            input "loginPassword", "password", title: "Password", required: true, submitOnChange: true

            def linkUrl = "${appEndpointBase()}/login?access_token=${getOrCreateAppToken()}"
            logLink("/login?access_token=${getOrCreateAppToken()}")
            href name: "doLogin", title: "Link Account",
                 style: "external", description: "Tap to authenticate now (opens new tab)",
                 url: linkUrl, state: "complete"

            paragraph "After tapping, return to this page. If you have multiple thermostats, you’ll get a 'Select HVAC' link."
        }

        if (state.lastLoginError) {
            section("Last Error") { paragraph "${state.lastLoginError}" }
        }

        if (state.sfpHvacChoices && (state.sfpHvacChoices as Map).size() > 1 && !state.sfpHvacId) {
            href name: "pickHvac", title: "Select HVAC", page: "selectHvacPage",
                 description: "Multiple thermostats found. Pick one."
        }

        if (state.sfpAccessToken && state.sfpUserId) {
            section("Linked") {
                paragraph "Account linked."
            }
        }
    }
}

def selectHvacPage() {
    dynamicPage(name: "selectHvacPage", title: "Select HVAC", install: false, uninstall: false) {
        Map opts = (state.sfpHvacChoices ?: [:])    // id -> label (name)
        input "chosenHvac", "enum", title: "Thermostat", required: true,
              options: opts, submitOnChange: true
        if (settings.chosenHvac) {
            String id    = settings.chosenHvac.toString()
            String label = (state.sfpHvacChoices ?: [:])[id] ?: id
            paragraph "Selected: ${label}"
            def saveUrl = "${appEndpointBase()}/chooseHvac?access_token=${getOrCreateAppToken()}&id=${URLEncoder.encode(id,'UTF-8')}"
            logLink("/chooseHvac?access_token=${getOrCreateAppToken()}&id=${id}")
            href name: "saveHvacHref", title: "Save Selection",
                 style: "external", url: saveUrl, description: "Tap to save"
        }
    }
}

def statusPage() {
    Map shown = state.sfpLastStatus ?: [:]
    if (shown.isEmpty() && state.sfpLastEnvelope) {
        shown = [note: "Payload was empty; showing envelope for debug.", envelope: state.sfpLastEnvelope]
    }
    def jsonPretty = groovy.json.JsonOutput.prettyPrint(
        groovy.json.JsonOutput.toJson(shown)
    )
    dynamicPage(name: "statusPage", title: "Last Polled ha_therm_status", install: false, uninstall: false) {
        section("Payload") {
            paragraph "<pre>${jsonPretty}</pre>"
        }
    }
}

/* ============================== CLOUD/LOCAL ENDPOINTS ============================== */

mappings {
    path("/login")      { action: [ GET: "cloudLogin" ] }
    path("/chooseHvac") { action: [ GET: "cloudChooseHvac" ] }
    path("/status")     { action: [ GET: "cloudShowStatus" ] }
}

/** Called by Link Account — POSTs to Bubble login and stores results. */
def cloudLogin() {
    state.lastLoginError = null
    try {
        def email = (settings.loginEmail ?: "").toString().trim()
        def pwd   = (settings.loginPassword ?: "").toString()

        if (!email || !pwd) {
            state.lastLoginError = "Email and Password are required. Return to the app, enter them, then tap Link Account again."
            return render(contentType: "text/html",
                data: """<html><body><h3>Missing email/password. Close this tab and return to the app.</h3></body></html>"""
            )
        }

        String url = DEFAULT_LOGIN_URL
        Integer timeoutSec = (settings.httpTimeout ?: DEFAULT_HTTP_TIMEOUT) as Integer

        Map loginResp
        httpPost([
            uri: url,
            contentType: "application/json",
            requestContentType: "application/json",
            timeout: timeoutSec,
            body: [ email: email, password: pwd ]
        ]) { resp ->
            loginResp = (resp.data instanceof Map) ? resp.data : [:]
        }

        def body = _bubbleBody(loginResp)

        String access  = (body?.access_token ?: body?.token ?: "").toString()
        String refresh = (body?.refresh_token ?: body?.rtoken ?: "").toString()
        Long   expires = null
        try { expires = (body?.expires_at as Long) } catch (e) { expires = null }
        if (!expires && body?.expires) {
            try { expires = ((now() / 1000L) as Long) + ((body.expires as Long) as Long) } catch (e) {}
        }
        String userId = (body?.user_id ?: "").toString()

        /* ---------- Build name-aware choices ---------- */
        List ids = []
        def idRaw = body?.hvac_id
        if (idRaw instanceof List) { ids.addAll(idRaw) }
        else if (idRaw) { ids << idRaw }
        if (body?.hvac_ids instanceof List) {
            body.hvac_ids.each { if (it) ids << it }
        }
        ids = ids.collect { it?.toString() }.findAll { it }.unique()

        List names = (body?.hvac_name instanceof List) ?
            body.hvac_name.collect { it?.toString() } : []

        Map choices    = [:]  // id -> label shown to user (prefer name)
        Map namesById  = [:]  // id -> raw name
        ids.eachWithIndex { String id, int idx ->
            String nm = (idx < names.size() && names[idx]) ? names[idx] : ""
            choices[id]   = nm ?: id
            namesById[id] = nm
        }
        state.sfpHvacChoices = choices
        state.sfpHvacNames   = namesById

        if (!access || !userId) {
            state.lastLoginError = "Login response missing access_token or user_id."
        } else if (_isBubbleSoft401(body)) {
            state.lastLoginError = "Login returned invalid_token — check credentials."
        } else {
            state.sfpAccessToken  = access
            state.sfpRefreshToken = refresh
            state.sfpExpiresAt    = expires   // epoch seconds
            state.sfpUserId       = userId

            if (choices.size() == 1) {
                String onlyId = choices.keySet().asList()[0]
                state.sfpHvacId   = onlyId
                state.sfpHvacName = namesById[onlyId] ?: choices[onlyId]
            } else if (choices.size() > 1) {
                state.sfpHvacId   = null
                state.sfpHvacName = null
            }

            // Clear sensitive input after use
            app.updateSetting("loginPassword", [type: "password", value: ""])
        }
    } catch (e) {
        state.lastLoginError = "Login failed: ${e}"
        log.error "SmartFilterPro login failed: ${e}"
    }

    render(contentType: "text/html",
        data: """<html><body><h3>Login processed. Close this tab and return to the app.</h3></body></html>"""
    )
}

/** Saves the selected HVAC id and its name. */
def cloudChooseHvac() {
    def id = params?.id?.toString()
    if (id) {
        state.sfpHvacId = URLDecoder.decode(id, "UTF-8")
        String lbl = (state.sfpHvacNames ?: [:])[state.sfpHvacId] ?: (state.sfpHvacChoices ?: [:])[state.sfpHvacId]
        state.sfpHvacName = (lbl ?: "").toString()
    }
    render(contentType: "text/html",
        data: """<html><body><h3>Saved. Close this tab and return to the app.</h3></body></html>"""
    )
}

/** Show last polled status (pretty JSON). */
def cloudShowStatus() {
    def jsonPretty = groovy.json.JsonOutput.prettyPrint(
        groovy.json.JsonOutput.toJson(state.sfpLastStatus ?: [:])
    )
    render(contentType: "text/html",
        data: """<html><body><pre>${jsonPretty}</pre></body></html>"""
    )
}

/* ============================== LIFECYCLE ============================== */

def installed() {
    log.info "🚀 SmartFilterPro Thermostat Bridge installed"
    ensureChildren()
    initialize()
}

def updated() {
    log.info "🔄 SmartFilterPro Thermostat Bridge updated"
    unsubscribe()
    unschedule()
    ensureChildren()

    if (settings.resetAllSessions) {
        resetAllDeviceSessions()
        app.updateSetting("resetAllSessions", [value:false, type:"bool"])
    }

    initialize()
}

def initialize() {
    if (settings.thermostat) {
        subscribe(settings.thermostat, "thermostatOperatingState", handleEvent)
        subscribe(settings.thermostat, "temperature", handleEvent)
    } else {
        if (enableDebugLogging) log.debug "No Hubitat thermostat selected; telemetry disabled."
    }

    unschedule()
    schedule("0 0/20 * * * ?", "pollStatus")
    runIn(5, "pollStatus")

    // Connectivity scanner
    Integer scanMins  = ((settings.connectivityScanMinutes ?: DEFAULT_SCAN_MINUTES) as Integer)
    schedule("0 0/${scanMins} * * * ?", "checkConnectivity")

    scheduleCleanup()
    if (settings.enableSessionStats) runEvery15Minutes(logSessionStats)

    log.info "✅ Initialized | Linked=${!!state.sfpAccessToken} | HVAC=${state.sfpHvacName ?: '(none)'}"
}

/* ============================== TELEMETRY ============================== */

// ---------- ADDED helpers: normalization & connectivity ----------

// Normalize Hubitat operating state → heating|cooling|fanonly|idle|unknown
private String _normOp(def op) {
    String s = op?.toString()?.toLowerCase() ?: ""
    if (s.contains("cool")) return "cooling"
    if (s.contains("heat")) return "heating"
    if (s.contains("fan"))  return "fanonly"
    if (s.contains("idle") || s == "off") return "idle"
    return "unknown"
}

// Per-device state keys
private String _kSessionStart(String userId, String devId) { "sessionStart_${userId}-${devId}" }
private String _kWasActive(String userId, String devId)  { "wasActive_${userId}-${devId}" }
private String _kSessionLast(String userId, String devId){ "sessionLast_${userId}-${devId}" }
private String _kReachable(String userId, String devId)  { "isReachable_${userId}-${devId}" }
private String _kLastSeen(String userId, String devId)   { "lastSeen_${userId}-${devId}" }

// Connectivity: mark seen (reachable) and optionally post flip event
private void _markSeen(String userId, String devId) {
    String kR = _kReachable(userId, devId)
    String kS = _kLastSeen(userId, devId)
    Boolean was = (state[kR] as Boolean)
    state[kS] = now()
    state[kR] = true
    if (publishConnectivity && was == false) {
        _postConnectivityChange(true, userId, devId, "event_seen")
    }
}

// Connectivity: maybe mark unreachable if stale
private boolean _maybeMarkUnreachableIfStale(String userId, String devId, Long nowMs, Long staleMs) {
    String kR = _kReachable(userId, devId)
    String kS = _kLastSeen(userId, devId)
    Long last = (state[kS] as Long) ?: 0L
    if ((nowMs - last) > staleMs && (state[kR] as Boolean) != false) {
        state[kR] = false
        if (publishConnectivity) _postConnectivityChange(false, userId, devId, "stale_timeout")
        return true
    }
    return false
}

// POST minimal connectivity event
private void _postConnectivityChange(boolean isReachable, String userId, String devId, String reason) {
    def t = settings.thermostat
    def payload = [
        userId       : userId,
        thermostatId : state.sfpHvacId,
        hubitat_deviceId: devId,
        deviceName   : state.sfpHvacName ?: t?.displayName,
        eventType    : "ConnectivityStatusChanged",
        isReachable  : isReachable,
        ts           : new Date().format("yyyy-MM-dd'T'HH:mm:ssXXX", location.timeZone),
        reason       : reason
    ]
    _authorizedPost(DEFAULT_TELEMETRY_URL, payload)
    if (enableDebugLogging) log.debug "📶 ConnectivityStatusChanged: ${devId} → ${isReachable} (${reason})"
}

def checkConnectivity() {
    if (!settings.thermostat || !state.sfpUserId) return
    String userId = state.sfpUserId
    String devId  = settings.thermostat?.getId()?.toString()
    if (!devId) return
    Long nowMs = now()
    Long staleMs = ((settings.reachabilityStaleMinutes ?: DEFAULT_STALE_MINUTES) as Integer) * 60_000L
    _maybeMarkUnreachableIfStale(userId, devId, nowMs, staleMs)
}

private Map _buildTelemetryPayload(Integer runtimeSecondsVal, Map lastSnapshot = null) {
    def t = settings.thermostat
    if (!t) return [:]

    String userId   = state.sfpUserId
    String devId    = t?.getId()?.toString()
    String kReach   = _kReachable(userId, devId)
    Boolean isReach = (state[kReach] as Boolean)
    if (isReach == null) isReach = true

    def deviceName = state.sfpHvacName ?: t?.displayName
    def tempF      = t?.currentTemperature
    def mode       = t?.currentThermostatMode
    def opRaw      = t?.currentThermostatOperatingState
    boolean isActive = ACTIVE_STATES.contains(opRaw?.toString()?.toLowerCase())
    def scale      = location.temperatureScale
    def vendor     = t?.getDataValue("manufacturer") ?: t?.getManufacturerName()
    def timestamp  = new Date().format("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", location.timeZone)

    Map out = [
        userId            : userId,
        thermostatId      : state.sfpHvacId,
        currentTemperature: tempF,
        isActive          : isActive,
        deviceName        : deviceName,
        vendor            : vendor,
        thermostatMode    : mode,
        temperatureScale  : scale,
        runtimeSeconds    : runtimeSecondsVal,
        timestamp         : timestamp,
        hubitat_deviceId  : devId,
        ts                : timestamp,
        // isReachable on every post
        isReachable       : isReach
    ]

    // Include last* fields when provided (session end)
    if (lastSnapshot) out << lastSnapshot

    return out
}

def handleEvent(evt) {
    if (!settings.thermostat) return
    if (!state.sfpAccessToken || !state.sfpUserId || !state.sfpHvacId) {
        if (enableDebugLogging) log.debug "Telemetry ignored; not fully linked (token/user/hvac missing)."
        return
    }

    def t = settings.thermostat
    def deviceId   = t?.getId()?.toString()
    def op         = t?.currentThermostatOperatingState
    boolean isActive = ACTIVE_STATES.contains(op?.toString()?.toLowerCase())

    def userId       = state.sfpUserId
    def key          = "${userId}-${deviceId}"
    def sessionKey   = _kSessionStart(userId, deviceId)
    def wasActiveKey = _kWasActive(userId, deviceId)
    def sessionLastKey = _kSessionLast(userId, deviceId)

    // Reachability mark-seen on any event
    _markSeen(userId, deviceId)

    int  maxRuntimeSec = ((settings.maxRuntimeHours ?: 24) as Integer) * 3600

    Integer runtimeSeconds = 0
    boolean wasActive = (state[wasActiveKey] as Boolean) ?: false
    Long    sessionStart = (state[sessionKey] as Long)

    if (sessionStart && sessionStart > now()) {
        if (enableDebugLogging) log.error "❌ Future sessionStart for ${key}; resetting"
        state.remove(sessionKey)
        sessionStart = null
    }

    // Track last* snapshot whenever active (heating/cooling/fanonly)
    String lastModeCandidate = _normOp(op)
    if (["heating","cooling","fanonly"].contains(lastModeCandidate)) {
        state[sessionLastKey] = [
            lastMode           : lastModeCandidate,
            lastIsHeating      : (lastModeCandidate == "heating"),
            lastIsCooling      : (lastModeCandidate == "cooling"),
            lastIsFanOnly      : (lastModeCandidate == "fanonly"),
            lastEquipmentStatus: lastModeCandidate
        ]
    }

    if (isActive && !wasActive) {
        state[sessionKey] = now()
        if (enableDebugLogging) log.debug "🟢 Starting new session for ${key}"
    } else if (!isActive && wasActive) {
        if (sessionStart) {
            def currentTime = now()
            runtimeSeconds = ((currentTime - sessionStart) / 1000) as int
            if (runtimeSeconds > maxRuntimeSec || runtimeSeconds < 0) runtimeSeconds = 0
            state.remove(sessionKey)
            if (enableDebugLogging) log.debug "🔴 Session ended for ${key}, runtime=${runtimeSeconds}s"
        } else {
            if (enableDebugLogging) log.warn "⚠️ Turned off but no sessionStart for ${key}"
        }
    } else if (isActive && wasActive && !sessionStart) {
        state[sessionKey] = now()
        if (enableDebugLogging) log.debug "🔄 Active without start; seeding start for ${key}"
    } else if (isActive && wasActive && sessionStart) {
        def ageSec = ((now() - sessionStart) / 1000) as long
        if (ageSec > maxRuntimeSec) {
            if (enableDebugLogging) log.warn "⚠️ Session age ${Math.round(ageSec/3600)}h exceeds cap; restarting for ${key}"
            state[sessionKey] = now()
        }
    }

    state[wasActiveKey] = isActive

    // Build payload; if we just ended a session, attach last*
    Map lastSnap = null
    if (!isActive && wasActive) {
        lastSnap = (state[sessionLastKey] as Map)
        if (!lastSnap) {
            String m = _normOp(op)
            lastSnap = [
                lastMode: m,
                lastIsHeating: (m == "heating"),
                lastIsCooling: (m == "cooling"),
                lastIsFanOnly: (m == "fanonly"),
                lastEquipmentStatus: m
            ]
        }
        // clear snapshot after consumption
        state.remove(sessionLastKey)
    }

    def payload = _buildTelemetryPayload(runtimeSeconds, lastSnap)
    _authorizedPost(DEFAULT_TELEMETRY_URL, payload)

    if (enableDebugLogging) {
        log.debug "📤 Sent: Active=${payload.isActive}, Runtime=${payload.runtimeSeconds}s, isReachable=${payload.isReachable}${ lastSnap ? ", lastMode=${lastSnap.lastMode}" : "" }"
    }
}

/* ---------- STATUS NORMALIZATION ---------- */

// case-insensitive, ignores spaces/underscores/dots
private def _pick(Map m, List<String> candidates) {
    if (!m) return null
    Map norm = [:]
    m.each { k, v ->
        if (k != null) {
            String nk = k.toString().toLowerCase().replaceAll(/[^a-z0-9]/, "")
            norm[nk] = v
        }
    }
    for (String c : candidates) {
        String nc = c.toLowerCase().replaceAll(/[^a-z0-9]/, "")
        if (norm.containsKey(nc)) return norm[nc]
    }
    return null
}

// Convert Bubble’s varying keys → stable keys our child device expects
private Map normalizeStatus(Map raw) {
    Map out = [:]
    if (!raw) return out

    out.percentageUsed = _pick(raw, [
        "percentage_used", "percentage used", "percent_used", "percent used",
        "percentage", "percent"
    ])

    out.todayMinutes = _pick(raw, [
        "today_minutes", "todays_minutes", "today", "daily_active_time_sum",
        "2.0.1_Daily Active Time Sum"
    ])

    out.totalMinutes = _pick(raw, [
        "total_minutes", "total_runtime", "total",
        "minutes_active", "1.0.1_Minutes active"
    ])

    out.device_name = _pick(raw, [
        "device_name", "thermostat_name", "hvac_name", "name"
    ])

    return out.findAll { k, v -> v != null }  // drop nulls
}


/* ============================== POLLING ============================== */

def pollStatus() {
    if (!state.sfpAccessToken || !state.sfpUserId) {
        if (enableDebugLogging) log.debug "Poll skipped; not linked."
        return
    }
    _ensureValidTokenSkew()

    String url = DEFAULT_STATUS_URL
    Integer timeoutSec = (settings.httpTimeout ?: DEFAULT_HTTP_TIMEOUT) as Integer

    Map body = [:]
    if (state.sfpHvacId) body.hvac_uid = state.sfpHvacId
    else if (enableDebugLogging) log.debug "Polling without hvac_uid (no HVAC selected yet)"

    def params = [
        uri: url,
        contentType: "application/json",
        requestContentType: "application/json",
        timeout: timeoutSec,
        headers: [ Authorization: "Bearer ${state.sfpAccessToken}" ],
        body: body ?: [:]
    ]

    if (enableDebugLogging) log.debug "📥 Polling ha_therm_status @ ${url} (hvac=${state.sfpHvacName ?: '(none)'})"

    try {
        httpPost(params) { resp ->
            if (resp.status >= 200 && resp.status < 300) {
                Map js = (resp.data instanceof Map) ? (Map) resp.data : [:]
                state.sfpLastEnvelope = js
                Map b  = _bubbleBody(js)

                if (_isBubbleSoft401(b)) {
                    if (enableDebugLogging) log.warn "Soft-401 on poll — attempting refresh"
                    if (_refreshToken()) _retryPoll()
                    return
                }

                Map normalized = normalizeStatus(b ?: [:])

                // Add current reachability to status payload for dashboard convenience
                try {
                    String userId = state.sfpUserId
                    String devId  = settings.thermostat?.getId()?.toString()
                    if (userId && devId) {
                        Boolean isReach = (state[_kReachable(userId, devId)] as Boolean)
                        if (isReach != null) normalized.isReachable = isReach
                    }
                } catch (ignored) {}

                state.sfpLastStatus = normalized
                updateStatusChild(normalized)

                if (enableDebugLogging) {
                    if (state.sfpLastStatus.isEmpty()) {
                        log.debug "Poll OK but empty payload. Keys: ${js?.keySet()}"
                    } else {
                        log.debug "✅ Poll OK; keys=${state.sfpLastStatus.keySet()}"
                    }
                }
            } else if (resp.status == 401) {
                if (_refreshToken()) _retryPoll()
            } else {
                log.warn "Poll non-OK status: ${resp.status}"
            }
        }
    } catch (groovyx.net.http.HttpResponseException e) {
        if ((e.statusCode as Integer) == 401) {
            if (_refreshToken()) _retryPoll()
        } else {
            log.error "Poll HTTP ${e.statusCode}: ${e.message}"
        }
    } catch (Exception e) {
        log.error "Poll error: ${e}"
    }
}

private void _retryPoll() { runIn(3, "pollStatus") }

/* ============================== SESSION MAINTENANCE & STATS ============================== */

def scheduleCleanup() {
    unschedule(cleanupOldSessions)
    switch ((settings.cleanupFrequency ?: "1 Hour") as String) {
        case "30 Minutes": runEvery30Minutes(cleanupOldSessions); break
        case "2 Hours"   : schedule("0 0 */2 * * ?", cleanupOldSessions); break
        case "6 Hours"   : schedule("0 0 */6 * * ?", cleanupOldSessions); break
        case "12 Hours"  : schedule("0 0 */12 * * ?", cleanupOldSessions); break
        default          : runEvery1Hour(cleanupOldSessions); break
    }
}

def cleanupOldSessions() {
    int maxRuntimeMs = ((settings.maxRuntimeHours ?: 24) as Integer) * 3600000
    long cutoff = now() - maxRuntimeMs
    List keysToRemove = []
    int activeSessionsCount = 0

    state.each { k, v ->
        if (k.startsWith("sessionStart_")) {
            if (v && (v as Long) < cutoff) {
                keysToRemove << k
                if (enableDebugLogging) log.debug "🧹 Cleaning old session: ${k}"
            } else if (v) {
                activeSessionsCount++
            }
        }
    }

    keysToRemove.each { key ->
        state.remove(key)
        def deviceKey = key.replace("sessionStart_", "")
        state.remove("wasActive_${deviceKey}")
        state.remove("sessionLast_${deviceKey}") // also clear last snapshot if stale
    }

    if (keysToRemove.size() > 0 && enableDebugLogging) {
        log.debug "🧹 Cleaned ${keysToRemove.size()} old sessions; ${activeSessionsCount} active remain"
    }
}

def logSessionStats() {
    def stats = getSessionStats()
    log.info "📊 Session Stats: ${stats.activeSessions} active, ${stats.totalDevices} total devices, oldest: ${stats.oldestSessionMinutes} min"
}

def getSessionStats() {
    int activeSessions = 0
    int totalDevices = 0
    long oldest = now()

    state.each { k, v ->
        if (k.startsWith("sessionStart_") && v) {
            activeSessions++
            long started = (v as Long)
            if (started < oldest) oldest = started
        } else if (k.startsWith("wasActive_")) {
            totalDevices++
        }
    }

    long oldestAgeMin = activeSessions > 0 ? Math.round((now() - oldest) / 1000 / 60) : 0
    if (enableDebugLogging) {
        log.debug "📊 Stats: active=${activeSessions}, total=${totalDevices}, oldest=${oldestAgeMin} min"
    }
    return [activeSessions: activeSessions, totalDevices: totalDevices, oldestSessionMinutes: oldestAgeMin]
}

def resetAllDeviceSessions() {
    List keysToRemove = []
    state.each { k, v ->
        if (k.startsWith("sessionStart_") || k.startsWith("wasActive_") || k.startsWith("sessionLast_")) keysToRemove << k
    }
    keysToRemove.each { state.remove(it) }
    log.warn "🔄 RESET: Cleared ${keysToRemove.size()} session-tracking entries"
}

/* ============================== AUTH HELPERS ============================== */

private Map _bubbleBody(Object respData) {
    Map top = (respData instanceof Map) ? (Map) respData : [:]
    Map body = (top.response instanceof Map) ? (Map) top.response : top
    def inner = (body.body instanceof Map) ? (Map) body.body : null
    if (inner) return inner
    return body
}

private boolean _isBubbleSoft401(Map body) {
    if (!body) return false
    def status = body.status ?: body.status_code
    if (status != null) {
        try { if ((status as Integer) == 401) return true } catch (ignored) {}
    }
    def err = (body.error ?: "").toString().toLowerCase()
    def msg = (body.message ?: "").toString().toLowerCase()
    if (err.contains("invalid_token")) return true
    if (msg.contains("access token") && (msg.contains("invalid") || msg.contains("expired"))) return true
    def nested = (body.body instanceof Map) ? (Map) body.body : null
    if (nested) return _isBubbleSoft401(nested)
    return false
}

private boolean _ensureValidTokenSkew() {
    Long exp = (state.sfpExpiresAt as Long)
    if (!exp) return true
    Long nowSec = (now() / 1000L) as Long
    if (nowSec >= (exp - TOKEN_SKEW_SECONDS)) {
        if (enableDebugLogging) log.debug "🔁 Pre-refreshing token (skew). now=${nowSec}, exp=${exp}"
        return _refreshToken()
    }
    return true
}

private boolean _refreshToken() {
    String refreshTarget = DEFAULT_REFRESH_URL
    String rt = (state.sfpRefreshToken ?: "").toString()
    Integer timeoutSec = (settings.httpTimeout ?: DEFAULT_HTTP_TIMEOUT) as Integer

    if (!rt) {
        log.warn "No refresh_token available; cannot refresh."
        return false
    }

    try {
        Map respMap
        httpPost([
            uri: refreshTarget,
            contentType: "application/json",
            requestContentType: "application/json",
            timeout: timeoutSec,
            body: [ refresh_token: rt ]
        ]) { resp ->
            respMap = (resp.data instanceof Map) ? resp.data : [:]
        }

        def body = _bubbleBody(respMap)
        String at  = (body?.access_token ?: body?.token ?: "").toString()
        String nrt = (body?.refresh_token ?: rt).toString()
        Long   exp = null
        try { exp = (body?.expires_at as Long) } catch (e) { exp = null }

        if (_isBubbleSoft401(body)) {
            log.warn "Refresh returned soft-401 / invalid_token."
            return false
        }

        if (at && exp) {
            state.sfpAccessToken  = at
            state.sfpRefreshToken = nrt
            state.sfpExpiresAt    = exp
            if (enableDebugLogging) log.debug "🔁 Token refreshed; exp=${exp}"
            return true
        } else {
            log.warn "Refresh response missing access_token/expires_at: ${body}"
            return false
        }
    } catch (groovyx.net.http.HttpResponseException e) {
        log.error "Refresh HTTP ${e.statusCode}: ${e.message}"
        return false
    } catch (Exception e) {
        log.error "Refresh error: ${e}"
        return false
    }
}

/* ============================== HTTP HELPERS ============================== */

private void _authorizedPost(String url, Map payload) {
    _ensureValidTokenSkew()

    Integer timeoutSec = (settings.httpTimeout ?: DEFAULT_HTTP_TIMEOUT) as Integer
    Map params = [
        uri: url,
        contentType: "application/json",
        requestContentType: "application/json",
        timeout: timeoutSec,
        headers: [ Authorization: "Bearer ${state.sfpAccessToken}" ],
        body: payload ?: [:]
    ]

    if (enableDebugLogging) {
        log.debug "📤 POST ${url}"
        log.debug "Payload: ${payload}"
    }

    try {
        httpPost(params) { resp ->
            if (resp.status >= 200 && resp.status < 300) {
                Map js = (resp.data instanceof Map) ? (Map) resp.data : [:]
                Map b  = _bubbleBody(js)

                if (_isBubbleSoft401(b)) {
                    if (_refreshToken()) {
                        httpPost(params) { r2 -> if (enableDebugLogging) log.debug "Telemetry retry: ${r2.status}" }
                    }
                } else {
                    if (enableDebugLogging) log.debug "✅ Telemetry OK (${resp.status})"
                }
            } else if (resp.status == 401) {
                if (_refreshToken()) {
                    httpPost(params) { r2 -> if (enableDebugLogging) log.debug "Telemetry retry: ${r2.status}" }
                }
            } else {
                log.warn "Telemetry non-OK status: ${resp.status}"
            }
        }
    } catch (groovyx.net.http.HttpResponseException e) {
        if ((e.statusCode as Integer) == 401) {
            if (_refreshToken()) {
                try {
                    httpPost(params) { r2 -> if (enableDebugLogging) log.debug "Telemetry retry: ${r2.status}" }
                } catch (Exception ex) { log.error "Telemetry retry error: ${ex}" }
            }
        } else {
            log.error "Telemetry HTTP ${e.statusCode}: ${e.message}"
        }
    } catch (Exception e) {
        log.error "Telemetry error: ${e}"
    }
}

/* ============================== CHILD DEVICES ============================== */

private void ensureChildren() {
    String dniBase = "sfp-${app.id}"
    if (!getChildDevice("${dniBase}-reset")) {
        try {
            addChildDevice("smartfilterpro", "SmartFilterPro Reset Button", "${dniBase}-reset",
                [name:"SmartFilterPro Reset", label:"SmartFilterPro Reset"])
        } catch (e) { log.error "Failed to create Reset child: ${e}" }
    }
    if (!getChildDevice("${dniBase}-status")) {
        try {
            addChildDevice("smartfilterpro", "SmartFilterPro Status Sensor", "${dniBase}-status",
                [name:"SmartFilterPro Status", label:"SmartFilterPro Status"])
        } catch (e) { log.error "Failed to create Status child: ${e}" }
    }
}

private void updateStatusChild(Map payload) {
    def d = getChildDevice("sfp-${app.id}-status")
    if (!d) return

    def pct   = payload.percentageUsed
    def today = payload.todayMinutes
    def total = payload.totalMinutes
    def name  = payload.device_name ?: state.sfpHvacName

    if (pct   != null) d.sendEvent(name:"percentageUsed", value: pct)
    if (today != null) d.sendEvent(name:"todayMinutes",   value: today)
    if (total != null) d.sendEvent(name:"totalMinutes",   value: total)
    if (name)          d.sendEvent(name:"deviceName",     value: name)

    d.sendEvent(name:"lastUpdated",
        value: new Date().format("yyyy-MM-dd HH:mm:ss", location.timeZone))
}


// called by the Reset child driver
def resetNow() {
    String url = DEFAULT_RESET_URL
    if (!state.sfpAccessToken || !state.sfpUserId || !state.sfpHvacId) {
        log.warn "Reset skipped; missing token/user/hvac."
        return
    }
    _ensureValidTokenSkew()
    Map payload = [user_id: state.sfpUserId, hvac_id: state.sfpHvacId]
    _authorizedPost(url, payload)
    runIn(3, "pollStatus")
}

// called by the Status child driver ("Refresh" command)
def pollNow() { pollStatus() }

/* ============================== LINK HELPERS ============================== */

def cloudShowStatusLink() {
    return "${appEndpointBase()}/status?access_token=${getOrCreateAppToken()}"
}

private String getOrCreateAppToken() {
    if (!state.appAccessToken) {
        createAccessToken()
        state.appAccessToken = state.accessToken
    }
    return state.appAccessToken
}

private String hubUidSafe() {
    try { return getHubUID() }
    catch (e) { return location?.hubs?.first()?.id }
}

private String appEndpointBase() {
    String base = getApiServerUrl() ?: ""
    if (base.startsWith("https://cloud.hubitat.com")) {
        if (base ==~ /https:\/\/cloud\.hubitat\.com\/api\/[A-Za-z0-9-]+.*/) {
            return "${base}/apps/${app.id}"
        } else {
            return "https://cloud.hubitat.com/api/${hubUidSafe()}/apps/${app.id}"
        }
    }
    return "${base}/apps/api/${app.id}"
}

private void logLink(String pathWithQuery) {
    def full = "${appEndpointBase()}${pathWithQuery}"
    log.info "SmartFilterPro link → ${full}"
}