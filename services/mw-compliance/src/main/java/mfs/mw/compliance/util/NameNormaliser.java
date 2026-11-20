package mfs.mw.compliance.util;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Bangla and English person-name comparison for eKYC (ticket 124). A name is normalised to NFC (so Bangla
 * {@code য়} U+09DF and {@code য} + nukta are one letter), zero-width joiners and punctuation (also the Bangla
 * danda) are dropped, case is folded, and the words are sorted; similarity is 1 - Levenshtein distance / longer
 * length, over code points.
 */
public final class NameNormaliser {

  private NameNormaliser() {}

  public static String normalise(String name) {
    if (name == null) {
      return "";
    }
    String s = Normalizer.normalize(name, Normalizer.Form.NFC)
        .replaceAll("[\\u200B-\\u200D\\uFEFF]", "")
        .replaceAll("[\\p{P}\\p{S}]", " ")
        .toLowerCase(Locale.ROOT)
        .strip();
    if (s.isEmpty()) {
      return "";
    }
    return Arrays.stream(s.split("\\s+")).sorted().collect(Collectors.joining(" "));
  }

  /** 0 when either side is blank, else 1 - distance / max length of the normalised names. */
  public static double similarity(String a, String b) {
    int[] x = normalise(a).codePoints().toArray();
    int[] y = normalise(b).codePoints().toArray();
    if (x.length == 0 || y.length == 0) {
      return 0;
    }
    return 1.0 - (double) levenshtein(x, y) / Math.max(x.length, y.length);
  }

  private static int levenshtein(int[] a, int[] b) {
    int[] prev = new int[b.length + 1];
    int[] cur = new int[b.length + 1];
    for (int j = 0; j <= b.length; j++) {
      prev[j] = j;
    }
    for (int i = 1; i <= a.length; i++) {
      cur[0] = i;
      for (int j = 1; j <= b.length; j++) {
        int cost = a[i - 1] == b[j - 1] ? 0 : 1;
        cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
      }
      int[] t = prev;
      prev = cur;
      cur = t;
    }
    return prev[b.length];
  }
}
