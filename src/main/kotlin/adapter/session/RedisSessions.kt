package ee.tenman.auth.adapter.session

import ee.tenman.auth.domain.randomToken
import ee.tenman.auth.domain.sha256
import klite.*
import klite.Cookie.SameSite
import redis.clients.jedis.RedisClient
import redis.clients.jedis.params.SetParams
import kotlin.time.Duration

private const val COOKIE = "__Host-AUTHSESSION"

class RedisSessions(private val redis: RedisClient) : SessionStore {
  override fun load(exchange: HttpExchange): Session {
    val stored = exchange.sessionId()?.let { redis.get(key(it)) } ?: return Session()
    return Session(HashMap<String, Comparable<*>?>(urlDecodeParams(stored)), isNew = false)
  }

  override fun save(exchange: HttpExchange, session: Session) {
    if (!session.changed) return
    val presented = exchange.sessionId()
    val lifetime = session.lifetime
    if (session.isNew || lifetime == Duration.ZERO) presented?.let { redis.del(key(it)) }
    val id = when {
      lifetime == Duration.ZERO -> ""
      session.isNew -> store(session, lifetime)
      else -> presented?.takeIf { redis.set(key(it), urlEncodeParams(session.params), SetParams().xx().ex(lifetime.inWholeSeconds)) != null }.orEmpty()
    }
    exchange += Cookie(COOKIE, id, maxAge = if (id.isEmpty()) Duration.ZERO else lifetime, httpOnly = true, secure = true, sameSite = SameSite.Lax)
  }

  private fun store(session: Session, lifetime: Duration) =
    randomToken().also { redis.set(key(it), urlEncodeParams(session.params), SetParams().ex(lifetime.inWholeSeconds)) }

  private fun key(id: String) = "auth:session:" + id.sha256()

  private fun HttpExchange.sessionId() =
    header("Cookie")?.split(';')?.map { it.trim(' ', '\t') }?.lastOrNull { it.startsWith("$COOKIE=") }?.substringAfter('=')
}
