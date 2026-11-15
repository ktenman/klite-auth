package ee.tenman.auth.adapter.session

import ee.tenman.auth.domain.*
import klite.Session
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes

var Session.signInAttempt: SignInAttempt?
  get() {
    val state = this["state"] ?: return null
    val codeVerifier = this["verifier"] ?: return null
    return SignInAttempt(state, codeVerifier, ReturnPath.of(this["page"]))
  }
  set(attempt) {
    this["state"] = attempt?.state
    this["verifier"] = attempt?.codeVerifier
    this["page"] = attempt?.returnPath?.path
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
  }

val Session.lifetime: Duration
  get() = when {
    params.isEmpty() -> Duration.ZERO
    user == null -> 10.minutes
    else -> 30.days
  }
