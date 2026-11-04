// Black-box e2e suite (spec 4.2 `tests/`, WP26): its own standalone Gradle build. It talks to
// the running Compose stack over the network only and never depends on a service module.
rootProject.name = "mw-e2e"
