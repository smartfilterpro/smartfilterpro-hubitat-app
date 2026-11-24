/**
 * "Sign in with SmartFilterPro" (OAuth + PKCE) tests for
 * SmartFilterProHubitatApp.groovy, run through tests/HubitatHarness.groovy.
 *
 *   tests/run_tests.sh
 */
import java.security.MessageDigest

import static HubitatHarness.t

String appPath = args ? args[0] : "SmartFilterProHubitatApp.groovy"

int passed = 0
List failures = []

def test = { String name, Closure body ->
    try {
        body.call()
        passed++
        println "  PASS  ${name}"
    } catch (Throwable e) {
        failures << name
        println "  FAIL  ${name}: ${e.class.simpleName} ${e.message?.readLines()?.take(6)?.join(' | ')}"
        if (System.getenv("HARNESS_TRACE")) e.printStackTrace()
    }
}

def check = { boolean cond, String msg -> if (!cond) throw new AssertionError(msg) }
def harness = { new HubitatHarness(appPath) }

/** The query parameters of a URL, decoded. */
def query = { String url ->
    Map out = [:]
    url.substring(url.indexOf('?') + 1).split('&').each { String kv ->
        int i = kv.indexOf('=')
        out[URLDecoder.decode(kv.substring(0, i), 'UTF-8')] = URLDecoder.decode(kv.substring(i + 1), 'UTF-8')
    }
    out
}

/** What SmartFilterPro's hub_token does with PKCE: base64url(sha256(verifier)). */
def s256 = { String v ->
    MessageDigest.getInstance("SHA-256").digest(v.getBytes("UTF-8")).encodeBase64().toString()
        .replace('+', '-').replace('/', '_').replaceAll('=+$', '')
}

/** Start a sign-in on a fresh (unlinked) hub; returns the hub_connect URL. */
def startSignIn = { HubitatHarness h ->
    String url = null
    h.exec { url = h.app._oauthAuthorizeUrl() }
    url
}

/** hub_token as Bubble runs it: checks the code and the PKCE verifier. */
def bubbleHubToken = { String expectedChallenge, Map tokens ->
    return { Map req ->
        if (req.code != "code-1" || s256(req.code_verifier as String) != expectedChallenge) return [error: "invalid_grant"]
        tokens
    }
}

Map twoThermostats = [access_token: "access-oauth", refresh_token: "refresh-oauth", expires_in: 3600,
                      user_id: "user-apple", hvac_id: ["hv-1", "hv-2"], hvac_name: ["Upstairs", "Downstairs"],
                      core_token: "core-oauth", core_token_expires_at: 1795000000]

println "\n(a) PKCE"
test("the S256 challenge matches the RFC 7636 example") {
    def h = harness()
    String c = null
    h.exec { c = h.app._pkceChallenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk") }
    check(c == "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", "got ${c}")
}

println "\n(b) the hub_connect link"
test("asks SmartFilterPro to send the code back through Hubitat's cloud to this app") {
    def h = harness()
    h.clock = t("2026-11-24 10:00:00")
    String url = startSignIn(h)
    Map q = query(url)
    check(url.startsWith("https://smartfilterpro.com/hub_connect?"), url)
    check(q.client == "hubitat", "client ${q.client}")
    check(q.redirect_uri == "https://cloud.hubitat.com/oauth/stateredirect", "redirect_uri ${q.redirect_uri}")
    check(q.state == "hub-1/apps/7/oauth/callback?access_token=app-token-1", "state ${q.state}")
    check(q.code_challenge_method == "S256", "method ${q.code_challenge_method}")
    String verifier = h.stateStore.sfpOauthPending.verifier
    check(verifier.length() >= 43 && verifier.length() <= 128 && verifier ==~ /[A-Za-z0-9\-._~]+/, "verifier shape")
    check(q.code_challenge == s256(verifier), "challenge is S256 of the stored verifier")
    check(!url.contains(verifier), "the verifier never leaves the hub in the link")
}

test("the link stays the same while the page re-renders, and changes with the environment or after 15 minutes") {
    def h = harness()
    h.clock = t("2026-11-24 10:00:00")
    String first = startSignIn(h)
    h.clock += 5 * 60000L
    check(startSignIn(h) == first, "same link on re-render")
    h.settings.useTestEnvironment = true
    String testUrl = startSignIn(h)
    check(testUrl.startsWith("https://smartfilterpro.com/version-test/hub_connect?"), testUrl)
    check(query(testUrl).code_challenge != query(first).code_challenge, "new verifier for the test environment")
    h.clock += 16 * 60000L
    check(query(startSignIn(h)).code_challenge != query(testUrl).code_challenge, "new verifier after it expired")
}

println "\n(c) the callback"
test("swaps the code at hub_token with the verifier and stores this hub's tokens") {
    def h = harness()
    h.clock = t("2026-11-24 10:00:00")
    h.settings.loginPassword = "old-password"
    Map q = query(startSignIn(h))
    h.hubTokenAnswer = bubbleHubToken(q.code_challenge, twoThermostats)
    h.clock += 60000L
    h.web("oauthCallback", [code: "code-1", state: q.state])

    check(h.hubTokenRequests.size() == 1, "one hub_token call")
    Map req = h.hubTokenRequests[0]
    check(req.uri == "https://smartfilterpro.com/api/1.1/wf/hub_token", req.uri)
    check(req.body.redirect_uri == "https://cloud.hubitat.com/oauth/stateredirect" && req.body.client == "hubitat", "${req.body}")
    def s = h.stateStore
    check(s.sfpAccessToken == "access-oauth" && s.sfpRefreshToken == "refresh-oauth" && s.sfpUserId == "user-apple", "tokens stored")
    check(s.sfpCoreToken == "core-oauth" && s.sfpCoreTokenExp == 1795000000L, "core token and its expiry (core_token_expires_at)")
    check(s.sfpHvacChoices == ["hv-1": "Upstairs", "hv-2": "Downstairs"], "thermostat choices ${s.sfpHvacChoices}")
    check(s.sfpHvacId == null, "two thermostats: the user picks one")
    check(s.sfpOauthPending == null, "pending sign-in cleared")
    check(!h.settings.containsKey("loginPassword"), "any saved password is removed")
    check(h.rendered.last().data.contains("Signed in") && h.rendered.last().data.contains("choose which thermostat"), "${h.rendered.last().data}")
    check(!h.log.lines.any { it.toString().contains("access-oauth") || it.toString().contains("code-1") }, "tokens and the code are never logged")
}

test("one thermostat is selected automatically; a re-link to another account drops the old choice") {
    def h = harness()
    h.clock = t("2026-11-24 10:00:00")
    h.stateStore.sfpHvacId = "old-thermostat"
    h.stateStore.sfpHvacName = "Old"
    Map q = query(startSignIn(h))
    h.hubTokenAnswer = bubbleHubToken(q.code_challenge, twoThermostats + [hvac_id: "hv-9", hvac_name: "Hall"])
    h.web("oauthCallback", [code: "code-1", state: q.state])
    check(h.stateStore.sfpHvacId == "hv-9" && h.stateStore.sfpHvacName == "Hall", "auto-selected ${h.stateStore.sfpHvacId}")
}

test("an expired sign-in is cleared by signing in again") {
    def h = harness()
    h.install(t("2026-11-24 09:00:00"))
    h.stateStore.sfpAuthBrokenSince = t("2026-11-24 08:00:00")
    h.clock = t("2026-11-24 10:00:00")
    Map q = query(startSignIn(h))
    h.hubTokenAnswer = bubbleHubToken(q.code_challenge, twoThermostats + [hvac_id: ["e2e-hubitat"], hvac_name: ["Hall"]])
    h.web("oauthCallback", [code: "code-1", state: q.state])
    check(h.stateStore.sfpAuthBrokenSince == null, "no longer marked expired")
    check(h.stateStore.sfpHvacId == "e2e-hubitat", "same thermostat kept")
}

test("a code SmartFilterPro refuses stores nothing and the next tap starts over") {
    def h = harness()
    h.clock = t("2026-11-24 10:00:00")
    Map q = query(startSignIn(h))
    h.hubTokenAnswer = { Map req -> [error: "invalid_grant"] }
    h.web("oauthCallback", [code: "code-1", state: q.state])
    check(h.stateStore.sfpAccessToken == null, "no tokens stored")
    check(h.stateStore.lastLoginError?.contains("invalid_grant"), "error shown on the page: ${h.stateStore.lastLoginError}")
    check(h.stateStore.sfpOauthPending == null, "spent verifier cleared")
    check(query(startSignIn(h)).code_challenge != q.code_challenge, "a new verifier for the next try")
}

test("a callback with no sign-in in progress (or an expired one) never calls SmartFilterPro") {
    def h = harness()
    h.clock = t("2026-11-24 10:00:00")
    h.hubTokenAnswer = { Map req -> twoThermostats }
    h.web("oauthCallback", [code: "code-1"])
    Map q = query(startSignIn(h))
    h.clock += 16 * 60000L
    h.web("oauthCallback", [code: "code-1", state: q.state])
    check(h.hubTokenRequests.isEmpty(), "hub_token not called")
    check(h.stateStore.sfpAccessToken == null, "nothing stored")
    check(h.rendered.last().data.contains("expired"), "${h.rendered.last().data}")
}

test("cancelling on smartfilterpro.com is reported, not treated as an error to retry") {
    def h = harness()
    h.clock = t("2026-11-24 10:00:00")
    Map q = query(startSignIn(h))
    h.hubTokenAnswer = { Map req -> twoThermostats }
    h.web("oauthCallback", [error: "access_denied", state: q.state])
    check(h.hubTokenRequests.isEmpty(), "hub_token not called")
    check(h.rendered.last().data.contains("cancelled"), "${h.rendered.last().data}")
}

test("the email + password sign-in still works and reads expires_at as the Core token expiry") {
    def h = harness()
    h.clock = t("2026-11-24 10:00:00")
    h.settings.loginEmail = "someone@example.com"
    h.settings.loginPassword = "pw"
    h.hubTokenAnswer = null
    def orig = h.&httpPost
    h.metaClass.httpPost = { Map params, Closure cb ->
        if ((params.uri as String).endsWith("/hubitat_password")) {
            cb.call([status: 200, data: [response: [access_token: "a", refresh_token: "r", user_id: "u", core_token: "c", expires_at: 1795000123, hvac_id: "hv-1", hvac_name: "Hall"]]])
        } else orig.call(params, cb)
    }
    h.web("cloudLogin", [:])
    check(h.stateStore.sfpAccessToken == "a" && h.stateStore.sfpCoreTokenExp == 1795000123L, "stored")
    check(h.stateStore.sfpHvacId == "hv-1", "single thermostat selected")
    check(!h.settings.containsKey("loginPassword"), "password removed")
}

println "\n${passed} passed, ${failures.size()} failed"
if (failures) {
    failures.each { println "  - ${it}" }
    System.exit(1)
}
