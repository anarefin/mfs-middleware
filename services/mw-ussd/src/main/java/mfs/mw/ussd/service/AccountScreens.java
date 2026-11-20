package mfs.mw.ussd.service;

import java.util.List;

/** The sentences a mini statement and a remittance status show. ASCII money, no MSISDN. */
public final class AccountScreens {

  private AccountScreens() {}

  public static String statement(String language, List<String> lines) {
    if (lines.isEmpty()) {
      return en(language) ? "No recent entries." : "কোনো লেনদেন নেই।";
    }
    return (en(language) ? "Recent" : "সাম্প্রতিক") + "\n" + String.join("\n", lines);
  }

  public static String remit(String language, String status) {
    return en(language) ? "Status " + status : "স্ট্যাটাস " + status;
  }

  public static String noRemit(String language) {
    return en(language) ? "No remittance for this reference." : "এই রেফারেন্সে রেমিট্যান্স নেই।";
  }

  private static boolean en(String language) {
    return "en".equals(language);
  }
}
