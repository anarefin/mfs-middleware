# 47: Docs + clean-host bring-up

**What to build:** Someone not on the team can bring up and demo the MVP from the repo alone (acceptance criterion 6, OWN-06). This ticket adds a README quick start, architecture decision records for D1–D15 and M1–M13, a runbook for the MVP host, and a demo script per milestone. Spec WP30.

**Blocked by:**

- 44: Chaos: kill containers mid-flow
- 45: Gatling 100 TPS mix
- 46: Security pass

**Status:** ready-for-agent

- [ ] A person not on the team brings up the full stack with seeded data on a clean host in under 15 minutes (left for the user: README quick start + `docs/runbook.md`; every command in them is checked by `scripts/test/test_repo_docs.sh`)
- [ ] The demo script runs every milestone demo end to end (`docs/demo.md`, M0–M6; running it needs the shared stack: deferred to milestone gate)
- [x] ADRs exist for each decision in the spec's decision tables (`docs/adr/`, D1–D15 and M1–M13, enforced by `test_adrs_cover_every_decision_in_the_spec_tables`)
