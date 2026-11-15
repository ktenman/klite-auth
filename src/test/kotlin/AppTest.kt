package ee.tenman.auth

import klite.json.JsonMapper
import klite.json.parse
import klite.urlEncode
import redis.clients.jedis.RedisClient
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AppTest {
  @Test fun `health is up while Redis answers`() {
    val response = get("${App.url}/actuator/health")
    assertEquals(200, response.statusCode())
    assertEquals("""{"status":"UP"}""", response.body())
  }

  @Test fun `health fails while Redis is unreachable`() {
    val down = App.start(server(RedisClient.create("redis://localhost:1")))
    assertEquals(500, get("$down/actuator/health").statusCode())
  }

  @Test fun `login page keeps the page to return to`() {
    val page = get("${App.url}/login?redirect=" + "/radio/catch-up?day=2026-10-21".urlEncode()).body()
    assertContains(page, """<input name="redirect" value="/radio/catch-up?day=2026-10-21" type="hidden">""")
  }

  @Test fun `login page escapes markup in the page to return to`() {
    val page = get("${App.url}/login?redirect=" + """"><script>alert('x&y')</script>""".urlEncode()).body()
    assertContains(page, """value="&quot;&gt;&lt;script&gt;alert(&#39;x&amp;y&#39;)&lt;/script&gt;" type="hidden">""")
  }

  @Test fun `login page without a page to return to has no hidden input`() {
    val page = get("${App.url}/login").body()
    assertContains(page, "Login with Google")
    assertFalse("name=\"redirect\"" in page)
  }

  @Test fun `an unknown page shows the error page`() {
    val response = get("${App.url}/missing")
    assertEquals(404, response.statusCode())
    assertContains(response.headers().firstValue("Content-Type").get(), "text/html")
    assertContains(response.body(), "<p>Not Found</p>")
    assertContains(response.body(), "<p>404</p>")
  }

  @Test fun `signing in returns to the page it started from`() {
    assertEquals("https://fov.ee/radio/catch-up?day=2026-10-21", signIn(MARI, "/radio/catch-up?day=2026-10-21").location)
  }

  @Test fun `signing in from no page, or from a page on another site, returns to the home page`() {
    for (page in listOf(null, "//evil.example", "https://evil.example", "@evil.example"))
      assertEquals("https://fov.ee", signIn(MARI, page).location, "from $page")
  }

  @Test fun `signing in starts a new session in a cookie for 30 days, sent only over HTTPS and hidden from scripts`() {
    val signedIn = signIn(MARI)
    assertNotEquals(signedIn.request().headers().firstValue("Cookie").get(), signedIn.cookie)
    val cookie = signedIn.headers().allValues("Set-Cookie").single()
    for (attribute in listOf("Max-Age=2592000", "Path=/", "HttpOnly", "Secure", "SameSite=None")) assertContains(cookie, "; $attribute")
  }

  @Test fun `an email not on the list is turned away without a session`() {
    val response = signIn(JURI)
    assertEquals(401, response.statusCode())
    assertEquals(listOf("AUTHSESSION="), response.headers().allValues("Set-Cookie").map { it.substringBefore(";") }) // only expires the pending one
  }

  @Test fun `a sign-in cancelled at Google returns to the login page`() {
    val start = startSignIn()
    val response = get("${App.url}/login/oauth2/code/google?error=access_denied&state=${start.location.param("state")}", "Cookie", start.cookie)
    assertEquals("/login?error", response.location)
  }

  @Test fun `a sign-in coming back with another state returns to the login page`() {
    val start = startSignIn()
    val code = atGoogle(start, MARI).param("code")
    assertEquals("/login?error", get("${App.url}/login/oauth2/code/google?code=$code&state=forged", "Cookie", start.cookie).location)
  }

  @Test fun `a callback for a sign-in that didn't start here returns to the login page`() {
    val code = atGoogle(startSignIn(), MARI).param("code")
    assertEquals("/login?error", get("${App.url}/login/oauth2/code/google?code=$code").location)
  }

  @Test fun `a code Google turns down returns to the login page`() {
    val start = startSignIn()
    val response = get("${App.url}/login/oauth2/code/google?code=made-up&state=${start.location.param("state")}", "Cookie", start.cookie)
    assertEquals("/login?error", response.location)
  }

  @Test fun `behind Caddy, Google sends the visitor back to the public site`() {
    val caddy = arrayOf("X-Forwarded-Proto", "https", "X-Forwarded-Host", "fov.ee")
    assertEquals("https://fov.ee/login/oauth2/code/google", startSignIn(null, *caddy).location.param("redirect_uri"))
    assertEquals("https://fov.ee/radio", signIn(MARI, "/radio", *caddy).location)
  }

  @Test fun `validate names the signed-in user to Caddy`() {
    val response = get("${App.url}/validate", "Cookie", signIn(MARI).cookie, "Accept", "application/json")
    assertEquals(200, response.statusCode())
    assertEquals(MARI, response.headers().firstValue("X-User-Id").get())
  }

  @Test fun `validate accepts a signed-in session whose request accepts only event streams`() {
    assertEquals(200, get("${App.url}/validate", "Cookie", signIn(MARI).cookie, "Accept", "text/event-stream").statusCode())
  }

  @Test fun `validate sends a visitor without a session to sign in, with the page they asked for`() {
    val response = get("${App.url}/validate", "Accept", "application/json", "X-Forwarded-Uri", "/radio/2026-10-22-1200-sihik")
    assertEquals(302, response.statusCode())
    assertEquals("/login?redirect=%2Fradio%2F2026-10-22-1200-sihik", response.location)
    assertEquals("", response.body())
  }

  @Test fun `validate sends a sign-in still in progress to the login page`() {
    assertEquals("/login", get("${App.url}/validate", "Cookie", startSignIn().cookie).location)
  }

  @Test fun `validate turns away a session whose email has left the list`() {
    val session = signIn(MARI).cookie
    val response = get(App.start(server(allowed = setOf("jaan.kask@näide.ee"))) + "/validate", "Cookie", session)
    assertEquals(401, response.statusCode())
    assertFalse(response.headers().firstValue("X-User-Id").isPresent)
  }

  @Test fun `signing out ends the session and expires its cookie`() {
    val session = signIn(MARI).cookie
    val response = post("${App.url}/logout", "Cookie", session)
    assertEquals("/", response.location)
    assertContains(response.headers().allValues("Set-Cookie").single(), "AUTHSESSION=; Max-Age=0")
    assertEquals("/login", get("${App.url}/validate", "Cookie", session).location)
  }

  @Test fun `signing out by GET leaves the session signed in`() {
    val session = signIn(MARI).cookie
    assertEquals(404, get("${App.url}/logout", "Cookie", session).statusCode())
    assertEquals(200, get("${App.url}/validate", "Cookie", session).statusCode())
  }

  @Test fun `the home page greets a signed-in visitor`() {
    val session = signIn(MARI).cookie
    for (path in listOf("/", "/home"))
      assertContains(get("${App.url}$path", "Cookie", session).body(), "Success! You have successfully logged in.", message = path)
  }

  @Test fun `a signed-out visitor is sent to sign in without a session`() {
    for (path in listOf("/", "/home", "/user")) {
      val response = get("${App.url}$path")
      assertEquals("/login", response.location, path)
      assertTrue(response.headers().allValues("Set-Cookie").isEmpty(), path)
    }
  }

  @Test fun `user info describes the signed-in user`() {
    val user = get("${App.url}/user", "Cookie", signIn(MARI).cookie)
    assertEquals(mapOf("status" to "AUTHORIZED", "provider" to "google", "user" to mapOf("email" to MARI, "name" to "Mari Tamm",
      "givenName" to "Mari", "familyName" to "Tamm", "picture" to "https://lh3.googleusercontent.com/a/Mari")), JsonMapper().parse<Map<String, Any?>>(user.body()))
  }

  @Test fun `user info for a session whose email has left the list says so`() {
    val session = signIn(MARI).cookie
    val user = get(App.start(server(allowed = setOf("jaan.kask@näide.ee"))) + "/user", "Cookie", session)
    assertEquals(mapOf("status" to "UNAUTHORIZED", "message" to "User not authorized"), JsonMapper().parse<Map<String, Any?>>(user.body()))
  }
}
