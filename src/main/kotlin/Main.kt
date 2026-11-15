package ee.tenman.auth

import ee.tenman.auth.adapter.google.GoogleClient
import ee.tenman.auth.adapter.session.RedisSessions
import ee.tenman.auth.adapter.web.authRoutes
import ee.tenman.auth.usecase.*
import klite.*
import redis.clients.jedis.RedisClient
import java.net.URI

fun main() {
  server().start()
}

fun server(
  redis: RedisClient = RedisClient.create(Config.optional("REDIS_URL", "redis://redis:6379")),
  allowed: Set<String> = Config["ALLOWED_EMAILS"].split(",").map(String::trim).toSet(),
): Server {
  val google = GoogleClient(
    clientId = Config["GOOGLE_CLIENT_ID"],
    clientSecret = Config["GOOGLE_CLIENT_SECRET"],
    authorizationUri = URI(Config.optional("GOOGLE_AUTHORIZATION_URI", "https://accounts.google.com/o/oauth2/v2/auth")),
    tokenUri = URI(Config.optional("GOOGLE_TOKEN_URI", "https://www.googleapis.com/oauth2/v4/token")),
    userInfoUri = URI(Config.optional("GOOGLE_USER_INFO_URI", "https://www.googleapis.com/oauth2/v3/userinfo")),
  )
  return Server(httpExchangeCreator = ::XForwardedHttpExchange, sessionStore = RedisSessions(redis)).apply {
    authRoutes(
      checkHealth = { redis.ping() },
      startSignIn = StartSignIn(google),
      finishSignIn = FinishSignIn(google, allowed, site = Config["REDIRECT_URI"]),
      allowed = allowed,
    )
  }
}
