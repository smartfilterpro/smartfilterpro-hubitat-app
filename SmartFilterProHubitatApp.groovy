/**
 * SmartFilterPro Thermostat Bridge (Hubitat) — 8-State System (2025-10-18)
 *   - Posts runtime and telemetry directly to Core with Bubble-issued JWT
 *   - 8-State classification: Cooling_Fan, Cooling, Heating_Fan, Heating, AuxHeat_Fan, AuxHeat, Fan_only, Fan_off
 *   - Automatically refreshes core_token if Core returns 401
 *
 * Key Changes:
 *   - Updated state classification to 8-state system
 *   - Maps boolean flags for heating/cooling/fan states
 *   - Uses eventType for equipment_status field
 *   - Tracks auxiliary heat separately
 *
 * © 2025 Eric Hanfman — Apache 2.0
 */

import groovy.transform.Field

/* ============================== CONSTANTS ============================== */

@Field static final String CORE_INGEST_URL = "https://core.smartfilterpro.com/ingest/v1/events:batch"
@Field static final String BUBBLE_LOGIN_URL = "https://smartfilterpro.com/api/1.1/wf/hubitat_password"
@Field static final String BUBBLE_REFRESH_URL = "https://smartfilterpro.com/api/1.1/wf/hubitat_refresh_token"
@Field static final String BUBBLE_CORE_JWT_URL = "https://smartfilterpro.com/api/1.1/wf/issue_core_token_hub"
@Field static final String BUBBLE_STATUS_URL = "https://smartfilterpro.com/api/1.1/wf/hubitat_therm_status"
@Field static final String BUBBLE_RESET_URL = "https://smartfilterpro.com/api/1.1/wf/hubitat_reset_filter"

@Field static final String  APP_VERSION = "1.0.1"
@Field static final String  VERSION_CHECK_URL = "https://raw.githubusercontent.com/smartfilterpro/smartfilterpro-hubitat-app/main/packageManifest.json"

@Field static final Integer DEFAULT_HTTP_TIMEOUT = 30
@Field static final Integer TOKEN_SKEW_SECONDS = 60

// Dedup window for Mode_Change events. Multiple overlapping thermostat
// attribute subscriptions (thermostatOperatingState, thermostatFanMode, etc.)
// can all fire within a few milliseconds for a single physical transition.
// We drop any Mode_Change whose equipment_status matches the most recently
// posted Mode_Change within this window.
@Field static final Long MODE_CHANGE_DEDUP_WINDOW_MS = 3000L

/* ============================== METADATA ============================== */

definition(
    name: "SmartFilterPro Thermostat Bridge (8-State)",
    namespace: "smartfilterpro",
    author: "Eric Hanfman",
    description: "Tracks thermostat runtime with 8-state system & posts directly to Core with JWT.",
    category: "Convenience",
    iconUrl: "https://51568b615cebbb736b16194a197c101f.cdn.bubble.io/f1752759064237x462020606147641540/sfp%20image.svg",
    iconX2Url: "https://51568b615cebbb736b16194a197c101f.cdn.bubble.io/f1752759064237x462020606147641540/sfp%20image.svg"
)

/* ============================== PREFERENCES ============================== */

preferences {
    page name: "mainPage"
    page name: "linkPage"
    page name: "selectHvacPage"
    page name: "statusPage"
}

/* ============================== MAIN PAGE ============================== */

def mainPage() {
    dynamicPage(name: "mainPage", title: "SmartFilterPro (8-State)", install: true, uninstall: true) {
        if (state.updateAvailable && state.latestVersion) {
            section("Update Available") {
                paragraph "<b>Version ${state.latestVersion} is available!</b> You are running ${APP_VERSION}."
                paragraph "Update via Hubitat Package Manager or download from GitHub."
            }
        }

        section("Hubitat Thermostat (optional)") {
            input "thermostat", "capability.thermostat",
                  title: "Select Thermostat (optional)", required: false, submitOnChange: true
        }

        section("Account Link") {
            if (state.sfpAccessToken && state.sfpUserId) {
                String hvName = state.sfpHvacName ?: "(not selected)"
                paragraph "Linked ✅ Thermostat: ${hvName}"
                href "linkPage", title: "Re-link / Switch HVAC",
                     description: "Update account or choose a different HVAC"
            } else {
                input "loginEmail",    "email",    title: "Email",    required: true
                input "loginPassword", "password", title: "Password", required: true
                href "linkPage", title: "Link Account",
                     description: "Authenticate and select HVAC"
            }
        }

        section("Options") {
            input "enableDebugLogging", "bool", title: "Enable Debug Logging", defaultValue: true
            input "httpTimeout", "number", title: "HTTP Timeout (seconds)",
                 defaultValue: DEFAULT_HTTP_TIMEOUT, range: "5..120"
            input "createStatusDevice", "bool", title: "Create Status Device",
                 description: "Creates a device showing filter health and runtime",
                 defaultValue: false, submitOnChange: true
            input "createResetDevice", "bool", title: "Create Reset Button",
                 description: "Creates a button device to reset filter tracking",
                 defaultValue: false, submitOnChange: true
        }

        section("Devices") {
            def statusDev = getStatusDevice()
            def resetDev = getResetDevice()
            if (statusDev) {
                paragraph "📊 Status Device: ${statusDev.displayName}"
            } else if (settings.createStatusDevice) {
                paragraph "📊 Status device will be created when you click Done"
            }
            if (resetDev) {
                paragraph "🔄 Reset Button: ${resetDev.displayName}"
            } else if (settings.createResetDevice) {
                paragraph "🔄 Reset button will be created when you click Done"
            }
        }

        section("Status") {
            href "statusPage", title: "View Last Bubble Status (20min poll)",
                 description: "Shows last ha_therm_status from Bubble"
        }

        section("About") {
            paragraph "SmartFilterPro v${APP_VERSION}"
        }
    }
}

/* ============================== LINK / HVAC SELECTION ============================== */

def linkPage() {
    dynamicPage(name: "linkPage", title: "Link SmartFilterPro", install: false, uninstall: false) {
        section("Sign In") {
            input "loginEmail", "email", title: "Email", required: true, submitOnChange: true
            input "loginPassword", "password", title: "Password", required: true, submitOnChange: true
            href name: "doLogin", title: "Authenticate", style: "external",
                 description: "Tap to log in (opens new tab)",
                 url: "${appEndpointBase()}/login?access_token=${getOrCreateAppToken()}"
        }

        if (state.lastLoginError) {
            section("Last Error") { paragraph "${state.lastLoginError}" }
        }

        // Show HVAC selection if multiple thermostats and none selected yet
        try {
            def choices = state.sfpHvacChoices
            def hvacId = state.sfpHvacId
            if (choices != null && hvacId == null && choices instanceof Map && choices.size() > 1) {
                section("Select Thermostat") {
                    href "selectHvacPage", title: "Select HVAC",
                         description: "Multiple thermostats found (${choices.size()}). Pick one."
                }
            }
        } catch (Exception e) {
            log.error "Error rendering HVAC selection: ${e.message}"
            section("Error") { paragraph "Error loading HVAC choices. Please re-authenticate." }
        }
    }
}

def selectHvacPage() {
    dynamicPage(name: "selectHvacPage", title: "Select HVAC", install: false, uninstall: false) {
        section("Choose Thermostat") {
            Map opts = (state.sfpHvacChoices ?: [:])
            input "chosenHvac", "enum", title: "Thermostat", required: true,
                  options: opts, submitOnChange: true
            if (settings.chosenHvac) {
                String id = settings.chosenHvac.toString()
                paragraph "Selected: ${opts[id] ?: id}"
                href "chooseHvac", title: "Save Selection",
                     style: "external",
                     url: "${appEndpointBase()}/chooseHvac?access_token=${getOrCreateAppToken()}&id=${URLEncoder.encode(id,'UTF-8')}"
            }
        }
    }
}

def statusPage() {
    Map shown = state.sfpLastStatus ?: [:]
    def jsonPretty = groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(shown))

    def bufferSize = state.eventBuffer?.size() ?: 0
    def oldestSeq = bufferSize > 0 ? state.eventBuffer?.first()?.event?.sequence_number : null
    def newestSeq = bufferSize > 0 ? state.eventBuffer?.last()?.event?.sequence_number : null

    Map seqMap = (atomicState.lastSequenceByDevice as Map) ?: [:]
    String currentKey = (state.sfpHvacId ?: "").toString()
    def currentSeq = currentKey ? seqMap[currentKey] : null

    dynamicPage(name: "statusPage", title: "Last Bubble Status (Polled)", install: false, uninstall: false) {
        section("Event Buffer") {
            paragraph "Buffered Events: ${bufferSize}/200"
            paragraph "Current Sequence: ${currentSeq ?: '(uninitialized)'}"
            if (seqMap.size() > 1) {
                paragraph "All Device Counters: ${seqMap}"
            }
            if (bufferSize > 0) {
                paragraph "Sequence Range: ${oldestSeq} - ${newestSeq}"
            }
        }
        section("Outbox (Reliable Delivery)") {
            Integer outboxDepth = ((atomicState.outbox as List)?.size() ?: 0) as Integer
            Integer dropCount = (atomicState.outboxDroppedCount ?: 0) as Integer
            Long lastFlush = atomicState.outboxLastFlushAt as Long
            String lastResult = atomicState.outboxLastFlushResult ?: "never"
            Long lastDropAt = atomicState.outboxLastDropAt as Long

            paragraph "Queue depth: ${outboxDepth}/${OUTBOX_MAX_ENTRIES}"
            paragraph "Last flush: " + (lastFlush ? "${((now() - lastFlush) / 1000L) as Integer}s ago (${lastResult})" : "never")
            paragraph "Dropped (lifetime): ${dropCount}"
            if (dropCount > 0 && lastDropAt) {
                paragraph "Last drop: ${((now() - lastDropAt) / 1000L) as Integer}s ago"
            }
        }
        section("Polled from ha_therm_status") {
            paragraph "<pre>${jsonPretty}</pre>"
        }
        section("Info") {
            paragraph "This data is polled every 20 minutes from Bubble's ha_therm_status endpoint"
        }
    }
}

/* ============================== MAPPINGS ============================== */

mappings {
    path("/login")      { action: [ GET: "cloudLogin" ] }
    path("/chooseHvac") { action: [ GET: "cloudChooseHvac" ] }
}

/* ============================== UTILITIES ============================== */

private String hubUidSafe() {
    try { return getHubUID() } catch (e) { return location?.hubs?.first()?.id }
}
private String appEndpointBase() {
    String base = getApiServerUrl() ?: ""
    if (base.startsWith("https://cloud.hubitat.com"))
        return "https://cloud.hubitat.com/api/${hubUidSafe()}/apps/${app.id}"
    return "${base}/apps/api/${app.id}"
}
private String getOrCreateAppToken() {
    if (!state.appAccessToken) { createAccessToken(); state.appAccessToken = state.accessToken }
    return state.appAccessToken
}

private boolean checkDeviceReachable(def dev) {
    try {
        // Method 1: Check deviceAlive attribute (most reliable for Hubitat LAN/cloud devices)
        def deviceAlive = dev.currentValue("deviceAlive")
        if (deviceAlive != null) {
            boolean alive = deviceAlive.toString().toLowerCase() == "true"
            if (!alive) {
                if (enableDebugLogging) log.debug "Device ${dev.displayName} has deviceAlive=false"
                return false
            }
            // deviceAlive is true — device is confirmed reachable
            if (enableDebugLogging) log.debug "Device ${dev.displayName} has deviceAlive=true"
            return true
        }

        // Method 2: Check device health status (if available)
        def healthStatus = dev.getStatus()
        if (healthStatus && healthStatus.toLowerCase() == "offline") {
            if (enableDebugLogging) log.debug "Device ${dev.displayName} is offline per getStatus()"
            return false
        }

        // Method 3: Check if device has recent activity (within last 24 hours)
        def lastActivity = dev.getLastActivity()
        if (lastActivity) {
            long lastActivityMs = lastActivity.time
            long twentyFourHoursAgo = now() - (24 * 60 * 60 * 1000)
            if (lastActivityMs < twentyFourHoursAgo) {
                if (enableDebugLogging) log.debug "Device ${dev.displayName} hasn't reported in 24+ hours"
                return false
            }
        }

        // Method 4: Check if critical attributes are null (might indicate offline)
        def temp = dev.currentTemperature
        def state = dev.currentThermostatOperatingState
        if (temp == null && state == null) {
            if (enableDebugLogging) log.warn "Device ${dev.displayName} has null critical attributes"
            return false
        }

        // If all checks pass, device is reachable
        return true

    } catch (Exception e) {
        log.warn "Error checking device reachability: ${e.message}"
        // Default to true if we can't determine status
        return true
    }
}

/**
 * Debug function to diagnose online status issues.
 * Call from Hubitat IDE or add a button to trigger it.
 * Check logs for which condition is causing is_reachable to be false.
 */
def debugOnlineStatus() {
    log.info "========== ONLINE STATUS DIAGNOSTIC =========="

    // 1. Check if thermostat is configured
    if (!settings.thermostat) {
        log.error "❌ No thermostat selected in settings"
        return
    }
    log.info "✅ Thermostat: ${settings.thermostat.displayName}"

    // 2. Check device attributes
    def temp = settings.thermostat.currentTemperature
    def opState = settings.thermostat.currentThermostatOperatingState
    def fanMode = settings.thermostat.currentThermostatFanMode
    def lastActivity = settings.thermostat.getLastActivity()
    def healthStatus = settings.thermostat.getStatus()
    def deviceAlive = settings.thermostat.currentValue("deviceAlive")

    log.info "📊 Current Temperature: ${temp}"
    log.info "📊 Operating State: ${opState}"
    log.info "📊 Fan Mode: ${fanMode}"
    log.info "📊 Health Status: ${healthStatus}"
    log.info "📊 Device Alive: ${deviceAlive}"
    log.info "📊 Last Activity: ${lastActivity}"

    // 3. Check what checkDeviceReachable returns
    boolean isReachable = checkDeviceReachable(settings.thermostat)
    log.info "🔍 checkDeviceReachable() returns: ${isReachable}"

    // 4. Check individual reachability conditions
    log.info "--- Reachability Checks ---"

    // Check 1: deviceAlive attribute (most reliable)
    if (deviceAlive != null) {
        boolean alive = deviceAlive.toString().toLowerCase() == "true"
        log.info "   deviceAlive: '${deviceAlive}' (alive=${alive}) — this is the primary check"
    } else {
        log.info "   deviceAlive: null (attribute not present, falling through to other checks)"
    }

    // Check 2: Health status
    if (healthStatus) {
        log.info "   Health status: '${healthStatus}' (offline check: ${healthStatus.toLowerCase() == 'offline'})"
    } else {
        log.info "   Health status: null (skipping check)"
    }

    // Check 3: Last activity time
    if (lastActivity) {
        long lastActivityMs = lastActivity.time
        long twentyFourHoursAgo = now() - (24 * 60 * 60 * 1000)
        long minutesAgo = (now() - lastActivityMs) / 60000
        boolean tooOld = (lastActivityMs < twentyFourHoursAgo)
        log.info "   Last activity: ${minutesAgo} minutes ago (>24hr check: ${tooOld})"
    } else {
        log.info "   Last activity: null (skipping check)"
    }

    // Check 4: Null attributes
    boolean bothNull = (temp == null && opState == null)
    log.info "   Null attributes check: temp=${temp}, opState=${opState}, bothNull=${bothNull}"

    // 5. Check authentication state
    log.info "--- Authentication State ---"
    log.info "   sfpAccessToken: ${state.sfpAccessToken ? 'SET' : 'NOT SET'}"
    log.info "   sfpUserId: ${state.sfpUserId ?: 'NOT SET'}"
    log.info "   sfpHvacId: ${state.sfpHvacId ?: 'NOT SET'}"
    log.info "   sfpCoreToken: ${state.sfpCoreToken ? 'SET' : 'NOT SET'}"

    // 6. Build and show what payload WOULD be sent
    log.info "--- Payload Preview ---"
    String op = settings.thermostat.currentThermostatOperatingState ?: "idle"
    String fan = settings.thermostat.currentThermostatFanMode ?: "auto"
    String equipStatus = classifyState(op, fan, false)

    log.info "   device_key: ${state.sfpHvacId}"
    log.info "   is_reachable: ${isReachable}"
    log.info "   equipment_status: ${equipStatus}"
    log.info "   temperature_f: ${temp}"

    log.info "========== END DIAGNOSTIC =========="

    // Return summary
    if (!isReachable) {
        log.warn "⚠️ ISSUE FOUND: Device is being marked as NOT REACHABLE"
        log.warn "   Check the conditions above to see which check is failing"
    } else if (!state.sfpAccessToken) {
        log.warn "⚠️ ISSUE FOUND: Not authenticated with SmartFilterPro"
    } else if (!state.sfpHvacId) {
        log.warn "⚠️ ISSUE FOUND: No HVAC ID configured"
    } else {
        log.info "✅ All checks passed - device should show as online"
    }
}

/* ============================== LOGIN / HVAC ============================== */

def cloudLogin() {
    try {
        def email = settings.loginEmail?.trim()
        def pwd = settings.loginPassword
        if (!email || !pwd) return _html("Missing credentials.")

        Map loginResp
        httpPost([
            uri: BUBBLE_LOGIN_URL,
            contentType: "application/json",
            body: [ email: email, password: pwd ],
            timeout: (settings.httpTimeout ?: DEFAULT_HTTP_TIMEOUT)
        ]) { resp -> loginResp = resp.data }

        Map body = _bubbleBody(loginResp)
        if (!body.access_token) return _html("Login failed.")

        state.sfpAccessToken  = body.access_token
        state.sfpRefreshToken = body.refresh_token
        state.sfpUserId       = body.user_id
        state.sfpCoreToken    = body.core_token
        state.sfpCoreTokenExp = body.expires_at

        // HVAC map - handle both single and multiple thermostats
        Map choices = [:]
        List ids = body.hvac_id instanceof List ? body.hvac_id : (body.hvac_id ? [body.hvac_id] : [])
        List names = body.hvac_name instanceof List ? body.hvac_name : (body.hvac_name ? [body.hvac_name] : [])

        ids.eachWithIndex { id, idx ->
            if (id) {
                // Safely get name, fall back to id if index out of bounds or null
                String name = (idx < names.size() && names[idx]) ? names[idx].toString() : id.toString()
                choices[id.toString()] = name
            }
        }
        state.sfpHvacChoices = choices

        if (choices.size() == 1) {
            state.sfpHvacId = choices.keySet().first()
            state.sfpHvacName = choices.values().first()
        } else if (choices.size() > 1) {
            log.info "📋 Multiple HVACs found: ${choices}"
        }

        app.removeSetting("loginPassword")
        state.lastLoginError = null
        _html("Login complete. Close this tab and return to the app.")
    } catch (e) {
        log.error "Login error: ${e}"
        state.lastLoginError = e.toString()
        _html("Login failed: ${e}")
    }
}

def cloudChooseHvac() {
    def id = params?.id?.toString()
    if (id) {
        state.sfpHvacId = URLDecoder.decode(id, "UTF-8")
        state.sfpHvacName = (state.sfpHvacChoices ?: [:])[state.sfpHvacId]
    }
    _html("Saved HVAC. Close this tab and return.")
}

private def _html(String msg) {
    render(contentType: "text/html", data: "<html><body><h3>${msg}</h3></body></html>")
}

private Map _bubbleBody(Map resp) {
    return (resp?.response ?: resp) as Map
}

/* ============================== LIFECYCLE ============================== */

def installed() {
    log.info "SmartFilterPro Bridge installed"
    // Per-device sequence counter (atomicState.lastSequenceByDevice) is
    // lazily bootstrapped + legacy-migrated by reserveSequenceNumber()
    // on first use. We deliberately DO NOT touch it here — resetting
    // it on install/update would reintroduce the sequence-reset bug
    // that causes Core to silently dedup every subsequent event until
    // the counter climbs back above its previous high-water mark.
    // Initialize event buffer for backfill
    if (state.eventBuffer == null) {
        state.eventBuffer = []
        log.info "Initialized event buffer"
    }
    initialize()
}

def updated()  {
    log.info "SmartFilterPro Bridge updated"
    // Per-device sequence counter (atomicState.lastSequenceByDevice) is
    // lazily bootstrapped + legacy-migrated by reserveSequenceNumber()
    // on first use. We deliberately DO NOT touch it here — resetting
    // it on install/update would reintroduce the sequence-reset bug
    // that causes Core to silently dedup every subsequent event until
    // the counter climbs back above its previous high-water mark.
    // Initialize event buffer if not present
    if (state.eventBuffer == null) {
        state.eventBuffer = []
        log.info "Initialized event buffer"
    }
    unsubscribe()
    unschedule()
    initialize()
}

def initialize() {
    log.info "🔧 Initializing SmartFilterPro Bridge (8-State System)…"

    if (settings.thermostat) {
        log.info "📡 Subscribing to thermostat: ${settings.thermostat.displayName} (ID: ${settings.thermostat.getId()})"
        subscribe(settings.thermostat, "thermostatOperatingState", handleEvent)
        subscribe(settings.thermostat, "temperature", handleEvent)
        subscribe(settings.thermostat, "thermostatFanMode", handleEvent)
        subscribe(settings.thermostat, "humidity", handleEvent)
        subscribe(settings.thermostat, "heatingSetpoint", handleEvent)
        subscribe(settings.thermostat, "coolingSetpoint", handleEvent)
        subscribe(settings.thermostat, "thermostatMode", handleEvent)
        log.info "✅ Subscriptions created"
    } else {
        log.warn "⚠️ No thermostat selected - skipping subscriptions"
    }

    // Handle child device creation/deletion based on settings
    if (settings.createStatusDevice) {
        createStatusDevice()
    } else {
        deleteStatusDevice()
    }

    if (settings.createResetDevice) {
        createResetDevice()
    } else {
        deleteResetDevice()
    }

    unschedule()
    runEvery30Minutes("heartbeat")
    runEvery5Minutes("checkDeviceHealth")

    // Reliable-delivery outbox safety-net timer. Drains any batches
    // whose nextAttemptAt has elapsed. Fresh POST successes also
    // trigger a drain via runIn(0) — this timer is the fallback for
    // when there's no traffic to piggyback on.
    runEvery1Minute("drainOutbox")

    // Reboot recovery: if the outbox survived from a previous run,
    // try it once the hub has settled. The runIn(30) delay gives
    // networking and any other startup activity time to finish.
    if (atomicState.outbox && (atomicState.outbox as List).size() > 0) {
        log.warn "📤 [outbox] ${(atomicState.outbox as List).size()} batches pending from previous run, scheduling drain"
        runIn(30, "drainOutbox")
    }

    schedule("0 0/20 * * * ?", "pollBubbleStatus")
    runIn(5, "pollBubbleStatus")

    // Check for app updates once daily (at a random minute to spread load)
    def randomMinute = Math.abs(new Random().nextInt() % 60)
    schedule("0 ${randomMinute} 3 * * ?", "checkForUpdate")
    runIn(30, "checkForUpdate")

    log.info "✅ Initialized v${APP_VERSION} | Linked=${!!state.sfpAccessToken} | HVAC=${state.sfpHvacName ?: '(none)'} | User=${state.sfpUserId ?: '(none)'}"
}

def heartbeat() {
    if (enableDebugLogging)
        log.debug "💓 Heartbeat — linked=${!!state.sfpAccessToken}, hvac=${state.sfpHvacId ?: '(none)'}"
}

def checkDeviceHealth() {
    if (!settings.thermostat) return

    boolean wasReachable = state.lastKnownReachable ?: true
    boolean isReachable = checkDeviceReachable(settings.thermostat)

    // Only send update if reachability status changed
    if (wasReachable != isReachable) {
        log.warn "⚠️ Device reachability changed: ${wasReachable} → ${isReachable}"
        state.lastKnownReachable = isReachable

        // Send a status update to Core
        if (state.sfpAccessToken) {
            String op = settings.thermostat.currentThermostatOperatingState ?: "idle"
            String fanMode = settings.thermostat.currentThermostatFanMode ?: "auto"
            String equipmentStatus = classifyState(op, fanMode, false)

            Map payload = buildCoreEventFromDevice(
                settings.thermostat,
                "Telemetry_Update",  // Event type
                null,                // runtimeSeconds
                equipmentStatus,     // Equipment status
                null,                // overrideIsActive (use default)
                isReachable          // overrideIsReachable
            )
            _postToCoreWithJwt(payload)
        }
    }
}

/* ============================== 8-STATE CLASSIFICATION ============================== */

/**
 * /* ============================== 8-STATE CLASSIFICATION ============================== */

/**
 * Classify thermostat state using 8-state system:
 * Cooling_Fan, Cooling, Heating_Fan, Heating, AuxHeat_Fan, AuxHeat, Fan_only, Idle
 *
 * Hubitat Logic:
 *   - operatingState tells us what equipment is running
 *   - fanMode tells us if fan is explicitly on
 *   - Check for auxiliary/emergency heat
 */
private String classifyState(String operatingState, String fanMode, boolean checkAuxHeat = true) {
    String op = (operatingState ?: "idle").toLowerCase()
    String fan = (fanMode ?: "auto").toLowerCase()

    boolean coolingActive = op.contains("cool")
    boolean heatingActive = op.contains("heat")
    boolean fanExplicitlyOn = (fan in ["on", "circulate"])
    boolean fanOnlyMode = (op == "fan only")

    // Check for auxiliary/emergency heat
    // Hubitat may report this in operatingState or we check thermostatMode
    boolean isAuxHeat = checkAuxHeat && (op.contains("emergency") || op.contains("aux"))

    // Residential forced-air systems always run the fan during heating/cooling,
    // so default to _Fan variants for accurate runtime tracking
    if (isAuxHeat) {
        return "AuxHeat_Fan"
    } else if (coolingActive) {
        return "Cooling_Fan"
    } else if (heatingActive) {
        return "Heating_Fan"
    } else if (fanOnlyMode || fanExplicitlyOn) {
        return "Fan_only"
    } else {
        return "Idle"
    }
}

/* ============================== RUNTIME / EVENTS ============================== */

/**
 * Claim a Mode_Change post slot for the given equipment_status.
 *
 * Returns true if the caller should proceed with posting (no recent duplicate),
 * false if a Mode_Change with the same equipment_status was already posted
 * within MODE_CHANGE_DEDUP_WINDOW_MS.
 *
 * Uses atomicState so concurrent handlers (multiple thermostat attribute
 * events firing within milliseconds of each other) see each other's writes
 * immediately and cannot both pass the dedup check.
 */
private boolean _claimModeChangeSlot(String equipmentStatus) {
    String uid = state.sfpUserId
    String devId = settings.thermostat?.getId()?.toString()
    if (!uid || !devId) return true   // Can't dedup without keys; fail open

    String keyLastType = "lastModeChangePostType_${uid}-${devId}"
    String keyLastTime = "lastModeChangePostTime_${uid}-${devId}"

    String lastType = atomicState[keyLastType]
    Long lastTime   = (atomicState[keyLastTime] as Long) ?: 0L
    Long nowMs      = now()

    if (lastType == equipmentStatus && (nowMs - lastTime) < MODE_CHANGE_DEDUP_WINDOW_MS) {
        if (enableDebugLogging) {
            log.debug "⏭️ Dedup: skipping duplicate Mode_Change (${equipmentStatus}) — last posted ${nowMs - lastTime}ms ago"
        }
        return false
    }

    // Claim the slot BEFORE posting so a concurrent handler sees it.
    atomicState[keyLastType] = equipmentStatus
    atomicState[keyLastTime] = nowMs
    return true
}

def handleEvent(evt) {
    if (enableDebugLogging)
        log.debug "🔔 Event received: ${evt.name} = ${evt.value} from ${evt.displayName}"

    def dev = settings.thermostat
    if (!dev) {
        if (enableDebugLogging) log.warn "⚠️ handleEvent: No thermostat configured, skipping"
        return
    }

    if (!state.sfpAccessToken) {
        if (enableDebugLogging) log.warn "⚠️ handleEvent: Not linked (no access token), skipping"
        return
    }

    String op = dev.currentThermostatOperatingState?.toLowerCase() ?: "idle"
    String fanMode = dev.currentThermostatFanMode?.toLowerCase() ?: "auto"
    String thermostatMode = dev.currentThermostatMode?.toLowerCase() ?: "auto"

    // Check reachability once per event so every payload carries current status
    boolean isReachable = checkDeviceReachable(dev)

    boolean isStateChangingEvent = (evt.name in ["thermostatOperatingState", "thermostatFanMode"])

    String uid = state.sfpUserId
    String devId = dev.getId().toString()
    String keyLastThermostatMode = "lastThermostatMode_${uid}-${devId}"
    String lastThermostatMode = state[keyLastThermostatMode] ?: "auto"
    boolean thermostatModeChanged = (thermostatMode != lastThermostatMode)

    if (thermostatModeChanged && enableDebugLogging) {
        log.debug "🔄 Thermostat mode changed: ${lastThermostatMode} → ${thermostatMode}"
    }

    // Classify current state using 8-state system
    String equipmentStatus = classifyState(op, fanMode, true)
    boolean isActive = (equipmentStatus != "Idle") 

    if (enableDebugLogging) {
        log.debug "📊 State: op=${op}, fanMode=${fanMode}, thermostatMode=${thermostatMode}, equipment_status=${equipmentStatus}, isActive=${isActive}, isStateChange=${isStateChangingEvent}"
    }

    String keyStart = "sessionStart_${uid}-${devId}"
    String keyWas   = "wasActive_${uid}-${devId}"
    String keyLastType = "lastEquipmentStatus_${uid}-${devId}"
    String keyLastRuntimePost = "lastRuntimePost_${uid}-${devId}"

    boolean wasActive = (state[keyWas] as Boolean) ?: false
    String lastEquipmentStatus = state[keyLastType] ?: "Idle"

    boolean equipmentModeChanged = (equipmentStatus != lastEquipmentStatus)

    // Debounce: Check if we recently posted runtime (within 5 seconds) to prevent race condition
    Long lastRuntimePostTime = (state[keyLastRuntimePost] as Long) ?: 0
    boolean recentlyPostedRuntime = (now() - lastRuntimePostTime) < 5000

    // Handle thermostat mode change (environmental update)
    if (thermostatModeChanged && !equipmentModeChanged && !isStateChangingEvent) {
        if (enableDebugLogging) log.debug "📊 Thermostat mode changed (${lastThermostatMode} → ${thermostatMode})"

        state[keyLastThermostatMode] = thermostatMode

        if (!_claimModeChangeSlot(equipmentStatus)) {
            return
        }

        // ✅ Use "Mode_Change" for event_type, but current state for equipment_status
        Map payload = buildCoreEventFromDevice(dev, "Mode_Change", null, equipmentStatus, isActive, isReachable)
        payload.previous_status = lastEquipmentStatus
        state.sfpLastCorePayload = payload

        _postToCoreWithJwt(payload)
        return
    }

    // Handle environmental telemetry updates (no state change)
    if (!isStateChangingEvent && !equipmentModeChanged) {
        if (enableDebugLogging) log.debug "📊 Telemetry update (environmental change)"

        // ✅ Use "Telemetry_Update" for event_type, but current state for equipment_status
        Map payload = buildCoreEventFromDevice(dev, "Telemetry_Update", null, equipmentStatus, isActive, isReachable)
        payload.previous_status = lastEquipmentStatus
        state.sfpLastCorePayload = payload

        _postToCoreWithJwt(payload)
        return
    }

    // Calculate runtime if transitioning (with debounce to prevent double-posting)
    Integer runtimeSeconds = null
    if (equipmentModeChanged && wasActive && state[keyStart] && !recentlyPostedRuntime) {
        // Set debounce timestamp FIRST to "claim" this runtime calculation
        // This prevents concurrent events from also calculating runtime
        state[keyLastRuntimePost] = now()

        Long start = (state[keyStart] as Long)
        if (start) {
            runtimeSeconds = Math.max(0L, ((now() - start) / 1000L) as int)
            if (enableDebugLogging) log.debug "⏱️ Runtime calculated: ${runtimeSeconds}s (was ${lastEquipmentStatus})"
        }
    } else if (recentlyPostedRuntime && equipmentModeChanged && wasActive) {
        if (enableDebugLogging) log.debug "⏭️ Skipping duplicate runtime calculation (debounce active)"
    }

    // Session START (inactive → active)
    if (isActive && !wasActive) {
        state[keyStart] = now()
        if (enableDebugLogging) log.debug "🏁 Session START: ${equipmentStatus}"

        // Update state BEFORE posting to prevent race conditions with concurrent events
        state[keyWas] = true
        state[keyLastType] = equipmentStatus
        state[keyLastThermostatMode] = thermostatMode

        if (!_claimModeChangeSlot(equipmentStatus)) {
            return
        }

        Map payload = buildCoreEventFromDevice(dev, "Mode_Change", null, equipmentStatus, true, isReachable)
        payload.previous_status = lastEquipmentStatus
        state.sfpLastCorePayload = payload

        _postToCoreWithJwt(payload)
        return
    }

    // Session END (active → inactive)
    if (!isActive && wasActive) {
        // Skip if this transition was already handled by a recent event (race condition protection)
        if (recentlyPostedRuntime && runtimeSeconds == null) {
            if (enableDebugLogging) log.debug "⏭️ Skipping duplicate Session END (already posted by concurrent event)"
            state[keyWas] = false
            state[keyLastType] = equipmentStatus
            return
        }

        // Clear session start and update state BEFORE posting to prevent race conditions
        // This ensures concurrent events see the updated state immediately
        state.remove(keyStart)
        state[keyWas] = false
        state[keyLastType] = equipmentStatus
        state[keyLastThermostatMode] = thermostatMode

        if (enableDebugLogging) log.debug "🛑 Session END: ${lastEquipmentStatus} -> Idle (runtime=${runtimeSeconds}s)"

        if (!_claimModeChangeSlot(equipmentStatus)) {
            return
        }

        Map payload = buildCoreEventFromDevice(dev, "Mode_Change", runtimeSeconds, equipmentStatus, false, isReachable)
        payload.previous_status = lastEquipmentStatus
        state.sfpLastCorePayload = payload

        _postToCoreWithJwt(payload)
        return
    }

    // Equipment mode changed while active (Heating → Cooling, etc.)
    if (isActive && equipmentModeChanged) {
        // Skip if this transition was already handled by a recent event (race condition protection)
        if (recentlyPostedRuntime && runtimeSeconds == null) {
            if (enableDebugLogging) log.debug "⏭️ Skipping duplicate mode switch (already posted by concurrent event)"
            state[keyLastType] = equipmentStatus
            return
        }

        // Update state BEFORE posting to prevent race conditions with concurrent events
        state[keyStart] = now()
        state[keyWas] = true
        state[keyLastType] = equipmentStatus
        state[keyLastThermostatMode] = thermostatMode

        if (enableDebugLogging) log.debug "🔄 Equipment mode switch: ${lastEquipmentStatus} → ${equipmentStatus} (runtime=${runtimeSeconds}s)"

        if (!_claimModeChangeSlot(equipmentStatus)) {
            return
        }

        Map payload = buildCoreEventFromDevice(dev, "Mode_Change", runtimeSeconds, equipmentStatus, true, isReachable)
        payload.previous_status = lastEquipmentStatus
        state.sfpLastCorePayload = payload

        _postToCoreWithJwt(payload)
        return
    }

    // State-changing event while active (fan mode changed, etc.)
    if (isActive && isStateChangingEvent && !equipmentModeChanged) {
        if (enableDebugLogging) log.debug "🔄 State change: ${evt.name} changed to ${evt.value}"

        // Update state BEFORE posting to prevent race conditions with concurrent events
        state[keyWas] = true
        state[keyLastType] = equipmentStatus
        state[keyLastThermostatMode] = thermostatMode

        if (!_claimModeChangeSlot(equipmentStatus)) {
            return
        }

        Map payload = buildCoreEventFromDevice(dev, "Mode_Change", null, equipmentStatus, true, isReachable)
        payload.previous_status = lastEquipmentStatus
        state.sfpLastCorePayload = payload

        _postToCoreWithJwt(payload)
        return
    }

    // State-changing event while inactive
    if (!isActive && isStateChangingEvent && !equipmentModeChanged) {
        if (enableDebugLogging) log.debug "🔄 State change while idle: ${evt.name} changed to ${evt.value}"

        // Update state BEFORE posting to prevent race conditions with concurrent events
        state[keyWas] = false
        state[keyLastType] = equipmentStatus
        state[keyLastThermostatMode] = thermostatMode

        if (!_claimModeChangeSlot(equipmentStatus)) {
            return
        }

        Map payload = buildCoreEventFromDevice(dev, "Mode_Change", null, equipmentStatus, false, isReachable)
        payload.previous_status = lastEquipmentStatus
        state.sfpLastCorePayload = payload

        _postToCoreWithJwt(payload)
        return
    }
}
/* ============================== PAYLOAD BUILDER - 8-STATE SYSTEM ============================== */

private Map buildCoreEventFromDevice(def dev, String eventType, Integer runtimeSeconds = null, String equipmentStatus = null, Boolean overrideIsActive = null, Boolean overrideIsReachable = null) {
    String userId = state.sfpUserId
    String deviceId = dev.getId().toString()
    String label = dev.label ?: dev.displayName ?: dev.name
    String manufacturer = dev.getDataValue("manufacturer") ?: "Hubitat"
    String model = dev.getDataValue("model") ?: "Unknown Model"
    String modelNumber = dev.deviceNetworkId ?: "Unknown"
    String op = dev.currentThermostatOperatingState
    String fanMode = dev.currentThermostatFanMode
    String thermostatMode = dev.currentThermostatMode
    Double temp = dev.currentTemperature
    Double heat = dev.currentHeatingSetpoint
    Double cool = dev.currentCoolingSetpoint
    Double humidity = dev.currentHumidity
    def payloadRaw = [
        version: APP_VERSION,
        temperature: temp,
        humidity: humidity,
        thermostatMode: thermostatMode,
        thermostatOperatingState: op,
        thermostatFanMode: fanMode,
        heatingSetpoint: heat,
        coolingSetpoint: cool,
        thermostatSetpoint: dev.currentThermostatSetpoint,
        supportedThermostatModes: dev.supportedThermostatModes?.toString(),
        supportedThermostatFanModes: dev.supportedThermostatFanModes?.toString()
    ]

    // Default to true (if we're sending an event, we have data from the device)
    // Allow override for health check scenarios
    boolean isReachable = (overrideIsReachable != null) ? overrideIsReachable : true

    String ts = new Date().toInstant().toString()

    // Use 8-state equipment status
	String finalEquipStatus = equipmentStatus ?: eventType ?: "Idle"  // Changed from "Fan_off"
	Boolean finalIsActive = (overrideIsActive != null) ? overrideIsActive : (eventType != "Idle")  // Changed from "Fan_off"

    // Map 8-state system to boolean flags
    boolean isCooling = (finalEquipStatus == "Cooling_Fan" || finalEquipStatus == "Cooling")
    boolean isHeating = (finalEquipStatus == "Heating_Fan" || finalEquipStatus == "Heating" ||
                        finalEquipStatus == "AuxHeat_Fan" || finalEquipStatus == "AuxHeat")
    boolean isFanOnly = (finalEquipStatus == "Fan_only")

    return [
        device_key: state.sfpHvacId,
        device_id: state.sfpHvacId,
        workspace_id: userId,
        user_id: userId,
        device_name: label,
        manufacturer: manufacturer,
        model: model,
        model_number: modelNumber,
        device_type: "thermostat",
        source: "hubitat",
        source_vendor: "hubitat",
        connection_source: "hubitat",
        frontend_id: state.sfpHvacId,
        firmware_version: dev.getDataValue("firmwareVersion"),
        serial_number: dev.getDataValue("serialNumber"),
        timezone: location?.timeZone?.ID ?: "UTC",
        
        // Use 8-state boolean flags
        last_mode: thermostatMode,
        thermostat_mode: thermostatMode,
        thermostatOperatingState: op,
        thermostatFanMode: fanMode,
        last_is_cooling: isCooling,
        last_is_heating: isHeating,
        last_is_fan_only: isFanOnly,
        last_equipment_status: finalEquipStatus,  // Use 8-state value
        is_reachable: isReachable,
        
        last_temperature: temp,
        temperature_f: temp,
        humidity: humidity,
        last_humidity: humidity,
        last_heat_setpoint: heat,
        heat_setpoint_f: heat,
        last_cool_setpoint: cool,
        cool_setpoint_f: cool,
        
        event_type: eventType,
        equipment_status: finalEquipStatus,  // Use 8-state value
        is_active: finalIsActive,
        runtime_seconds: runtimeSeconds,
        sequence_number: reserveSequenceNumber(state.sfpHvacId),
        timestamp: ts,
        recorded_at: ts,
        observed_at: ts,
        payload_raw: payloadRaw
    ]
}

/* ============================== CORE POST / TOKEN ============================== */

/**
 * Public entrypoint for posting events to Core.
 *
 * Responsibilities:
 *   - Validate / refresh the core_token before trying
 *   - Buffer each event into state.eventBuffer for gap backfill
 *     (the ONLY place that calls addToEventBuffer in the fresh path)
 *   - Call _doCorePost for the actual HTTP work
 *   - On "ok": handle gaps, schedule a piggyback outbox drain
 *   - On "permanent": log, bump dropped counter, return false
 *   - On "transient": enqueue the batch for retry, return false
 *
 * Returns true only if the POST actually succeeded. An enqueued
 * transient failure returns false so callers can still reason about
 * "did this event reach Core right now" vs. "is it queued."
 */
private boolean _postToCoreWithJwt(Object body) {
    if (enableDebugLogging) log.debug "🚀 _postToCoreWithJwt called"

    if (!_ensureCoreTokenValid()) {
        log.warn "❌ No valid core_token available; skipping Core post"
        return false
    }

    List batch = (body instanceof List) ? (body as List) : [body]

    // Buffer events on the fresh path only. Outbox drain retries
    // call _doCorePost directly and skip buffering, so this is the
    // sole entry point into addToEventBuffer.
    batch.each { addToEventBuffer(it as Map) }

    if (enableDebugLogging) {
        log.debug "📤 POST to Core: ${CORE_INGEST_URL}"
        log.debug "📤 Body (${batch.size()} events):"
        batch.each { evt ->
            log.debug "   → device_key: ${evt.device_key}, event_type: ${evt.event_type}, equipment_status: ${evt.equipment_status}, sequence=${evt.sequence_number}"
        }
    }

    Map result = _doCorePost(batch, false)

    if (result.kind == "ok") {
        log.info "✅ Core POST OK (${result.status}) — ${batch.size()} event(s)"
        try {
            if (result.data?.gaps) {
                logDebug "⚠️ Core detected ${result.data.gaps.size()} gap(s)"
                handleGapResponse(result.data.gaps as List)
            }
        } catch (Exception ge) {
            logDebug "Could not parse gap response: ${ge.message}"
        }
        // Piggyback drain via runIn(0) instead of a direct call so we
        // yield to Hubitat's scheduler. Prevents re-entrancy (drain
        // can't call back into _postToCoreWithJwt while we're still
        // inside the current handler's HTTP callback) and gives the
        // drain its own try/catch envelope.
        if (atomicState.outbox && (atomicState.outbox as List).size() > 0) {
            runIn(0, "drainOutbox")
        }
        return true
    }

    if (result.kind == "permanent") {
        log.error "❌ Core permanent error (${result.reason}) — dropping ${batch.size()} event(s): ${result.error}"
        bumpOutboxDroppedCount()
        return false
    }

    // Transient: enqueue for retry.
    log.warn "⚠️ Core transient error (${result.reason}) — enqueueing ${batch.size()} event(s) to outbox"
    enqueueOutbox(batch, (result.reason as String) ?: "transient")
    return false
}

/**
 * Pure HTTP POST to Core. No side effects on buffer, outbox, or
 * sequence counter. Handles its own 401-refresh internally, bounded
 * to a single recursion via the isRetry flag.
 *
 * Result shape (matched on .kind as an exact string literal):
 *   [kind: "ok",        status: Integer, data: Object]
 *   [kind: "permanent", reason: String,  error: String]
 *   [kind: "transient", reason: String,  error: String]
 */
private Map _doCorePost(List batch, boolean isRetry) {
    String token = state.sfpCoreToken ?: ""
    Integer timeoutSec = (settings.httpTimeout ?: DEFAULT_HTTP_TIMEOUT) as Integer

    if (isRetry) log.info "🔄 RETRY: Attempting Core post with refreshed token..."

    Map params = [
        uri: CORE_INGEST_URL,
        contentType: "application/json",
        requestContentType: "application/json",
        timeout: timeoutSec,
        headers: [ Authorization: "Bearer ${token}" ],
        body: batch
    ]

    try {
        int status = 0
        def respData = null
        httpPost(params) { resp ->
            status = resp.status
            respData = resp.data
            if (enableDebugLogging) {
                log.debug "📥 Core response status: ${resp.status}"
                log.debug "📥 Core response data: ${resp.data}"
            }
        }
        if (status >= 200 && status < 300) {
            if (isRetry) log.info "✅ RETRY SUCCESSFUL!"
            return [kind: "ok", status: status, data: respData]
        }
        // Non-2xx that somehow didn't throw. Treat as transient so
        // the outbox retries — if it's actually a permanent client
        // error, the next attempt will throw and reclassify.
        log.warn "⚠️ Core returned non-2xx without throwing: ${status}"
        return [kind: "transient", reason: "non_2xx_${status}", error: "status=${status}"]
    } catch (Exception e) {
        String errMsg = e.toString()
        boolean is401 = errMsg.contains("401") || errMsg.toLowerCase().contains("unauthorized")
        boolean is4xx = _isHttpClientError(errMsg)

        if (is401 && !isRetry) {
            log.warn "⚠️ Core 401 — refreshing token and retrying"
            if (_issueCoreTokenOrLog()) {
                return _doCorePost(batch, true)
            }
            return [kind: "permanent", reason: "401_refresh_failed", error: errMsg]
        }

        if (is4xx) {
            log.error "❌ Core 4xx: ${errMsg}"
            return [kind: "permanent", reason: "4xx", error: errMsg]
        }

        log.error "❌ Core post exception: ${e.message}"
        return [kind: "transient", reason: "exception_${e.class.simpleName}", error: errMsg]
    }
}

private boolean _ensureCoreTokenValid() {
    Long exp = (state.sfpCoreTokenExp as Long)
    Long nowSec = (now() / 1000L) as Long

    if (state.sfpCoreToken && exp && nowSec < (exp - TOKEN_SKEW_SECONDS)) {
        if (enableDebugLogging) 
            log.debug "✅ Core token valid (expires in ${exp - nowSec}s)"
        return true
    }

    if (enableDebugLogging)
        log.debug "🔄 Core token expired, requesting new one..."

    return _issueCoreTokenOrLog()
}

private boolean _issueCoreTokenOrLog() {
    String at = (state.sfpAccessToken ?: "")
    if (!at) {
        log.warn "❌ Cannot issue core token — no Bubble access_token"
        return false
    }

    try {
        log.info "🔄 Requesting new core_token from Bubble..."
        
        Map respMap
        httpPost([
            uri: BUBBLE_CORE_JWT_URL,
            contentType: "application/json",
            requestContentType: "application/json",
            headers: [ Authorization: "Bearer ${at}" ],
            body: [ user_id: state.sfpUserId ],
            timeout: (settings.httpTimeout ?: DEFAULT_HTTP_TIMEOUT)
        ]) { resp -> respMap = resp.data }
        
        Map body = _bubbleBody(respMap)
        
        String core = (body?.core_token ?: body?.token ?: "").toString()
        Long exp = (body?.core_token_exp ?: body?.exp ?: body?.expires_at) as Long
        
        if (core) {
            state.sfpCoreToken = core
            state.sfpCoreTokenExp = exp
            log.info "✅ Core token refreshed (exp: ${exp})"
            return true
        } else {
            log.error "❌ Core token response missing token: ${body}"
            return false
        }
    } catch (Exception e) {
        log.error "❌ Failed to issue core token: ${e.message}"
        return false
    }
}

/* ============================== BUBBLE STATUS POLLING ============================== */

def pollBubbleStatus() {
    if (!state.sfpAccessToken || !state.sfpHvacId) {
        if (enableDebugLogging) log.debug "⏭️ Skipping status poll (not linked)"
        return
    }

    try {
        if (enableDebugLogging) log.debug "📡 Polling Bubble status…"

        Map respMap
        httpPost([
            uri: BUBBLE_STATUS_URL,
            contentType: "application/json",
            requestContentType: "application/json",
            headers: [ Authorization: "Bearer ${state.sfpAccessToken}" ],
            body: [ hvac_id: state.sfpHvacId ],
            timeout: (settings.httpTimeout ?: DEFAULT_HTTP_TIMEOUT)
        ]) { resp -> respMap = resp.data }

        Map body = _bubbleBody(respMap)
        state.sfpLastStatus = body

        // Update HVAC name if it changed in Bubble
        if (body.deviceName && body.deviceName != state.sfpHvacName) {
            log.info "📝 HVAC name updated: '${state.sfpHvacName}' → '${body.deviceName}'"
            state.sfpHvacName = body.deviceName
            updateChildDeviceLabels()
        }

        if (enableDebugLogging) {
            log.debug "✅ Bubble status: filterHealth=${body.filterHealth}%, minutesActive=${body.minutesActive}"
        }

        // Update child status device if it exists
        updateStatusDevice(body)

    } catch (Exception e) {
        log.error "❌ Bubble status poll failed: ${e.message}"
    }
}

/* ============================== VERSION CHECK ============================== */

/**
 * Check GitHub for a newer version of the app.
 * Fetches packageManifest.json and compares version to APP_VERSION.
 * Stores result in state.updateAvailable and state.latestVersion.
 */
def checkForUpdate() {
    try {
        logDebug "🔍 Checking for app updates..."

        httpGet([uri: VERSION_CHECK_URL, timeout: 15]) { resp ->
            if (resp.status == 200) {
                def manifest = new groovy.json.JsonSlurper().parseText(resp.data.text)
                String latestVersion = manifest?.version

                if (latestVersion && isNewerVersion(latestVersion, APP_VERSION)) {
                    state.updateAvailable = true
                    state.latestVersion = latestVersion
                    log.info "🆕 Update available: ${APP_VERSION} → ${latestVersion}"
                } else {
                    state.updateAvailable = false
                    state.latestVersion = APP_VERSION
                    logDebug "✅ App is up to date (${APP_VERSION})"
                }
            }
        }
    } catch (Exception e) {
        logDebug "Could not check for updates: ${e.message}"
        // Don't clear update state on failure - keep previous result
    }
}

/**
 * Compare semantic versions (e.g. "1.1.0" > "1.0.0").
 * Returns true if remoteVersion is newer than localVersion.
 */
private boolean isNewerVersion(String remoteVersion, String localVersion) {
    try {
        def remote = remoteVersion.tokenize('.').collect { it as Integer }
        def local = localVersion.tokenize('.').collect { it as Integer }

        for (int i = 0; i < Math.max(remote.size(), local.size()); i++) {
            int r = i < remote.size() ? remote[i] : 0
            int l = i < local.size() ? local[i] : 0
            if (r > l) return true
            if (r < l) return false
        }
        return false
    } catch (Exception e) {
        return false
    }
}

private void updateChildDeviceLabels() {
    def statusDev = getStatusDevice()
    if (statusDev) {
        String newLabel = "SmartFilterPro Status${state.sfpHvacName ? ' - ' + state.sfpHvacName : ''}"
        if (statusDev.label != newLabel) {
            statusDev.setLabel(newLabel)
            log.info "📝 Updated status device label: ${newLabel}"
        }
    }

    def resetDev = getResetDevice()
    if (resetDev) {
        String newLabel = "SmartFilterPro Reset${state.sfpHvacName ? ' - ' + state.sfpHvacName : ''}"
        if (resetDev.label != newLabel) {
            resetDev.setLabel(newLabel)
            log.info "📝 Updated reset button label: ${newLabel}"
        }
    }
}

/* ============================== STATUS DEVICE MANAGEMENT ============================== */

private def getStatusDevice() {
    String dni = "sfp-status-${app.id}"
    return getChildDevice(dni)
}

def createStatusDevice() {
    String dni = "sfp-status-${app.id}"
    def existing = getChildDevice(dni)

    if (existing) {
        log.info "📊 Status device already exists: ${existing.displayName}"
        return existing
    }

    try {
        String label = "SmartFilterPro Status${state.sfpHvacName ? ' - ' + state.sfpHvacName : ''}"
        def device = addChildDevice("smartfilterpro", "SmartFilterPro Status Sensor", dni, [
            label: label,
            isComponent: false
        ])
        log.info "✅ Created status device: ${label}"

        // Immediately update with current status if available
        if (state.sfpLastStatus) {
            updateStatusDevice(state.sfpLastStatus)
        }

        return device
    } catch (Exception e) {
        log.error "❌ Failed to create status device: ${e.message}"
        return null
    }
}

def deleteStatusDevice() {
    String dni = "sfp-status-${app.id}"
    def existing = getChildDevice(dni)

    if (existing) {
        try {
            deleteChildDevice(dni)
            log.info "🗑️ Deleted status device"
            return true
        } catch (Exception e) {
            log.error "❌ Failed to delete status device: ${e.message}"
            return false
        }
    }
    return true
}

private void updateStatusDevice(Map status) {
    def device = getStatusDevice()
    if (device && status) {
        device.updateStatus(status)
        if (enableDebugLogging) log.debug "📊 Updated status device with: filterHealth=${status.filterHealth}, minutesActive=${status.minutesActive}"
    }
}

/* ============================== RESET BUTTON DEVICE MANAGEMENT ============================== */

private def getResetDevice() {
    String dni = "sfp-reset-${app.id}"
    return getChildDevice(dni)
}

def createResetDevice() {
    String dni = "sfp-reset-${app.id}"
    def existing = getChildDevice(dni)

    if (existing) {
        log.info "🔄 Reset button already exists: ${existing.displayName}"
        return existing
    }

    try {
        String label = "SmartFilterPro Reset${state.sfpHvacName ? ' - ' + state.sfpHvacName : ''}"
        def device = addChildDevice("smartfilterpro", "SmartFilterPro Reset Button", dni, [
            label: label,
            isComponent: false
        ])
        log.info "✅ Created reset button: ${label}"
        return device
    } catch (Exception e) {
        log.error "❌ Failed to create reset button: ${e.message}"
        return null
    }
}

def deleteResetDevice() {
    String dni = "sfp-reset-${app.id}"
    def existing = getChildDevice(dni)

    if (existing) {
        try {
            deleteChildDevice(dni)
            log.info "🗑️ Deleted reset button"
            return true
        } catch (Exception e) {
            log.error "❌ Failed to delete reset button: ${e.message}"
            return false
        }
    }
    return true
}

/* ============================== SEQUENCE NUMBER HELPERS ============================== */

/**
 * Reserve and advance the sequence number for a given device.
 *
 * Counter is persisted in atomicState.lastSequenceByDevice, a map
 * keyed by device_key so the counter is scoped to the
 * (device_key, source_vendor) tuple Core uses as its dedup key:
 * partial unique index on
 *   (device_key, source_vendor, payload_raw->>'sequence_number')
 * (core-ingest migration 024). A single shared counter would
 * collide as soon as a second device went through this SmartApp.
 *
 * Using atomicState (not plain state) means concurrent event handlers
 * see each other's writes immediately — plain state is only flushed
 * at handler return, which would otherwise allow two concurrent
 * handlers to peek the same value and post two events under the same
 * sequence_number. See _claimModeChangeSlot for the same pattern
 * applied to mode-change dedup.
 *
 * Persistence & bootstrap:
 *   Both state and atomicState survive hub reboots, SmartApp restarts,
 *   code updates via the Hubitat IDE, and crashes. That's the right
 *   place for a counter Core relies on being monotonic across all time.
 *
 *   On the very first call for a device after this code ships — or
 *   after the counter was somehow wiped — we MUST NOT reset to 0.
 *   Core enforces a unique index on
 *   (device_key, source_vendor, sequence_number), so a counter that
 *   rolls back causes Core to silently drop every event until the
 *   counter climbs back above its previous high-water mark. The
 *   canonical production failure: Core had last_sequence_number=5219,
 *   the SmartApp restarted and started posting at sequence_number=7,
 *   every post returned "Inserted 0 events" and the dashboard went
 *   quiet for hours. See smartfilterpro/core-ingest#217 for the
 *   observability-only 🔄 [sequence-reset] warning Core added for
 *   this; Core can't fix it itself without mutating dedup semantics.
 *
 *   To guarantee we leap above any prior high-water mark, the
 *   bootstrap seed is now() — Hubitat's current epoch time in
 *   milliseconds. That's orders of magnitude larger than any integer
 *   counter the previous implementation could have produced (Core's
 *   last_sequence_number for existing devices is a small integer
 *   like 5219, whereas now() is ~1.8e12), so the first event
 *   post-upgrade lands well above the largest
 *   (device_key, 'hubitat', sequence_number) row already in Core.
 *   Subsequent events increment by exactly 1 so Core's gap-detection
 *   (handleGapResponse → getEventsFromBuffer) continues to see a
 *   dense, contiguous tail and doesn't explode the backfill set.
 *
 *   A legacy migration floor is also applied: if the old single-counter
 *   atomicState.sequenceNumber (or even older state.sequenceNumber) is
 *   present from a previous version, we advance past it as well so we
 *   never go backwards. The old keys are then cleared so this branch
 *   is a one-time migration.
 *
 * Concurrency: this is not a true compare-and-swap (Hubitat doesn't
 * expose one), so there's a microsecond-wide TOCTOU window. That's
 * vastly narrower than the handler-lifetime window that existed
 * before and is effectively impossible to hit at Hubitat event rates.
 */
private Long reserveSequenceNumber(String deviceKey) {
    // Fallback key. device_key should always be present (state.sfpHvacId),
    // but if it isn't we still want to produce a monotonic sequence
    // without crashing the event path.
    String key = (deviceKey ?: "_unknown")

    // Rebuild the map rather than mutating in place. Hubitat's
    // atomicState has returned both live references and snapshots
    // across versions; an explicit put-and-reassign is the only
    // unambiguous write.
    Map counters = [:]
    Map existing = (atomicState.lastSequenceByDevice as Map)
    if (existing != null) counters.putAll(existing)

    Long current = counters[key] as Long

    Long reserved
    if (current == null) {
        // First call for this device since (re)initialization.
        // Bootstrap above:
        //   - any legacy single-counter (older versions of this app
        //     stored the counter in atomicState.sequenceNumber or
        //     state.sequenceNumber), so upgrades don't go backwards.
        //   - now() in epoch milliseconds, which guarantees we leap
        //     above any small-integer high-water mark Core already
        //     has for this device — the real fix for the reset bug.
        Long legacyAtomic = (atomicState.sequenceNumber as Long) ?: 0L
        Long legacyState  = (state.sequenceNumber as Long) ?: 0L
        Long legacyFloor  = Math.max(legacyAtomic, legacyState)
        Long nowFloor     = now() as Long
        reserved = Math.max(nowFloor, legacyFloor + 1L)

        log.info "📊 Bootstrapping sequence counter for device=${key} at ${reserved} (now=${nowFloor}, legacyFloor=${legacyFloor})"

        // One-time cleanup of legacy single-counter keys. Safe because
        // we've already folded their values into the floor above.
        if (atomicState.sequenceNumber != null) {
            log.info "📊 Clearing legacy atomicState.sequenceNumber=${atomicState.sequenceNumber}"
            atomicState.remove("sequenceNumber")
        }
        if (state.sequenceNumber != null) {
            log.info "📊 Clearing legacy state.sequenceNumber=${state.sequenceNumber}"
            state.remove("sequenceNumber")
        }
    } else {
        reserved = current + 1L
    }

    counters[key] = reserved
    atomicState.lastSequenceByDevice = counters
    return reserved
}

/* ============================== EVENT BUFFER HELPERS ============================== */

/**
 * Debug logging helper
 */
private void logDebug(String msg) {
    if (enableDebugLogging) log.debug msg
}

/**
 * Add event to buffer (keep last 200).
 *
 * After the outbox refactor, _postToCoreWithJwt is the sole caller
 * of this function on the fresh path, and drainOutbox deliberately
 * bypasses it on retries. The sequence_number guard below is a
 * defense-in-depth check: if anything ever re-enters this function
 * with an event whose sequence is already buffered, we skip the
 * duplicate so "buffer depth" stays a meaningful metric.
 */
private void addToEventBuffer(Map event) {
    if (state.eventBuffer == null) {
        state.eventBuffer = []
    }

    def seq = event?.sequence_number
    Long seqLong = (seq != null) ? (seq as Long) : null
    if (seqLong != null && state.eventBuffer.any { (it?.event?.sequence_number as Long) == seqLong }) {
        logDebug "📦 Skipping duplicate buffer entry for sequence ${seqLong}"
        return
    }

    def bufferedEvent = [
        event: event,
        buffered_at: now()
    ]

    state.eventBuffer.add(bufferedEvent)

    // Keep only last 200 events
    if (state.eventBuffer.size() > 200) {
        state.eventBuffer = state.eventBuffer.drop(state.eventBuffer.size() - 200)
    }

    logDebug "📦 Buffered event (sequence ${event.sequence_number}), buffer size: ${state.eventBuffer.size()}"
}

/**
 * Retrieve events from buffer by sequence numbers
 */
private List getEventsFromBuffer(List sequences) {
    if (state.eventBuffer == null || sequences == null || sequences.isEmpty()) {
        return []
    }

    // Coerce to Long throughout — sequence numbers are now bootstrapped
    // at now() (epoch ms), which overflows Integer.
    def seqSet = sequences.collect { it as Long } as Set
    def found = []

    state.eventBuffer.each { buffered ->
        def seq = buffered.event?.sequence_number
        if (seq != null && seqSet.contains(seq as Long)) {
            found.add(buffered.event)
        }
    }

    return found
}

/**
 * Handle gaps reported by Core - resend missing events
 */
private void handleGapResponse(List gaps) {
    gaps.each { gap ->
        def deviceKey = gap.device_key
        def missingSeqs = gap.missing_sequences

        logDebug "🔍 Core reports gap for ${deviceKey}: missing sequences ${missingSeqs}"

        def foundEvents = getEventsFromBuffer(missingSeqs)

        if (foundEvents.isEmpty()) {
            log.warn "⚠️ Could not find ${missingSeqs.size()} missing event(s) in buffer"
            return
        }

        logDebug "✅ Found ${foundEvents.size()} of ${missingSeqs.size()} missing events in buffer"

        resendBufferedEvents(foundEvents)
    }
}

/**
 * Resend buffered events to Core (for gap backfill)
 */
private void resendBufferedEvents(List events) {
    if (events == null || events.isEmpty()) {
        return
    }

    String token = state.sfpCoreToken
    if (!token) {
        log.error "Cannot resend events: no Core token"
        return
    }

    log.info "🔄 Resending ${events.size()} buffered event(s) to fill gap"

    Map params = [
        uri: CORE_INGEST_URL,
        contentType: "application/json",
        requestContentType: "application/json",
        headers: [ Authorization: "Bearer ${token}" ],
        body: events,
        timeout: (settings.httpTimeout ?: DEFAULT_HTTP_TIMEOUT)
    ]

    try {
        httpPostJson(params) { resp ->
            if (resp.status >= 200 && resp.status < 300) {
                log.info "✅ Successfully backfilled ${events.size()} event(s)"
            } else {
                log.error "❌ Backfill failed (${resp.status})"
            }
        }
    } catch (Exception e) {
        log.error "❌ Backfill error: ${e.message}"
    }
}

/* ============================== OUTBOX (RELIABLE DELIVERY) ============================== */

// Bounded retry queue for batches that failed the fresh POST.
//
// Works in tandem with state.eventBuffer:
//   - eventBuffer handles "Core landed it but reports a gap later"
//     via handleGapResponse / resendBufferedEvents.
//   - outbox handles "the POST never landed" — network errors,
//     timeouts, and 5xx from Core.
//
// Entries are full batches (List<Map>) as _doCorePost accepts. Drain
// calls _doCorePost directly and inherits its JWT-refresh path for
// free. Buffering events is _postToCoreWithJwt's responsibility and
// happens ONCE per logical event on the fresh attempt, so drain
// retries don't grow the buffer.

@Field static final Integer OUTBOX_MAX_ENTRIES    = 50
@Field static final Integer OUTBOX_MAX_ATTEMPTS   = 10
@Field static final List    OUTBOX_BACKOFF_SECS   = [30, 60, 120, 300, 600, 1800]

/**
 * Add a failed batch to the outbox.
 *
 * Called from _postToCoreWithJwt when _doCorePost returns a
 * transient result. The event(s) in `batch` are already in
 * state.eventBuffer from _postToCoreWithJwt's addToEventBuffer
 * call, so the gap-backfill path will still work even if this
 * batch eventually hits the attempt cap and gets dropped.
 *
 * Drop-newest overflow policy: sequence_number advances at reserve
 * time, not at POST success. Dropping an older queued entry would
 * leave a used-but-never-sent sequence number that Core's gap
 * detector would chase forever. Dropping the newest keeps the
 * sequence stream's tail contiguous and deterministic: "we stopped
 * at sequence N, everything ≥ N+k is lost."
 *
 * Note: the read-modify-write on atomicState.outbox here is not
 * truly atomic. Two concurrent failures can lose one enqueue. In
 * practice failures are rare enough that this is acceptable;
 * flagged in comments so the next reader doesn't assume otherwise.
 */
private Boolean enqueueOutbox(List batch, String reason) {
    List outbox = (atomicState.outbox ?: []) as List

    if (outbox.size() >= OUTBOX_MAX_ENTRIES) {
        log.warn "⚠️ [outbox] Full (${outbox.size()}/${OUTBOX_MAX_ENTRIES}), dropping incoming batch (reason=${reason}, events=${batch.size()})"
        bumpOutboxDroppedCount()
        return false
    }

    Long nowMs = now()
    outbox << [
        body: batch,
        firstEnqueuedAt: nowMs,
        nextAttemptAt: nowMs + ((OUTBOX_BACKOFF_SECS[0] as Long) * 1000L),
        attemptCount: 1,
        lastReason: reason
    ]
    atomicState.outbox = outbox

    log.warn "📤 [outbox] Enqueued batch (reason=${reason}, events=${batch.size()}, depth=${outbox.size()})"
    return true
}

/**
 * Drain as many outbox entries as possible right now.
 *
 * Called:
 *   - as runIn(0, "drainOutbox") from the 2xx branch of
 *     _postToCoreWithJwt (piggyback on the fresh POST proving the
 *     network is back)
 *   - from runEvery1Minute("drainOutbox") as a safety net
 *   - from initialize() via runIn(30) on reboot if the outbox
 *     survived in atomicState from a previous run
 *
 * Drain rules:
 *   - nextAttemptAt > now()                : defer, keep in outbox
 *   - attemptCount >= OUTBOX_MAX_ATTEMPTS  : drop + bump + CONTINUE
 *   - _doCorePost "ok"                     : remove, continue draining
 *   - _doCorePost "permanent" (4xx)        : drop + bump + CONTINUE
 *   - _doCorePost "transient" (5xx/net)    : reschedule + STOP
 *
 * Per-batch 4xx skip is important: one poisoned batch must not
 * block unrelated batches behind it. But 5xx/network stops the
 * whole drain because it signals "Core is down, don't hammer it."
 */
def drainOutbox() {
    List outbox = (atomicState.outbox ?: []) as List
    if (outbox.isEmpty()) {
        atomicState.outboxLastFlushAt = now()
        atomicState.outboxLastFlushResult = "empty"
        return
    }

    Long nowMs = now()
    List remaining = []
    int delivered = 0
    int deferred  = 0
    int dropped   = 0
    int givenUp   = 0
    boolean stopped = false
    String stopReason = null

    for (int i = 0; i < outbox.size(); i++) {
        def entry = outbox[i] as Map

        if (stopped) {
            // Already hit a transient failure. Append this and all
            // later entries untouched so they get retried next cycle.
            remaining << entry
            continue
        }

        if ((entry.nextAttemptAt as Long) > nowMs) {
            remaining << entry
            deferred++
            continue
        }

        if ((entry.attemptCount as Integer) >= OUTBOX_MAX_ATTEMPTS) {
            log.error "☠️ [outbox] Giving up after ${entry.attemptCount} attempts, events=${(entry.body as List).size()}"
            bumpOutboxDroppedCount()
            givenUp++
            continue
        }

        Map result
        try {
            result = _doCorePost(entry.body as List, false)
        } catch (Exception e) {
            log.error "❌ [outbox] Unexpected throw in _doCorePost: ${e.message}"
            result = [kind: "transient", reason: "uncaught_throw", error: e.message]
        }

        if (result.kind == "ok") {
            delivered++
            // Gap handling is relevant on retry too — Core may detect
            // a gap in the replayed sequence and ask for backfill.
            try {
                if (result.data?.gaps) {
                    handleGapResponse(result.data.gaps as List)
                }
            } catch (Exception ge) {
                logDebug "Could not parse gap response during drain: ${ge.message}"
            }
            continue
        }

        if (result.kind == "permanent") {
            log.error "❌ [outbox] Dropping batch due to permanent error (${result.reason}): ${result.error}"
            bumpOutboxDroppedCount()
            dropped++
            continue
        }

        // Transient: reschedule this entry and stop draining the rest.
        remaining << scheduleRetry(entry, (result.reason as String) ?: "transient")
        stopped = true
        stopReason = (result.reason as String) ?: "transient"
    }

    atomicState.outbox = remaining
    atomicState.outboxLastFlushAt = now()
    atomicState.outboxLastFlushResult = stopped ? "partial" : (remaining.isEmpty() ? "ok" : "partial")

    if (delivered > 0 || givenUp > 0 || dropped > 0 || stopped) {
        log.info "🧹 [outbox] drain: delivered=${delivered}, dropped=${dropped}, givenUp=${givenUp}, deferred=${deferred}, depth=${remaining.size()}${stopped ? ', stopped=' + stopReason : ''}"
    }
}

private Map scheduleRetry(Map entry, String reason) {
    int attempts = ((entry.attemptCount ?: 0) as Integer) + 1
    int idx = Math.min(attempts - 1, OUTBOX_BACKOFF_SECS.size() - 1)
    long base = (OUTBOX_BACKOFF_SECS[idx] as Long) * 1000L
    long jitterMs = (long)(Math.random() * (base * 0.1))   // up to +10%
    return [
        body: entry.body,
        firstEnqueuedAt: entry.firstEnqueuedAt,
        nextAttemptAt: now() + base + jitterMs,
        attemptCount: attempts,
        lastReason: reason
    ]
}

private void bumpOutboxDroppedCount() {
    atomicState.outboxDroppedCount = ((atomicState.outboxDroppedCount ?: 0) as Integer) + 1
    atomicState.outboxLastDropAt = now()
}

private boolean _isHttpClientError(String errMsg) {
    if (!errMsg) return false
    // Hubitat's httpPost throws exceptions with the status code
    // embedded in the message. Match 400-499 excluding 401 (which
    // is handled separately for token-refresh).
    return (errMsg =~ /\b4\d{2}\b/) && !errMsg.contains("401")
}

/* ============================== FILTER RESET ============================== */

def resetNow() {
    if (!state.sfpAccessToken || !state.sfpHvacId) {
        log.warn "❌ Cannot reset filter - not linked to SmartFilterPro"
        return false
    }

    try {
        log.info "🔄 Resetting filter tracking for HVAC: ${state.sfpHvacName ?: state.sfpHvacId}"

        Map respMap
        httpPost([
            uri: BUBBLE_RESET_URL,
            contentType: "application/json",
            requestContentType: "application/json",
            headers: [ Authorization: "Bearer ${state.sfpAccessToken}" ],
            body: [ hvac_id: state.sfpHvacId ],
            timeout: (settings.httpTimeout ?: DEFAULT_HTTP_TIMEOUT)
        ]) { resp -> respMap = resp.data }

        Map body = _bubbleBody(respMap)
        log.info "✅ Filter reset successful: ${body}"

        // Refresh status after reset
        runIn(2, "pollBubbleStatus")

        return true
    } catch (Exception e) {
        log.error "❌ Filter reset failed: ${e.message}"
        return false
    }
}
