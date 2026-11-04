package mfs.mw.portal.api.service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import mfs.mw.portal.api.config.PortalApiProperties;
import mfs.mw.portal.api.dto.BatchView;
import mfs.mw.portal.api.dto.BulkRow;
import mfs.mw.portal.api.dto.PortalUser;
import mfs.mw.portal.api.entity.Batch;
import mfs.mw.portal.api.entity.BatchStatus;
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
 * Org bulk disbursement, upload and maker-checker (spec 9.4, ticket 33). A file is validated in
 * full and stored only when every row is valid, as one {@code PENDING_APPROVAL} batch. A user of
 * the same org with the checker role, who is not the batch's maker, approves or rejects it once.
 * Another org's batch is never visible (404).
 */
@Service
public class BatchService {

  static final String CURRENCY = "BDT";
  static final int LIST_LIMIT = 50;

  private static final Logger log = LoggerFactory.getLogger(BatchService.class);

  private final BatchRepository batches;
  private final BulkCsvParser parser;
  private final PortalApiProperties props;
  private final Clock clock;

  public BatchService(BatchRepository batches, BulkCsvParser parser, PortalApiProperties props, Clock clock) {
    this.batches = batches;
    this.parser = parser;
    this.props = props;
    this.clock = clock;
  }

  @Transactional
  public BatchView upload(PortalUser maker, byte[] file) {
    require(maker, props.roles().maker());
    if (file.length > props.bulk().maxBytes().toBytes()) {
      throw new MwException(
          ErrorCodes.VAL_BULK_TOO_LARGE,
          "File is larger than " + props.bulk().maxBytes().toKilobytes() + " KB");
    }
    List<BulkRow> rows = parser.parse(file);
    long total = 0;
    for (BulkRow r : rows) {
      total = Math.addExact(total, r.amountMinor());
    }
    Batch batch =
        new Batch(
            UuidV7.next(clock), maker.orgId(), BatchStatus.PENDING_APPROVAL, rows.size(), total, CURRENCY,
            sha256(file), maker.subject(), maker.username(), null, null, null, clock.instant(), null);
    batches.insert(batch, rows);
    log.atInfo()
        .addKeyValue("event", "bulk.batch.uploaded")
        .addKeyValue("batchId", batch.batchId())
        .addKeyValue("orgId", batch.orgId())
        .addKeyValue("rowCount", batch.rowCount())
        .log("Bulk batch uploaded, awaiting approval");
    return BatchMapper.view(batch);
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
    return viewWithProgress(load(checker, batchId));
  }

  /**
   * The result file of an approved batch (CSV, one line per row in file order): line, masked
   * MSISDN (18.3), amount in taka, reference, row status, child txnId and error code. Final once
   * the batch is COMPLETED; while it runs it shows the rows' current state.
   */
  @Transactional(readOnly = true)
  public String resultFile(PortalUser user, String batchId) {
    requireAny(user);
    Batch batch = load(user, batchId);
    if (!batch.status().approved()) {
      throw new MwException(ErrorCodes.VAL_NO_RESULT, "The batch was not approved, so it has no result")
          .withStatus(HttpStatus.CONFLICT);
    }
    return ResultFile.of(batches.items(batch.batchId()));
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
  public List<BatchView> list(PortalUser user, BatchStatus status) {
    requireAny(user);
    return batches.list(user.orgId(), status, LIST_LIMIT).stream().map(BatchMapper::view).toList();
  }

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
    if (batch.status() != BatchStatus.PENDING_APPROVAL
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
    log.atInfo()
        .addKeyValue("event", "bulk.batch.decided")
        .addKeyValue("batchId", batch.batchId())
        .addKeyValue("orgId", batch.orgId())
        .addKeyValue("decision", decision.name())
        .log("Bulk batch " + decision.name().toLowerCase(java.util.Locale.ROOT));
    return viewWithProgress(load(checker, batchId));
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
