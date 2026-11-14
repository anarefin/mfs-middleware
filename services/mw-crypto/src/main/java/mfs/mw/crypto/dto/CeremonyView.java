package mfs.mw.crypto.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A key ceremony (crypto-internal.v1 {@code Ceremony}, ticket 75): metadata only.
 *
 * @param kid the key version the ceremony makes, {@code <keyId>.v<version>}
 * @param state {@code OPEN}, {@code COMBINED}, {@code COMMITTED} or {@code ABORTED}
 * @param shares who entered which share and when
 * @param checkValue the combined key's KCV (6 hex digits) once both shares are in
 */
public record CeremonyView(
    UUID ceremonyId, String keyId, String purpose, Integer version, String kid, String state, List<Share> shares,
    String checkValue) {

  public CeremonyView {
    shares = List.copyOf(shares);
  }

  public record Share(int share, String custodian, Instant enteredAt) {}
}
