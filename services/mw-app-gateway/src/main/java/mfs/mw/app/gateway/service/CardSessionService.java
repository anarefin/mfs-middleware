package mfs.mw.app.gateway.service;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import mfs.mw.app.gateway.client.AuthClient;
import mfs.mw.app.gateway.config.AppGatewayProperties;
import mfs.mw.app.gateway.dto.AppUser;
import mfs.mw.app.gateway.dto.CardSessionRequest;
import mfs.mw.app.gateway.dto.CardSessionView;
import mfs.mw.app.gateway.dto.StatusMessage;
import mfs.mw.app.gateway.dto.TransactionView;
import mfs.mw.app.gateway.dto.TxnRequest;
import mfs.mw.app.gateway.exception.ErrorCodes;
import mfs.mw.app.gateway.exception.MwException;
import mfs.mw.app.gateway.mapper.RequestMapper;
import mfs.mw.app.gateway.messaging.EventPublisher;
import mfs.mw.app.gateway.repository.AppTransactionRepository;
import mfs.mw.app.gateway.util.UuidV7;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Add money from a card (ticket 118b, FRS UC-13, EXT-14, FL-07). Start: customer role only; mints the
 * txnId, gets mw-auth's assertion for an ADD_MONEY_CARD from {@code CARD_TOKEN cybersource} to the
 * token's own wallet, publishes it with this instance's {@code mw-reply-to} and waits (the reply budget)
 * for the PARTNER_PENDING status that carries the acquirer's hosted page ({@code attributes.hostedPageUrl},
 * already checked by mw-orchestrator: https on an allowed host), or a refusal. The URL is handed back,
 * never logged. Status: the caller's own ADD_MONEY_CARD from the projection.
 */
@Service
public class CardSessionService {

  private static final Logger log = LoggerFactory.getLogger(CardSessionService.class);

  static final String HOSTED_PAGE_URL = "hostedPageUrl";
  static final String SESSION_ID = "sessionId";

  private final TxnReplyExchange exchange;
  private final RequestMapper mapper;
  private final AppTransactionRepository transactions;
  private final AppGatewayProperties props;
  private final AuthClient auth;
  private final Clock clock;

  public CardSessionService(
      EventPublisher publisher,
      ReplyWaiters waiters,
      RequestMapper mapper,
      AppTransactionRepository transactions,
      AppGatewayProperties props,
      AuthClient auth,
      Clock clock) {
    this.exchange = new TxnReplyExchange(publisher, waiters, props);
    this.mapper = mapper;
    this.transactions = transactions;
    this.props = props;
    this.auth = auth;
    this.clock = clock;
  }

  public CardSessionView start(AppUser user, String idempotencyKey, CardSessionRequest body) {
    if (!props.mayInitiate(user.walletType(), RequestMapper.ADD_MONEY_CARD)) {
      throw new MwException(ErrorCodes.AUTH_FORBIDDEN, "This app role cannot add money from a card")
          .withStatus(HttpStatus.FORBIDDEN);
    }
    Instant now = clock.instant();
    String txnId = UuidV7.next(clock).toString();
    TxnRequest unauthorised =
        mapper.toCardRequest(user, idempotencyKey, body, txnId, TxnReplyExchange.correlationId(), now);
    TxnRequest req = unauthorised.withAssertion(
        auth.authorise(mapper.toAuthorisation(user, user.deviceId(), unauthorised, body.encryptedPin())).assertion());
    CompletableFuture<StatusMessage> answer = exchange.publish(req);
    transactions.insertAccepted(user.msisdn(), user.channel(), mapper.accepted(req), now);
    log.atInfo()
        .addKeyValue("event", "txn.request.published")
        .addKeyValue("txnId", txnId)
        .addKeyValue("channel", user.channel())
        .log("add money from card requested");
    StatusMessage status = exchange.await(answer, txnId);
    if (status == null) {
      return CardSessionView.processing(txnId);
    }
    TxnReplyExchange.throwIfRefused(status);
    String url = status.attributes() == null ? null : status.attributes().get(HOSTED_PAGE_URL);
    if (url == null) {
      // a repeated key whose session was already handed out, or a status without the page: poll the GET
      return CardSessionView.processing(status.awaitedTxnId().equals(txnId) ? txnId : status.txnId());
    }
    return new CardSessionView(status.txnId(), status.status(), status.attributes().get(SESSION_ID), url, status.amount());
  }

  /** The caller's own ADD_MONEY_CARD; any other transaction or wallet is 404. */
  public TransactionView find(AppUser user, String txnId) {
    UUID id;
    try {
      id = UUID.fromString(txnId);
    } catch (IllegalArgumentException e) {
      throw notFound();
    }
    return transactions.find(id, user.msisdn())
        .filter(v -> RequestMapper.ADD_MONEY_CARD.equals(v.txnType()))
        .orElseThrow(CardSessionService::notFound);
  }

  private static MwException notFound() {
    return new MwException(ErrorCodes.VAL_NOT_FOUND, "Not found").withStatus(HttpStatus.NOT_FOUND);
  }
}
