package ee.tenman.auth.usecase

import ee.tenman.auth.domain.*

class FinishSignIn(private val google: GoogleAccounts, private val allowed: Set<String>, private val site: String) {
  sealed interface Outcome
  data object UnknownCallback : Outcome
  data class GoogleFailed(val cause: Throwable) : Outcome
  data class NotAllowed(val email: String) : Outcome
  data class SignedIn(val user: User, val destination: String) : Outcome

  operator fun invoke(attempt: SignInAttempt?, callbackState: String?, code: String?, callbackUrl: String): Outcome {
    if (attempt == null || code == null || callbackState != attempt.state) return UnknownCallback
    val user = runCatching { google.userFor(code, attempt, callbackUrl) }.getOrElse { return GoogleFailed(it) }
    if (user.email !in allowed) return NotAllowed(user.email)
    return SignedIn(user, destination = attempt.returnPath?.on(site) ?: site)
  }
}
