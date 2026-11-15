package ee.tenman.auth

import klite.Config
import klite.MimeTypes
import klite.Server
import klite.StatusCode.Companion.OK
import redis.clients.jedis.JedisPooled

fun main() { server().start() }

fun server(redis: JedisPooled = JedisPooled(Config.optional("REDIS_URL", "redis://redis:6379"))) = Server().apply {
  context("/") {
    get("actuator/health") { redis.ping(); send(OK, """{"status":"UP"}""", MimeTypes.json) }
  }
}
