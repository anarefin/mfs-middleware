package mfs.mw.ussd.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** A menu text in Bangla and English (spec 9.2); a missing language falls back to the other. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Text(String bn, String en) {

  public static final String BN = "bn";
  public static final String EN = "en";

  public String in(String language) {
    String t = EN.equals(language) ? en : bn;
    if (t == null) {
      t = EN.equals(language) ? bn : en;
    }
    return t == null ? "" : t;
  }
}
