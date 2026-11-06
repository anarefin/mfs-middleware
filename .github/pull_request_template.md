## Work package

<!-- WP number and title, ticket link, module(s) touched (one WP = one branch = one PR). -->

## What changed

<!-- Short summary. Target: 1,500 changed lines or fewer. -->

## Contracts

<!-- Contract files added or changed under contracts/, and the owning service. "None" if none. -->

## Test report

Commands run locally on this branch (paste the summary lines of each):

```
$ make verify M=<module>
<paste summary>

$ make api-test FOLDER=<folders>   # if the WP has API suite requests (tests/api)
<paste summary>
```

Acceptance tests from the WP and where they live:

- [ ] ...

## Author checklist

- [ ] Tests written first; all acceptance tests from the WP pass
- [ ] `make verify` passes for the module(s) touched
- [ ] `make check-isolation` passes (no cross-module dependency, `includeBuild`, convention plugin or path outside the module except `contracts/`)
- [ ] No secrets, no `System.out`, no field injection, no blocking on Kafka threads (see `CLAUDE.md`)
- [ ] Only contracts owned by this service changed

Definition of done (spec 23.2):

- [ ] Unit, route and integration tests green; coverage gate met (JaCoCo >= 80% lines on `service`, `mapper`, `client`; `make verify` enforces it)
- [ ] Contracts (OpenAPI/AsyncAPI/JSON Schema) updated
- [ ] Masking verified for any new field (`@Sensitive` on every personal field, with a `MaskingPolicy` test)
- [ ] Metrics, log events and alert rule (if new failure mode) added
- [ ] Runbook / ADR updated where behaviour or design changed

## Reviewer checklist

- [ ] Re-ran `make verify` on this branch and it passed
- [ ] Re-ran the relevant `make api-test` folders (if any) and they passed
- [ ] Test report above matches the acceptance tests in the WP
- [ ] Code follows the package layout, error model, masking and log-field rules in `CLAUDE.md`
