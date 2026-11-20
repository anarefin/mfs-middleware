package mfs.mw.app.gateway.repository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

/**
 * The bills this gateway fetched (ticket 113b): key {@code billfetch:<sha256(channel, wallet,
 * billerCode, billNumber)>} (no MSISDN or bill number in the clear), value the fetched amount in
 * poisha, kept {@link #TTL}. A BILL_PAY preview of the same wallet, biller and bill number reads it
 * and the confirm attaches it as {@code attributes.billOutstandingMinor}, so the orchestrator checks
 * a FIXED biller's amount against what the aggregator answered, never against a client value.
 */
@Repository
public class BillFetchStore {

  /** How long a fetched bill can be paid with its amount attached. */
  public static final Duration TTL = Duration.ofMinutes(30);

  static final String PREFIX = "billfetch:";

  private final StringRedisTemplate redis;

  public BillFetchStore(StringRedisTemplate redis) {
    this.redis = redis;
  }

  public void save(String channel, String wallet, String billerCode, String billNumber, long amountMinor) {
    redis.opsForValue().set(key(channel, wallet, billerCode, billNumber), Long.toString(amountMinor), TTL);
  }

  public Optional<Long> find(String channel, String wallet, String billerCode, String billNumber) {
    String v = redis.opsForValue().get(key(channel, wallet, billerCode, billNumber));
    if (v == null || v.isBlank()) {
      return Optional.empty();
    }
    try {
      return Optional.of(Long.valueOf(v));
    } catch (NumberFormatException e) {
      return Optional.empty();
    }
  }

  static String key(String channel, String wallet, String billerCode, String billNumber) {
    try {
      MessageDigest sha = MessageDigest.getInstance("SHA-256");
      for (String part : new String[] {channel, wallet, billerCode, billNumber}) {
        byte[] b = (part == null ? "" : part).getBytes(StandardCharsets.UTF_8);
        sha.update((b.length + ":").getBytes(StandardCharsets.US_ASCII));
        sha.update(b);
      }
      return PREFIX + HexFormat.of().formatHex(sha.digest());
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
