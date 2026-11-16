package ee.tenman.auth.usecase

import ee.tenman.auth.domain.*
import java.net.URI

class StartSignIn(private val google: GoogleAccounts) {
  class Started(val attempt: SignInAttempt, val googleUrl: URI)

  operator fun invoke(returnPath: ReturnPath?): Started {
    val attempt = SignInAttempt.start(returnPath)
    return Started(attempt, google.signInUrl(attempt))
  }
}
