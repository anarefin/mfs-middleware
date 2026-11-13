// Root build script (spec 4.2). No allprojects/subprojects, nothing applied to or inherited by any
// module: each services/*/build.gradle.kts is self-contained (D13, D14). The core `base` plugin only
// gives the root project its own clean / check / build lifecycle tasks.
//
// `./gradlew clean build` is the full gate: every subproject's clean, check (all its tests; the
// Testcontainers ones are skipped without Docker) and bootJar, plus the repo checks below, wired into
// the root `check`. `./gradlew :mw-x:build` builds one module; `./gradlew :checkIsolation` runs one
// repo check.
//
// The repo checks are the shell scripts in scripts/, run as Exec tasks. Each declares the files it
// reads as inputs and writes a marker file, so it is UP-TO-DATE (or FROM-CACHE after a clean) when
// nothing it reads changed. Docker and node must be on PATH for contractsLint, composeCheck and
// scriptTests, as for the scripts themselves.
plugins {
    base
}

// Directories never read by a check: build output, caches, dependencies, local tool state.
val ignoredDirs = listOf(
    "**/build/**", "**/.gradle/**", "**/node_modules/**", "**/.next/**", "**/test-results/**",
    "**/playwright-report/**", ".git/**", ".scratch/**", ".claude/**", ".idea/**", ".vscode/**",
    "graphify-out/**", "**/.DS_Store",
)
val markers = layout.buildDirectory.dir("repo-checks")

// repoCheck NAME: an Exec task in group verification that runs scripts/SCRIPT from the repo root,
// with the given inputs, and records a marker file on success.
fun repoCheck(name: String, text: String, script: String, inputs: ConfigurableFileTree.() -> Unit) =
    tasks.register<Exec>(name) {
        group = "verification"
        description = text
        workingDir = layout.projectDirectory.asFile
        commandLine("bash", "scripts/$script")
        val tree = fileTree(layout.projectDirectory) {
            exclude(ignoredDirs)
            inputs()
        }
        this.inputs.files(tree).withPropertyName("repoFiles").withPathSensitivity(PathSensitivity.RELATIVE)
        val marker = markers.map { it.file("$name.ok") }
        outputs.file(marker).withPropertyName("marker")
        outputs.cacheIf { true }
        doLast { marker.get().asFile.writeText("ok\n") }
    }

val checkIsolation = repoCheck(
    "checkIsolation",
    "Module independence rules (spec 4.3, D13, D14): scripts/check-isolation.sh.",
    "check-isolation.sh",
) {
    // the Gradle files of every module and the root, and the whole repo for the compose-layout rule
    exclude("services/*/src/**", "templates/*/src/**", "ui/**", "spec/**")
}

val checkFieldInjection = repoCheck(
    "checkFieldInjection",
    "No @Autowired field injection in any module: scripts/check-field-injection.sh.",
    "check-field-injection.sh",
) {
    include("scripts/check-field-injection.sh", "scripts/lib/**", "services/*/src/**", "templates/*/src/**")
}

val contractsLint = repoCheck(
    "contractsLint",
    "Contracts lint, derived body schemas and API suite traceability: scripts/contracts-lint.sh.",
    "contracts-lint.sh",
) {
    include(
        "contracts/**", "scripts/contracts-lint.sh", "scripts/derived-schemas.sh",
        "scripts/check-api-traceability.sh", "scripts/contracts-lint/**", "scripts/lib/**",
        "services/*/contracts.yml", "services/*/src/main/resources/derived-schemas/**", "tests/api/**",
    )
}

val composeCheck = repoCheck(
    "composeCheck",
    "Memory limits of deploy/docker-compose.yml within the laptop budget: scripts/compose-check.sh.",
    "compose-check.sh",
) {
    include("scripts/compose-check.sh", "scripts/lib/**", "deploy/docker-compose.yml", "deploy/.env")
}
// a memory knob (`memory: "${KNOB:-default}"` in the compose file) or the budget set in the shell
// changes the result too
composeCheck.configure {
    val compose = providers.fileContents(layout.projectDirectory.file("deploy/docker-compose.yml")).asText
    val knobs = Regex("""memory:\s*"?\$\{([A-Z0-9_]+)""").findAll(compose.getOrElse(""))
        .map { it.groupValues[1] }.toSortedSet() + "MW_RAM_BUDGET_MIB"
    knobs.forEach { inputs.property("env.$it", providers.environmentVariable(it).orElse("")) }
}

val scriptTests = repoCheck(
    "scriptTests",
    "Shell tests of scripts/ and the repo layout and docs: scripts/test/run.sh.",
    "test/run.sh",
) {
    // the tests read scripts, deploy, docs, the template and the services' layout, not their tests
    exclude("services/*/src/test/**")
}

tasks.named("check") {
    dependsOn(checkIsolation, checkFieldInjection, contractsLint, composeCheck, scriptTests)
}
