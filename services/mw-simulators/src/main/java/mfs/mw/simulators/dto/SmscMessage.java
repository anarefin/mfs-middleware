package mfs.mw.simulators.dto;

import java.time.Instant;
import java.util.List;

/**
 * An SMS the SMSC simulator received, reassembled from its parts (test data, shown in clear by the
 * control API {@code GET /sim/smsc/messages}).
 *
 * @param encoding GSM7 or UCS2 (from data_coding)
 * @param messageIds the SMSC message_id of each part, in sequence order
 * @param dlrStatus the receipt sent (worst over the parts), null while none was sent
 */
public record SmscMessage(
    String destination,
    String source,
    String text,
    String encoding,
    int segments,
    List<String> messageIds,
    String dlrStatus,
    Instant receivedAt) {}
