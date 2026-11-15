package ee.tenman.auth.domain

class SignInAttempt(val state: String, val codeVerifier: String, val returnPath: ReturnPath?) {
  companion object {
    fun start(returnPath: ReturnPath?) = SignInAttempt(state = randomToken(), codeVerifier = randomToken(), returnPath)
  }
}
