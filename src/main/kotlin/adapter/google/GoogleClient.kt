package ee.tenman.auth.adapter.google

import ee.tenman.auth.domain.*
import ee.tenman.auth.usecase.GoogleAccounts
import klite.*
import klite.http.*
import klite.json.*
import java.net.URI
import java.security.MessageDigest

class GoogleClient(
  private val clientId: String,
  private val clientSecret: String,
  private val authorizationUri: URI,
  private val tokenUri: URI,
  private val userInfoUri: URI,
) : GoogleAccounts {
  private val http by lazy { httpClient() }
  private val json = JsonMapper(keys = SnakeCase)

  override fun signInUrl(attempt: SignInAttempt, callbackUrl: String): URI = authorizationUri + mapOf(
    "client_id" to clientId,
    "redirect_uri" to callbackUrl,
    "response_type" to "code",
    "scope" to "email profile",
    "state" to attempt.state,
    "code_challenge" to attempt.codeVerifier.sha256(),
    "code_challenge_method" to "S256",
  )

  override fun userFor(code: String, attempt: SignInAttempt, callbackUrl: String): User {
    val form = urlEncodeParams(
      "grant_type" to "authorization_code",
      "code" to code,
      "redirect_uri" to callbackUrl,
      "client_id" to clientId,
      "client_secret" to clientSecret,
      "code_verifier" to attempt.codeVerifier,
    )
    val token = json.parse<Token>(http.post(tokenUri, form) { contentType(MimeTypes.wwwForm) }.bodyOrThrow())
    return json.parse(http.get(userInfoUri) { authBearer(token.accessToken) }.bodyOrThrow())
  }

  class Token(val accessToken: String)

  private fun String.sha256() = MessageDigest.getInstance("SHA-256").digest(toByteArray()).base64UrlEncode()
}
