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
	implementation(klite("slf4j"))
	implementation("redis.clients:jedis:8.0.2")
	testImplementation("org.junit.jupiter:junit-jupiter:6.1.3")
	testImplementation("ch.tutteli.atrium:atrium-fluent:1.3.0")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher:6.1.3")
}

kotlin { jvmToolchain(25) }
application { mainClass = "ee.tenman.auth.MainKt" }
tasks.test { useJUnitPlatform() }
