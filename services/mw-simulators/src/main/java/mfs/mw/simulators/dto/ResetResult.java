package mfs.mw.simulators.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * Answer of {@code POST /mock-control/reset} (FRS MCT-07, ticket 103): one line per mock party, the
 * local ones first, then what mw-core-mock reported.
 *
 * @param parties what each party reported
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ResetResult(List<PartyResult> parties) {

  /**
   * One party's reset.
   *
   * @param party the mock party
   * @param status {@code RESET} or {@code FAILED}
   * @param seedVersion the seed files the party reloaded, when it has any
   * @param detail why it failed
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record PartyResult(String party, String status, String seedVersion, String detail) {

    public static PartyResult reset(String party) {
      return new PartyResult(party, "RESET", null, null);
    }

    public static PartyResult failed(String party, String detail) {
      return new PartyResult(party, "FAILED", null, detail);
    }
  }
}
