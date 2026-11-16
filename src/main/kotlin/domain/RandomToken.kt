package ee.tenman.auth.domain

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

private val random = SecureRandom()
private val base64Url = Base64.getUrlEncoder().withoutPadding()

fun randomToken(): String = base64Url.encodeToString(ByteArray(32).also(random::nextBytes))

fun String.sha256(): String = base64Url.encodeToString(MessageDigest.getInstance("SHA-256").digest(toByteArray()))
