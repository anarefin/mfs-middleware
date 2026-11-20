# Implementation context

## 120a
INTERFACE: SOAP `/partner/ucbatm/ws/v1` (WSDL `atm.wsdl`): VerifyTokenAndDebit, ConfirmDispense, ReversalAdvice, CashIn. Family `atm` on request/reply/route-status/recon/config.changed. Topic `mw.partner.request.atm.v1`. Mock `GET /ucbatm/v1/dispenses/{reference}`.
DECISION: inbound calls are recorded in `atm_inbound` and not published. `mw.partner.inbound.v1` (owner mw-orchestrator) allows only CALLBACK, REMITTANCE, NPSB_CREDIT and has no debit party, so the four ATM operations do not fit; inventing a contract that service must own is out of scope.
CONSTRAINT: admin family CHECK is Flyway V20 (V19 is remit). Keep merchant and card in every family enum. Never log or store the ATM token or the wallet MSISDN. The `partner/` engine copy stays identical to billpay apart from the package.
DEFERRED: ticket 120b — extend `mw.partner.inbound.v1` then publish, token HMAC/expiry/single-use/lockout, `POST /app/v1/atm/tokens`, orchestrator `ATM_CASH_OUT`/`ATM_CASH_IN`, test-console page, `AtmCashOutIT`, Newman 54.

## 115b
INTERFACE: `MerchantPayFlow.MERCHANT` on `mw.partner.request.merchant.v1` (INQUIRY, then PAY notify). Dev wallet `01911000020`. API folder `52-merchant-soap`.
DECISION: a failed notify is a new request id on the same topic, then audit `merchant.notify.failed`. It does not reverse the payment and it is not a Kafka DLQ topic.
CONSTRAINT: folder 52 is merchant SOAP. 50 is checkout refund, 51 bill aggregators, 53 remittance, 54 ATM. A notify never leaves COMPLETED.
DEFERRED: Newman folder 52 against the stack (not run on this branch).
