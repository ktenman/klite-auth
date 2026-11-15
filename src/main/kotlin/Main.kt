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
import klite.html.escapeHtml
import klite.json.JsonMapper
import klite.json.parse
import klite.logger
import klite.urlDecodeParams
import klite.urlEncodeParams
import klite.warn
import redis.clients.jedis.JedisPooled
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpRequest.BodyPublishers
import java.net.http.HttpResponse.BodyHandlers
import java.security.SecureRandom
import java.util.Base64
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days

private val clientId = Config["GOOGLE_CLIENT_ID"]
private val clientSecret = Config["GOOGLE_CLIENT_SECRET"]
private val authorizationUri = Config.optional("GOOGLE_AUTHORIZATION_URI", "https://accounts.google.com/o/oauth2/v2/auth")
private val tokenUri = Config.optional("GOOGLE_TOKEN_URI", "https://www.googleapis.com/oauth2/v4/token")
private val userInfoUri = Config.optional("GOOGLE_USER_INFO_URI", "https://www.googleapis.com/oauth2/v3/userinfo")
private val redirectUrl = Config["REDIRECT_URI"]
private val allowedEmails = Config["ALLOWED_EMAILS"].split(",").map { it.trim() }.toSet()

private val LOGIN = page("login")
private val ERROR = page("error")
private val log = logger("auth")

fun main() { server().start() }

fun server(redis: JedisPooled = JedisPooled(Config.optional("REDIS_URL", "redis://redis:6379"))) =
  Server(httpExchangeCreator = ::XForwardedHttpExchange, sessionStore = RedisSessions(redis)).apply {
    renderers.add(0, ErrorPage)
    context("/") {
      get("actuator/health") { redis.ping(); send(OK, """{"status":"UP"}""", MimeTypes.json) }

      get("login") {
        val input = query("redirect")?.let { """<input name="redirect" value="${it.escapeHtml()}" type="hidden">""" }
        send(OK, LOGIN.replace("<!--redirect-->", input.orEmpty()), MimeTypes.html)
      }

      get("oauth2/authorization/google") {
        session["state"] = randomId()
        session["page"] = query("redirect")?.takeIf { it.startsWith("/") && !it.startsWith("//") }
        redirect("$authorizationUri?" + urlEncodeParams("client_id" to clientId, "redirect_uri" to callbackUrl(),
          "response_type" to "code", "scope" to "email profile", "state" to session["state"]))
      }

      get("login/oauth2/code/google") {
        val state = session["state"]
        val page = session["page"]
        session.clear()
        val code = query("code")
        if (code == null || state == null || query("state") != state) redirect("/login?error")
        val email = runCatching { googleEmail(code) }.getOrElse { log.warn("Google sign-in failed: $it"); redirect("/login?error") }
        if (email !in allowedEmails) throw UnauthorizedException("Not allowed to sign in: $email")
        session["email"] = email
        redirect(page?.let { redirectUrl.removeSuffix("/") + it } ?: redirectUrl)
      }
    }
  }

private const val COOKIE = "AUTHSESSION"
private val TTL = 30.days

/** Sessions in Redis, under a random id in the AUTHSESSION cookie. A changed session moves to a new id, so signing in never keeps the id from before. */
private class RedisSessions(private val redis: JedisPooled) : SessionStore {
  override fun load(exchange: HttpExchange) = exchange.cookie(COOKIE)?.let { redis.get(key(it)) }
    ?.let { Session(HashMap<String, Comparable<*>?>(urlDecodeParams(it)), isNew = false) } ?: Session()

  override fun save(exchange: HttpExchange, session: Session) {
    if (!session.changed) return
    exchange.cookie(COOKIE)?.let { redis.del(key(it)) }
    val id = if (session.params.isEmpty()) "" else randomId().also { redis.setex(key(it), TTL.inWholeSeconds, urlEncodeParams(session.params)) }
    exchange += Cookie(COOKIE, id, maxAge = if (id.isEmpty()) Duration.ZERO else TTL, httpOnly = true, secure = true, sameSite = SameSite.None)
  }

  private fun key(id: String) = "auth:session:$id"
}

private val http = HttpClient.newHttpClient()
private val json = JsonMapper()

/** Swaps the code for a token and asks Google whose it is */
private fun HttpExchange.googleEmail(code: String): String? {
  val token = google(HttpRequest.newBuilder(URI(tokenUri)).header("Content-Type", MimeTypes.wwwForm)
    .POST(BodyPublishers.ofString(urlEncodeParams("grant_type" to "authorization_code", "code" to code,
      "redirect_uri" to callbackUrl(), "client_id" to clientId, "client_secret" to clientSecret))))["access_token"]
  return google(HttpRequest.newBuilder(URI(userInfoUri)).header("Authorization", "Bearer $token"))["email"] as String?
}

private fun google(request: HttpRequest.Builder): Map<String, Any?> {
  val response = http.send(request.build(), BodyHandlers.ofString())
  check(response.statusCode() == 200) { "Google answered ${response.statusCode()}: ${response.body()}" }
  return json.parse(response.body())
}

private fun HttpExchange.callbackUrl() = fullUrl("/login/oauth2/code/google").toString()

private val random = SecureRandom()
private fun randomId() = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))

private object ErrorPage : BodyRenderer {
  override val contentType = MimeTypes.html
  override fun render(e: HttpExchange, code: StatusCode, value: Any?) =
    e.send(code, ERROR.replace("<!--status-->", "<p>${code.reason}</p>\n        <p>${code.value}</p>"), contentType)
}

private fun page(name: String) = ClassLoader.getSystemResource("$name.html").readText()
