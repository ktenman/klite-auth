package ee.tenman.auth.adapter.session

import ee.tenman.auth.domain.*
import klite.Session
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private val attemptLifetime = 10.minutes
private val userLifetime = 30.days
private fun now() = Instant.now().epochSecond

var Session.signInAttempt: SignInAttempt?
  get() {
    val state = this["state"] ?: return null
    val codeVerifier = this["verifier"] ?: return null
    val started = this["started"]?.toLongOrNull() ?: return null
    if (now() - started >= attemptLifetime.inWholeSeconds) return null
    return SignInAttempt(state, codeVerifier, ReturnPath.of(this["page"]))
  }
  set(attempt) {
    this["state"] = attempt?.state
    this["verifier"] = attempt?.codeVerifier
    this["page"] = attempt?.returnPath?.path
    this["started"] = attempt?.let { now() }
  }

var Session.user: User?
  get() {
    val email = this["email"] ?: return null
    return User(email, this["name"].orEmpty(), this["given_name"].orEmpty(), this["family_name"].orEmpty(), this["picture"].orEmpty())
  }
  set(user) {
    this["email"] = user?.email
    this["name"] = user?.name
    this["given_name"] = user?.givenName
    this["family_name"] = user?.familyName
    this["picture"] = user?.picture
    this["expires"] = user?.let { now() + userLifetime.inWholeSeconds }
  }

// Each expiry counts from the sign-in or attempt that set it, so saving the session again never extends it
val Session.lifetime: Duration
  get() = when {
    params.isEmpty() -> Duration.ZERO
    user == null -> attemptLifetime
    else -> ((this["expires"]?.toLongOrNull() ?: 0L) - now()).coerceAtLeast(0).seconds
  }
