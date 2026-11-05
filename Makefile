# upay MFS middleware: local quality gate (no CI/CD in the MVP, spec "MVP build plan").
#
# Every target works for all modules, or for one with M=<module> (name such as
# mw-orchestrator, or a path such as services/mw-orchestrator).
# A module is any services/* or templates/* folder with its own settings.gradle(.kts).
#
#   make verify [M=]            isolation + field injection + contracts lint + ./gradlew check per module (+ script self-tests)
#   make check-isolation [M=]   module independence rules (spec 4.3)
#   make check-field-injection [M=]  no @Autowired fields under any module's src/
#   make contracts-lint         lint the root contracts/ folder
#   make versions-check [M=]    report version drift between module version catalogs
#   make up [M=]                start the Compose stack (deploy/compose/mvp) or one module's compose.yml
#   make smoke                  live checks against the running MVP stack (scripts/smoke.sh)
#   make edge-test              live APISIX edge policy tests: 401/400/413/429, headers, idempotent apply
#   make down [DOWN_ARGS=-v]    stop the MVP stack (-v also drops its volumes)
#   make e2e [M=] [TAGS=]       Cucumber suite in tests/e2e against the running stack
#   make e2e-changed [BASE=main] [TAGS=]  rebuild only modules changed since BASE into the running stack, then e2e (no verify)
#   make perf [M=]              Gatling suite in tests/perf
#   make security [M=]          dependency, container and secret scans
#   make new-service NAME=mw-x  copy templates/mw-service-template to services/mw-x
#   make test-scripts           shell tests for this Makefile and scripts/

SCRIPTS := $(patsubst %/,%,$(dir $(abspath $(lastword $(MAKEFILE_LIST)))))/scripts
MW_ROOT ?= $(patsubst %/,%,$(dir $(abspath $(lastword $(MAKEFILE_LIST)))))
SELFTEST ?= 1
M ?=
BASE ?=
export MW_ROOT

.PHONY: help verify check-isolation check-field-injection contracts-lint versions-check up smoke edge-test down e2e e2e-changed perf security new-service test-scripts

help:
	@sed -n '3,22p' $(abspath $(lastword $(MAKEFILE_LIST)))

verify:
ifeq ($(SELFTEST)$(M),1)
	@$(SCRIPTS)/test/run.sh
endif
	@$(SCRIPTS)/verify.sh "$(M)"

check-isolation:
	@$(SCRIPTS)/check-isolation.sh "$(M)"

check-field-injection:
	@$(SCRIPTS)/check-field-injection.sh "$(M)"

contracts-lint:
	@$(SCRIPTS)/contracts-lint.sh

versions-check:
	@$(SCRIPTS)/versions-check.sh "$(M)"

up:
	@$(SCRIPTS)/up.sh "$(M)"

smoke:
	@$(SCRIPTS)/smoke.sh

edge-test:
	@$(SCRIPTS)/edge-test.sh

down:
	@$(SCRIPTS)/down.sh

e2e:
	@$(SCRIPTS)/suite.sh e2e "$(M)" "$(TAGS)"

e2e-changed:
	@$(SCRIPTS)/e2e-changed.sh "$(BASE)" "$(TAGS)"

perf:
	@$(SCRIPTS)/suite.sh perf "$(M)" "$(TAGS)"

security:
	@$(SCRIPTS)/security.sh "$(M)"

new-service:
	@$(SCRIPTS)/new-service.sh "$(NAME)"

test-scripts:
	@$(SCRIPTS)/test/run.sh
