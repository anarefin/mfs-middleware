package mfs.mw.ussd.service;

import java.util.List;

/**
 * One row of {@code ussd.short_code} (CH-USSD-08): the dialled code, its MNO, the root menu and the
 * account types that may open it.
 */
public record ShortCode(String code, String mno, String rootMenuId, List<String> allowedTypes) {

  public ShortCode {
    allowedTypes = allowedTypes == null ? List.of() : List.copyOf(allowedTypes);
  }
}
