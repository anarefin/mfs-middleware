package mfs.mw.app.gateway.service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import mfs.mw.app.gateway.client.CoreRefusedException;
import mfs.mw.app.gateway.client.CoreWalletClient;
import mfs.mw.app.gateway.dto.CoreAccount;
import mfs.mw.app.gateway.dto.CoreLimits;
import mfs.mw.app.gateway.dto.CoreStatement;
import mfs.mw.app.gateway.dto.ErrorDetail;
import mfs.mw.app.gateway.dto.LedgerStatement;
import mfs.mw.app.gateway.dto.WalletLimits;
import mfs.mw.app.gateway.dto.WalletProfile;
import mfs.mw.app.gateway.exception.ErrorCodes;
import mfs.mw.app.gateway.exception.MwException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Profile, limits and the statement of the signed-in wallet (ticket 107b, CH-APP-08). The wallet is
 * always the token's MSISDN. Profile is WAL-01 plus the default language Bangla. Limits are WAL-05
 * for a P2P of 1 Tk (100 poisha, the sheet minimum), so {@code remaining} is what is left before
 * that amount. The statement is WAL-12 and refuses a range longer than 90 days. The core out of
 * reach (or its breaker open) is 503 {@code MW-SYS-015}, retryable.
 */
@Service
public class AccountReadService {

  /** Product default (CH-USSD-10): Bangla until a language switch is stored. */
  static final String LANGUAGE = "bn";

  /** The limit check is a read of what is left before a minimum P2P, not a payment. */
  static final String LIMITS_TXN_TYPE = "P2P";

  static final long LIMITS_PROBE_MINOR = 100L;

  static final Duration MAX_STATEMENT = Duration.ofDays(90);

  private static final Logger log = LoggerFactory.getLogger(AccountReadService.class);

  private final CoreWalletClient core;

  public AccountReadService(CoreWalletClient core) {
    this.core = core;
  }

  public WalletProfile profile(String msisdn) {
    CoreAccount account = read(() -> core.account(msisdn));
    return new WalletProfile(account.holderName(), account.type(), account.status(), account.kycLevel(), LANGUAGE);
  }

  public WalletLimits limits(String msisdn) {
    CoreLimits limits = read(() -> core.limits(msisdn, LIMITS_TXN_TYPE, LIMITS_PROBE_MINOR));
    return new WalletLimits(limits.allowed(), limits.reasonCode(), limits.remaining());
  }

  public LedgerStatement statement(String msisdn, Instant from, Instant to, int limit, int page) {
    if (from == null || to == null || !from.isBefore(to)) {
      throw new MwException(ErrorCodes.VAL_INVALID, "Request validation failed")
          .withDetails(List.of(new ErrorDetail("from", "must be before to")));
    }
    if (Duration.between(from, to).compareTo(MAX_STATEMENT) > 0) {
      throw new MwException(ErrorCodes.VAL_INVALID, "Request validation failed")
          .withDetails(List.of(new ErrorDetail("to", "range is longer than 90 days")));
    }
    CoreStatement statement = read(() -> core.statement(msisdn, from, to, limit, page));
    List<LedgerStatement.LedgerEntry> entries = statement.entries() == null
        ? List.of()
        : statement.entries().stream()
            .map(e -> new LedgerStatement.LedgerEntry(
                e.txnId(), e.direction(), e.kind(), e.amount(), e.balanceAfter(), e.at()))
            .toList();
    return new LedgerStatement(from, to, page, statement.hasMore(), entries);
  }

  /** CSV of a statement: no wallet id, one row per entry. */
  public static String csv(LedgerStatement page) {
    StringBuilder out = new StringBuilder("at,txnId,direction,kind,amountMinor,balanceAfterMinor\n");
    for (LedgerStatement.LedgerEntry e : page.entries()) {
      out.append(e.at()).append(',')
          .append(e.txnId()).append(',')
          .append(e.direction()).append(',')
          .append(e.kind()).append(',')
          .append(e.amount().valueMinor()).append(',')
          .append(e.balanceAfter() == null ? "" : e.balanceAfter().valueMinor())
          .append('\n');
    }
    return out.toString();
  }

  private <T> T read(java.util.function.Supplier<T> call) {
    try {
      return call.get();
    } catch (CoreRefusedException e) {
      throw refused(e);
    } catch (RuntimeException e) {
      throw unavailable(e);
    }
  }

  private static MwException refused(CoreRefusedException e) {
    if (e.status() == 404) {
      return new MwException(ErrorCodes.VAL_NOT_FOUND, "Not found").withStatus(HttpStatus.NOT_FOUND);
    }
    if (e.status() == 422 && e.code() != null && e.code().startsWith("MW-CORE-")) {
      return new MwException(e.code(), "The core refused the read");
    }
    return unavailable(e);
  }

  private static MwException unavailable(RuntimeException e) {
    log.atWarn()
        .addKeyValue("event", "core.read.unavailable")
        .addKeyValue("errorCode", ErrorCodes.SYS_CORE_UNAVAILABLE)
        .addKeyValue("errorClass", e.getClass().getName())
        .log("core read could not be answered");
    return new MwException(ErrorCodes.SYS_CORE_UNAVAILABLE, "Account data temporarily unavailable, try again", e)
        .withStatus(HttpStatus.SERVICE_UNAVAILABLE);
  }
}
