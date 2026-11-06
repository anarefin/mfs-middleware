# D10: Out of this spec: orchestration, CI/CD and observability tooling

Status: Accepted, 2026-11-03 (spec revision 1).

## Context

Kubernetes, CI/CD and observability stacks are chosen by Bits separately and should not block the middleware build.

## Decision

This spec does not cover Kubernetes, Helm, Argo CD, GitLab CI/CD, OpenTelemetry, Prometheus, Grafana, Loki or Tempo, nor (for the MVP) Apicurio Registry, Avro or Harbor. It defines only what services emit (JSON logs with fixed fields, `/actuator/prometheus` metrics, W3C trace context) and how they are built.

## Consequences

- Services stay tool-neutral: any log shipper or Prometheus scraper can be added later.
- The MVP has no dashboards or alert rules; alert events are stable `event` names in the logs.

## Where in the repo

- root `CLAUDE.md`, section "Log fields"
- spec sections 20, 21
