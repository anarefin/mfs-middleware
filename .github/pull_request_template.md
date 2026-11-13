## Work package

<!-- WP number and title, ticket link, module(s) touched (one WP = one branch = one PR). -->

## What changed

<!-- Short summary. Target: 1,500 changed lines or fewer. -->

## Contracts

<!-- Contract files added or changed under contracts/, and the owning service. "None" if none. -->

## Test report

Commands run locally on this branch (paste the summary lines of each):

```
$ ./gradlew :mw-<module>:build        # each module touched
<paste summary>

$ ./gradlew clean build                # the full gate: every module plus the repo checks
<paste summary>

$ scripts/api-test.sh "<folders>"      # if the WP has API suite requests (tests/api)
<paste summary>
```

Acceptance tests from the WP and where they live:

- [ ] ...

## Author checklist

- [ ] Tests written first; all acceptance tests from the WP pass
- [ ] `./gradlew :mw-<module>:build` passes for the module(s) touched, and `./gradlew clean build` passes from the root
- [ ] `./gradlew :checkIsolation` passes (no cross-module dependency, shared build logic, version catalog or path outside the module except `contracts/`)
- [ ] No secrets, no `System.out`, no field injection, no blocking on Kafka threads (see `CLAUDE.md`)
- [ ] Only contracts owned by this service changed

Definition of done (spec 23.2):

- [ ] Unit, route and integration tests green (one `test` task per module; the Testcontainers tests ran, with Docker up)
- [ ] Contracts (OpenAPI/AsyncAPI/JSON Schema) updated
- [ ] Masking verified for any new field (`@Sensitive` on every personal field, with a `MaskingPolicy` test)
- [ ] Metrics, log events and alert rule (if new failure mode) added
- [ ] Runbook / ADR updated where behaviour or design changed

## Reviewer checklist

- [ ] Re-ran `./gradlew clean build` on this branch, with Docker running, and it passed
- [ ] Re-ran the relevant `scripts/api-test.sh` folders (if any) and they passed
- [ ] Test report above matches the acceptance tests in the WP
- [ ] Code follows the package layout, error model, masking and log-field rules in `CLAUDE.md`
