# Implementer brief (FRS run 89-138) — read fully before starting

You implement exactly ONE ticket of the Bits MFS middleware repo. The ticket file is your spec.

Pointers (read, don't re-explore): ticket file; `/Users/ahmadnaqibularefin/Projects/mfs-middleware/.scratch/bits-mfs-middleware/notes/frs-run-notes.md`
(code map for 90-104, paths + line numbers; note MAP.md is partly stale: the build is one root multi-module Gradle build, no Makefile);
root `CLAUDE.md` + the touched services' `CLAUDE.md`; `spec/FRS-Upay-Middleware.md`; ADRs `docs/adr/D29..D33`;
`docs/frs-traceability.md` (update the status of the FRS ids your ticket closes).

Rules:
1. Integration branch `upay-main`. Confirm your worktree is based on its tip (`git merge-base --is-ancestor <sha-given> HEAD`);
   if not, `git checkout -B ticket/<NN-slug> upay-main` (`git reset --hard` is permission-blocked). Work on `ticket/<NN-slug>`.
2. Call the Skill tool with `tdd`; tests first.
3. Latest stable versions (user rule): every library, plugin or image you add or touch is pinned to the latest stable
   release (no RC/milestone/beta); check via Context7 / the release page. Gradle plugin versions must stay identical
   across all modules (`./gradlew :checkIsolation`).
4. Verify only what you touch: `./gradlew :mw-x:build` per touched module, plus `:scriptTests` (scripts/docs),
   `:contractsLint` (contracts / contracts.yml), `:composeCheck` (compose), `:checkIsolation` (build files). Docker is
   available. Do NOT run the full `clean build`, the stack or the API suite (the milestone gate does).
5. Never change a contract another service owns unless the ticket says so; Flyway: next free V number of that module;
   constructor injection only; no System.out; masking rules of CLAUDE.md.
6. Commit(s) Conventional Commits, ending with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
   Do not edit `.scratch/bits-mfs-middleware/issues/DEPENDENCIES.md`.
7. Before reporting: merge the current `upay-main` tip into your branch, re-run the touched modules' build if anything came in.
8. Time box: if the ticket needs more than ~2 h, a dependency's latest version breaks things badly, or the ticket
   contradicts the code/spec, STOP and report the problem with options rather than guessing.

Report (short): branch, commit sha(s), diffstat, elapsed minutes, test commands + result lines, versions pinned, open items.
