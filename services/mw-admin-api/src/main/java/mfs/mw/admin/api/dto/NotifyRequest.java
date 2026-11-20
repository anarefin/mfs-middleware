package mfs.mw.admin.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;

/**
 * This service's own type for {@code mw.notify.request.v1} (contract owned by mw-notify, ticket
 * 131): the ops email of a credential expiry alert. Key the recipient; {@code messageId} is the
 * idempotency key (mw-notify drops a duplicate).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record NotifyRequest(
    int schemaVersion,
    String messageId,
    String correlationId,
    Recipient recipient,
    String templateCode,
    String locale,
    Map<String, String> params,
    List<String> channels,
    String priority) {

  /** An operations mailbox, never a customer's address. */
  public record Recipient(String email) {}
}
