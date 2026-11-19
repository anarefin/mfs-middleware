package mfs.mw.app.gateway.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import mfs.mw.app.gateway.config.IdempotentReplayProperties;
import mfs.mw.app.gateway.dto.AppUser;
import mfs.mw.app.gateway.dto.TransactionRequest;
import mfs.mw.app.gateway.dto.TransactionView;
import mfs.mw.app.gateway.exception.ErrorCodes;
import mfs.mw.app.gateway.exception.MwException;
import mfs.mw.app.gateway.repository.IdempotentResponseStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Stored original response (FRS PLT-011, ticket 99): the first 200 answer under an idempotency key
 * (scope channel, wallet, key) is kept with a hash of the request content, for the key's lifetime.
 * A repeat with the same content gets that body again, byte for byte, before mw-auth or the
 * orchestrator is asked (the live transaction may have changed since, e.g. reversed); other content
 * is 409 {@code MW-IDEM-001}. The content is what the orchestrator hashes: type, parties, amount,
 * partner, attributes; not the PIN ciphertext (fresh every time), the locale or
 * {@code confirmDuplicate}. Redis trouble never fails a request: it is logged
 * {@code txn.idempotent.store_unavailable} and the orchestrator answers as before. A key whose first
 * answer was not 200 (202, FAILED, REJECTED) has nothing stored: the orchestrator answers a repeat
 * from the transaction, which for a terminal FAILED cannot change any more.
 */
@Service
public class IdempotentReplay {

  private static final Logger log = LoggerFactory.getLogger(IdempotentReplay.class);
  private static final HexFormat HEX = HexFormat.of();

  private final IdempotentResponseStore store;
  private final IdempotentReplayProperties props;
  private final JsonMapper json;
  private final JsonMapper canonical;

  public IdempotentReplay(IdempotentResponseStore store, IdempotentReplayProperties props, JsonMapper json) {
    this.store = store;
    this.props = props;
    this.json = json;
    this.canonical = json.rebuild().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();
  }

  /** The stored response for this key and content; 409 MW-IDEM-001 when the content differs. */
  public Optional<TransactionView> replay(AppUser user, String idempotencyKey, TransactionRequest body) {
    if (!props.enabled()) {
      return Optional.empty();
    }
    Optional<IdempotentResponseStore.Stored> stored;
    try {
      stored = store.find(scope(user, idempotencyKey));
    } catch (RuntimeException e) {
      unavailable(e);
      return Optional.empty();
    }
    if (stored.isEmpty()) {
      return Optional.empty();
    }
    if (!stored.get().requestHash().equals(requestHash(body))) {
      throw new MwException(ErrorCodes.IDEM_CONFLICT, "Idempotency key was already used with a different request");
    }
    log.atInfo()
        .addKeyValue("event", "txn.idempotent.replay")
        .addKeyValue("channel", user.channel())
        .addKeyValue("source", "stored-response")
        .log("repeated request answered with the stored response");
    return Optional.of(json.readValue(stored.get().body(), TransactionView.class));
  }

  /** Keeps a 200 answer unless the key already has one. */
  public void remember(AppUser user, String idempotencyKey, TransactionRequest body, TransactionView view) {
    if (!props.enabled()) {
      return;
    }
    try {
      store.saveFirst(
          scope(user, idempotencyKey),
          new IdempotentResponseStore.Stored(requestHash(body), json.writeValueAsString(view)),
          props.ttl());
    } catch (RuntimeException e) {
      unavailable(e);
    }
  }

  String requestHash(TransactionRequest body) {
    Map<String, Object> content = new LinkedHashMap<>();
    content.put("txnType", body.txnType());
    content.put("creditParty", body.creditParty());
    content.put("debitParty", body.debitParty());
    content.put("amount", body.amount());
    content.put("partner", body.partner());
    content.put("attributes", body.attributes());
    return sha256(canonical.writeValueAsString(content));
  }

  private static String scope(AppUser user, String idempotencyKey) {
    return sha256(user.channel() + "\u0000" + user.msisdn() + "\u0000" + idempotencyKey);
  }

  private static String sha256(String text) {
    try {
      return HEX.formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is not available", e);
    }
  }

  private static void unavailable(RuntimeException e) {
    log.atWarn()
        .addKeyValue("event", "txn.idempotent.store_unavailable")
        .addKeyValue("reason", e.getClass().getSimpleName())
        .log("stored-response store unavailable; the orchestrator answers the repeat");
  }
}
