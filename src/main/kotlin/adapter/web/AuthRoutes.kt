package ee.tenman.auth.adapter.web

import ee.tenman.auth.adapter.session.signInAttempt
import ee.tenman.auth.adapter.session.user
import ee.tenman.auth.domain.ReturnPath
import ee.tenman.auth.usecase.*
import ee.tenman.auth.usecase.FinishSignIn.*
import klite.*
import klite.StatusCode.Companion.OK
import klite.json.JsonMapper

private val log = logger("auth")
private val json = JsonMapper()

fun Server.authRoutes(checkHealth: () -> Unit, startSignIn: StartSignIn, finishSignIn: FinishSignIn, allowed: Set<String>) {
  renderers.clear()
  renderers += ErrorPage

  context("/") {
    get("actuator/health") {
      checkHealth()
      send(OK, """{"status":"UP"}""", MimeTypes.json)
    }

    get("login") {
      send(OK, Pages.login(query("redirect")), MimeTypes.html)
    }

    get("oauth2/authorization/google") {
      val started = startSignIn(ReturnPath.of(query("redirect")), callbackUrl())
      session.signInAttempt = started.attempt
      redirect(started.googleUrl)
    }

    get("login/oauth2/code/google") {
      val outcome = finishSignIn(session.signInAttempt, query("state"), query("code"), callbackUrl())
      if (outcome != UnknownCallback) session.clear()
      when (outcome) {
        UnknownCallback -> redirect("/login?error")
        is GoogleFailed -> {
          log.warn("Google sign-in failed: ${outcome.cause}")
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
      val user = signedInUser()
      if (user.email !in allowed) throw UnauthorizedException("No longer allowed: ${user.email}")
      header("X-User-Id", user.email)
      send(OK, """{"message":"Session validated successfully"}""", MimeTypes.json)
    }

    post("logout") {
      session.clear()
      redirect("/")
    }

    for (path in listOf("", "home")) {
      get(path) {
        signedInUser()
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

private fun HttpExchange.callbackUrl() = fullUrl("/login/oauth2/code/google").toString()

private fun HttpExchange.signedInUser() =
  session.user ?: redirect("/login" + header("X-Forwarded-Uri")?.let { "?redirect=" + it.urlEncode() }.orEmpty())
