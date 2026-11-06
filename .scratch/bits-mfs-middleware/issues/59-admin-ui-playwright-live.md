# 59: Admin UI Playwright against the live stack (mock removed)

**What to build:** Run the admin UI Playwright suite against the running, seeded stack, with real Keycloak and the real mw-admin-api behind APISIX, as BitsPay's `~/Desktop/BitsPay/frontend/playwright.config.ts` and `e2e/support/*` do. Delete the mocked IdP and API. The user chose live-only on 2026-11-06 (plan `check-the-project-deployment-ancient-possum`, Phase D).

- **`ui/mw-admin-ui/playwright.config.ts`:**
  - `testDir: ./e2e`, with `globalSetup: ./e2e/support/global-setup.ts`;
  - no `webServer`;
  - `use.baseURL = process.env.E2E_BASE_URL ?? <admin UI URL through APISIX>`;
  - `workers: 1`, `fullyParallel: false`, `retries: 0`, `timeout: 90_000`, `expect.timeout: 15_000`;
  - `trace`/`video: retain-on-failure`, `screenshot: only-on-failure`;
  - reporters `list` and `html` (open never), plus `junit` to `test-results/junit.xml`;
  - one Chromium project.
- **`e2e/support/`:**
  - `global-setup.ts`: checks the health of APISIX, Keycloak, mw-admin-api and the admin UI, and that the seed manifest from ticket 57 is present. On failure it stops with the exact commands, e.g. `make up && make seed`. It resets `e2e/.state/run.json`.
  - `session.ts`: one real Keycloak login per persona (maker, checker, viewer from the seed) per run, cached and injected again with `addInitScript` or `storageState`, whichever matches how the UI keeps its session. A rate limit is retried inside `toPass`.
  - `personas.ts`: maps persona names to seeded users. Passwords come from `deploy/.env` through env vars, never from the repo.
  - `state.ts`: `saveState` / `readState` / `need()`.
  - `approvals.ts`: `asChecker()` opens a new browser context.
- **Migrate the 17 tests** (approvals 5, console 5, templates 3, partners 2, ussd 2) into numbered spec files (`00-smoke.spec.ts`, `10-console…`). They use seeded data and data they create themselves with unique names. A test that needed a mock fault (`/__test/fail-change-request-list`, the "no action buttons when the open-request check fails" note from commit 0da2ea9) becomes a component or unit test in vitest with a mocked fetch. Tag the specs `@smoke`, `@approvals`, … for `--grep`.
- **Delete** `e2e/mock/`, `e2e/fixtures/admin-api.json`, the `/__test/*` helpers and `E2E_SKIP_BUILD`.
- **Make:** `make ui-e2e [GREP=]` runs `pnpm install --frozen-lockfile` if needed, then `pnpm test:e2e`, against the running stack. The browsers install with `pnpm exec playwright install chromium` on first use. Update the README, `ui/mw-admin-ui/CLAUDE.md`, the root `CLAUDE.md`, `docs/demo.md` and `test_repo_docs.sh` expectations.

**Blocked by:** 57 (seed), 56 (stack); can run in parallel with 58

**Status:** done

- [x] On a fresh stack, `make reset && make build && make up && make seed && make ui-e2e` passes, and a second `make ui-e2e` passes too
- [x] With mw-admin-api stopped, `make ui-e2e` fails in global setup within 30 s, with the fix command printed
- [x] The vitest that replaces the failing-list mock passes (`pnpm test`); `e2e/mock` is gone
- [x] `make test-scripts` passes; `make log-scan` is clean after the run

## Evidence (run 2026-11-06, 2ad973ce + d614ac37)
- First live run: 1/16 (every login 502 "upstream sent too big header" at APISIX). Fixed with proxy_buffer_size 16k; two spec fixes.
- Seeded stack, `make ui-e2e` twice without reset: 16/16 in 47 s, 16/16 in 61 s; third run after Postgres recreate 16/16 in 38 s.
- Fail-fast with mw-admin-api hidden: global setup failed in 1 s with `make up && make seed`.
- log-scan: Postgres DETAIL leaked an unmasked wallet MSISDN -> log_error_verbosity=terse; then clean.
- pnpm test 80/80; e2e/mock gone. Open: compliance persona (user: add, end-of-run fixer).
