package ee.tenman.auth.adapter.google

import ee.tenman.auth.domain.*
import ee.tenman.auth.usecase.GoogleAccounts
import klite.*
import klite.http.*
import klite.json.*
import java.net.URI
import kotlin.time.Duration.Companion.seconds

class GoogleClient(
  private val clientId: String,
  private val clientSecret: String,
  private val callbackUrl: String,
  private val authorizationUri: URI,
  private val tokenUri: URI,
  private val userInfoUri: URI,
) : GoogleAccounts {
  private val http by lazy { httpClient() }
  private val json = JsonMapper(keys = SnakeCase)

  override fun signInUrl(attempt: SignInAttempt): URI = authorizationUri + mapOf(
    "client_id" to clientId,
    "redirect_uri" to callbackUrl,
    "response_type" to "code",
    "scope" to "openid email profile",
    "state" to attempt.state,
    "code_challenge" to attempt.codeVerifier.sha256(),
    "code_challenge_method" to "S256",
  )

  override fun userFor(code: String, attempt: SignInAttempt): User {
    val form = urlEncodeParams(
      "grant_type" to "authorization_code",
      "code" to code,
      "redirect_uri" to callbackUrl,
      "client_id" to clientId,
      "client_secret" to clientSecret,
      "code_verifier" to attempt.codeVerifier,
    )
    val token = json.parse<Token>(http.post(tokenUri, form) { contentType(MimeTypes.wwwForm); timeout(10.seconds) }.bodyOrThrow())
    val userInfo = http.get(userInfoUri) { authBearer(token.accessToken); timeout(10.seconds) }.bodyOrThrow()
    val verification = json.parse<Verification>(userInfo)
    check(verification.emailVerified) { "Google has not verified the account's email" }
    val user = json.parse<User>(userInfo).run { copy(email = email.lowercase()) }
    check(user.email.endsWith("@gmail.com") || verification.hd?.lowercase() == user.email.substringAfter('@')) { "Google is not the authority for the account's email" }
    return user
  }

  class Token(val accessToken: String)
  class Verification(val emailVerified: Boolean, val hd: String?)
}
