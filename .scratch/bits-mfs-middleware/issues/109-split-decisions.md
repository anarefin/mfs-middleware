# 109 split (user decisions 2026-11-20) — read with 109-ussd-frs.md

Ticket 109 is past the two-hour box. The first implementer stopped with no commit. Copy this file onto the branch that implements a slice.

- **109b** (no dependency on 109a): per-screen 30s timeout (`@Tag("slow-wait")`) beside the 120s session timeout; back stack `0. Next` / `00. Back`; Flyway `ussd.short_code` and `ussd.customer_pref` (MSISDN hash, default bn). Customer type for a short code is a read of core `GET /core/v1/accounts/{msisdn}` (WAL-01, service JWT). The Transfer-to-bank node shows the maintenance text until 117. No TLV rewrite, no fee call, no PIN change.
- **109a** (after 109b merges; same module): `MnoTlvAdapter` per MNO, transceiver vs transmitter+receiver binds, simulator ports, mock-control parties `ussd-gp`, `ussd-robi`, `ussd-bl`, `ussd-teletalk`.
- **109c** (after 109a merges): My Account except change-PIN, Send Money, Cash Out, Payment, Mobile Recharge, Pay Bill, Add Money, Remittance status. API folder `43-ussd-frs` lands with the walks that exist. Change PIN is not in this slice.

User decisions:

1. TLV: the same 3GPP tags for all four MNOs (`ussd_service_op` 0x0501, `its_session_info` 0x1383). Per-MNO differences are bind mode, port, and session-info packing only. Do not invent tag numbers.
2. Fee: USSD quotes a fee with core `POST /core/v1/charges/calculate` (WAL-04), not app `/preview`.
3. Change PIN: blocked until mw-auth grows an internal change-PIN that accepts PIN ciphertext from a channel service. No second PIN store. The change-PIN menu item stays on maintenance text and must not call `PUT /auth/v1/pin`.
