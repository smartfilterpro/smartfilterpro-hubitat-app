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

@Field static final String  APP_VERSION = "1.0.0"
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
            input "loginEmail", "email", title: "Email", required: true
            input "loginPassword", "password", title: "Password", required: true
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

    dynamicPage(name: "statusPage", title: "Last Bubble Status (Polled)", install: false, uninstall: false) {
        section("Event Buffer") {
            paragraph "Buffered Events: ${bufferSize}/200"
            paragraph "Current Sequence: ${state.sequenceNumber ?: 0}"
            if (bufferSize > 0) {
                paragraph "Sequence Range: ${oldestSeq} - ${newestSeq}"
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
        // Method 1: Check device health status (if available)
        def healthStatus = dev.getStatus()
        if (healthStatus && healthStatus.toLowerCase() == "offline") {
            if (enableDebugLogging) log.debug "Device ${dev.displayName} is offline"
            return false
        }

        // Method 2: Check if device has recent activity (within last 2 hours)
        def lastActivity = dev.getLastActivity()
        if (lastActivity) {
            long lastActivityMs = lastActivity.time
            long twoHoursAgo = now() - (2 * 60 * 60 * 1000)
            if (lastActivityMs < twoHoursAgo) {
                if (enableDebugLogging) log.debug "Device ${dev.displayName} hasn't reported in 2+ hours"
                return false
            }
        }
        
        // Method 3: Check if critical attributes are null (might indicate offline)
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

    log.info "📊 Current Temperature: ${temp}"
    log.info "📊 Operating State: ${opState}"
    log.info "📊 Fan Mode: ${fanMode}"
    log.info "📊 Health Status: ${healthStatus}"
    log.info "📊 Last Activity: ${lastActivity}"

    // 3. Check what checkDeviceReachable returns
    boolean isReachable = checkDeviceReachable(settings.thermostat)
    log.info "🔍 checkDeviceReachable() returns: ${isReachable}"

    // 4. Check individual reachability conditions
    log.info "--- Reachability Checks ---"

    // Check 1: Health status
    if (healthStatus) {
        log.info "   Health status: '${healthStatus}' (offline check: ${healthStatus.toLowerCase() == 'offline'})"
    } else {
        log.info "   Health status: null (skipping check)"
    }

    // Check 2: Last activity time
    if (lastActivity) {
        long lastActivityMs = lastActivity.time
        long twoHoursAgo = now() - (2 * 60 * 60 * 1000)
        long minutesAgo = (now() - lastActivityMs) / 60000
        boolean tooOld = (lastActivityMs < twoHoursAgo)
        log.info "   Last activity: ${minutesAgo} minutes ago (>2hr check: ${tooOld})"
    } else {
        log.info "   Last activity: null (skipping check)"
    }

    // Check 3: Null attributes
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

        app.updateSetting("loginPassword", [type:"password", value:""])
        _html("Login complete. Close this tab and return to the app.")
    } catch (e) {
        log.error "Login error: ${e}"
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
    // Initialize sequence counter for gap detection
    if (state.sequenceNumber == null) {
        state.sequenceNumber = 0
        log.info "Initialized sequence counter to 0"
    }
    // Initialize event buffer for backfill
    if (state.eventBuffer == null) {
        state.eventBuffer = []
        log.info "Initialized event buffer"
    }
    initialize()
}

def updated()  {
    log.info "SmartFilterPro Bridge updated"
    // Initialize sequence counter if not present
    if (state.sequenceNumber == null) {
        state.sequenceNumber = 0
        log.info "Initialized sequence counter to 0"
    }
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
        Map payload = buildCoreEventFromDevice(dev, "Mode_Change", null, equipmentStatus, isActive)
        payload.previous_status = lastEquipmentStatus
        state.sfpLastCorePayload = payload

        _postToCoreWithJwt(payload)
        return
    }

    // Handle environmental telemetry updates (no state change)
    if (!isStateChangingEvent && !equipmentModeChanged) {
        if (enableDebugLogging) log.debug "📊 Telemetry update (environmental change)"

        // ✅ Use "Telemetry_Update" for event_type, but current state for equipment_status
        Map payload = buildCoreEventFromDevice(dev, "Telemetry_Update", null, equipmentStatus, isActive)
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

        Map payload = buildCoreEventFromDevice(dev, "Mode_Change", null, equipmentStatus, true)
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

        Map payload = buildCoreEventFromDevice(dev, "Mode_Change", runtimeSeconds, equipmentStatus, false)
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

        Map payload = buildCoreEventFromDevice(dev, "Mode_Change", runtimeSeconds, equipmentStatus, true)
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

        Map payload = buildCoreEventFromDevice(dev, "Mode_Change", null, equipmentStatus, true)
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

        Map payload = buildCoreEventFromDevice(dev, "Mode_Change", null, equipmentStatus, false)
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
        sequence_number: getNextSequenceNumber(),
        timestamp: ts,
        recorded_at: ts,
        observed_at: ts,
        payload_raw: payloadRaw
    ]
}

/* ============================== CORE POST / TOKEN ============================== */

private boolean _postToCoreWithJwt(Object body) {
    if (enableDebugLogging) log.debug "🚀 _postToCoreWithJwt called"

    if (!_ensureCoreTokenValid()) {
        log.warn "❌ No valid core_token available; skipping Core post"
        return false
    }

    if (enableDebugLogging) log.debug "✅ Core token validated, proceeding with POST"
    return _postToCoreAttempt(body, false)
}

private boolean _postToCoreAttempt(Object body, boolean isRetry) {
    String token = state.sfpCoreToken ?: ""
    Integer timeoutSec = (settings.httpTimeout ?: DEFAULT_HTTP_TIMEOUT) as Integer

    if (isRetry) log.info "🔄 RETRY: Attempting Core post with refreshed token..."

    def requestBody = body instanceof List ? body : [body]

    Map params = [
        uri: CORE_INGEST_URL,
        contentType: "application/json",
        requestContentType: "application/json",
        timeout: timeoutSec,
        headers: [ Authorization: "Bearer ${token}" ],
        body: requestBody
    ]

    // Buffer events before posting (for potential gap backfill) - only on first attempt
    if (!isRetry) {
        requestBody.each { evt ->
            addToEventBuffer(evt)
        }
    }

    // Debug: Log outgoing request details
    if (enableDebugLogging) {
        log.debug "📤 POST to Core: ${CORE_INGEST_URL}"
        log.debug "📤 Token present: ${token ? 'yes (' + token.take(20) + '...)' : 'NO TOKEN'}"
        log.debug "📤 Timeout: ${timeoutSec}s"
        log.debug "📤 Body (${requestBody.size()} events):"
        requestBody.each { evt ->
            log.debug "   → device_key: ${evt.device_key}, event_type: ${evt.event_type}, equipment_status: ${evt.equipment_status}, runtime_seconds: ${evt.runtime_seconds}"
        }
    }

    boolean success = false
    try {
        httpPost(params) { resp ->
            if (enableDebugLogging) {
                log.debug "📥 Core response status: ${resp.status}"
                log.debug "📥 Core response data: ${resp.data}"
            }
            if (resp.status >= 200 && resp.status < 300) {
                log.info "✅ Core POST OK (${resp.status}) - ${requestBody.size()} event(s) sent"

                // Increment sequence number after successful post
                incrementSequenceNumber()

                // Check for gaps in response
                try {
                    def jsonResp = resp.data
                    if (jsonResp?.gaps) {
                        logDebug "⚠️ Core detected ${jsonResp.gaps.size()} gap(s)"
                        handleGapResponse(jsonResp.gaps)
                    }
                } catch (Exception ge) {
                    logDebug "Could not parse gap response: ${ge.message}"
                }

                if (isRetry) log.info "✅ RETRY SUCCESSFUL!"
                success = true
            } else {
                log.warn "⚠️ Core returned non-OK status: ${resp.status}, data: ${resp.data}"
                success = false
            }
        }
        return success
    } catch (Exception e) {
        log.error "❌ Core post exception: ${e.message}"
        log.error "❌ Exception details: ${e}"

        String errMsg = e.toString()
        boolean is401 = errMsg.contains("401") || errMsg.toLowerCase().contains("unauthorized")

        if (is401 && !isRetry) {
            log.warn "⚠️ Core 401 — refreshing token and retrying"
            if (_issueCoreTokenOrLog()) {
                return _postToCoreAttempt(body, true)
            }
        }
        return false
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
 * Get the next sequence number for event posting
 * Returns the current sequence number (will be incremented after successful post)
 */
private Integer getNextSequenceNumber() {
    if (state.sequenceNumber == null) {
        state.sequenceNumber = 0
    }
    return state.sequenceNumber
}

/**
 * Increment the sequence number after successful event post
 * Called only after Core returns 200/201
 */
private void incrementSequenceNumber() {
    def current = state.sequenceNumber ?: 0
    state.sequenceNumber = current + 1
    logDebug "📊 Incremented sequence number: ${current} → ${state.sequenceNumber}"
}

/* ============================== EVENT BUFFER HELPERS ============================== */

/**
 * Debug logging helper
 */
private void logDebug(String msg) {
    if (enableDebugLogging) log.debug msg
}

/**
 * Add event to buffer (keep last 200)
 */
private void addToEventBuffer(Map event) {
    if (state.eventBuffer == null) {
        state.eventBuffer = []
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

    def seqSet = sequences.collect { it as Integer } as Set
    def found = []

    state.eventBuffer.each { buffered ->
        def seq = buffered.event?.sequence_number
        if (seq != null && seqSet.contains(seq as Integer)) {
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
