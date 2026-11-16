package ee.tenman.auth

import klite.BadRequestException
import klite.Config
import klite.MimeTypes
import klite.Server
import klite.StatusCode.Companion.BadRequest
import klite.StatusCode.Companion.OK
import klite.StatusCode.Companion.Unauthorized
import klite.base64UrlEncode
import klite.queryParams
import klite.urlEncode
import klite.urlDecodeParams
import klite.urlEncodeParams
import redis.clients.jedis.RedisClient
import redis.clients.jedis.params.SetParams
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpRequest.BodyPublishers
import java.net.http.HttpResponse
import java.net.http.HttpResponse.BodyHandlers
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

const val MARI = "mari.tamm@näide.ee"
const val JURI = "jüri.kask@näide.ee"
const val KATI = "kati.kask@näide.ee"

object App {
  init {
    Config["PORT"] = "0"
    Config["REDIS_URL"] = "redis://localhost:6380"
    Config["GOOGLE_CLIENT_ID"] = "fov"
    Config["GOOGLE_CLIENT_SECRET"] = "saladus"
    Config["GOOGLE_AUTHORIZATION_URI"] = "${Google.url}/auth"
    Config["GOOGLE_TOKEN_URI"] = "${Google.url}/token"
    Config["GOOGLE_USER_INFO_URI"] = "${Google.url}/userinfo"
    Config["REDIRECT_URI"] = "https://fov.ee"
    Config["ALLOWED_EMAILS"] = "jaan.kask@näide.ee, $MARI, $KATI,"
  }

  val server: Server = server()
  val url = start(server)
  val redis = RedisClient.create(Config["REDIS_URL"])

  fun start(server: Server) = server.run { start(); "http://localhost:${address.port}" }
}

object Google {
  private class Grant(val email: String, val redirectUri: String, val codeChallenge: String)
  private val grants = ConcurrentHashMap<String, Grant>()
  private val emailsByToken = ConcurrentHashMap<String, String>()

  val url = Server(InetSocketAddress(0)).apply {
    context("/") {
      get("auth") {
        val scopes = query("scope").orEmpty().split(" ")
        if (query("client_id") != "fov" || query("response_type") != "code" || "email" !in scopes || "profile" !in scopes || query("code_challenge_method") != "S256")
          throw BadRequestException("invalid_request")
        val code = UUID.randomUUID().toString().also { grants[it] = Grant(query("login_hint")!!, query("redirect_uri")!!, query("code_challenge")!!) }
        redirect(query("redirect_uri") + "?" + urlEncodeParams("state" to query("state"), "code" to code,
          "scope" to "email profile https://www.googleapis.com/auth/userinfo.email https://www.googleapis.com/auth/userinfo.profile",
          "authuser" to 0, "prompt" to "consent"))
      }
      post("token") {
        if (body<String>("client_id") != "fov" || body<String>("client_secret") != "saladus")
          return@post send(Unauthorized, """{"error":"invalid_client","error_description":"Unauthorized"}""", MimeTypes.json)
        val email = grants.remove(body<String>("code"))
          ?.takeIf { body<String>("grant_type") == "authorization_code" && body<String>("redirect_uri") == it.redirectUri && sha256(body("code_verifier")) == it.codeChallenge }?.email
          ?: return@post send(BadRequest, """{"error":"invalid_grant","error_description":"Bad Request"}""", MimeTypes.json)
        val token = UUID.randomUUID().toString().also { emailsByToken[it] = email }
        send(OK, """{"access_token":"$token","expires_in":3599,"scope":"https://www.googleapis.com/auth/userinfo.email https://www.googleapis.com/auth/userinfo.profile","token_type":"Bearer"}""", MimeTypes.json)
      }
      get("userinfo") {
        val email = emailsByToken[header("Authorization")?.removePrefix("Bearer ")]
          ?: return@get send(Unauthorized, """{"error":"invalid_request","error_description":"Invalid Credentials"}""", MimeTypes.json)
        val (given, family) = email.substringBefore("@").split(".").map { n -> n.replaceFirstChar { it.uppercase() } }
        send(OK, """{"sub":"${email.hashCode()}","name":"$given $family","given_name":"$given","family_name":"$family","picture":"https://lh3.googleusercontent.com/a/$given","email":"$email","email_verified":${email != KATI}}""", MimeTypes.json)
      }
    }
    start()
  }.let { "http://localhost:${it.address.port}" }
}

private val http = HttpClient.newHttpClient()

fun get(url: String, vararg headers: String) = send(request(url, *headers))
fun post(url: String, vararg headers: String) = send(request(url, *headers).POST(BodyPublishers.noBody()))

private fun request(url: String, vararg headers: String) = HttpRequest.newBuilder(URI(App.url).resolve(url)).apply { if (headers.isNotEmpty()) headers(*headers) }
private fun send(request: HttpRequest.Builder): HttpResponse<String> = http.send(request.build(), BodyHandlers.ofString())

fun startSignIn(page: String? = null, vararg headers: String) =
  get("/oauth2/authorization/google" + page?.let { "?redirect=" + it.urlEncode() }.orEmpty(), *headers)

fun atGoogle(start: HttpResponse<*>, email: String) = get(start.location + "&login_hint=" + email.urlEncode()).location

fun signIn(email: String, page: String? = null, vararg headers: String): HttpResponse<String> {
  val start = startSignIn(page, *headers)
  val back = URI(atGoogle(start, email))
  return get("${back.rawPath}?${back.rawQuery}", "Cookie", start.cookie, *headers)
}

val HttpResponse<*>.location: String get() =
  headers().firstValue("Location").orElseThrow { AssertionError("no Location in ${statusCode()} ${body()}") }

val HttpResponse<*>.cookie: String get() =
  headers().allValues("Set-Cookie").firstOrNull { it.startsWith("__Host-AUTHSESSION=") }?.substringBefore(";")
    ?: throw AssertionError("no session cookie in ${statusCode()} ${headers()}")

val HttpResponse<*>.maxAge: Long get() =
  headers().firstValue("Set-Cookie").get().substringAfter("Max-Age=").substringBefore(";").toLong()

fun String.param(name: String) = URI(this).queryParams[name]

fun sessionTtl(cookie: String) = App.redis.ttl(sessionKey(cookie))

fun rewriteSession(cookie: String, vararg values: Pair<String, Any>) {
  val key = sessionKey(cookie)
  App.redis.set(key, urlEncodeParams(urlDecodeParams(App.redis.get(key)) + values), SetParams().keepTtl())
}

private fun sessionKey(cookie: String) = "auth:session:" + sha256(cookie.substringAfter("="))

private fun sha256(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).base64UrlEncode()
