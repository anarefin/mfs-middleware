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

$ make e2e TAGS=<tags>      # if the WP has an e2e scenario
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

## Reviewer checklist

- [ ] Re-ran `make verify` on this branch and it passed
- [ ] Re-ran the relevant `make e2e` tags (if any) and they passed
- [ ] Test report above matches the acceptance tests in the WP
- [ ] Code follows the package layout, error model, masking and log-field rules in `CLAUDE.md`
