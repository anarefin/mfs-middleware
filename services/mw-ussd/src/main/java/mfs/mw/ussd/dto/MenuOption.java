package mfs.mw.ussd.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Map;

/**
 * One numbered option of a MENU or CONFIRM node.
 *
 * @param switchLanguage when true, choosing it toggles the session language (bn and en)
 * @param set inputs stored when the option is chosen (input name to value); the placeholders
 *     {@code {msisdn}} (the dialling subscriber) and {@code {mno}} (the session's MNO) are filled
 *     in, e.g. a TOPUP of the own number
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MenuOption(String key, Text label, String next, boolean switchLanguage, Map<String, String> set) {

  public MenuOption {
    set = set == null ? Map.of() : Map.copyOf(set);
  }
}
