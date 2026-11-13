// Root build (spec 4.2, D13, D14): one Gradle build whose subprojects are the services/* modules.
// Each module's build.gradle.kts stays self-contained: nothing else goes in this file (no
// pluginManagement, no dependencyResolutionManagement, no version catalog, no includeBuild), and
// there is no allprojects/subprojects, buildSrc or convention plugin anywhere. templates/ and
// tests/perf (its own standalone build) are never included.
rootProject.name = "mfs-middleware"

// Modules are discovered at configuration time, so adding a service never edits this file:
// every services/* folder with a build.gradle.kts is the subproject :<folder name>.
file("services").listFiles()
    ?.filter { it.isDirectory && it.resolve("build.gradle.kts").isFile }
    ?.sortedBy { it.name }
    ?.forEach { dir ->
        include(dir.name)
        project(":${dir.name}").projectDir = dir
    }
