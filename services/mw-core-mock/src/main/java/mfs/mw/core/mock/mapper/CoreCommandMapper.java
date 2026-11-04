package mfs.mw.core.mock.mapper;

import mfs.mw.core.mock.dto.CoreCommand;
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
        || p.encryptedPin() == null) {
      throw new MwException(ErrorCodes.VAL_INVALID, "transfer payload is incomplete");
    }
    return p;
  }

  private static boolean blank(String s) {
    return s == null || s.isBlank();
  }
}
