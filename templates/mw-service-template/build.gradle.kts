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
    implementation(platform(libs.resilience4j.bom))
    testImplementation(platform(libs.testcontainers.bom))

    implementation(libs.spring.boot.starter.webmvc)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.micrometer.tracing.brave)
    implementation(libs.micrometer.tracing.bridge.brave)
    implementation(libs.micrometer.registry.prometheus)
    implementation(libs.spring.cloud.starter.vault.config)

    // Kafka through Camel (spec 6.1, 19.2): idempotent producer, retry topics, DLQ.
    implementation(libs.camel.spring.boot.starter)
    implementation(libs.camel.kafka.starter)
    // Resilience4j for client/ calls (19.2); annotations need AspectJ.
    implementation(libs.resilience4j.spring.boot4)
    implementation(libs.spring.boot.starter.aspectj)
    // Own schema, migrated by Flyway on start-up (16.4).
    implementation(libs.spring.boot.starter.jdbc)
    implementation(libs.spring.boot.starter.flyway)
    implementation(libs.flyway.database.postgresql)
    runtimeOnly(libs.postgresql)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.boot.starter.webmvc.test)
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.kafka)
    testImplementation(libs.spring.boot.testcontainers)
    testImplementation(libs.camel.test.spring.junit6)
    testImplementation(libs.json.schema.validator)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.addAll(listOf("-parameters", "-Xlint:all,-processing,-serial", "-Werror"))
}

tasks.withType<Test>().configureEach {
    // Schema tests read the root contracts/ folder through mwContractsDir only (spec 4.3 rule 2).
    val contracts = layout.projectDirectory.dir(providers.gradleProperty("mwContractsDir").get())
    inputs.dir(contracts).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("mwContracts")
    systemProperty("mwContractsDir", contracts.asFile.absolutePath)
}

tasks.test {
    useJUnitPlatform { excludeTags("conformance") }
}

// Conformance suite (spec 4.3 rule 5, 22.1): tests tagged @Conformance, copied from the template
// into every service. `./gradlew conformance` runs them alone; `check` (make verify) runs both.
val conformance by tasks.registering(Test::class) {
    description = "Runs the conformance suite (@Conformance tests)."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("conformance") }
    shouldRunAfter(tasks.test)
}
tasks.check { dependsOn(conformance) }

// SBOM on every build (spec 3, OWN-05), written to build/reports/cyclonedx-direct/ and
// copied into the image by the Dockerfile.
tasks.named("bootJar") { dependsOn(tasks.named("cyclonedxDirectBom")) }
