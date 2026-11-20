package mfs.mw.core.mock.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/**
 * Optional body of WAL-07 commit and WAL-08 release (contract {@code HoldActionRequest}). {@code round}
 * (ticket 95a): the operator decision it carries out, 0 or absent for the saga's own call. Each round is
 * its own operation ({@code <txnId>:rest:commit:manual-<n>}), so the core decides an operator's forced
 * commit afresh instead of repeating the refusal it stored for the saga's.
 */
public record HoldActionRequest(@Size(max = 200) String reason, @Min(0) @Max(1000) Integer round) {

  public static final HoldActionRequest NONE = new HoldActionRequest(null, null);
}
