package ee.tenman.auth.domain

import java.security.SecureRandom
import java.util.Base64

private val random = SecureRandom()

fun randomToken(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))
