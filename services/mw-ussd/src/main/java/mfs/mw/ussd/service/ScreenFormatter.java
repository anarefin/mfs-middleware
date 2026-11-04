package mfs.mw.ussd.service;

import java.util.ArrayList;
import java.util.List;
import mfs.mw.ussd.config.UssdProperties;
import org.springframework.stereotype.Component;

/**
 * Fits a screen to the MNO's maximum length (spec 9.2): 182 characters for GSM-7 text, 70 for
 * UCS-2 (Bangla) by default. Longer screens are cut (TRUNCATE) or split into pages that end with
 * the menu's "more" option (PAGE), broken at a line break or space where possible.
 */
@Component
public class ScreenFormatter {

  /** The GSM 03.38 basic character set (plus line breaks). */
  private static final String GSM7 =
      "@£$¥èéùìòÇ\nØø\rÅåΔ_ΦΓΛΩΠΨΣΘΞÆæßÉ !\"#¤%&'()*+,-./0123456789:;<=>?"
          + "¡ABCDEFGHIJKLMNOPQRSTUVWXYZÄÖÑÜ§¿abcdefghijklmnopqrstuvwxyzäöñüà";

  public static boolean isGsm7(String text) {
    return text.chars().allMatch(c -> GSM7.indexOf(c) >= 0);
  }

  public static int limit(String text, UssdProperties.Mno mno) {
    return isGsm7(text) ? mno.maxLengthGsm() : mno.maxLengthUcs2();
  }

  public List<String> pages(String text, UssdProperties.Mno mno, String more) {
    int limit = limit(text, mno);
    if (text.length() <= limit) {
      return List.of(text);
    }
    if (mno.overflow() == UssdProperties.Overflow.TRUNCATE) {
      return List.of(text.substring(0, limit));
    }
    String suffix = "\n" + more;
    int pageLimit = Math.min(limit, limit(text + more, mno));
    int room = pageLimit - suffix.length();
    if (room < 1) {
      return List.of(text.substring(0, limit));
    }
    List<String> pages = new ArrayList<>();
    String rest = text;
    while (rest.length() > pageLimit) {
      int cut = breakAt(rest, room);
      pages.add(rest.substring(0, cut) + suffix);
      rest = rest.substring(cut);
      if (!rest.isEmpty() && (rest.charAt(0) == '\n' || rest.charAt(0) == ' ')) {
        rest = rest.substring(1);
      }
    }
    pages.add(rest);
    return List.copyOf(pages);
  }

  /** The cut position at most {@code room}: the last line break, else the last space, else room. */
  private static int breakAt(String text, int room) {
    int nl = text.lastIndexOf('\n', room);
    if (nl > 0) {
      return nl;
    }
    int sp = text.lastIndexOf(' ', room);
    return sp > 0 ? sp : room;
  }
}
