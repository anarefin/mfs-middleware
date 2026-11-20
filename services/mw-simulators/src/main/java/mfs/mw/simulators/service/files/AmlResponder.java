package mfs.mw.simulators.service.files;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import mfs.mw.simulators.service.files.TrustStatementGenerator.Mismatch;
import mfs.mw.simulators.util.Hashing;

/**
 * The AML vendor's answer to one transaction file (ticket 123, FRS EXT-05, MCK-03), in the draft format of {@code
 * contracts/files/aml/README.md} (ticket 125 finalises it). Pure and deterministic: the same file gives the same
 * bytes.
 *
 * <p>In: {@code H|AML_TXN|1|<fileId>|<createdAt>}, rows {@code D|txnId|type|channel|payerType:payerId|
 * payeeType:payeeId|amountMinor|time}, trailer {@code T|rows|totalMinor|sha256} (hex SHA-256 of every byte before
 * the trailer). Out: {@code H|AML_RESP|1|<source file>|<source sha256>}, one {@code ACTION|txnId|walletId|rule|
 * reference} line per flagged transaction (BLOCK {@code BLOCK_SUFFIX}: a WALLET or MSISDN party ending the block
 * suffix; HOLD {@code WATCH_LIST}: such a party on the watch-list; ALERT {@code AMOUNT_THRESHOLD}: the amount above
 * the threshold, on the payer; the strongest wins, payer before payee), reference {@code AML-<first 8 hex of the
 * source sha256>-<n>}, trailer {@code T|lines|alerts|holds|blocks}. A file that cannot be read (header, row, trailer
 * count, total or checksum) gets one {@code E|<code>|<detail>} line and {@code T|0|0|0|0}. {@code INJECTED_MISMATCH}
 * kind {@code UNKNOWN_TXN} adds {@code count} ALERT lines for transactions the file never had ({@code UNKNOWN-<n>}).
 */
public class AmlResponder {

  /** The mismatch kinds of {@code INJECTED_MISMATCH} on the aml party. */
  public static final Set<String> MISMATCH_KINDS = Set.of("UNKNOWN_TXN");

  private static final Set<String> PERSON_TYPES = Set.of("WALLET", "MSISDN");

  /** The rules (seed {@code seed/<version>/aml/rules.json}). */
  public record AmlRules(long alertAboveMinor, List<String> watchList, String blockSuffix) {}

  /** The response file. */
  public record Response(String fileName, byte[] content, int alerts, int holds, int blocks, String error) {}

  private record Hit(String action, String txnId, String walletId, String rule) {}

  private static final class Rejected extends Exception {
    private static final long serialVersionUID = 1L;
    final String code;

    Rejected(String code, String detail) {
      super(detail, null, false, false);
      this.code = code;
    }
  }

  private final AmlRules rules;

  public AmlResponder(AmlRules rules) {
    this.rules = rules;
  }

  /** The response file name: {@code AML_TXN_<x>} becomes {@code AML_RESP_<x>}, any other name gets {@code .resp}. */
  public static String responseName(String fileName) {
    return fileName.startsWith("AML_TXN_") ? "AML_RESP_" + fileName.substring("AML_TXN_".length()) : fileName + ".resp";
  }

  public Response respond(String fileName, byte[] content, Mismatch mismatch) {
    String sha = Hashing.sha256Hex(content);
    String ref = "AML-" + sha.substring(0, 8) + "-";
    List<String> out = new ArrayList<>();
    out.add("H|AML_RESP|1|" + fileName + "|" + sha);
    List<Hit> hits;
    try {
      hits = hits(content);
    } catch (Rejected e) {
      out.add("E|" + e.code + "|" + e.getMessage());
      out.add("T|0|0|0|0");
      return new Response(responseName(fileName), join(out), 0, 0, 0, e.code);
    }
    if (mismatch != null && "UNKNOWN_TXN".equals(mismatch.kind())) {
      for (int i = 1; i <= mismatch.count(); i++) {
        hits.add(new Hit("ALERT", "UNKNOWN-" + i, "-", "INJECTED"));
      }
    }
    int alerts = 0;
    int holds = 0;
    int blocks = 0;
    int n = 0;
    for (Hit h : hits) {
      out.add(h.action() + "|" + h.txnId() + "|" + h.walletId() + "|" + h.rule() + "|" + ref + (++n));
      switch (h.action()) {
        case "BLOCK" -> blocks++;
        case "HOLD" -> holds++;
        default -> alerts++;
      }
    }
    out.add("T|" + hits.size() + "|" + alerts + "|" + holds + "|" + blocks);
    return new Response(responseName(fileName), join(out), alerts, holds, blocks, null);
  }

  private List<Hit> hits(byte[] content) throws Rejected {
    String text = new String(content, StandardCharsets.UTF_8);
    List<String> lines = text.lines().toList();
    if (lines.isEmpty() || !lines.get(0).startsWith("H|AML_TXN|")) {
      throw new Rejected("BAD_HEADER", "first line is not H|AML_TXN|...");
    }
    String last = lines.get(lines.size() - 1);
    if (!last.startsWith("T|")) {
      throw new Rejected("BAD_TRAILER", "last line is not the T trailer");
    }
    String[] t = last.split("\\|", -1);
    if (t.length != 4) {
      throw new Rejected("BAD_TRAILER", "trailer has " + t.length + " fields, expected 4");
    }
    List<Hit> hits = new ArrayList<>();
    long total = 0;
    int rows = 0;
    for (int i = 1; i < lines.size() - 1; i++) {
      String[] d = lines.get(i).split("\\|", -1);
      if (d.length != 8 || !"D".equals(d[0])) {
        throw new Rejected("BAD_ROW", "line " + (i + 1) + " is not a D row of 8 fields");
      }
      long amount;
      try {
        amount = Long.parseLong(d[6]);
      } catch (NumberFormatException e) {
        throw new Rejected("BAD_ROW", "line " + (i + 1) + " amount is not an integer");
      }
      rows++;
      total += amount;
      Hit h = rule(d[1], d[4], d[5], amount);
      if (h != null) {
        hits.add(h);
      }
    }
    if (!t[1].equals(Integer.toString(rows))) {
      throw new Rejected("COUNT_MISMATCH", "trailer says " + t[1] + " rows, file has " + rows);
    }
    if (!t[2].equals(Long.toString(total))) {
      throw new Rejected("TOTAL_MISMATCH", "trailer says total " + t[2] + ", rows add up to " + total);
    }
    int trailerAt = text.lastIndexOf("\nT|") + 1;
    String sha = Hashing.sha256Hex(text.substring(0, trailerAt).getBytes(StandardCharsets.UTF_8));
    if (!t[3].equalsIgnoreCase(sha)) {
      throw new Rejected("CHECKSUM_MISMATCH", "trailer sha256 does not match the file");
    }
    return hits;
  }

  private Hit rule(String txnId, String payer, String payee, long amount) {
    String payerId = person(payer);
    String payeeId = person(payee);
    for (String id : new String[] {payerId, payeeId}) {
      if (id != null && !rules.blockSuffix().isEmpty() && id.endsWith(rules.blockSuffix())) {
        return new Hit("BLOCK", txnId, id, "BLOCK_SUFFIX");
      }
    }
    for (String id : new String[] {payerId, payeeId}) {
      if (id != null && rules.watchList().contains(id)) {
        return new Hit("HOLD", txnId, id, "WATCH_LIST");
      }
    }
    if (amount > rules.alertAboveMinor()) {
      return new Hit("ALERT", txnId, payerId != null ? payerId : idOf(payer), "AMOUNT_THRESHOLD");
    }
    return null;
  }

  private static String person(String party) {
    int colon = party.indexOf(':');
    return colon > 0 && PERSON_TYPES.contains(party.substring(0, colon)) ? party.substring(colon + 1) : null;
  }

  private static String idOf(String party) {
    int colon = party.indexOf(':');
    return colon >= 0 ? party.substring(colon + 1) : party;
  }

  private static byte[] join(List<String> lines) {
    return (String.join("\n", lines) + "\n").getBytes(StandardCharsets.UTF_8);
  }
}
