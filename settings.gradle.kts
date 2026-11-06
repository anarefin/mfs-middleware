// Root composite build, for convenience only (spec 4.2, D13, D14).
//
// One includeBuild per module: every services/* folder with its own settings.gradle(.kts)
// is included, discovered at configuration time so parallel work packages never edit
// this file. templates/ is deliberately not included.
//
// Nothing is shared with the included builds: no pluginManagement, no
// dependencyResolutionManagement, no version catalog, no buildSrc or convention
// plugins. Each module builds alone from its own directory (`make check-isolation`).
rootProject.name = "bits-middleware"

file("services").listFiles()
    ?.filter { it.isDirectory && (it.resolve("settings.gradle.kts").isFile || it.resolve("settings.gradle").isFile) }
    ?.sortedBy { it.name }
    ?.forEach { includeBuild(it) }
