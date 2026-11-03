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
