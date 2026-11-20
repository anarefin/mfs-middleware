package mfs.mw.orchestrator.service;

import mfs.mw.orchestrator.client.CoreWalletClient;
import mfs.mw.orchestrator.client.CoreWalletClient.CoreRead;
import mfs.mw.orchestrator.client.CoreWalletClient.CoreUnavailableException;
import mfs.mw.orchestrator.dto.Amount;
import mfs.mw.orchestrator.dto.CoreCallBodies;
import mfs.mw.orchestrator.dto.Party;
import mfs.mw.orchestrator.dto.Quote;
import mfs.mw.orchestrator.dto.QuoteRequest;
import mfs.mw.orchestrator.exception.MwException;
import mfs.mw.orchestrator.util.MaskingPolicy;
import mfs.mw.orchestrator.util.SensitiveType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/**
 * The quote before the PIN (ticket 105, FRS CH-APP-05, PLT-026; contract {@code txn-query.v1} 1.4.0):
 * reads the core's charges (WAL-04), the payer's limit check (WAL-05) and, for a wallet payee, its
 * account (WAL-01) over REST on the HTTP request's thread. A read only: no saga, no row, nothing
 * moved. A broken limit is part of the answer ({@code limits.allowed} false, the core's reason); the
 * channel decides how to show it. The payee comes back masked (id by type, name to the initial).
 */
@Service
public class QuoteService {

  static final String CORE_UNAVAILABLE = "MW-SYS-CORE_UNAVAILABLE";
  static final String PAYEE_NOT_FOUND = "MW-CORE-CREDIT_WALLET_NOT_FOUND";

  private static final Logger log = LoggerFactory.getLogger(QuoteService.class);

  private final CoreWalletClient core;

  public QuoteService(CoreWalletClient core) {
    this.core = core;
  }

  public Quote quote(QuoteRequest req, String correlationId) {
    try {
      return read(req, correlationId);
    } catch (CoreUnavailableException e) {
      throw unavailable("core", 0, e);
    }
  }

  private Quote read(QuoteRequest req, String correlationId) {
    Amount amount = req.amount().toAmount();
    JsonNode charges = answer(core.charges(
        new CoreCallBodies.Charges(req.txnType(), amount, req.channel(), req.walletType()), correlationId), "charges");
    JsonNode limits = answer(core.limits(new CoreCallBodies.Limits(req.walletId(), req.txnType(), amount), correlationId),
        "limits");
    Party payee = payee(req.counterparty(), correlationId);
    return new Quote(
        req.txnType(),
        amount,
        money(charges.path("fee")),
        money(charges.path("vat")),
        money(charges.path("commission")),
        charges.has("total") ? money(charges.path("total")) : Amount.bdt(amount.valueMinor() + money(charges.path("fee")).valueMinor()),
        limits(limits),
        payee);
  }

  private Party payee(QuoteRequest.Counterparty counterparty, String correlationId) {
    if (counterparty == null) {
      return null;
    }
    String maskedId = MaskingPolicy.maskPartyId(counterparty.type(), counterparty.id());
    if (!"WALLET".equals(counterparty.type())) {
      return new Party(counterparty.type(), maskedId, null, null);
    }
    CoreRead account = core.account(counterparty.id(), correlationId);
    if (account.status() == 404) {
      throw new MwException(PAYEE_NOT_FOUND, "The receiving wallet does not exist")
          .withMessageBn("প্রাপকের ওয়ালেট পাওয়া যায়নি");
    }
    JsonNode a = answer(account, "account");
    String name = a.path("holderName").asString(null);
    return new Party(counterparty.type(), maskedId, name == null ? null : MaskingPolicy.mask(SensitiveType.NAME, name),
        a.path("type").asString(null));
  }

  /** A 2xx body; a core refusal with an envelope passes through; anything else is the core being unavailable. */
  private static JsonNode answer(CoreRead read, String what) {
    if (read.ok() && read.body() != null) {
      return read.body();
    }
    if ((read.status() == 400 || read.status() == 422) && read.code() != null && read.code().startsWith("MW-")) {
      throw new MwException(read.code(), "The core refused the quote");
    }
    throw unavailable(what, read.status(), null);
  }

  private static MwException unavailable(String what, int status, Throwable cause) {
    log.atWarn()
        .addKeyValue("event", "txn.quote.core_unavailable")
        .addKeyValue("call", what)
        .addKeyValue("status", status)
        .log("core gave no usable answer to a quote read");
    return new MwException(CORE_UNAVAILABLE, "The core could not be asked; try again", cause)
        .withStatus(HttpStatus.SERVICE_UNAVAILABLE)
        .withRetryable(true);
  }

  private static Quote.Limits limits(JsonNode l) {
    JsonNode r = l.path("remaining");
    Quote.Remaining remaining = new Quote.Remaining(
        optionalMoney(r.path("perTxn")), optionalMoney(r.path("daily")), optionalMoney(r.path("monthly")),
        r.has("dailyCount") ? r.path("dailyCount").asInt() : null,
        r.has("monthlyCount") ? r.path("monthlyCount").asInt() : null);
    boolean allowed = l.path("allowed").asBoolean(false);
    return new Quote.Limits(allowed, allowed ? null : l.path("reasonCode").asString(null), remaining);
  }

  /**
   * A charge the core actually sent. A missing or non-numeric {@code valueMinor} is not a zero fee:
   * the quote fails as the core being unavailable. A real zero (the core sent {@code 0}) stands.
   */
  private static Amount money(JsonNode n) {
    JsonNode minor = n.path("valueMinor");
    if (!minor.isIntegralNumber()) {
      throw unavailable("amount", 200, null);
    }
    return new Amount(minor.asLong(), n.path("currency").asString(Amount.BDT));
  }

  private static Amount optionalMoney(JsonNode n) {
    return n.isMissingNode() || n.isNull() ? null : money(n);
  }
}
