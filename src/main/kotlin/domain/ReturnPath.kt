package ee.tenman.auth.domain

import java.net.URI

@JvmInline
value class ReturnPath private constructor(val path: String) {
  fun on(site: String) = site.removeSuffix("/") + path

  companion object {
    private const val MAX_LENGTH = 2048

    fun of(path: String?): ReturnPath? = path?.takeIf { it.staysOnSite() && it.isValidUri() }?.let(::ReturnPath)

    private fun String.staysOnSite() = length <= MAX_LENGTH && startsWith("/") && !startsWith("//")

    private fun String.isValidUri() = runCatching { URI(this) }.isSuccess
  }
}
