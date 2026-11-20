package mfs.mw.portal.api.service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.List;
import java.util.OptionalLong;
import java.util.UUID;
import mfs.mw.portal.api.config.PortalApiProperties;
import mfs.mw.portal.api.dto.BatchPreview;
import mfs.mw.portal.api.dto.BatchView;
import mfs.mw.portal.api.dto.BulkFileFormat;
import mfs.mw.portal.api.dto.BulkFile;
import mfs.mw.portal.api.dto.InvalidRow;
import mfs.mw.portal.api.dto.PortalUser;
import mfs.mw.portal.api.entity.Batch;
import mfs.mw.portal.api.entity.BatchStatus;
import mfs.mw.portal.api.exception.CoreUnavailableException;
import mfs.mw.portal.api.exception.ErrorCodes;
import mfs.mw.portal.api.exception.MwException;
import mfs.mw.portal.api.mapper.BatchMapper;
import mfs.mw.portal.api.mapper.ResultFile;
import mfs.mw.portal.api.repository.BatchRepository;
import mfs.mw.portal.api.security.PortalUserResolver;
import mfs.mw.portal.api.util.BulkIds;
import mfs.mw.portal.api.util.UuidV7;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Org bulk disbursement, upload and maker-checker (spec 9.4, ticket 33). A file is read in full and
 * stored as one {@code PREVIEW} batch with every row, the invalid ones excluded from execution
 * (ticket 129b); its maker submits it ({@code PENDING_APPROVAL}). A user of
 * the same org with the checker role, who is not the batch's maker, approves or rejects it once.
 * Another org's batch is never visible (404).
 */
@Service
public class BatchService {

  static final String CURRENCY = "BDT";
  static final int LIST_LIMIT = 50;
  /** Invalid rows listed in the upload's answer; the preview pages through all of them. */
  static final int MAX_ROW_ERRORS = 100;
  static final int PREVIEW_MAX_LIMIT = 200;

  private static final Logger log = LoggerFactory.getLogger(BatchService.class);

  private final BatchRepository batches;
  private final BulkFileParser parser;
  private final PortalApiProperties props;
  private final Clock clock;
  private final BatchAudit audit;
  private final FundsCheck funds;

  public BatchService(
      BatchRepository batches, BulkFileParser parser, PortalApiProperties props, Clock clock, BatchAudit audit,
      FundsCheck funds) {
    this.batches = batches;
    this.parser = parser;
    this.props = props;
    this.clock = clock;
    this.audit = audit;
    this.funds = funds;
  }

  /** A CSV upload ({@link #upload(PortalUser, byte[], BulkFileFormat)}). */
  @Transactional
  public BatchView upload(PortalUser maker, byte[] file) {
    return upload(maker, file, BulkFileFormat.CSV);
  }

  /**
   * Reads a CSV or XLSX file (ticket 129) and stores it as one PREVIEW batch (ticket 129b) with
   * every row, the invalid ones with their error codes; the answer lists the first {@link
   * #MAX_ROW_ERRORS} invalid rows. The maker then submits it ({@link #submit}).
   */
  @Transactional
  public BatchView upload(PortalUser maker, byte[] file, BulkFileFormat format) {
    require(maker, props.roles().maker());
    if (file.length > props.bulk().maxBytes().toBytes()) {
      throw new MwException(
          ErrorCodes.VAL_BULK_TOO_LARGE,
          "File is larger than " + props.bulk().maxBytes().toKilobytes() + " KB");
    }
    BulkFile parsed = parser.parse(file, format);
    Batch batch =
        new Batch(
            UuidV7.next(clock), maker.orgId(), BatchStatus.PREVIEW, parsed.rowCount(), parsed.totalMinor(), CURRENCY,
            sha256(file), maker.subject(), maker.username(), null, null, null, clock.instant(), null, null, null,
            null, parsed.invalid().size());
    batches.insert(batch, parsed);
    audit.record(batch, "uploaded", maker.username(), null, BatchStatus.PREVIEW, null);
    log.atInfo()
        .addKeyValue("event", "bulk.batch.uploaded")
        .addKeyValue("batchId", batch.batchId())
        .addKeyValue("orgId", batch.orgId())
        .addKeyValue("rowCount", batch.rowCount())
        .addKeyValue("invalidCount", batch.invalidCount())
        .log("Bulk batch uploaded, in preview");
    List<InvalidRow> invalid = parsed.invalid();
    return BatchMapper.uploaded(batch, invalid.subList(0, Math.min(invalid.size(), MAX_ROW_ERRORS)));
  }

  /**
   * Sends a PREVIEW batch for approval (ticket 129b): only its valid rows will ever be executed. Only
   * the maker who uploaded it; not a PREVIEW {@code 409 MW-VAL-036}, no valid row {@code 409
   * MW-VAL-037}. One conditional UPDATE, so a batch is submitted once.
   */
  @Transactional
  public BatchView submit(PortalUser maker, String batchId) {
    require(maker, props.roles().maker());
    Batch batch = load(maker, batchId);
    if (!batch.makerSub().equals(maker.subject())) {
      throw PortalUserResolver.forbidden("Only the maker who uploaded the batch can submit it");
    }
    if (batch.status() != BatchStatus.PREVIEW) {
      throw new MwException(ErrorCodes.VAL_BATCH_NOT_PREVIEW, "The batch is no longer in preview")
          .withStatus(HttpStatus.CONFLICT);
    }
    if (batch.validCount() == 0) {
      throw new MwException(ErrorCodes.VAL_BATCH_NO_VALID_ROWS, "The batch has no valid row to pay")
          .withStatus(HttpStatus.CONFLICT);
    }
    if (!batches.submit(batch.batchId(), maker.orgId())) {
      throw new MwException(ErrorCodes.VAL_BATCH_NOT_PREVIEW, "The batch is no longer in preview")
          .withStatus(HttpStatus.CONFLICT);
    }
    Batch submitted = load(maker, batchId);
    audit.record(submitted, "submitted", maker.username(), BatchStatus.PREVIEW, BatchStatus.PENDING_APPROVAL, null);
    logControl("bulk.batch.submitted", submitted, maker);
    return BatchMapper.view(submitted);
  }

  /**
   * One page of the batch's rows in file order with its totals (ticket 129b), maker or checker, in
   * any status: {@code limit} 1..{@link #PREVIEW_MAX_LIMIT}, {@code offset} from 0, else {@code 400
   * MW-VAL-001}; {@code valid} true or false keeps only the valid or invalid rows.
   */
  @Transactional(readOnly = true)
  public BatchPreview preview(PortalUser user, String batchId, int offset, int limit, Boolean valid) {
    requireAny(user);
    if (offset < 0 || limit < 1 || limit > PREVIEW_MAX_LIMIT) {
      throw new MwException(ErrorCodes.VAL_INVALID, "offset must be 0 or more, limit 1 to " + PREVIEW_MAX_LIMIT);
    }
    Batch batch = load(user, batchId);
    return BatchMapper.preview(batch, offset, limit, batches.page(batch.batchId(), valid, offset, limit));
  }

  @Transactional(readOnly = true)
  public BatchView get(PortalUser user, String batchId) {
    requireAny(user);
    return viewWithProgress(load(user, batchId));
  }

  /** Stops sending further rows of a running batch; children already sent still settle. Checker. */
  @Transactional
  public BatchView pause(PortalUser checker, String batchId) {
    require(checker, props.roles().checker());
    Batch batch = load(checker, batchId);
    if (!batches.pause(batch.batchId(), checker.orgId())) {
      throw notRunning("Only an approved or executing batch can be paused");
    }
    logControl("bulk.batch.paused", batch, checker);
    audit.record(batch, "paused", checker.username(), batch.status(), BatchStatus.PAUSED, null);
    return viewWithProgress(load(checker, batchId));
  }

  /** Sends the remaining rows of a paused batch again. Checker. */
  @Transactional
  public BatchView resume(PortalUser checker, String batchId) {
    require(checker, props.roles().checker());
    Batch batch = load(checker, batchId);
    if (!batches.resume(batch.batchId(), checker.orgId())) {
      throw notRunning("Only a paused batch can be resumed");
    }
    logControl("bulk.batch.resumed", batch, checker);
    audit.record(batch, "resumed", checker.username(), BatchStatus.PAUSED, BatchStatus.EXECUTING, null);
    return viewWithProgress(load(checker, batchId));
  }

  /**
   * A maker asks to retry the FAILED rows of a settled batch (ticket 129c): COMPLETED or
   * COMPLETED_WITH_EXCEPTIONS with at least one FAILED row, else {@code 409 MW-VAL-038}; a request
   * already pending {@code 409 MW-VAL-039}. Nothing is sent until a checker approves it.
   */
  @Transactional
  public BatchView requestRetry(PortalUser maker, String batchId) {
    require(maker, props.roles().maker());
    Batch batch = load(maker, batchId);
    if (!batches.requestRetry(batch.batchId(), maker.orgId(), maker.subject(), maker.username(), clock.instant())) {
      throw load(maker, batchId).retryRequestedSub() != null
          ? retryRequest("A retry of this batch is already awaiting approval")
          : new MwException(ErrorCodes.VAL_NOTHING_TO_RETRY, "Only a settled batch with failed rows can be retried")
              .withStatus(HttpStatus.CONFLICT);
    }
    Batch requested = load(maker, batchId);
    audit.record(requested, "retry_requested", maker.username(), batch.status(), batch.status(), null);
    logControl("bulk.batch.retry_requested", requested, maker);
    return viewWithProgress(requested);
  }

  /**
   * A checker approves a pending retry request (ticket 129c), maker-checker as for the batch: never
   * the maker who asked for it ({@code 403 MW-AUTH-030}); none pending {@code 409 MW-VAL-039}. One
   * conditional UPDATE starts the next retry round, so a repeated approval sends nothing twice; the
   * FAILED rows are sent again once their failed attempt is proven to have posted nothing
   * ({@link BulkRetryService}).
   */
  @Transactional
  public BatchView approveRetry(PortalUser checker, String batchId) {
    require(checker, props.roles().checker());
    Batch batch = load(checker, batchId);
    if (batch.retryRequestedSub() == null) {
      throw retryRequest("No retry of this batch is awaiting approval");
    }
    if (batch.retryRequestedSub().equals(checker.subject())) {
      throw new MwException(ErrorCodes.AUTH_MAKER_IS_CHECKER, "The maker of a retry cannot approve it")
          .withStatus(HttpStatus.FORBIDDEN);
    }
    if (!batches.approveRetry(batch.batchId(), checker.orgId(), checker.subject(), clock.instant())) {
      throw retryRequest("No retry of this batch is awaiting approval");
    }
    Batch approved = load(checker, batchId);
    audit.record(approved, "retry_approved", checker.username(), batch.status(), BatchStatus.EXECUTING, null);
    logControl("bulk.batch.retry_approved", approved, checker);
    return viewWithProgress(approved);
  }

  private static MwException retryRequest(String message) {
    return new MwException(ErrorCodes.VAL_RETRY_REQUEST, message).withStatus(HttpStatus.CONFLICT);
  }

  /**
   * The result file of an approved batch (CSV, one line per row in file order): line, masked
   * MSISDN (18.3), amount in taka, reference, row status, child txnId, error code and the batch's
   * status (COMPLETED_WITH_EXCEPTIONS when a row is MANUAL_REVIEW or UNRESOLVED). Final once the
   * batch is COMPLETED or COMPLETED_WITH_EXCEPTIONS; while it runs it shows the rows' current state.
   */
  @Transactional(readOnly = true)
  public String resultFile(PortalUser user, String batchId) {
    requireAny(user);
    Batch batch = load(user, batchId);
    if (!batch.status().approved()) {
      throw new MwException(ErrorCodes.VAL_NO_RESULT, "The batch was not approved, so it has no result")
          .withStatus(HttpStatus.CONFLICT);
    }
    return ResultFile.of(batch.status(), batches.items(batch.batchId()));
  }

  private BatchView viewWithProgress(Batch batch) {
    return BatchMapper.view(
        batch, batch.status().approved() ? batches.progress(batch.batchId(), batch.currency()) : null);
  }

  private static void logControl(String event, Batch batch, PortalUser user) {
    log.atInfo()
        .addKeyValue("event", event)
        .addKeyValue("batchId", batch.batchId())
        .addKeyValue("orgId", batch.orgId())
        .addKeyValue("actor", user.username())
        .log(event);
  }

  private static MwException notRunning(String message) {
    return new MwException(ErrorCodes.VAL_BATCH_NOT_RUNNING, message).withStatus(HttpStatus.CONFLICT);
  }

  @Transactional(readOnly = true)
  public List<BatchView> list(PortalUser user, String status) {
    requireAny(user);
    BatchStatus filter =
        status == null
            ? null
            : BatchStatus.parse(status)
                .orElseThrow(() -> new MwException(ErrorCodes.VAL_INVALID, "Unknown batch status"));
    return batches.list(user.orgId(), filter, LIST_LIMIT).stream().map(BatchMapper::view).toList();
  }

  /**
   * Approves a batch awaiting approval (PENDING_APPROVAL, or BLOCKED_FUNDS again once funded). The
   * org wallet's balance is read from the core first (ticket 129c): short, the batch becomes
   * BLOCKED_FUNDS with the shortfall ({@code MW-CORE-INSUFFICIENT_BALANCE}) and nothing is sent; no
   * balance from the core {@code 503 MW-SYS-041}, nothing changed.
   */
  @Transactional
  public BatchView approve(PortalUser checker, String batchId) {
    return decide(checker, batchId, BatchStatus.APPROVED, null);
  }

  @Transactional
  public BatchView reject(PortalUser checker, String batchId, String reason) {
    return decide(checker, batchId, BatchStatus.REJECTED, reason);
  }

  private BatchView decide(PortalUser checker, String batchId, BatchStatus decision, String reason) {
    require(checker, props.roles().checker());
    Batch batch = load(checker, batchId);
    if (batch.makerSub().equals(checker.subject())) {
      throw new MwException(ErrorCodes.AUTH_MAKER_IS_CHECKER, "The maker of a batch cannot approve or reject it")
          .withStatus(HttpStatus.FORBIDDEN);
    }
    String wallet = props.bulk().orgWallets().get(batch.orgId());
    if (decision == BatchStatus.APPROVED && wallet == null) {
      throw new MwException(ErrorCodes.VAL_NO_PAYOUT_WALLET, "The org has no payout wallet configured")
          .withStatus(HttpStatus.CONFLICT);
    }
    boolean awaiting = batch.status() == BatchStatus.PENDING_APPROVAL || batch.status() == BatchStatus.BLOCKED_FUNDS;
    if (decision == BatchStatus.APPROVED && awaiting) {
      OptionalLong shortfall;
      try {
        shortfall = funds.shortfall(wallet, batch.totalMinor());
      } catch (CoreUnavailableException e) {
        throw new MwException(ErrorCodes.SYS_CORE_UNAVAILABLE, "The org wallet balance could not be read; try again")
            .withStatus(HttpStatus.SERVICE_UNAVAILABLE)
            .withRetryable(true);
      }
      if (shortfall.isPresent()) {
        return blockFunds(checker, batch, shortfall.getAsLong());
      }
    }
    if (!awaiting
        || !batches.decide(
            batch.batchId(), checker.orgId(), decision, checker.subject(), checker.username(), reason,
            clock.instant())) {
      throw notPending();
    }
    if (decision == BatchStatus.APPROVED) {
      // the parent BULK_DISBURSE txn (spec 9.4): its id ties every child together; the dispatcher
      // starts sending the rows (BulkExecutionService)
      batches.startPayout(batch.batchId(), BulkIds.parentTxnId(batch.batchId()), wallet);
    }
    Batch decided = load(checker, batchId);
    audit.record(
        decided, decision == BatchStatus.APPROVED ? "approved" : "rejected", checker.username(),
        batch.status(), decision, reason);
    log.atInfo()
        .addKeyValue("event", "bulk.batch.decided")
        .addKeyValue("batchId", batch.batchId())
        .addKeyValue("orgId", batch.orgId())
        .addKeyValue("decision", decision.name())
        .log("Bulk batch " + decision.name().toLowerCase(java.util.Locale.ROOT));
    return viewWithProgress(decided);
  }

  private BatchView blockFunds(PortalUser checker, Batch batch, long shortfallMinor) {
    if (!batches.blockFunds(
        batch.batchId(), batch.status(), checker.subject(), shortfallMinor, FundsCheck.INSUFFICIENT_BALANCE)) {
      throw notPending();
    }
    Batch blocked = load(checker, batch.batchId().toString());
    audit.record(blocked, "blocked_funds", checker.username(), batch.status(), BatchStatus.BLOCKED_FUNDS, null);
    log.atWarn()
        .addKeyValue("event", "bulk.batch.blocked_funds")
        .addKeyValue("batchId", batch.batchId())
        .addKeyValue("orgId", batch.orgId())
        .addKeyValue("errorCode", FundsCheck.INSUFFICIENT_BALANCE)
        .log("Bulk batch blocked at approval: the org wallet holds less than the batch");
    return viewWithProgress(blocked);
  }

  private Batch load(PortalUser user, String batchId) {
    UUID id;
    try {
      id = UUID.fromString(batchId);
    } catch (IllegalArgumentException e) {
      throw notFound();
    }
    return batches.find(id, user.orgId()).orElseThrow(BatchService::notFound);
  }

  private void require(PortalUser user, String role) {
    if (!user.has(role)) {
      throw PortalUserResolver.forbidden("This operation needs the " + role + " role");
    }
  }

  private void requireAny(PortalUser user) {
    if (!user.has(props.roles().maker()) && !user.has(props.roles().checker())) {
      throw PortalUserResolver.forbidden("This operation needs an org portal role");
    }
  }

  private static MwException notFound() {
    return new MwException(ErrorCodes.VAL_NOT_FOUND, "No such batch").withStatus(HttpStatus.NOT_FOUND);
  }

  private static MwException notPending() {
    return new MwException(ErrorCodes.VAL_BATCH_NOT_PENDING, "The batch is no longer awaiting approval")
        .withStatus(HttpStatus.CONFLICT);
  }

  private static String sha256(byte[] file) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(file));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
