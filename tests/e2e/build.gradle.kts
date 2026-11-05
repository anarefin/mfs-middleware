// Black-box e2e suite (spec WP26): Cucumber on the JUnit Platform, run by `make e2e [M=] [TAGS=]`
// (scripts/suite.sh -> ./gradlew test -Dcucumber.filter.tags=...). Needs the MVP stack up
// (`make up`); it reaches the stack only through APISIX, plus `docker compose exec` for the
// audit rows (see README.md).
plugins {
    java
}

java {
    toolchain { languageVersion = JavaLanguageVersion.of(25) }
}

repositories { mavenCentral() }

dependencies {
    testImplementation(platform(libs.cucumber.bom))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.cucumber.java)
    testImplementation(libs.cucumber.picocontainer)
    testImplementation(libs.cucumber.junit.platform.engine)
    testImplementation(libs.junit.platform.suite)
    // Offline checks of the suite itself (TraceabilityTest), no stack needed.
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.jackson.databind)
    testImplementation(libs.assertj)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.addAll(listOf("-parameters", "-Xlint:all,-processing,-serial", "-Werror"))
}

tasks.test {
    useJUnitPlatform()
    // The stack is the input: never skip a run as up to date.
    outputs.upToDateWhen { false }
    testLogging {
        events("passed", "failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStandardStreams = false
    }
    // -D on gradlew sets properties on the Gradle JVM only: forward the suite's to the test JVM.
    System.getProperties().stringPropertyNames()
        .filter { it == "cucumber.filter.tags" || it.startsWith("mw.e2e.") }
        .forEach { systemProperty(it, System.getProperty(it)) }
    // The repository root: compose file and generated dev credentials (deploy/compose/...).
    systemProperty("mw.e2e.root", rootDir.resolve("../..").canonicalPath)
    // Compose project of the stack under test (per-worktree stacks set it, default "mw").
    System.getenv("COMPOSE_PROJECT_NAME")?.let { environment("COMPOSE_PROJECT_NAME", it) }
}
