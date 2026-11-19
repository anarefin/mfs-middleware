package mfs.mw.core.mock.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * Answer of {@code POST /mock-control/reset} (FRS MCT-07, ticket 103): one line per party reset.
 *
 * @param parties what each party reported
 */
public record ResetResult(List<PartyResult> parties) {

  /**
   * One party's reset.
   *
   * @param party the mock party ({@code core})
   * @param status {@code RESET} or {@code FAILED}
   * @param seedVersion the seed files the party reloaded, when it has any
   * @param detail why it failed
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record PartyResult(String party, String status, String seedVersion, String detail) {

    public static PartyResult reset(String party, String seedVersion) {
      return new PartyResult(party, "RESET", seedVersion, null);
    }
  }
}
