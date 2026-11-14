package mfs.mw.audit.dw.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;

/**
 * The anchor status the audit search returns beside its results (ticket 74; contract {@code
 * audit-query.v1} {@code AuditPage.anchor}), for the admin console's audit view.
 *
 * @param status {@link #INTACT}, {@link #BROKEN}, {@link #UNVERIFIED} (not checked since start, or
 *     mw-crypto's JWKS could not be read) or {@link #OFF} (anchoring switched off)
 * @param lastAnchorDate the newest anchor seen by the last check or signing, {@code yyyy-MM-dd}
 * @param verifiedAt when the anchors were last checked
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AnchorStatus(String status, String lastAnchorDate, Instant verifiedAt) {

  public static final String INTACT = "INTACT";
  public static final String BROKEN = "BROKEN";
  public static final String UNVERIFIED = "UNVERIFIED";
  public static final String OFF = "OFF";

  public static AnchorStatus off() {
    return new AnchorStatus(OFF, null, null);
  }
}
