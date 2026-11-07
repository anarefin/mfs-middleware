// Aggregate tasks only (spec 4.2). No allprojects/subprojects, no plugins, no
// dependencies: nothing here is inherited by any module.

tasks.register("checkAll") {
    group = "verification"
    description = "Runs :check in every included module build."
    dependsOn(gradle.includedBuilds.map { it.task(":check") })
}

tasks.register("bootJarAll") {
    group = "build"
    description = "Runs :bootJar in every included module build."
    dependsOn(gradle.includedBuilds.map { it.task(":bootJar") })
}

// `./gradlew clean build` (deploy/RUNNING.html step 2): fresh jars for the images, with each module's
// Docker-free test tier only. Not each module's :build, whose check adds the Testcontainers
// integrationTest and conformance suites (those stay in checkAll / make verify).
val cleanAll = tasks.register("clean") {
    group = "build"
    description = "Runs :clean in every included module build."
    dependsOn(gradle.includedBuilds.map { it.task(":clean") })
}

tasks.register("build") {
    group = "build"
    description = "Runs :test and :bootJar in every included module build (no Testcontainers)."
    dependsOn(gradle.includedBuilds.flatMap { listOf(it.task(":test"), it.task(":bootJar")) })
    mustRunAfter(cleanAll)
}
