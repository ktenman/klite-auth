package ee.tenman.auth

import ch.tutteli.atrium.api.fluent.en_GB.*
import ch.tutteli.atrium.api.verbs.expect
import klite.json.JsonMapper
import klite.json.parse
import klite.urlEncode
import org.junit.jupiter.api.Test
import redis.clients.jedis.RedisClient

private val withoutMari by lazy { App.start(server(allowed = setOf("jaan.kask@näide.ee"))) }

class AppTest {
  @Test fun `health is up while Redis answers`() {
    val response = get("/actuator/health")
    expect(response.statusCode()).toEqual(200)
    expect(response.body()).toEqual("""{"status":"UP"}""")
  }

  @Test fun `health fails while Redis is unreachable`() {
    val down = App.start(server(RedisClient.create("redis://localhost:1")))
    expect(get("$down/actuator/health").statusCode()).toEqual(500)
  }

  @Test fun `login page keeps the page to return to`() {
    val page = get("/login?redirect=" + "/radio/catch-up?day=2026-10-21".urlEncode()).body()
    expect(page).toContain("""<input name="redirect" value="/radio/catch-up?day=2026-10-21" type="hidden">""")
  }

  @Test fun `login page escapes markup in the page to return to`() {
    val page = get("/login?redirect=" + """"><script>alert('x&y')</script>""".urlEncode()).body()
    expect(page).toContain("""value="&quot;&gt;&lt;script&gt;alert(&#39;x&amp;y&#39;)&lt;/script&gt;" type="hidden">""")
  }

  @Test fun `login page without a page to return to has no hidden input`() {
    expect(get("/login").body()).toContain("Login with Google").notToContain("name=\"redirect\"")
  }

  @Test fun `an unknown page shows the error page`() {
    val response = get("/missing")
    expect(response.statusCode()).toEqual(404)
    expect(response.headers().firstValue("Content-Type")).toBePresent().toContain("text/html")
    expect(response.body()).toContain("<p>Not Found</p>", "<p>404</p>")
  }

  @Test fun `an error shows the error page, whatever the request accepts`() {
    expect(listOf("text/plain", "application/json").map { get("/missing", "Accept", it).body() })
      .toHaveElementsAndAll { toContain("<p>404</p>") }
  }

  @Test fun `a redirect has no body, whatever the request accepts`() {
    expect(listOf("text/plain", "application/json", "text/html").map { get("/validate", "Accept", it).body() })
      .toHaveElementsAndAll { toBeEmpty() }
  }

  @Test fun `signing in returns to the page it started from`() {
    expect(signIn(MARI, "/radio/catch-up?day=2026-10-21").location).toEqual("https://fov.ee/radio/catch-up?day=2026-10-21")
  }

  @Test fun `signing in from no page, or from a page on another site, returns to the home page`() {
    expect(listOf(null, "//evil.example", "https://evil.example", "@evil.example").map { signIn(MARI, it).location })
      .toHaveElementsAndAll { toEqual("https://fov.ee") }
  }

  @Test fun `signing in starts a new session in a cookie for 30 days, sent only over HTTPS and hidden from scripts`() {
    val signedIn = signIn(MARI)
    expect(signedIn.cookie).notToEqual(signedIn.request().headers().firstValue("Cookie").get())
    expect(signedIn.headers().allValues("Set-Cookie"))
      .toContainExactly { toContain("; Max-Age=2592000", "; Path=/", "; HttpOnly", "; Secure", "; SameSite=None") }
  }

  @Test fun `an email not on the list is turned away without a session`() {
    val response = signIn(JURI)
    expect(response.statusCode()).toEqual(401)
    expect(response.headers().allValues("Set-Cookie").map { it.substringBefore(";") }).toContainExactly("AUTHSESSION=")
  }

  @Test fun `a sign-in cancelled at Google returns to the login page`() {
    val start = startSignIn()
    val response = get("/login/oauth2/code/google?error=access_denied&state=${start.location.param("state")}", "Cookie", start.cookie)
    expect(response.location).toEqual("/login?error")
  }

  @Test fun `a sign-in coming back with another state returns to the login page`() {
    val start = startSignIn()
    val code = atGoogle(start, MARI).param("code")
    expect(get("/login/oauth2/code/google?code=$code&state=forged", "Cookie", start.cookie).location).toEqual("/login?error")
  }

  @Test fun `a callback for a sign-in that didn't start here returns to the login page`() {
    val code = atGoogle(startSignIn(), MARI).param("code")
    expect(get("/login/oauth2/code/google?code=$code").location).toEqual("/login?error")
  }

  @Test fun `a code Google turns down returns to the login page`() {
    val start = startSignIn()
    val response = get("/login/oauth2/code/google?code=made-up&state=${start.location.param("state")}", "Cookie", start.cookie)
    expect(response.location).toEqual("/login?error")
  }

  @Test fun `behind Caddy, Google sends the visitor back to the public site`() {
    val caddy = arrayOf("X-Forwarded-Proto", "https", "X-Forwarded-Host", "fov.ee")
    expect(startSignIn(null, *caddy).location.param("redirect_uri")).toEqual("https://fov.ee/login/oauth2/code/google")
    expect(signIn(MARI, "/radio", *caddy).location).toEqual("https://fov.ee/radio")
  }

  @Test fun `validate names the signed-in user to Caddy`() {
    val response = get("/validate", "Cookie", signIn(MARI).cookie, "Accept", "application/json")
    expect(response.statusCode()).toEqual(200)
    expect(response.headers().firstValue("X-User-Id")).toBePresent().toEqual(MARI)
  }

  @Test fun `validate accepts a signed-in session whose request accepts only event streams`() {
    expect(get("/validate", "Cookie", signIn(MARI).cookie, "Accept", "text/event-stream").statusCode()).toEqual(200)
  }

  @Test fun `validate sends a visitor without a session to sign in, with the page they asked for`() {
    val response = get("/validate", "Accept", "application/json", "X-Forwarded-Uri", "/radio/2026-10-22-1200-sihik")
    expect(response.statusCode()).toEqual(302)
    expect(response.location).toEqual("/login?redirect=%2Fradio%2F2026-10-22-1200-sihik")
    expect(response.body()).toBeEmpty()
  }

  @Test fun `validate sends a sign-in still in progress to the login page`() {
    expect(get("/validate", "Cookie", startSignIn().cookie).location).toEqual("/login")
  }

  @Test fun `validate turns away a session whose email has left the list`() {
    val response = get("$withoutMari/validate", "Cookie", signIn(MARI).cookie)
    expect(response.statusCode()).toEqual(401)
    expect(response.headers().firstValue("X-User-Id")).toBeEmpty()
  }

  @Test fun `signing out ends the session and expires its cookie`() {
    val session = signIn(MARI).cookie
    val response = post("/logout", "Cookie", session)
    expect(response.location).toEqual("/")
    expect(response.headers().allValues("Set-Cookie")).toContainExactly { toContain("AUTHSESSION=; Max-Age=0") }
    expect(get("/validate", "Cookie", session).location).toEqual("/login")
  }

  @Test fun `signing out by GET leaves the session signed in`() {
    val session = signIn(MARI).cookie
    expect(get("/logout", "Cookie", session).statusCode()).toEqual(404)
    expect(get("/validate", "Cookie", session).statusCode()).toEqual(200)
  }

  @Test fun `the home page greets a signed-in visitor`() {
    val session = signIn(MARI).cookie
    expect(listOf("/", "/home").map { get(it, "Cookie", session).body() })
      .toHaveElementsAndAll { toContain("Success! You have successfully logged in.") }
  }

  @Test fun `a signed-out visitor is sent to sign in without a session`() {
    expect(listOf("/", "/home", "/user").map { get(it) }).toHaveElementsAndAll {
      its { location }.toEqual("/login")
      its { headers().allValues("Set-Cookie") }.toBeEmpty()
    }
  }

  @Test fun `user info describes the signed-in user`() {
    val user = get("/user", "Cookie", signIn(MARI).cookie)
    expect(JsonMapper().parse<Map<String, Any?>>(user.body())).toEqual(mapOf("status" to "AUTHORIZED", "provider" to "google",
      "user" to mapOf("email" to MARI, "name" to "Mari Tamm", "givenName" to "Mari", "familyName" to "Tamm", "picture" to "https://lh3.googleusercontent.com/a/Mari")))
  }

  @Test fun `user info for a session whose email has left the list says so`() {
    val user = get("$withoutMari/user", "Cookie", signIn(MARI).cookie)
    expect(JsonMapper().parse<Map<String, Any?>>(user.body())).toEqual(mapOf("status" to "UNAUTHORIZED", "message" to "User not authorized"))
  }
}
