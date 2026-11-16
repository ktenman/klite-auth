package ee.tenman.auth.adapter.web

import klite.*
import klite.html.escapeHtml

object Pages {
  private val loginTemplate = load("login")
  private val errorTemplate = load("error")
  val home = load("home")

  fun login(redirect: String?): String {
    val redirectInput = redirect?.let { """<input name="redirect" value="${it.escapeHtml()}" type="hidden">""" }
    return loginTemplate.replace("<!--redirect-->", redirectInput.orEmpty())
  }

  fun error(status: StatusCode) = errorTemplate.replace("<!--status-->", "<p>${status.reason ?: "Error"}</p>\n        <p>${status.value}</p>")

  private fun load(name: String) = ClassLoader.getSystemResource("$name.html").readText()
}

object ErrorPage : BodyRenderer {
  override val contentType = MimeTypes.html

  override fun render(e: HttpExchange, code: StatusCode, value: Any?) =
    if (code.isError) e.send(code, Pages.error(code), contentType) else e.send(code)
}
