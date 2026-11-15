package ee.tenman.auth

import klite.urlEncode
import redis.clients.jedis.JedisPooled
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class AppTest {
  @Test fun `health is up while Redis answers`() {
    val response = get("${App.url}/actuator/health")
    assertEquals(200, response.statusCode())
    assertEquals("""{"status":"UP"}""", response.body())
  }

  @Test fun `health fails while Redis is unreachable`() {
    val down = App.start(server(JedisPooled("redis://localhost:1")))
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
}
