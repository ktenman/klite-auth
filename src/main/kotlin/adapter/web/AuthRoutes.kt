package ee.tenman.auth.adapter.web

import ee.tenman.auth.adapter.session.signInAttempt
import ee.tenman.auth.adapter.session.user
import ee.tenman.auth.domain.ReturnPath
import ee.tenman.auth.domain.SignInAttempt
import ee.tenman.auth.usecase.*
import ee.tenman.auth.usecase.FinishSignIn.*
import klite.*
import klite.StatusCode.Companion.OK
import klite.json.JsonMapper
import java.net.URI

private val log = logger("auth")
private val json = JsonMapper()

fun Server.authRoutes(checkHealth: () -> Unit, google: GoogleAccounts, finishSignIn: FinishSignIn, allowed: Set<String>, site: String) {
  val siteOrigin = URI(site).let { "${it.scheme}://${it.authority}" }

  renderers.clear()
  renderers += ErrorPage

  before {
    it.header("Content-Security-Policy", "frame-ancestors 'none'")
    it.header("X-Content-Type-Options", "nosniff")
  }

  context("/") {
    get("actuator/health") {
      checkHealth()
      send(OK, """{"status":"UP"}""", MimeTypes.json)
    }

    get("login") {
      send(OK, Pages.login(query("redirect")), MimeTypes.html)
    }

    get("oauth2/authorization/google") {
      val attempt = SignInAttempt.start(ReturnPath.of(query("redirect")))
      session.signInAttempt = attempt
      redirect(google.signInUrl(attempt))
    }

    get("login/oauth2/code/google") {
      val outcome = finishSignIn(session.signInAttempt, query("state"), query("code"))
      if (outcome != UnknownCallback) session.clear()
      when (outcome) {
        UnknownCallback -> redirect("/login?error")
        is GoogleFailed -> {
          log.warn("Google sign-in failed: ${outcome.cause}".loggable())
          redirect("/login?error")
        }
        is NotAllowed -> throw UnauthorizedException("Not allowed to sign in: ${outcome.email}")
        is SignedIn -> {
          session.user = outcome.user
          redirect(outcome.destination)
        }
      }
    }

    get("validate") {
      val user = allowedUser(allowed)
      header("X-User-Id", user.email)
      send(OK, """{"message":"Session validated successfully"}""", MimeTypes.json)
    }

    post("logout") {
      val crossSite = header("Sec-Fetch-Site")?.let { it != "same-origin" } ?: header("Origin").let { it != null && it != siteOrigin }
      if (crossSite) throw ForbiddenException("Cross-site sign-out")
      session.clear()
      redirect("/")
    }

    for (path in listOf("", "home")) {
      get(path) {
        allowedUser(allowed)
        send(OK, Pages.home, MimeTypes.html)
      }
    }

    get("user") {
      val user = signedInUser()
      val body = if (user.email in allowed) mapOf("status" to "AUTHORIZED", "provider" to "google", "user" to user)
      else mapOf("status" to "UNAUTHORIZED", "message" to "User not authorized")
      send(OK, json.render(body), MimeTypes.json)
    }
  }
}

val requestLog: RequestLogFormatter = { ms ->
  val message = failure?.takeIf { it !is RedirectException }?.let { e -> if (e is StatusCodeException) e.message else e.toString() }
  ("$remoteAddress $method $path: $statusCode in $ms ms - $browser" + message?.let { " - $it" }.orEmpty()).loggable()
}

private val controlChars = Regex("[\\p{Cc}\\u2028\\u2029]")

internal fun String.loggable() = replace(controlChars) { "\\u%04x".format(it.value[0].code) }

private fun HttpExchange.allowedUser(allowed: Set<String>) =
  signedInUser().also { if (it.email !in allowed) throw UnauthorizedException("No longer allowed: ${it.email}") }

private fun HttpExchange.signedInUser() =
  session.user ?: redirect("/login" + header("X-Forwarded-Uri")?.let { "?redirect=" + it.urlEncode() }.orEmpty())
