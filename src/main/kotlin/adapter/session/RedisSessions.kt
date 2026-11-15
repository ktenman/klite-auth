package ee.tenman.auth.adapter.session

import ee.tenman.auth.domain.randomToken
import klite.*
import klite.Cookie.SameSite
import redis.clients.jedis.RedisClient
import redis.clients.jedis.params.SetParams
import kotlin.time.Duration

private const val COOKIE = "AUTHSESSION"

class RedisSessions(private val redis: RedisClient) : SessionStore {
  override fun load(exchange: HttpExchange): Session {
    val stored = exchange.cookie(COOKIE)?.let { redis.get(key(it)) } ?: return Session()
    return Session(HashMap<String, Comparable<*>?>(urlDecodeParams(stored)), isNew = false)
  }

  override fun save(exchange: HttpExchange, session: Session) {
    if (!session.changed) return
    exchange.cookie(COOKIE)?.let { redis.del(key(it)) }
    val lifetime = session.lifetime
    val id = if (lifetime == Duration.ZERO) "" else store(session, lifetime)
    exchange += Cookie(COOKIE, id, maxAge = lifetime, httpOnly = true, secure = true, sameSite = SameSite.None)
  }

  private fun store(session: Session, lifetime: Duration) =
    randomToken().also { redis.set(key(it), urlEncodeParams(session.params), SetParams().ex(lifetime.inWholeSeconds)) }

  private fun key(id: String) = "auth:session:$id"
}
