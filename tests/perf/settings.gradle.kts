// Black-box performance suite (spec 4.2 `tests/`, WP28): its own standalone Gradle build. It
// talks to the running Compose stack over the network (plus `docker compose exec` for user
// provisioning and the post-run overhead breakdown) and never depends on a service module.
rootProject.name = "mw-perf"
