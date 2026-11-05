# D7: One USSD adapter, SMPP or HTTP per MNO by configuration

Status: Accepted, 2026-11-03 (spec revision 1).

## Context

Bangladeshi MNOs offer USSD over SMPP (GP) or over HTTP gateways. The menu logic must not be written twice.

## Decision

`mw-ussd` has one menu engine (menu JSON, Valkey sessions) and a `UssdTransport` interface with `SmppUssdTransport` and `HttpUssdTransport`. Each MNO's transport is a configuration value (`mw.ussd.mno.<mno>.transport`); switching needs no code change.

## Consequences

- The same menu walk is tested over both transports (`UssdMenuWalkSuite`).
- The SMPP transport owns bind handling (bind_transceiver, enquire_link, reconnect, throttling).

## Where in the repo

- `services/mw-ussd/src/main/java/mfs/mw/ussd/transport`
- `services/mw-ussd/src/main/resources/application.yml` (per-MNO block)
- spec section 9.2
