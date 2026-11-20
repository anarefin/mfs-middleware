package mfs.mw.admin.api.dto;

import java.util.List;

/**
 * A draft USSD menu walked on one MNO (ticket 133b; contract {@code admin.v1} {@code
 * UssdMenuPreview}): the code the MNO dials, the root menu it opens and the screens shown.
 */
public record UssdPreviewView(
    String menuId, String mno, String shortCode, String rootMenuId, String language, List<Screen> screens) {

  public UssdPreviewView {
    screens = List.copyOf(screens);
  }

  /** One screen: the answer that led to it (null for the dial, {@code ****} for a PIN). */
  public record Screen(String input, String screen, int page, int pages, boolean ended) {}
}
