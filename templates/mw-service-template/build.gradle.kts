// Standalone module build (spec 4.2, 4.3). The Boot, Spring Cloud and Camel BOMs are
// Gradle platforms; every version comes from this module's gradle/libs.versions.toml.
plugins {
    java
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.cyclonedx)
}

group = "mfs.mw"
version = "0.1.0-SNAPSHOT"

java {
    toolchain { languageVersion = JavaLanguageVersion.of(25) }
}

repositories { mavenCentral() }

dependencies {
    implementation(platform(libs.spring.boot.bom))
    implementation(platform(libs.spring.cloud.bom))
    implementation(platform(libs.camel.bom))

    implementation(libs.spring.boot.starter.webmvc)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.micrometer.tracing.brave)
    implementation(libs.micrometer.tracing.bridge.brave)
    implementation(libs.micrometer.registry.prometheus)
    implementation(libs.spring.cloud.starter.vault.config)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.boot.starter.webmvc.test)
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.addAll(listOf("-parameters", "-Xlint:all,-processing,-serial", "-Werror"))
}

tasks.test {
    useJUnitPlatform()
}

// SBOM on every build (spec 3, OWN-05), written to build/reports/cyclonedx-direct/ and
// copied into the image by the Dockerfile.
tasks.named("bootJar") { dependsOn(tasks.named("cyclonedxDirectBom")) }
