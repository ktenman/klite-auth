package ee.tenman.auth

import klite.Config
import klite.Server
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpResponse.BodyHandlers

/** The app on a free port, against the Redis in compose.yaml */
object App {
  init {
    Config["PORT"] = "0"
    Config["REDIS_URL"] = "redis://localhost:6380"
  }

  val url = start(server())

  fun start(server: Server) = server.run { start(); "http://localhost:${address.port}" }
}

private val http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()

fun get(url: String, vararg headers: String): HttpResponse<String> =
  http.send(HttpRequest.newBuilder(URI(url)).apply { if (headers.isNotEmpty()) headers(*headers) }.build(), BodyHandlers.ofString())
