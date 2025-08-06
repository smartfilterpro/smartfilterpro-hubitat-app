/**
 *  SmartFilterPro Thermostat Bridge
 *
 *  Copyright 2025 Eric Hanfman
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 *
 */

definition(
    name: "SmartFilterPro Thermostat Bridge",
    namespace: "smartfilterpro",
    author: "Eric Hanfman",
    description: "Sends thermostat state and runtime to SmartFilterPro (Bubble)",
    category: "Convenience",
    iconUrl: "",
    iconX2Url: ""
)

preferences {
    section("Thermostat Tracking Configuration") {
        input "enableDebugLogging", "bool", title: "Enable Debug Logging", defaultValue: true, description: "Turn on detailed logging for troubleshooting"
        input "maxRuntimeHours", "number", title: "Maximum Runtime Hours", defaultValue: 24, range: "1..168", description: "Maximum runtime before session is considered stale (1-168 hours)"
        input "cleanupFrequency", "enum", title: "Cleanup Frequency", defaultValue: "1 Hour", options: ["30 Minutes", "1 Hour", "2 Hours", "6 Hours", "12 Hours"], description: "How often to clean up old sessions"
        input "bubbleEndpoint", "text", title: "Bubble API Endpoint", defaultValue: "https://smartfilterpro-scaling.bubbleapps.io/version-test/api/1.1/wf/hubitat", description: "Full URL to your Bubble API endpoint"
        input "httpTimeout", "number", title: "HTTP Timeout (seconds)", defaultValue: 30, range: "5..120", description: "Timeout for HTTP requests to Bubble"
        input "enableSessionStats", "bool", title: "Enable Session Statistics", defaultValue: false, description: "Log session statistics periodically"
    }
    
    section("Advanced Settings") {
        input "resetAllSessions", "bool", title: "Reset All Sessions on Save", defaultValue: false, description: "WARNING: This will clear all active runtime tracking"
        input "enableRetryLogic", "bool", title: "Enable HTTP Retry Logic", defaultValue: true, description: "Retry failed HTTP requests up to 3 times"
    }
}

def installed() {
    log.info "🚀 SmartFilterPro Thermostat Bridge Installed"
    initialize()
}

def updated() {
    log.info "🔄 SmartFilterPro Thermostat Bridge Updated"
    unschedule()
    
    if (resetAllSessions) {
        resetAllDeviceSessions()
        app.updateSetting("resetAllSessions", false) // Reset the setting
    }
    
    initialize()
}

def initialize() {
    // Initialize session tracking
    if (!state.sessionTrackingInitialized) {
        initializeSessionTracking()
        state.sessionTrackingInitialized = true
    }
    
    // Schedule cleanup based on user preference
    scheduleCleanup()
    
    // Schedule stats logging if enabled
    if (enableSessionStats) {
        runEvery15Minutes(logSessionStats)
    }
    
    log.info "✅ SmartFilterPro Thermostat Bridge Initialized"
}

def sendThermostatData(thermostat, userId, deviceId, thermostatId, tempF, isActive, deviceName, mode, scale) {
    def vendor = thermostat.getDataValue("manufacturer")
    def timestamp = new Date().format("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", location.timeZone)
    def maxRuntimeSeconds = (maxRuntimeHours ?: 24) * 3600

    def key = "${userId}-${deviceId}"
    def sessionKey = "sessionStart_${key}"
    def wasActiveKey = "wasActive_${key}"
    def runtimeSeconds = 0

    // Get previous state
    def wasActive = state[wasActiveKey] ?: false
    def sessionStart = state[sessionKey]

    // Validate session start time if it exists
    if (sessionStart && sessionStart > now()) {
        if (enableDebugLogging) log.error "❌ Invalid session start time in future for ${key}, resetting"
        state.remove(sessionKey)
        sessionStart = null
    }

    if (isActive && !wasActive) {
        // System just turned on - start new session
        state[sessionKey] = now()
        if (enableDebugLogging) log.debug "🟢 Starting new session for ${key}"
        
    } else if (!isActive && wasActive) {
        // System just turned off - calculate runtime
        if (sessionStart) {
            def currentTime = now()
            runtimeSeconds = ((currentTime - sessionStart) / 1000) as int
            
            // Validate runtime (reject if exceeds max runtime setting)
            if (runtimeSeconds > maxRuntimeSeconds) {
                if (enableDebugLogging) log.warn "⚠️ Runtime ${runtimeSeconds}s exceeds maximum ${maxRuntimeSeconds}s for ${key}, resetting"
                runtimeSeconds = 0
            } else if (runtimeSeconds < 0) {
                if (enableDebugLogging) log.error "❌ Negative runtime calculated for ${key}, resetting"
                runtimeSeconds = 0
            }
            
            state.remove(sessionKey)
            if (enableDebugLogging) log.debug "🔴 Session ended for ${key}, runtime: ${runtimeSeconds}s (${Math.round(runtimeSeconds/60)} minutes)"
        } else {
            if (enableDebugLogging) log.warn "⚠️ System turned off but no session start found for ${key}"
        }
        
    } else if (isActive && wasActive && !sessionStart) {
        // System is active but we don't have a start time (likely after restart)
        state[sessionKey] = now()
        if (enableDebugLogging) log.debug "🔄 System was active after restart, restarting session tracking for ${key}"
        
    } else if (isActive && wasActive && sessionStart) {
        // System continues to be active - validate session isn't too old
        def sessionAge = (now() - sessionStart) / 1000
        if (sessionAge > maxRuntimeSeconds) {
            if (enableDebugLogging) log.warn "⚠️ Session for ${key} is ${Math.round(sessionAge/3600)} hours old, restarting"
            state[sessionKey] = now()
        }
    }

    // Update the previous state for next comparison
    state[wasActiveKey] = isActive

    // Prepare payload
    def bubblePayload = [
        userId            : userId,
        thermostatId      : thermostatId,
        currentTemperature: tempF,
        isActive          : isActive,
        deviceName        : deviceName,
        vendor            : vendor,
        thermostatMode    : mode,
        temperatureScale  : scale,
        runtimeSeconds    : runtimeSeconds,
        timestamp         : timestamp
    ]

    def bubbleParams = [
        uri: bubbleEndpoint ?: "https://smartfilterpro-scaling.bubbleapps.io/version-test/api/1.1/wf/hubitat",
        contentType: "application/json",
        requestContentType: "application/json",
        timeout: httpTimeout ?: 30,
        body: bubblePayload
    ]

    if (enableDebugLogging) {
        log.debug "📤 Sending to Bubble for ${key} - Active: ${isActive}, Runtime: ${runtimeSeconds}s"
        log.debug "📤 Payload: ${bubblePayload}"
    }

    sendHttpRequest(bubbleParams, key)
}

// HTTP request with retry logic
def sendHttpRequest(bubbleParams, key, attempt = 1) {
    def maxAttempts = enableRetryLogic ? 3 : 1
    
    try {
        httpPost(bubbleParams) { response ->
            if (enableDebugLogging) log.debug "✅ Posted to Bubble: ${response.status}"
            if (response.status != 200) {
                log.warn "⚠️ Unexpected response status: ${response.status}"
            }
        }
    } catch (groovyx.net.http.HttpResponseException e) {
        log.error "❌ HTTP Error posting to Bubble (attempt ${attempt}/${maxAttempts}): ${e.statusCode} - ${e.message}"
        if (attempt < maxAttempts && e.statusCode >= 500) {
            // Retry on server errors
            runIn(attempt * 2, "retryHttpRequest", [data: [params: bubbleParams, key: key, attempt: attempt + 1]])
        }
    } catch (java.net.SocketTimeoutException e) {
        log.error "❌ Timeout posting to Bubble (attempt ${attempt}/${maxAttempts}): ${e.message}"
        if (attempt < maxAttempts) {
            runIn(attempt * 2, "retryHttpRequest", [data: [params: bubbleParams, key: key, attempt: attempt + 1]])
        }
    } catch (Exception e) {
        log.error "❌ Error posting to Bubble (attempt ${attempt}/${maxAttempts}): ${e.message}"
        if (enableDebugLogging) log.error "❌ Full error: $e"
        if (attempt < maxAttempts) {
            runIn(attempt * 2, "retryHttpRequest", [data: [params: bubbleParams, key: key, attempt: attempt + 1]])
        }
    }
}

// Retry method for HTTP requests
def retryHttpRequest(data) {
    sendHttpRequest(data.params, data.key, data.attempt)
}

// Initialize session tracking - clean up old sessions
def initializeSessionTracking() {
    def maxRuntimeMs = (maxRuntimeHours ?: 24) * 3600000 // Convert to milliseconds
    def cutoff = now() - maxRuntimeMs
    def keysToRemove = []
    def sessionsFound = 0
    def sessionsRemoved = 0
    
    state.each { key, value ->
        if (key.startsWith("sessionStart_")) {
            sessionsFound++
            if (value && value < cutoff) {
                keysToRemove << key
                sessionsRemoved++
            }
        }
    }
    
    keysToRemove.each { 
        state.remove(it) 
        // Also remove corresponding wasActive state
        def deviceKey = it.replace("sessionStart_", "")
        state.remove("wasActive_${deviceKey}")
    }
    
    if (enableDebugLogging) log.debug "🧹 Session cleanup: Found ${sessionsFound}, removed ${sessionsRemoved} old sessions"
    
    // Schedule regular cleanup
    scheduleCleanup()
}

// Schedule cleanup based on user preference
def scheduleCleanup() {
    unschedule(cleanupOldSessions)
    
    def frequency = cleanupFrequency ?: "1 Hour"
    switch (frequency) {
        case "30 Minutes":
            runEvery30Minutes(cleanupOldSessions)
            break
        case "2 Hours":
            schedule("0 0 */2 * * ?", cleanupOldSessions)
            break
        case "6 Hours":
            schedule("0 0 */6 * * ?", cleanupOldSessions)
            break
        case "12 Hours":
            schedule("0 0 */12 * * ?", cleanupOldSessions)
            break
        default: // "1 Hour"
            runEvery1Hour(cleanupOldSessions)
            break
    }
}

// Scheduled cleanup method
def cleanupOldSessions() {
    def maxRuntimeMs = (maxRuntimeHours ?: 24) * 3600000 // Convert to milliseconds
    def cutoff = now() - maxRuntimeMs
    def keysToRemove = []
    def activeSessionsCount = 0
    
    state.each { key, value ->
        if (key.startsWith("sessionStart_")) {
            if (value && value < cutoff) {
                keysToRemove << key
                if (enableDebugLogging) log.debug "🧹 Cleaning up old session: ${key}"
            } else if (value) {
                activeSessionsCount++
            }
        }
    }
    
    keysToRemove.each { 
        state.remove(it)
        // Also remove corresponding wasActive state
        def deviceKey = it.replace("sessionStart_", "")
        state.remove("wasActive_${deviceKey}")
    }
    
    if (keysToRemove.size() > 0) {
        if (enableDebugLogging) log.debug "🧹 Cleaned up ${keysToRemove.size()} old sessions, ${activeSessionsCount} active sessions remain"
    }
}

// Log session statistics
def logSessionStats() {
    def stats = getSessionStats()
    log.info "📊 Session Stats: ${stats.activeSessions} active, ${stats.totalDevices} total devices, oldest: ${stats.oldestSessionMinutes}min"
}

// Reset all device sessions (called from preferences)
def resetAllDeviceSessions() {
    def keysToRemove = []
    
    state.each { key, value ->
        if (key.startsWith("sessionStart_") || key.startsWith("wasActive_")) {
            keysToRemove << key
        }
    }
    
    keysToRemove.each { state.remove(it) }
    
    log.warn "🔄 RESET: Cleared all ${keysToRemove.size()} session tracking entries"
}

// Method to get current runtime for a device (useful for debugging)
def getCurrentRuntime(userId, deviceId) {
    def key = "${userId}-${deviceId}"
    def sessionKey = "sessionStart_${key}"
    def wasActiveKey = "wasActive_${key}"
    
    def isActive = state[wasActiveKey] ?: false
    def sessionStart = state[sessionKey]
    
    if (isActive && sessionStart) {
        def currentRuntime = ((now() - sessionStart) / 1000) as int
        return currentRuntime
    }
    
    return 0
}

// Method to manually reset a device's session (useful for debugging)
def resetDeviceSession(userId, deviceId) {
    def key = "${userId}-${deviceId}"
    def sessionKey = "sessionStart_${key}"
    def wasActiveKey = "wasActive_${key}"
    
    state.remove(sessionKey)
    state.remove(wasActiveKey)
    
    if (enableDebugLogging) log.debug "🔄 Reset session for device: ${key}"
}

// Method to get session statistics (useful for debugging)
def getSessionStats() {
    def activeSessions = 0
    def totalDevices = 0
    def oldestSession = now()
    
    state.each { key, value ->
        if (key.startsWith("sessionStart_") && value) {
            activeSessions++
            if (value < oldestSession) {
                oldestSession = value
            }
        } else if (key.startsWith("wasActive_")) {
            totalDevices++
        }
    }
    
    def oldestSessionAge = activeSessions > 0 ? (now() - oldestSession) / 1000 / 60 : 0 // in minutes
    
    if (enableDebugLogging) log.debug "📊 Session Stats: ${activeSessions} active sessions, ${totalDevices} total devices, oldest session: ${Math.round(oldestSessionAge)} minutes"
    
    return [
        activeSessions: activeSessions,
        totalDevices: totalDevices,
        oldestSessionMinutes: Math.round(oldestSessionAge)
    ]
}