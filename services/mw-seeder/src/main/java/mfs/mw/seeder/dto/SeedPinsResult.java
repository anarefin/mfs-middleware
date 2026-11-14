package mfs.mw.seeder.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import mfs.mw.seeder.util.Sensitive;
import mfs.mw.seeder.util.SensitiveType;

/** mw-auth's answer: one outcome per wallet (CREATED, UNCHANGED, UNKNOWN_WALLET, WEAK). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SeedPinsResult(List<Outcome> results) {

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Outcome(@Sensitive(SensitiveType.MSISDN) String walletId, String outcome) {}
}
