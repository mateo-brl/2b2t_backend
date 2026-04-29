plugins {
    kotlin("jvm") version "2.0.21"
    kotlin("plugin.serialization") version "2.0.21"
    id("io.ktor.plugin") version "3.0.1"
    application
}

group = "com.basefinder.backend"
version = "0.1.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation("io.ktor:ktor-server-core:3.0.1")
    implementation("io.ktor:ktor-server-netty:3.0.1")
    implementation("io.ktor:ktor-server-content-negotiation:3.0.1")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.0.1")
    implementation("io.ktor:ktor-server-status-pages:3.0.1")
    implementation("io.ktor:ktor-server-call-logging:3.0.1")
    implementation("ch.qos.logback:logback-classic:1.5.12")

    // Persistence: Exposed ORM + SQLite for MVP. Postgres dialect once a VPS is up.
    implementation("org.jetbrains.exposed:exposed-core:0.56.0")
    implementation("org.jetbrains.exposed:exposed-jdbc:0.56.0")
    implementation("org.xerial:sqlite-jdbc:3.47.1.0")
    implementation("com.zaxxer:HikariCP:6.2.1")

    testImplementation(kotlin("test"))
    testImplementation("io.ktor:ktor-server-test-host:3.0.1")
}

application {
    mainClass.set("com.basefinder.backend.MainKt")
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}
