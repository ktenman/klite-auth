package ee.tenman.auth

import klite.BodyRenderer
import klite.Config
import klite.HttpExchange
import klite.MimeTypes
import klite.Server
import klite.StatusCode
import klite.StatusCode.Companion.OK
import klite.html.escapeHtml
import redis.clients.jedis.JedisPooled

private val LOGIN = page("login")
private val ERROR = page("error")

fun main() { server().start() }

fun server(redis: JedisPooled = JedisPooled(Config.optional("REDIS_URL", "redis://redis:6379"))) = Server().apply {
  renderers.add(0, ErrorPage)
  context("/") {
    get("actuator/health") { redis.ping(); send(OK, """{"status":"UP"}""", MimeTypes.json) }

    get("login") {
      val input = query("redirect")?.let { """<input name="redirect" value="${it.escapeHtml()}" type="hidden">""" }
      send(OK, LOGIN.replace("<!--redirect-->", input.orEmpty()), MimeTypes.html)
    }
  }
}

private object ErrorPage : BodyRenderer {
  override val contentType = MimeTypes.html
  override fun render(e: HttpExchange, code: StatusCode, value: Any?) =
    e.send(code, ERROR.replace("<!--status-->", "<p>${code.reason}</p>\n        <p>${code.value}</p>"), contentType)
}

private fun page(name: String) = ClassLoader.getSystemResource("$name.html").readText()
