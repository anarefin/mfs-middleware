package mfs.mw.app.gateway.service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import mfs.mw.app.gateway.client.AuthClient;
import mfs.mw.app.gateway.config.AppGatewayProperties;
import mfs.mw.app.gateway.dto.AppUser;
import mfs.mw.app.gateway.dto.ErrorDetail;
import mfs.mw.app.gateway.dto.PartnerRef;
import mfs.mw.app.gateway.dto.StatusMessage;
import mfs.mw.app.gateway.dto.SubmitResult;
import mfs.mw.app.gateway.dto.TransactionRequest;
import mfs.mw.app.gateway.dto.TransactionView;
import mfs.mw.app.gateway.dto.TxnRequest;
import mfs.mw.app.gateway.exception.ErrorCodes;
import mfs.mw.app.gateway.exception.MwException;
import mfs.mw.app.gateway.mapper.RequestMapper;
import mfs.mw.app.gateway.messaging.EventPublisher;
import mfs.mw.app.gateway.repository.AppTransactionRepository;
import mfs.mw.app.gateway.util.Msisdn;
import mfs.mw.app.gateway.util.UuidV7;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * App transactions (spec 9.1, 7.5): mints the txnId, gets mw-auth's transaction assertion for the
 * PIN and the request's values (ticket 72, D22), publishes the canonical request with this
 * instance's {@code mw-reply-to}, then waits on the HTTP request's own (virtual) thread for the
 * answer, at most the reply budget. Idempotency itself is decided by mw-orchestrator (7.3); this
 * service requires the key, passes it on and replays the first 200 answer under it
 * ({@link IdempotentReplay}, FRS PLT-011).
 */
@Service
public class TransactionService {

  private static final Logger log = LoggerFactory.getLogger(TransactionService.class);

  /** Types whose counterparty is another wallet of this core. */
  private static final java.util.Set<String> WALLET_TO_WALLET = java.util.Set.of("P2P", "CASH_IN", "CASH_OUT", RequestMapper.MERCHANT_PAY);

  private final TxnReplyExchange exchange;
  private final RequestMapper mapper;
  private final AppTransactionRepository transactions;
  private final AppGatewayProperties props;
  private final Clock clock;
  private final BillerService billers;
  private final AuthClient auth;
  private final IdempotentReplay replay;

  public TransactionService(
      EventPublisher publisher,
      ReplyWaiters waiters,
      RequestMapper mapper,
      AppTransactionRepository transactions,
      AppGatewayProperties props,
      Clock clock,
      BillerService billers,
      AuthClient auth,
      IdempotentReplay replay) {
    this.exchange = new TxnReplyExchange(publisher, waiters, props);
    this.mapper = mapper;
    this.transactions = transactions;
    this.props = props;
    this.clock = clock;
    this.billers = billers;
    this.auth = auth;
    this.replay = replay;
  }

  public SubmitResult submit(AppUser user, String idempotencyKey, TransactionRequest body) {
    check(user, body);
    // FRS PLT-011: a key already answered 200 gets that very body again (or 409 for other content)
    Optional<TransactionView> stored = replay.replay(user, idempotencyKey, body);
    if (stored.isPresent()) {
      return SubmitResult.done(stored.get());
    }
    PartnerRef partner =
        RequestMapper.BILL_PAY.equals(body.txnType())
            ? billers.partnerFor(body.partner().billerCode())
            : body.partner() == null
                ? null
                : RequestMapper.partnerOf(body);
    Instant now = clock.instant();
    String txnId = UuidV7.next(clock).toString();
    String correlationId = TxnReplyExchange.correlationId();
    TxnRequest unauthorised = mapper.toRequest(user, idempotencyKey, body, txnId, correlationId, now, partner);
    // the PIN goes to mw-auth only (ticket 72): its refusal (wrong PIN 401, lock 423, ...) is
    // answered at once and nothing is published; the request carries the assertion instead
    TxnRequest req = unauthorised.withAssertion(
        auth.authorise(mapper.toAuthorisation(user, user.deviceId(), unauthorised, body.encryptedPin())).assertion());

    CompletableFuture<StatusMessage> answer = exchange.publish(req);
    transactions.insertAccepted(user.msisdn(), user.channel(), mapper.accepted(req), now);
    log.atInfo()
        .addKeyValue("event", "txn.request.published")
        .addKeyValue("txnId", txnId)
        .addKeyValue("channel", user.channel())
        .log("transaction request published");

    StatusMessage status = exchange.await(answer, txnId);
    if (status == null) {
      return SubmitResult.processing(TransactionView.processing(txnId));
    }
    SubmitResult result = answerFor(status);
    if (result.done()) {
      replay.remember(user, idempotencyKey, body, result.view());
    }
    return result;
  }

  /**
   * The channel rules of a transaction before anything is asked (also the preview's, ticket 105): type
   * offered, role may initiate it, attributes allow-listed, the fields of its flow, not the own wallet.
   */
  public void check(AppUser user, TransactionRequest body) {
    if (!props.supportedTxnTypes().contains(body.txnType())) {
      throw new MwException(ErrorCodes.VAL_UNSUPPORTED_TXN_TYPE, "Transaction type is not supported");
    }
    if (!props.mayInitiate(user.walletType(), body.txnType())) {
      throw new MwException(ErrorCodes.AUTH_FORBIDDEN, "This app role cannot initiate this transaction type")
          .withStatus(HttpStatus.FORBIDDEN);
    }
    refuseUnknownAttributes(body);
    refuseFieldsOfOtherFlows(body);
    refuseOwnWallet(user, body);
  }

  /** Only allow-listed attribute keys (MW-VAL-001 naming each other key). */
  private void refuseUnknownAttributes(TransactionRequest body) {
    if (body.attributes() == null) {
      return;
    }
    List<ErrorDetail> refused =
        body.attributes().keySet().stream()
            .filter(k -> !props.allowedAttributes().contains(k))
            .sorted()
            .map(k -> new ErrorDetail("attributes." + k, "is not allowed"))
            .toList();
    if (!refused.isEmpty()) {
      throw new MwException(ErrorCodes.VAL_INVALID, "Request validation failed").withDetails(refused);
    }
  }

  /**
   * TOPUP needs {@code partner} (operator and product) and recharges an MSISDN; BILL_PAY needs
   * {@code partner.billerCode} only and pays a bill number ({@code BILLER_ACCOUNT}); every other
   * type pays a wallet and takes no partner. {@code creditParty.type} may be left out except for
   * BILL_PAY (the flow's type is used). MW-VAL-001 naming the field.
   */
  private static void refuseFieldsOfOtherFlows(TransactionRequest body) {
    boolean topup = RequestMapper.TOPUP.equals(body.txnType());
    boolean bill = RequestMapper.BILL_PAY.equals(body.txnType());
    boolean addMoney = RequestMapper.ADD_MONEY_BANK.equals(body.txnType());
    String expected = topup ? "MSISDN" : bill ? "BILLER_ACCOUNT" : "WALLET";
    String type = body.creditParty() == null ? null : body.creditParty().type();
    TransactionRequest.Partner partner = body.partner();
    String issue = null;
    String field = null;
    if (addMoney) {
      // ADD_MONEY_BANK (ticket 23): from the caller's bank account into the caller's own wallet
      if (body.debitParty() == null) {
        field = "debitParty";
        issue = "the bank account (type BANK_ACCOUNT) is required for ADD_MONEY_BANK";
      } else if (partner == null || !partner.isBank()) {
        field = "partner.partnerId";
        issue = "a partner bank is required for ADD_MONEY_BANK, without productCode or billerCode";
      } else if (body.creditParty() != null) {
        field = "creditParty";
        issue = "is not allowed for ADD_MONEY_BANK: the money goes to your own wallet";
      }
    } else if (body.debitParty() != null) {
      field = "debitParty";
      issue = "is only allowed for ADD_MONEY_BANK";
    } else if (body.creditParty() == null) {
      field = "creditParty";
      issue = "is required for " + body.txnType();
    } else if (topup && (partner == null || partner.productCode() == null || partner.billerCode() != null)) {
      field = "partner";
      issue = "productCode is required for TOPUP";
    } else if (topup && partner.partnerId() != null && TransactionRequest.BANKS.contains(partner.partnerId())) {
      field = "partner.partnerId";
      issue = "a bank is not a mobile operator";
    } else if (bill && (partner == null || !partner.isBiller())) {
      field = "partner.billerCode";
      issue = "is required for BILL_PAY, without partnerId or productCode";
    } else if (!topup && !bill && partner != null) {
      field = "partner";
      issue = "is only allowed for TOPUP, BILL_PAY and ADD_MONEY_BANK";
    } else if ((type != null || bill) && !expected.equals(type)) {
      // BILL_PAY names its type: the id is checked as a bill number only for BILLER_ACCOUNT
      field = "creditParty.type";
      issue = "must be " + expected + " for " + body.txnType();
    }
    if (field != null) {
      throw new MwException(ErrorCodes.VAL_INVALID, "Request validation failed")
          .withDetails(List.of(new ErrorDetail(field, issue)));
    }
  }

  /**
   * Wallet-to-wallet types never name the initiator's own wallet as counterparty (MW-VAL-012).
   * The core also refuses debit = credit and the wrong wallet types; this answers early.
   */
  private static void refuseOwnWallet(AppUser user, TransactionRequest body) {
    if (WALLET_TO_WALLET.contains(body.txnType())
        && user.msisdn().equals(Msisdn.canonical(body.creditParty().id()))) {
      throw new MwException(ErrorCodes.VAL_SELF_COUNTERPARTY, "The counterparty cannot be your own wallet")
          .withDetails(List.of(new ErrorDetail("creditParty.id", "is your own wallet")));
    }
  }

  public TransactionView find(AppUser user, String txnId) {
    UUID id;
    try {
      id = UUID.fromString(txnId);
    } catch (IllegalArgumentException e) {
      throw notFound();
    }
    return transactions.find(id, user.msisdn()).orElseThrow(TransactionService::notFound);
  }

  /**
   * A duplicate answer that is still running is 202 under the stored txnId; COMPLETED/REVERSED
   * is 200; FAILED/REJECTED is the error envelope with the status's error code and txnId.
   */
  private static SubmitResult answerFor(StatusMessage s) {
    if (!s.terminal()) {
      return SubmitResult.processing(TransactionView.processing(s.txnId()));
    }
    TxnReplyExchange.throwIfRefused(s);
    return SubmitResult.done(TransactionView.of(s));
  }

  private static MwException notFound() {
    return new MwException(ErrorCodes.VAL_NOT_FOUND, "Not found").withStatus(HttpStatus.NOT_FOUND);
  }
}
