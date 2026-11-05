# D15: Layered packages under mfs.mw.<service>

Status: Accepted, 2026-11-03 (spec revision 3).

## Context

Many agent-written services must look alike so reviewers can read any of them quickly.

## Decision

Package root `mfs.mw.<service>`; conventional layered Spring Boot packages (`controller`, `service`, `repository`, `entity`, `dto`, `mapper`, `exception`, `config` and integration packages such as `messaging`, `route`, `client`, `scheduler`), one kind of class per package; dependencies point one way. No Spring Modulith, no ArchUnit: layering is enforced by review and `CLAUDE.md`. Sanctioned exception: the adapter framework `partner/` package in each adapter.

## Consequences

- Tests mirror the main packages.
- Constructor injection only (`make check-field-injection`).

## Where in the repo

- root `CLAUDE.md`, section "Repository and package layout"
- `scripts/check-field-injection.sh`
- spec section 4.4
