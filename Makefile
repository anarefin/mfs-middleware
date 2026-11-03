# upay MFS middleware: local quality gate (no CI/CD in the MVP, spec "MVP build plan").
#
# Every target works for all modules, or for one with M=<module> (name such as
# mw-orchestrator, or a path such as services/mw-orchestrator).
# A module is any services/* or templates/* folder with its own settings.gradle(.kts).
#
#   make verify [M=]            isolation + contracts lint + ./gradlew check per module (+ script self-tests)
#   make check-isolation [M=]   module independence rules (spec 4.3)
#   make contracts-lint         lint the root contracts/ folder
#   make versions-check [M=]    report version drift between module version catalogs
#   make up [M=]                start the Compose stack (deploy/compose/mvp) or one module's compose.yml
#   make e2e [M=] [TAGS=]       Cucumber suite in tests/e2e against the running stack
#   make perf [M=]              Gatling suite in tests/perf
#   make security [M=]          dependency, container and secret scans
#   make new-service NAME=mw-x  copy templates/mw-service-template to services/mw-x
#   make test-scripts           shell tests for this Makefile and scripts/

SCRIPTS := $(patsubst %/,%,$(dir $(abspath $(lastword $(MAKEFILE_LIST)))))/scripts
MW_ROOT ?= $(patsubst %/,%,$(dir $(abspath $(lastword $(MAKEFILE_LIST)))))
SELFTEST ?= 1
M ?=
export MW_ROOT

.PHONY: help verify check-isolation contracts-lint versions-check up e2e perf security new-service test-scripts

help:
	@sed -n '3,17p' $(abspath $(lastword $(MAKEFILE_LIST)))

verify:
ifeq ($(SELFTEST)$(M),1)
	@$(SCRIPTS)/test/run.sh
endif
	@$(SCRIPTS)/verify.sh "$(M)"

check-isolation:
	@$(SCRIPTS)/check-isolation.sh "$(M)"

contracts-lint:
	@$(SCRIPTS)/contracts-lint.sh

versions-check:
	@$(SCRIPTS)/versions-check.sh "$(M)"

up:
	@$(SCRIPTS)/up.sh "$(M)"

e2e:
	@$(SCRIPTS)/suite.sh e2e "$(M)" "$(TAGS)"

perf:
	@$(SCRIPTS)/suite.sh perf "$(M)" "$(TAGS)"

security:
	@$(SCRIPTS)/security.sh "$(M)"

new-service:
	@$(SCRIPTS)/new-service.sh "$(NAME)"

test-scripts:
	@$(SCRIPTS)/test/run.sh
