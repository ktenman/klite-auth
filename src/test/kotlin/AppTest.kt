package ee.tenman.auth

import redis.clients.jedis.JedisPooled
import kotlin.test.Test
import kotlin.test.assertEquals

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
}
