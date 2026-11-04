package mfs.mw.core.mock.mapper;

import mfs.mw.core.mock.dto.CoreCommand;
import mfs.mw.core.mock.dto.CreditPayload;
import mfs.mw.core.mock.dto.HoldPayload;
import mfs.mw.core.mock.dto.QueryPayload;
import mfs.mw.core.mock.dto.ReversePayload;
import mfs.mw.core.mock.dto.TransferPayload;
import mfs.mw.core.mock.exception.ErrorCodes;
import mfs.mw.core.mock.exception.MwException;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/** Reads Kafka command JSON into the service's own types and rejects what cannot be a command. */
@Component
public class CoreCommandMapper {

  private final JsonMapper json;

  public CoreCommandMapper(JsonMapper json) {
    this.json = json;
  }

  /** Parses the envelope; a malformed record is a poison message (retry topics, then DLQ). */
  public CoreCommand command(String body) {
    CoreCommand cmd;
    try {
      cmd = json.readValue(body, CoreCommand.class);
    } catch (JacksonException e) {
      throw new MwException(ErrorCodes.VAL_MALFORMED, "command is not valid JSON", e);
    }
    if (blank(cmd.commandType()) || blank(cmd.commandId()) || blank(cmd.txnId()) || cmd.payload() == null) {
      throw new MwException(ErrorCodes.VAL_INVALID, "command envelope is incomplete");
    }
    return cmd;
  }

  /** The payload of ValidateTransaction / PostTransfer. */
  public TransferPayload transfer(CoreCommand cmd) {
    TransferPayload p;
    try {
      p = json.treeToValue(cmd.payload(), TransferPayload.class);
    } catch (JacksonException e) {
      throw new MwException(ErrorCodes.VAL_INVALID, "transfer payload is malformed", e);
    }
    if (p == null
        || p.debitParty() == null
        || blank(p.debitParty().id())
        || p.creditParty() == null
        || blank(p.creditParty().id())
        || p.amount() == null
        || (p.encryptedPin() == null && p.pinRequired())) {
      throw new MwException(ErrorCodes.VAL_INVALID, "transfer payload is incomplete");
    }
    return p;
  }

  /** The payload of CommitHold / ReleaseHold. */
  public HoldPayload hold(CoreCommand cmd) {
    HoldPayload p = read(cmd, HoldPayload.class);
    if (p == null || blank(p.holdId())) {
      throw new MwException(ErrorCodes.VAL_INVALID, "hold payload is incomplete");
    }
    return p;
  }

  /** The payload of CreditWallet. */
  public CreditPayload credit(CoreCommand cmd) {
    CreditPayload p = read(cmd, CreditPayload.class);
    if (p == null || p.creditParty() == null || blank(p.creditParty().id()) || p.amount() == null) {
      throw new MwException(ErrorCodes.VAL_INVALID, "credit payload is incomplete");
    }
    return p;
  }

  /** The payload of ReverseTransaction. */
  public ReversePayload reverse(CoreCommand cmd) {
    ReversePayload p = read(cmd, ReversePayload.class);
    if (p == null || blank(p.originalTxnId()) || p.amount() == null) {
      throw new MwException(ErrorCodes.VAL_INVALID, "reverse payload is incomplete");
    }
    return p;
  }

  /** The payload of QueryTransaction (may be empty). */
  public QueryPayload query(CoreCommand cmd) {
    QueryPayload p = read(cmd, QueryPayload.class);
    return p == null ? new QueryPayload(null) : p;
  }

  private <T> T read(CoreCommand cmd, Class<T> type) {
    try {
      return json.treeToValue(cmd.payload(), type);
    } catch (JacksonException e) {
      throw new MwException(ErrorCodes.VAL_INVALID, cmd.commandType() + " payload is malformed", e);
    }
  }

  private static boolean blank(String s) {
    return s == null || s.isBlank();
  }
}
