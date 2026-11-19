# 109: mw-ussd: FRS gaps: four MNOs over SMPP, navigation, short codes, menus (FRS 4.2)

**What to build:** Close the USSD gaps found in the FRS sweep. All four MNOs (GP, Robi, Teletalk, Banglalink) bind
over SMPP against the simulator. Add the transmitter + receiver bind mode, one TLV adapter per MNO, a per-screen
timeout, `00. Back` navigation, short-code root menus, a per-customer language and the missing FRS menus. HTTP mode
stays, chosen per MNO by config (M3).

**Principles:**
- MNO differences live only in `transport/` adapters (CH-USSD-03). The menu engine never branches on the MNO.
- PIN rules do not change: ciphertext only, never in the session after use (CH-USSD-07).

**Scope:**
- `services/mw-ussd/transport/`: replace the one shared TLV reader in `SmppUssdTransport` with an
  `MnoTlvAdapter` per MNO (`GpTlvAdapter`, `RobiTlvAdapter`, `TeletalkTlvAdapter`, `BanglalinkTlvAdapter`).
  Each one reads and writes `ussd_service_op`, `its_session_info` or the MNO-specific TLV. Selected by `mw.ussd.mno.<mno>.tlv-profile`.
- `UssdSmppClient` / `UssdSmppBind`: bind mode `TRANSCEIVER` or `TRANSMITTER_RECEIVER` per MNO (CH-USSD-01). One or
  more binds per MNO. The existing enquire_link, window and reconnect logic is kept.
- `services/mw-simulators`: `UssdSmppGateway` serves all four MNOs on their own ports, each with its own TLV
  profile and a TX+RX mode. The FRS faults (bind refusal, dropped enquire_link, `ESME_RTHROTTLED`) are driven
  through the `/mock-control/ussd-<mno>/scenarios` engine of ticket 102.
- Session (Valkey/Redis per ticket 90, `ussd:{mno}:{sessionId}`): a 30 s per-screen timeout next to the 120 s
  overall timeout, both per MNO (CH-USSD-05).
- Pagination: `0. Next` / `00. Back` (CH-USSD-06). A back stack of node ids is kept in the session.
- Short codes (CH-USSD-08): config table `ussd.short_code` (Flyway): code, MNO, root menu id, allowed customer
  types. An unknown short code or a disallowed type ends the session with a bn/en text.
- Language (CH-USSD-10): stored per MSISDN-hash in the `ussd.customer_pref` table (no plain MSISDN; hash as in
  `MaskingPolicy` helpers). Default bn.
- Menus (`menus/main-v4.json`, published through the existing admin maker-checker): My Account (balance, mini
  statement via core WAL-12 from ticket 94, change PIN via mw-auth `PUT /auth/v1/pin` step-up, language), Send Money,
  Cash Out, Payment (MERCHANT_PAY), Mobile Recharge, Pay Bill, Add Money from Bank, Transfer to Bank (calls the
  W2B flow once 117 lands; until then the node shows the maintenance text), Remittance status.
- Financial nodes show the fee from `/preview` (ticket 105) before the PIN screen.
- Docs: `services/mw-ussd/CLAUDE.md`, `docs/concepts` USSD page, `deploy/dev.env.defaults` knobs, README.

**FRS IDs covered:** CH-USSD-01, 03, 05, 06, 08, 10; minimum USSD menu list (4.2); UC-02/03 on USSD; EXT-01, MCT-04 (USSD part)

**Blocked by:** 94, 105

**Status:** open

- [ ] Unit: each `MnoTlvAdapter` round-trips its TLV set from recorded PDUs; `MenuNavigator` back stack and pagination
- [ ] `UssdSmppMnoIT` (Testcontainers + simulator): one menu walk per MNO over SMPP, in both bind modes
- [ ] `ScreenTimeoutIT`: an idle screen ends after 30 s (`@Tag("slow-wait")`), and the overall timeout still applies
- [ ] Short code IT: a disallowed customer type is refused with bn/en text
- [ ] Language persists across two sessions for the same MSISDN
- [ ] Mini statement, change PIN, Payment, Add Money and Remittance status menus walk end to end against the stack
- [ ] No PIN or unmasked MSISDN in logs (`scripts/log-scan.sh` after the folder run)
- [ ] `./gradlew :mw-ussd:build :mw-simulators:build :contractsLint :scriptTests` green with Docker
- [ ] API suite folder `43-ussd-frs` (4 MNOs, back, short codes, new menus) passes; `19-ussd-flows`, `20-ussd-smpp` still pass
