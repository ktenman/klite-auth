package ee.tenman.auth

import klite.BodyRenderer
import klite.Config
import klite.Cookie
import klite.Cookie.SameSite
import klite.HttpExchange
import klite.MimeTypes
import klite.Server
import klite.Session
import klite.SessionStore
import klite.StatusCode
import klite.StatusCode.Companion.OK
import klite.UnauthorizedException
import klite.XForwardedHttpExchange
import klite.base64UrlEncode
import klite.html.escapeHtml
import klite.http.authBearer
import klite.http.bodyOrThrow
import klite.http.contentType
import klite.http.get
import klite.http.httpClient
import klite.http.post
import klite.json.JsonMapper
import klite.json.parse
import klite.logger
import klite.plus
import klite.urlDecodeParams
import klite.urlEncode
import klite.urlEncodeParams
import klite.warn
import redis.clients.jedis.RedisClient
import redis.clients.jedis.params.SetParams
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes

private val clientId = Config["GOOGLE_CLIENT_ID"]
private val clientSecret = Config["GOOGLE_CLIENT_SECRET"]
private val authorizationUri = Config.optional("GOOGLE_AUTHORIZATION_URI", "https://accounts.google.com/o/oauth2/v2/auth")
private val tokenUri = Config.optional("GOOGLE_TOKEN_URI", "https://www.googleapis.com/oauth2/v4/token")
private val userInfoUri = Config.optional("GOOGLE_USER_INFO_URI", "https://www.googleapis.com/oauth2/v3/userinfo")
private val redirectUrl = Config["REDIRECT_URI"]

private val LOGIN = page("login")
private val HOME = page("home")
private val ERROR = page("error")
private val log = logger("auth")

fun main() { server().start() }

fun server(
  redis: RedisClient = RedisClient.create(Config.optional("REDIS_URL", "redis://redis:6379")),
  allowed: Set<String> = Config["ALLOWED_EMAILS"].split(",").map { it.trim() }.toSet()
) =
  Server(httpExchangeCreator = ::XForwardedHttpExchange, sessionStore = RedisSessions(redis)).apply {
    renderers.clear()
    renderers += ErrorPage
    context("/") {
      get("actuator/health") { redis.ping(); send(OK, """{"status":"UP"}""", MimeTypes.json) }

      get("login") {
        val input = query("redirect")?.let { """<input name="redirect" value="${it.escapeHtml()}" type="hidden">""" }
        send(OK, LOGIN.replace("<!--redirect-->", input.orEmpty()), MimeTypes.html)
      }

      get("oauth2/authorization/google") {
        session["state"] = randomId()
        session["verifier"] = randomId()
        session["page"] = query("redirect")?.takeIf { it.isPathOnThisSite() }
        redirect(URI(authorizationUri) + mapOf("client_id" to clientId, "redirect_uri" to callbackUrl(),
          "response_type" to "code", "scope" to "email profile", "state" to session["state"],
          "code_challenge" to session["verifier"]!!.sha256(), "code_challenge_method" to "S256"))
      }

      get("login/oauth2/code/google") {
        val state = session["state"]
        val verifier = session["verifier"]
        val code = query("code")
        if (code == null || state == null || verifier == null || query("state") != state) redirect("/login?error")
        val page = session["page"]
        session.clear()
        val profile = runCatching { googleProfile(code, verifier) }.getOrElse { log.warn("Google sign-in failed: $it"); redirect("/login?error") }
        if (profile["email"] !in allowed) throw UnauthorizedException("Not allowed to sign in: ${profile["email"]}")
        for (key in listOf("email", "name", "given_name", "family_name", "picture")) session[key] = profile[key] as String?
        redirect(page?.let { redirectUrl.removeSuffix("/") + it } ?: redirectUrl)
      }

      get("validate") {
        val email = signedIn()
        if (email !in allowed) throw UnauthorizedException("No longer allowed: $email")
        header("X-User-Id", email)
        send(OK, """{"message":"Session validated successfully"}""", MimeTypes.json)
      }

      post("logout") { session.clear(); redirect("/") }

      for (path in listOf("", "home")) get(path) { signedIn(); send(OK, HOME, MimeTypes.html) }

      get("user") {
        val email = signedIn()
        send(OK, json.render(
          if (email !in allowed) mapOf("status" to "UNAUTHORIZED", "message" to "User not authorized")
          else mapOf("status" to "AUTHORIZED", "provider" to "google", "user" to mapOf("email" to email, "name" to session["name"].orEmpty(),
            "givenName" to session["given_name"].orEmpty(), "familyName" to session["family_name"].orEmpty(), "picture" to session["picture"].orEmpty()))
        ), MimeTypes.json)
      }
    }
  }

private fun HttpExchange.signedIn() =
  session["email"] ?: redirect("/login" + header("X-Forwarded-Uri")?.let { "?redirect=" + it.urlEncode() }.orEmpty())

private fun String.isPathOnThisSite() = length <= 2048 && startsWith("/") && !startsWith("//") && runCatching { URI(this) }.isSuccess

private const val COOKIE = "AUTHSESSION"

private class RedisSessions(private val redis: RedisClient) : SessionStore {
  override fun load(exchange: HttpExchange) = exchange.cookie(COOKIE)?.let { redis.get(key(it)) }
    ?.let { Session(HashMap<String, Comparable<*>?>(urlDecodeParams(it)), isNew = false) } ?: Session()

  override fun save(exchange: HttpExchange, session: Session) {
    if (!session.changed) return
    exchange.cookie(COOKIE)?.let { redis.del(key(it)) }
    val lifetime = session.lifetime()
    val id = if (lifetime == Duration.ZERO) "" else randomId().also { redis.set(key(it), urlEncodeParams(session.params), SetParams().ex(lifetime.inWholeSeconds)) }
    exchange += Cookie(COOKIE, id, maxAge = lifetime, httpOnly = true, secure = true, sameSite = SameSite.None)
  }

  private fun Session.lifetime() = when {
    params.isEmpty() -> Duration.ZERO
    this["email"] == null -> 10.minutes
    else -> 30.days
  }

  private fun key(id: String) = "auth:session:$id"
}

private val http by lazy { httpClient() }
private val json = JsonMapper()

private fun HttpExchange.googleProfile(code: String, verifier: String): Map<String, Any?> {
  val token = json.parse<Map<String, Any?>>(http.post(URI(tokenUri), urlEncodeParams("grant_type" to "authorization_code", "code" to code,
    "redirect_uri" to callbackUrl(), "client_id" to clientId, "client_secret" to clientSecret, "code_verifier" to verifier)) { contentType(MimeTypes.wwwForm) }.bodyOrThrow())
  return json.parse(http.get(URI(userInfoUri)) { authBearer("${token["access_token"]}") }.bodyOrThrow())
}

private fun HttpExchange.callbackUrl() = fullUrl("/login/oauth2/code/google").toString()

private val random = SecureRandom()
private fun randomId() = ByteArray(32).also(random::nextBytes).base64UrlEncode()
private fun String.sha256() = MessageDigest.getInstance("SHA-256").digest(toByteArray()).base64UrlEncode()

private object ErrorPage : BodyRenderer {
  override val contentType = MimeTypes.html
  override fun render(e: HttpExchange, code: StatusCode, value: Any?) =
    if (code.value < 400) e.send(code)
    else e.send(code, ERROR.replace("<!--status-->", "<p>${code.reason}</p>\n        <p>${code.value}</p>"), contentType)
}

private fun page(name: String) = ClassLoader.getSystemResource("$name.html").readText()
