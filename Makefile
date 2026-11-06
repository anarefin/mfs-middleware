# Bits MFS middleware: local quality gate (no CI/CD in the MVP, spec "MVP build plan").
#
# Every target works for all modules, or for one with M=<module> (name such as
# mw-orchestrator, or a path such as services/mw-orchestrator).
# A module is any services/* or templates/* folder with its own settings.gradle(.kts).
#
#   make verify [M=] [JOBS=3]   isolation + field injection + contracts lint + ./gradlew check per module, JOBS at a time (+ script self-tests); KEEP_TC=1 keeps the reused test containers
#   make verify-changed [BASE=main] [JOBS=3]  the same gate for the modules changed since BASE only (contracts mapped precisely), JOBS at a time
#   make check-isolation [M=]   module independence rules (spec 4.3)
#   make check-field-injection [M=]  no @Autowired fields under any module's src/
#   make contracts-lint         lint the root contracts/ folder
#   make versions-check [M=]    report version drift between module version catalogs
#   make build [M=] [JOBS=3]    bootJar per stack module (JOBS at a time), then docker compose build
#   make up [PROFILE=core] [TIMERS=fast] [M=]  start the stack (deploy/docker-compose.yml) without building
#   make seed                   run the seed profile's mw-seeder one-shot; fails unless it exits 0
#   make compose-check          fail when the stack's memory limits exceed the budget (10 GB)
#   make smoke                  live checks against the running MVP stack (scripts/smoke.sh)
#   make edge-test              live APISIX edge policy tests: 401/400/413/429, headers, idempotent apply
#   make chaos [TARGETS=]       kill each mw-* container, kafka, postgres mid-flow; no stuck txns, ledger consistent (scripts/chaos.sh)
#   make down [DOWN_ARGS=-v]    stop the stack, every profile (-v also drops its volumes)
#   make reset                  remove every container, volume and orphan of the stack (fresh start)
#   make api-test [FOLDER=] [ALL=1]  Newman API suite in tests/api against the running stack (make up TIMERS=fast && make seed)
#   make ui-e2e [GREP=]         admin UI Playwright suite against the running, seeded stack (ui/mw-admin-ui)
#   make perf [M=]              Gatling suite in tests/perf
#   make security [M=]          secret, dependency and image scans; with the stack up also ZAP baseline + log scan
#   make log-scan               scan every container log of the running stack for PIN / unmasked MSISDN / NID
#   make new-service NAME=mw-x  copy templates/mw-service-template to services/mw-x
#   make test-scripts           shell tests for this Makefile and scripts/

SCRIPTS := $(patsubst %/,%,$(dir $(abspath $(lastword $(MAKEFILE_LIST)))))/scripts
MW_ROOT ?= $(patsubst %/,%,$(dir $(abspath $(lastword $(MAKEFILE_LIST)))))
SELFTEST ?= 1
M ?=
BASE ?=
JOBS ?= 3
PROFILE ?=
TIMERS ?=
export MW_ROOT PROFILE TIMERS

.PHONY: help verify verify-changed check-isolation check-field-injection contracts-lint versions-check build compose-check up seed reset smoke edge-test chaos down api-test ui-e2e perf security log-scan new-service test-scripts

help:
	@sed -n '3,28p' $(abspath $(lastword $(MAKEFILE_LIST)))

verify:
ifeq ($(SELFTEST)$(M),1)
	@$(SCRIPTS)/test/run.sh
endif
	@$(SCRIPTS)/verify.sh "$(M)" "$(JOBS)"

verify-changed:
	@$(SCRIPTS)/verify-changed.sh "$(BASE)" "$(JOBS)"

check-isolation:
	@$(SCRIPTS)/check-isolation.sh "$(M)"

check-field-injection:
	@$(SCRIPTS)/check-field-injection.sh "$(M)"

contracts-lint:
	@$(SCRIPTS)/contracts-lint.sh

versions-check:
	@$(SCRIPTS)/versions-check.sh "$(M)"

build:
	@$(SCRIPTS)/build.sh "$(M)" "$(JOBS)"

compose-check:
	@$(SCRIPTS)/compose-check.sh

up:
	@$(SCRIPTS)/up.sh "$(M)"

seed:
	@$(SCRIPTS)/seed.sh

smoke:
	@$(SCRIPTS)/smoke.sh

edge-test:
	@$(SCRIPTS)/edge-test.sh

chaos:
	@CHAOS_TARGETS="$(or $(TARGETS),$(CHAOS_TARGETS))" $(SCRIPTS)/chaos.sh

down:
	@$(SCRIPTS)/down.sh

reset:
	@$(SCRIPTS)/down.sh --reset

api-test:
	@$(SCRIPTS)/api-test.sh "$(FOLDER)" "$(ALL)"

ui-e2e:
	@$(SCRIPTS)/ui-e2e.sh "$(GREP)"

perf:
	@$(SCRIPTS)/suite.sh perf "$(M)"

security:
	@$(SCRIPTS)/security.sh "$(M)"

log-scan:
	@$(SCRIPTS)/log-scan.sh

new-service:
	@$(SCRIPTS)/new-service.sh "$(NAME)"

test-scripts:
	@$(SCRIPTS)/test/run.sh
