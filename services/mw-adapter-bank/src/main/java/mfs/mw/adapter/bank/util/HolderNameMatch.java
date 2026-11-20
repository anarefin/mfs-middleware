package mfs.mw.adapter.bank.util;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Owner binding of a linked bank account (M9 review, FRS FL-08, UC-14): is the bank's holder name
 * the same person as the wallet's KYC name? Both names are normalised (case, accents, whitespace,
 * punctuation) and the common honorifics and name prefixes are dropped ({@link #HONORIFICS}: Mr,
 * Mrs, Dr, Md, Mohammad, Mst, ...). Then every token of the shorter name must pair with a distinct
 * token of the longer one, in any order, either exactly or, for tokens of 6+ letters with the same
 * first letter, one edit apart (spelling variants such as Rahman / Rahaman, Hossain / Hosain; never
 * Kamal / Jamal). At least one pair is exact, an initial never pairs, and a one-token name matches
 * only a one-token name. Names are never logged here.
 */
public final class HolderNameMatch {

  /** Dropped before comparing: titles and the prefixes a bank or the KYC record often leaves out. */
  static final Set<String> HONORIFICS = Set.of(
      "mr", "mrs", "ms", "miss", "dr", "prof", "engr", "haji", "hajji", "alhaj", "alhajj", "late",
      "md", "mohd", "mohammad", "mohammed", "muhammad", "muhammed", "mst", "mosammat", "mosammot",
      "mossammat", "most", "sk", "sheikh", "shaikh");

  private HolderNameMatch() {}

  public static boolean matches(String kycName, String bankName) {
    List<String> a = tokens(kycName);
    List<String> b = tokens(bankName);
    if (a.isEmpty() || b.isEmpty()) {
      return false;
    }
    List<String> shorter = a.size() <= b.size() ? a : b;
    List<String> longer = new ArrayList<>(a.size() <= b.size() ? b : a);
    if (shorter.size() == 1 && longer.size() > 1) {
      return false;
    }
    boolean anyExact = false;
    for (String t : shorter) {
      int at = pair(t, longer);
      if (at < 0) {
        return false;
      }
      anyExact |= longer.remove(at).equals(t);
    }
    return anyExact;
  }

  private static int pair(String token, List<String> candidates) {
    int exact = candidates.indexOf(token);
    if (exact >= 0) {
      return exact;
    }
    for (int i = 0; i < candidates.size(); i++) {
      String c = candidates.get(i);
      if (token.length() >= 6 && c.length() >= 6 && token.charAt(0) == c.charAt(0) && withinOneEdit(token, c)) {
        return i;
      }
    }
    return -1;
  }

  /** Lower-case ASCII-folded words of 2+ letters, honorifics dropped (an initial is no evidence). */
  static List<String> tokens(String name) {
    List<String> out = new ArrayList<>();
    if (name == null) {
      return out;
    }
    String folded = Normalizer.normalize(name, Normalizer.Form.NFKD)
        .replaceAll("\\p{M}+", "")
        .toLowerCase(Locale.ROOT)
        .replaceAll("[^\\p{L}\\p{N}]+", " ")
        .trim();
    if (folded.isEmpty()) {
      return out;
    }
    for (String t : folded.split(" ")) {
      if (t.length() >= 2 && !HONORIFICS.contains(t)) {
        out.add(t);
      }
    }
    return out;
  }

  private static boolean withinOneEdit(String x, String y) {
    int lx = x.length();
    int ly = y.length();
    if (Math.abs(lx - ly) > 1) {
      return false;
    }
    int i = 0;
    int j = 0;
    int edits = 0;
    while (i < lx && j < ly) {
      if (x.charAt(i) == y.charAt(j)) {
        i++;
        j++;
        continue;
      }
      if (++edits > 1) {
        return false;
      }
      if (lx > ly) {
        i++;
      } else if (ly > lx) {
        j++;
      } else {
        i++;
        j++;
      }
    }
    return edits + (lx - i) + (ly - j) <= 1;
  }
}
