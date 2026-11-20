package mfs.mw.app.gateway.service;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import mfs.mw.app.gateway.client.OrchestratorQuoteClient;
import mfs.mw.app.gateway.config.PreviewProperties;
import mfs.mw.app.gateway.dto.AppUser;
import mfs.mw.app.gateway.dto.ConfirmRequest;
import mfs.mw.app.gateway.dto.PreviewRequest;
import mfs.mw.app.gateway.dto.PreviewView;
import mfs.mw.app.gateway.dto.Quote;
import mfs.mw.app.gateway.dto.QuoteRequest;
import mfs.mw.app.gateway.dto.SubmitResult;
import mfs.mw.app.gateway.dto.TransactionRequest;
import mfs.mw.app.gateway.exception.ErrorCodes;
import mfs.mw.app.gateway.exception.MwException;
import mfs.mw.app.gateway.exception.OrchestratorUnavailableException;
import mfs.mw.app.gateway.mapper.RequestMapper;
import mfs.mw.app.gateway.repository.PreviewStore;
import mfs.mw.app.gateway.util.Msisdn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * The two-step financial API (ticket 105, FRS CH-APP-05, PLT-026, 5.2 steps 1-2). Preview: the
 * channel rules of {@link TransactionService#check}, then mw-orchestrator's quote (the core's charges,
 * the payer's limit check, the payee masked; a read, nothing moves, no PIN); a broken limit is 422
 * {@code MW-CORE-<reason>} with English and Bangla text. A preview that passes is kept in Redis for
 * {@code ttl} (5 min) as the canonical transaction hash, under a previewId {@code <uuid>.<HMAC-SHA256
 * over uuid, channel and wallet>}. Confirm: the previewId's signature, the kept preview of the same
 * wallet (else 400 {@code MW-VAL-027}), the same group and the same canonical hash (else 400 {@code
 * MW-VAL-026}), then exactly {@link TransactionService#submit} (mw-auth's assertion over the same
 * values, the request, the sync wait). The canonical hash covers type, payee (default type, canonical
 * MSISDN), bank account, amount, partner and attributes; not the PIN, the locale or {@code
 * confirmDuplicate}. A preview is not consumed by its confirm: a repeat under the same key is the
 * idempotent repeat of the same transaction.
 */
@Service
public class PreviewService {

  /** The groups of {@code /app/v1/{group}/preview|confirm} and their txnType. */
  public static final Map<String, String> GROUPS = Map.of(
      "send-money", "P2P",
      "cash-in", "CASH_IN",
      "cash-out", "CASH_OUT",
      "payment", RequestMapper.MERCHANT_PAY,
      "bill-pay", RequestMapper.BILL_PAY,
      "top-up", RequestMapper.TOPUP,
      "add-money", RequestMapper.ADD_MONEY_BANK);

  /** The core's limit reasons (WAL-05) as the customer reads them: English, Bangla. */
  static final Map<String, String[]> LIMIT_TEXTS = Map.of(
      "LIMIT_BELOW_MIN_PER_TXN", new String[] {"The amount is below the minimum for this transaction",
          "লেনদেনের পরিমাণ সর্বনিম্ন সীমার কম"},
      "LIMIT_EXCEEDED_PER_TXN", new String[] {"The amount is above the limit for one transaction",
          "লেনদেনের পরিমাণ একক লেনদেনের সীমা ছাড়িয়ে গেছে"},
      "LIMIT_EXCEEDED_DAILY", new String[] {"This would exceed your daily limit",
          "এই লেনদেনে আপনার দৈনিক সীমা ছাড়িয়ে যাবে"},
      "LIMIT_EXCEEDED_DAILY_COUNT", new String[] {"You have reached today's number of transactions",
          "আজকের লেনদেনের সংখ্যার সীমা পূর্ণ হয়েছে"},
      "LIMIT_EXCEEDED_MONTHLY", new String[] {"This would exceed your monthly limit",
          "এই লেনদেনে আপনার মাসিক সীমা ছাড়িয়ে যাবে"},
      "LIMIT_EXCEEDED_MONTHLY_COUNT", new String[] {"You have reached this month's number of transactions",
          "এই মাসের লেনদেনের সংখ্যার সীমা পূর্ণ হয়েছে"});

  private static final Logger log = LoggerFactory.getLogger(PreviewService.class);
  private static final HexFormat HEX = HexFormat.of();
  private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
  private static final Pattern PREVIEW_ID =
      Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.[A-Za-z0-9_-]{43}");

  private final OrchestratorQuoteClient quotes;
  private final TransactionService transactions;
  private final PreviewStore store;
  private final PreviewProperties props;
  private final Clock clock;
  private final SecretKeySpec key;

  public PreviewService(
      OrchestratorQuoteClient quotes, TransactionService transactions, PreviewStore store, PreviewProperties props,
      Clock clock) {
    this.quotes = quotes;
    this.transactions = transactions;
    this.store = store;
    this.props = props;
    this.clock = clock;
    this.key = new SecretKeySpec(props.signingKey().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
  }

  public PreviewView preview(AppUser user, String group, PreviewRequest body) {
    TransactionRequest txn = body.toTransaction(txnType(group));
    transactions.check(user, txn);
    Quote quote;
    try {
      quote = quotes.quote(quoteRequest(user, txn));
    } catch (OrchestratorUnavailableException e) {
      throw new MwException(ErrorCodes.SYS_ORCHESTRATOR_UNAVAILABLE, "Preview temporarily unavailable, try again", e)
          .withStatus(HttpStatus.SERVICE_UNAVAILABLE)
          .withRetryable(true);
    }
    if (quote.limits() == null || !quote.limits().allowed()) {
      throw limitBroken(quote.limits() == null ? null : quote.limits().reasonCode());
    }
    String id = UUID.randomUUID().toString();
    Instant expiresAt = clock.instant().plus(props.ttl());
    try {
      store.save(id, new PreviewStore.Stored(scope(user), txn.txnType(), txnHash(txn)), props.ttl());
    } catch (RuntimeException e) {
      throw new MwException(ErrorCodes.SYS_SESSION_STORE_UNAVAILABLE, "Preview temporarily unavailable, try again", e)
          .withStatus(HttpStatus.SERVICE_UNAVAILABLE)
          .withRetryable(true);
    }
    log.atInfo()
        .addKeyValue("event", "txn.preview.created")
        .addKeyValue("channel", user.channel())
        .addKeyValue("txnType", txn.txnType())
        .log("transaction previewed");
    return new PreviewView(
        id + "." + sign(id, user), expiresAt, txn.txnType(), quote.amount(), quote.fee(), quote.vat(),
        quote.commission(), quote.total(), quote.counterparty(),
        new PreviewView.Limits(quote.limits().remaining()));
  }

  public SubmitResult confirm(AppUser user, String group, String idempotencyKey, ConfirmRequest body) {
    TransactionRequest txn = body.toTransaction(txnType(group));
    PreviewStore.Stored preview = previewOf(user, body.previewId());
    if (!preview.txnType().equals(txn.txnType()) || !preview.txnHash().equals(txnHash(txn))) {
      log.atInfo()
          .addKeyValue("event", "txn.preview.mismatch")
          .addKeyValue("channel", user.channel())
          .addKeyValue("txnType", txn.txnType())
          .log("confirm differs from its preview");
      throw new MwException(ErrorCodes.VAL_PREVIEW_MISMATCH, "The transaction differs from its preview; preview again")
          .withMessageBn("লেনদেনটি প্রিভিউ থেকে ভিন্ন; আবার প্রিভিউ করুন");
    }
    return transactions.submit(user, idempotencyKey, txn);
  }

  /**
   * {@code POST /app/v1/transactions} stays for one release (PLT-051) and runs this preview, then this
   * confirm, on the same body. A limit the preview refuses is answered before the PIN is checked and
   * nothing is published.
   */
  public SubmitResult submitOneShot(AppUser user, String idempotencyKey, TransactionRequest body) {
    String group = groupOf(body.txnType());
    PreviewView view = preview(user, group, new PreviewRequest(
        body.creditParty(), body.debitParty(), body.partner(), body.amount(), body.locale(), body.attributes()));
    return confirm(user, group, idempotencyKey, new ConfirmRequest(
        view.previewId(), body.creditParty(), body.debitParty(), body.partner(), body.amount(),
        body.encryptedPin(), body.locale(), body.confirmDuplicate(), body.attributes()));
  }

  private static String groupOf(String txnType) {
    for (Map.Entry<String, String> entry : GROUPS.entrySet()) {
      if (entry.getValue().equals(txnType)) {
        return entry.getKey();
      }
    }
    throw new MwException(ErrorCodes.VAL_UNSUPPORTED_TXN_TYPE, "Transaction type is not supported");
  }

  private PreviewStore.Stored previewOf(AppUser user, String previewId) {
    if (previewId == null || !PREVIEW_ID.matcher(previewId).matches()) {
      throw expired();
    }
    int dot = previewId.indexOf('.');
    String id = previewId.substring(0, dot);
    byte[] expected = sign(id, user).getBytes(StandardCharsets.US_ASCII);
    if (!MessageDigest.isEqual(expected, previewId.substring(dot + 1).getBytes(StandardCharsets.US_ASCII))) {
      throw expired();
    }
    Optional<PreviewStore.Stored> stored;
    try {
      stored = store.find(id);
    } catch (RuntimeException e) {
      throw new MwException(ErrorCodes.SYS_SESSION_STORE_UNAVAILABLE, "Confirm temporarily unavailable, try again", e)
          .withStatus(HttpStatus.SERVICE_UNAVAILABLE)
          .withRetryable(true);
    }
    if (stored.isEmpty() || !stored.get().scope().equals(scope(user))) {
      throw expired();
    }
    return stored.get();
  }

  private static MwException expired() {
    return new MwException(ErrorCodes.VAL_PREVIEW_EXPIRED, "The preview has expired or is unknown; preview again")
        .withMessageBn("প্রিভিউয়ের মেয়াদ শেষ বা অজানা; আবার প্রিভিউ করুন");
  }

  private static MwException limitBroken(String reason) {
    String code = reason == null ? "LIMIT_EXCEEDED" : reason;
    String[] text = LIMIT_TEXTS.getOrDefault(code, new String[] {"This transaction is over your limit",
        "এই লেনদেন আপনার সীমার বাইরে"});
    return new MwException("MW-CORE-" + code, text[0]).withMessageBn(text[1]);
  }

  private static String txnType(String group) {
    String type = group == null ? null : GROUPS.get(group);
    if (type == null) {
      throw new MwException(ErrorCodes.VAL_NOT_FOUND, "Not found").withStatus(HttpStatus.NOT_FOUND);
    }
    return type;
  }

  /** The quote of the signed-in wallet; the payee in its canonical form (an add money: the bank account). */
  private static QuoteRequest quoteRequest(AppUser user, TransactionRequest txn) {
    QuoteRequest.Counterparty payee = null;
    if (txn.debitParty() != null) {
      payee = new QuoteRequest.Counterparty(txn.debitParty().type(), txn.debitParty().id());
    } else if (txn.creditParty() != null) {
      payee = new QuoteRequest.Counterparty(creditType(txn), creditId(txn));
    }
    return new QuoteRequest(user.msisdn(), user.walletType(), txn.txnType(), user.channel(), txn.amount(), payee);
  }

  private static String creditType(TransactionRequest txn) {
    if (txn.creditParty().type() != null) {
      return txn.creditParty().type();
    }
    return RequestMapper.TOPUP.equals(txn.txnType()) ? "MSISDN"
        : RequestMapper.BILL_PAY.equals(txn.txnType()) ? "BILLER_ACCOUNT" : "WALLET";
  }

  private static String creditId(TransactionRequest txn) {
    String id = txn.creditParty().id();
    String canonical = "BILLER_ACCOUNT".equals(creditType(txn)) ? null : Msisdn.canonical(id);
    return canonical == null ? id : canonical;
  }

  /** SHA-256 over the canonical transaction (sorted keys), the values the confirm must repeat. */
  String txnHash(TransactionRequest txn) {
    Map<String, Object> c = new TreeMap<>();
    c.put("txnType", txn.txnType());
    if (txn.creditParty() != null) {
      c.put("creditParty", Map.of("type", creditType(txn), "id", creditId(txn)));
    }
    if (txn.debitParty() != null) {
      c.put("debitParty", Map.of("type", txn.debitParty().type(), "id", txn.debitParty().id()));
    }
    c.put("amount", txn.amount().valueMinor() + " " + txn.amount().currency());
    if (txn.partner() != null) {
      Map<String, String> p = new TreeMap<>();
      putIfSet(p, "partnerId", txn.partner().partnerId());
      putIfSet(p, "productCode", txn.partner().productCode());
      putIfSet(p, "billerCode", txn.partner().billerCode());
      c.put("partner", p);
    }
    if (txn.attributes() != null && !txn.attributes().isEmpty()) {
      c.put("attributes", new TreeMap<>(txn.attributes()));
    }
    return sha256(canonical(c));
  }

  private static void putIfSet(Map<String, String> m, String k, String v) {
    if (v != null) {
      m.put(k, v);
    }
  }

  /** A deterministic text form: sorted keys, each value length-prefixed (no delimiter can be forged). */
  private static String canonical(Object v) {
    if (v instanceof Map<?, ?> m) {
      StringBuilder b = new StringBuilder("{");
      new TreeMap<>(m).forEach((k, x) -> b.append(len(String.valueOf(k))).append(canonical(x)));
      return b.append('}').toString();
    }
    return len(String.valueOf(v));
  }

  private static String len(String s) {
    return s.length() + ":" + s;
  }

  private static String scope(AppUser user) {
    return sha256(user.channel() + "\u0000" + user.msisdn());
  }

  private String sign(String id, AppUser user) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(key);
      byte[] tag = mac.doFinal((id + "\u0000" + user.channel() + "\u0000" + user.msisdn()).getBytes(StandardCharsets.UTF_8));
      return B64.encodeToString(tag);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("HmacSHA256 is not available", e);
    }
  }

  private static String sha256(String text) {
    try {
      return HEX.formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("SHA-256 is not available", e);
    }
  }
}
