package mfs.mw.admin.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.UUID;

/**
 * A key ceremony as admin.v1 shows it ({@code KeyCeremony}, ticket 75): from the KEY_CEREMONY change
 * request's payload, which holds who entered which share and when and, once both are in, the
 * combined key's KCV. Never a component.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record KeyCeremonyView(
    UUID changeRequestId, String keyId, String purpose, String kid, String status, List<Share> shares,
    String checkValue) {

  public KeyCeremonyView {
    shares = List.copyOf(shares);
  }

  public record Share(int share, String custodian, String enteredAt) {}
}
