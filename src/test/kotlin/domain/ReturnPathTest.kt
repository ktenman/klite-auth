package ee.tenman.auth.domain

import ch.tutteli.atrium.api.fluent.en_GB.*
import ch.tutteli.atrium.api.verbs.expect
import org.junit.jupiter.api.Test

class ReturnPathTest {
  @Test fun `a path on this site leads back to it`() {
    expect(ReturnPath.of("/radio/catch-up?day=2026-10-21")?.on("https://fov.ee/")).toEqual("https://fov.ee/radio/catch-up?day=2026-10-21")
  }

  @Test fun `anything that could leave the site or break the redirect is dropped`() {
    val unsafe = listOf(null, "", "radio", "//evil.example", "https://evil.example", "@evil.example", "/radio\r\nSet-Cookie: x=1", "/radio/\u0161", "/radio/\ufffd", "/" + "a".repeat(2048))
    expect(unsafe.mapNotNull { ReturnPath.of(it) }).toBeEmpty()
  }
}
