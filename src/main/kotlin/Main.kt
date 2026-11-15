package ee.tenman.auth

import klite.Config
import klite.MimeTypes
import klite.Server
import klite.StatusCode.Companion.OK
import klite.html.escapeHtml
import redis.clients.jedis.JedisPooled

private val LOGIN = page("login")

fun main() { server().start() }

fun server(redis: JedisPooled = JedisPooled(Config.optional("REDIS_URL", "redis://redis:6379"))) = Server().apply {
  context("/") {
    get("actuator/health") { redis.ping(); send(OK, """{"status":"UP"}""", MimeTypes.json) }

    get("login") {
      val input = query("redirect")?.let { """<input name="redirect" value="${it.escapeHtml()}" type="hidden">""" }
      send(OK, LOGIN.replace("<!--redirect-->", input.orEmpty()), MimeTypes.html)
    }
  }
}

private fun page(name: String) = ClassLoader.getSystemResource("$name.html").readText()
