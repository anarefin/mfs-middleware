// Black-box perf suite (spec WP28, MVP acceptance criterion 7): a Gatling scenario mix across the
// MVP flows at 100 TPS on the single MVP host. Run by `make perf` (scripts/suite.sh ->
// ./gradlew gatlingRun perfOverhead) against a running stack (`make up`); see README.md.
//
// - src/main/java: stack access, users, signed request building, scenario mix, overhead report
// - src/gatling/java: the simulation (MixedLoadSimulation)
// - src/test/java: offline checks (`./gradlew test`, no stack needed)
plugins {
    java
    alias(libs.plugins.gatling)
}

java {
    toolchain { languageVersion = JavaLanguageVersion.of(25) }
}

repositories { mavenCentral() }

// Console logging of the standalone report (perfOverhead); the simulation logs through Gatling's logback.
val reportRuntime by configurations.creating

dependencies {
    implementation(libs.jackson.databind)
    implementation(libs.slf4j.api)
    reportRuntime(libs.slf4j.simple)
    // The gatling source set sees main's classes but not its dependencies.
    gatlingImplementation(libs.jackson.databind)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.addAll(listOf("-parameters", "-Xlint:all,-processing,-serial", "-Werror"))
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

// The suite's settings: -D on gradlew sets them on the Gradle JVM only, so forward every
// mw.perf.* property, the repository root and the run directory to the JVMs that need them.
val runDir = layout.buildDirectory.dir("reports/perf").get().asFile
val perfProperties: Map<String, Any> =
    System.getProperties().stringPropertyNames()
        .filter { it.startsWith("mw.perf.") }
        .associateWith { System.getProperty(it) as Any } +
        mapOf("mw.perf.root" to rootDir.resolve("../..").canonicalPath, "mw.perf.run-dir" to runDir.path)

gatling {
    // jvmArgs: the plugin's defaults (1 GB heap, G1, --add-opens java.base/java.lang) are enough at 100 TPS
    systemProperties = perfProperties
    // Compose project of the stack under test (per-worktree stacks set it, default "mw").
    environment = System.getenv("COMPOSE_PROJECT_NAME")?.let { mapOf("COMPOSE_PROJECT_NAME" to it) } ?: emptyMap<String, Any>()
}

// Post-run breakdown: reads the run's transaction log (written by the simulation), subtracts the
// core-mock and partner (simulator) time of every transaction, writes overhead.md/.json next to it
// and fails when the p95 middleware overhead is not under the target.
val perfOverhead by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Middleware overhead (end to end minus core-mock and simulator time) of the last run"
    classpath = sourceSets.main.get().runtimeClasspath + reportRuntime
    mainClass = "mfs.mw.perf.report.OverheadReport"
    javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(25) }
    systemProperties(perfProperties)
    System.getenv("COMPOSE_PROJECT_NAME")?.let { environment("COMPOSE_PROJECT_NAME", it) }
    outputs.upToDateWhen { false }
}
