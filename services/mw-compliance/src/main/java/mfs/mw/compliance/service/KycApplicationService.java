package mfs.mw.compliance.service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import mfs.mw.compliance.client.EcNidClient;
import mfs.mw.compliance.config.ComplianceProperties;
import mfs.mw.compliance.dto.ApplicationView;
import mfs.mw.compliance.dto.Caller;
import mfs.mw.compliance.dto.Channel;
import mfs.mw.compliance.dto.CreateApplicationRequest;
import mfs.mw.compliance.dto.DocumentRequest;
import mfs.mw.compliance.dto.DocumentType;
import mfs.mw.compliance.dto.DocumentView;
import mfs.mw.compliance.dto.EcPerson;
import mfs.mw.compliance.dto.EcVerifyRequest;
import mfs.mw.compliance.dto.KycCompletedEvent;
import mfs.mw.compliance.dto.KycStatus;
import mfs.mw.compliance.dto.MatchResult;
import mfs.mw.compliance.entity.Consent;
import mfs.mw.compliance.entity.KycApplication;
import mfs.mw.compliance.entity.KycDocument;
import mfs.mw.compliance.exception.ErrorCodes;
import mfs.mw.compliance.exception.MwException;
import mfs.mw.compliance.messaging.EventMetadata;
import mfs.mw.compliance.messaging.OutboxWriter;
import mfs.mw.compliance.repository.KycStore;
import mfs.mw.compliance.util.MaskingPolicy;
import mfs.mw.compliance.util.SensitiveType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Customer eKYC (ticket 124, FRS UC-01, FL-01, FL-02, CMP-04, CMP-05).
 *
 * <ol>
 *   <li>{@link #create}: the consent row, then the application (NID, date of birth and names sealed, the NID
 *       tokenised), in one transaction: both commit before anything else happens.
 *   <li>{@link #addDocument}: an image (type and magic bytes checked, at most 2 MB), sealed, with its sha256.
 *   <li>{@link #submit}: in a first transaction the application moves to EC_CALLED, only when every required image
 *       is there and its consent row is committed (CMP-04: otherwise the EC is never called); then, outside any
 *       transaction, the EC verify call carrying the consent id; then in a second transaction the outcome
 *       (MATCHED with a level, or MISMATCHED) and, when matched, {@code mw.kyc.completed.v1} through the outbox.
 *       An EC timeout, open breaker, EC or mw-crypto failure leaves the application FAILED: submit again.
 * </ol>
 *
 * <p>Ticket 124b sets the level on the core (core wallet REST) between MATCHED and APPROVED.
 */
@Service
public class KycApplicationService {

  public static final String TOPIC = "mw.kyc.completed.v1";
  /** MANUAL_REVIEW reason: another wallet already holds this NID (matched, approved or being checked). */
  public static final String NID_IN_USE = "NID_IN_USE";
  /** An application left EC_CALLED longer than this (a crash mid-call) may be submitted again. */
  static final Duration STALE_EC_CALL = Duration.ofMinutes(2);

  private static final Logger log = LoggerFactory.getLogger(KycApplicationService.class);
  private static final char SEP = '\n';

  private final KycStore store;
  private final PiiCipher pii;
  private final EcNidClient ec;
  private final KycMatchService match;
  private final OutboxWriter outbox;
  private final TransactionTemplate tx;
  private final Clock clock;
  private final ComplianceProperties.Kyc kyc;

  public KycApplicationService(KycStore store, PiiCipher pii, EcNidClient ec, KycMatchService match,
      OutboxWriter outbox, TransactionTemplate tx, Clock clock, ComplianceProperties props) {
    this.store = store;
    this.pii = pii;
    this.ec = ec;
    this.match = match;
    this.outbox = outbox;
    this.tx = tx;
    this.clock = clock;
    this.kyc = props.kyc();
  }

  public ApplicationView create(CreateApplicationRequest req, Caller caller, String correlationId) {
    if (blank(req.nameEn()) && blank(req.nameBn())) {
      throw new MwException(ErrorCodes.VAL_INVALID, "nameEn or nameBn is required");
    }
    Instant now = clock.instant();
    UUID consentId = UUID.randomUUID();
    UUID id = UUID.randomUUID();
    if (caller.subject() == Caller.Subject.WALLET
        && (req.channel() != Channel.APP || !req.walletId().equals(caller.subjectId()))) {
      throw forbidden("A customer applies for their own wallet only");
    }
    String requested = req.channel() == Channel.APP ? null : req.agentId();
    if (caller.subject() == Caller.Subject.AGENT
        && (req.channel() == Channel.APP || (requested != null && !requested.equals(caller.subjectId())))) {
      throw forbidden("An agent applies as itself on an assisted channel only");
    }
    String agentId = caller.subject() == Caller.Subject.AGENT ? caller.subjectId() : requested;
    if (agentId == null && req.channel() != Channel.APP) {
      throw new MwException(ErrorCodes.VAL_INVALID, "agentId is required on an assisted channel");
    }
    String row = "kyc_application/" + id + "/";
    String token = pii.tokeniseNid(req.nid());
    String nidEnc = pii.seal(req.nid(), row + "nid");
    String dobEnc = pii.seal(req.dob().toString(), row + "dob");
    String nameEnc = pii.seal(names(req.nameEn(), req.nameBn()), row + "name");
    Instant granted = req.consent().grantedAt() == null ? now : req.consent().grantedAt();
    KycApplication app = new KycApplication(id, req.walletId(), req.channel(), agentId, consentId,
        KycStatus.SUBMITTED, token, req.nid().substring(req.nid().length() - 3), nidEnc, dobEnc, nameEnc, null,
        null, null, null, null, correlationId, now, now);
    tx.executeWithoutResult(s -> {
      store.insertConsent(new Consent(consentId, req.walletId(), req.consent().purpose(), req.channel(), agentId, granted));
      store.insertApplication(app);
    });
    log.atInfo().addKeyValue("event", "kyc.application.created").addKeyValue("channel", req.channel().name())
        .log("KYC application {} created", id);
    return view(app, List.of());
  }

  public DocumentView addDocument(UUID id, DocumentRequest req, Caller caller) {
    KycApplication app = find(id, caller);
    if (app.status() != KycStatus.SUBMITTED && app.status() != KycStatus.FAILED) {
      throw state("Documents can no longer be changed");
    }
    byte[] bytes;
    try {
      bytes = Base64.getDecoder().decode(req.content());
    } catch (IllegalArgumentException e) {
      throw document("content is not base64");
    }
    if (bytes.length == 0 || bytes.length > kyc.maxDocumentBytes()) {
      throw document("image must be 1 to " + kyc.maxDocumentBytes() + " bytes");
    }
    if (!kyc.contentTypes().contains(req.contentType()) || !magicMatches(req.contentType(), bytes)) {
      throw document("content type not accepted or not what the image is");
    }
    UUID docId = UUID.randomUUID();
    String sha = HexFormat.of().formatHex(sha256(bytes));
    String blob = pii.sealBytes(bytes, "kyc_document/" + id + "/" + req.type().name());
    store.upsertDocument(new KycDocument(docId, id, req.type(), req.contentType(), bytes.length, sha, blob));
    return new DocumentView(docId, id, req.type(), bytes.length, sha);
  }

  public ApplicationView submit(UUID id, Caller caller) {
    KycApplication app = tx.execute(s -> {
      KycApplication a = store.lockApplication(id).filter(x -> visible(x, caller)).orElseThrow(() -> notFound(id));
      boolean staleCall = a.status() == KycStatus.EC_CALLED
          && a.updatedAt().plus(STALE_EC_CALL).isBefore(clock.instant());
      if (a.status() != KycStatus.SUBMITTED && a.status() != KycStatus.FAILED && !staleCall) {
        throw state("Application is " + a.status());
      }
      List<DocumentType> missing = new ArrayList<>(kyc.requiredDocuments());
      missing.removeAll(store.documentTypes(id));
      if (!missing.isEmpty()) {
        throw new MwException(ErrorCodes.VAL_DOCUMENTS_MISSING, "Missing documents: " + missing)
            .withStatus(HttpStatus.CONFLICT);
      }
      if (!store.consentExists(a.consentId())) {
        throw new MwException(ErrorCodes.VAL_CONSENT_MISSING, "No consent recorded").withStatus(HttpStatus.CONFLICT);
      }
      // Security review: an NID another wallet already holds is never auto-issued a level; an operator decides
      // (ticket 134). The advisory lock keeps two wallets from passing this check at once.
      store.lockNid(a.nidToken());
      if (store.nidHeldByAnotherWallet(a.nidToken(), a.walletId())) {
        store.recordOutcome(id, KycStatus.MANUAL_REVIEW, null, 0.0, null, NID_IN_USE, clock.instant());
        return null;
      }
      store.updateStatus(id, KycStatus.EC_CALLED, null, clock.instant());
      return a;
    });
    if (app == null) {
      log.atWarn().addKeyValue("event", "kyc.application.decided").addKeyValue("status", "MANUAL_REVIEW")
          .addKeyValue("reason", NID_IN_USE).log("KYC application {} held for review: NID in use", id);
      return get(id, caller);
    }
    MatchResult result;
    Optional<EcPerson> person;
    try {
      String nid = pii.open(app.nidEnc());
      LocalDate dob = LocalDate.parse(pii.open(app.dobEnc()));
      String[] names = pii.open(app.nameEnc()).split(String.valueOf(SEP), -1);
      person = ec.verify(new EcVerifyRequest(nid, dob, app.consentId().toString(), id.toString()));
      result = person.map(p -> match.match(dob, emptyToNull(names[0]), emptyToNull(names[1]), p))
          .orElseGet(KycMatchService::notFound);
    } catch (MwException e) {
      tx.executeWithoutResult(s -> store.updateStatus(id, KycStatus.FAILED, e.code(), clock.instant()));
      throw e.withRetryable(true);
    }
    String ecNames = person.map(p -> pii.seal(names(p.nameEn(), p.nameBn()), "kyc_application/" + id + "/ec_name"))
        .orElse(null);
    KycStatus status = result.matched() ? KycStatus.MATCHED : KycStatus.MISMATCHED;
    Instant now = clock.instant();
    tx.executeWithoutResult(s -> {
      store.recordOutcome(id, status, ecNames, result.score(), result.level(), result.reason(), now);
      if (result.matched()) {
        outbox.append(TOPIC, app.walletId(),
            new EventMetadata(app.correlationId(), id.toString(), "kyc-completed:" + id, 1),
            new KycCompletedEvent(1, UUID.randomUUID().toString(), id.toString(), app.walletId(),
                result.level().name(), app.channel().name(), app.agentId(), (int) Math.round(result.score() * 100), now.toString(),
                app.correlationId()));
      }
    });
    log.atInfo().addKeyValue("event", "kyc.application.decided").addKeyValue("status", status.name())
        .log("KYC application {} {}", id, status);
    return get(id, caller);
  }

  public ApplicationView get(UUID id, Caller caller) {
    return view(find(id, caller), store.documentTypes(id));
  }

  /** The application if this caller may see it; another wallet's or agent's is 404, as if it did not exist. */
  private KycApplication find(UUID id, Caller caller) {
    return store.findApplication(id).filter(a -> visible(a, caller)).orElseThrow(() -> notFound(id));
  }

  /** The unscoped caller (mw-admin-api) sees all; a customer their wallet's; an agent the ones it assisted. */
  static boolean visible(KycApplication a, Caller caller) {
    if (caller.isUnscoped()) {
      return true;
    }
    return switch (caller.subject()) {
      case WALLET -> a.walletId().equals(caller.subjectId());
      case AGENT -> caller.subjectId().equals(a.agentId());
    };
  }

  private static ApplicationView view(KycApplication a, List<DocumentType> docs) {
    return new ApplicationView(a.id(), a.walletId(), a.channel(), a.agentId(), a.consentId(), a.status(),
        MaskingPolicy.mask(SensitiveType.NID, "**" + a.nidLast3()), a.matchScore(), a.kycLevel(), a.reason(),
        a.errorCode(), a.status() == KycStatus.FAILED, docs, a.createdAt(), a.updatedAt());
  }

  private static String names(String en, String bn) {
    return (en == null ? "" : en) + SEP + (bn == null ? "" : bn);
  }

  private static boolean magicMatches(String contentType, byte[] b) {
    return switch (contentType) {
      case "image/jpeg" -> b.length > 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF;
      case "image/png" -> b.length > 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G';
      default -> false;
    };
  }

  private static byte[] sha256(byte[] bytes) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(bytes);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static boolean blank(String s) {
    return s == null || s.isBlank();
  }

  private static String emptyToNull(String s) {
    return s == null || s.isEmpty() ? null : s;
  }

  private static MwException notFound(UUID id) {
    return new MwException(ErrorCodes.VAL_NOT_FOUND, "No such application").withStatus(HttpStatus.NOT_FOUND);
  }

  private static MwException forbidden(String message) {
    return new MwException(ErrorCodes.AUTH_FORBIDDEN, message).withStatus(HttpStatus.FORBIDDEN);
  }

  private static MwException state(String message) {
    return new MwException(ErrorCodes.VAL_KYC_STATE, message).withStatus(HttpStatus.CONFLICT);
  }

  private static MwException document(String message) {
    return new MwException(ErrorCodes.VAL_DOCUMENT, message);
  }
}
