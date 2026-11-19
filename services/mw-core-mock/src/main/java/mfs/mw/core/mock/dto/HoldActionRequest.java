package mfs.mw.core.mock.dto;

import jakarta.validation.constraints.Size;

/** Optional body of WAL-07 commit and WAL-08 release (contract {@code HoldActionRequest}). */
public record HoldActionRequest(@Size(max = 200) String reason) {

  public static final HoldActionRequest NONE = new HoldActionRequest(null);
}
