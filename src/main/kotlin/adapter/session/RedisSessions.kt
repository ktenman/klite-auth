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
    exchange.sessionId()?.let { redis.del(key(it)) }
    val lifetime = session.lifetime
    val id = if (lifetime == Duration.ZERO) "" else store(session, lifetime)
    exchange += Cookie(COOKIE, id, maxAge = lifetime, httpOnly = true, secure = true, sameSite = SameSite.Lax)
  }

  private fun store(session: Session, lifetime: Duration) =
    randomToken().also { redis.set(key(it), urlEncodeParams(session.params), SetParams().ex(lifetime.inWholeSeconds)) }

  // Only a hash of the id is kept, so reading Redis doesn't reveal working session cookies
  private fun key(id: String) = "auth:session:" + id.sha256()

  // Reads our cookie only: klite's exchange.cookie() decodes every cookie and fails on any malformed one.
  // Trims just spaces and tabs (RFC 6265 5.2), so a name like "\u00a0__Host-AUTHSESSION" doesn't pass for ours
  private fun HttpExchange.sessionId() =
    header("Cookie")?.split(';')?.map { it.trim(' ', '\t') }?.lastOrNull { it.startsWith("$COOKIE=") }?.substringAfter('=')
}
