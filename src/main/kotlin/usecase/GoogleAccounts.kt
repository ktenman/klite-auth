package ee.tenman.auth.usecase

import ee.tenman.auth.domain.*
import java.net.URI

interface GoogleAccounts {
  fun signInUrl(attempt: SignInAttempt): URI

  fun userFor(code: String, attempt: SignInAttempt): User
}
