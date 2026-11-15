plugins {
	kotlin("jvm") version "2.4.21"
	application
}

repositories {
	mavenCentral()
	maven("https://jitpack.io")
}

dependencies {
	fun klite(module: String) = "com.github.keksworks.klite:klite-$module:2.1.3"
	implementation(klite("server"))
	implementation(klite("json"))
	implementation(klite("slf4j")) // one line per log entry, Jedis's included
	implementation("redis.clients:jedis:7.4.1")
	testImplementation(kotlin("test"))
}

kotlin { jvmToolchain(25) }
application { mainClass = "ee.tenman.auth.MainKt" }
tasks.test { useJUnitPlatform() }
