package mfs.mw.ussd.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import mfs.mw.ussd.dto.Text;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * {@code mw.ussd.*}: the menu engine and its transports (spec 9.2).
 *
 * @param instanceId value of the {@code mw-reply-to} header and suffix of the reply consumer
 *     group; blank means a random id per start
 * @param syncBudget how long an ACTION waits for the final status before the menu ends with
 *     "being processed" (8 s, spec 9.2)
 * @param sessionGrace added to the MNO session timeout for the Valkey TTL (30 s, spec 9.2)
 * @param defaultLanguage {@code bn} or {@code en} at dial-in (no customer profile lookup yet)
 * @param maxInvalidAttempts invalid inputs in a row on one screen before the session ends
 * @param pin the core's PIN encryption key (public key from OpenBao in the stack)
 * @param mno per MNO ({@code gp}, {@code robi}, {@code bl}, {@code teletalk})
 * @param lists static lists for DYNAMIC nodes, by source name
 */
@Validated
@ConfigurationProperties("mw.ussd")
public record UssdProperties(
    String instanceId,
    @NotBlank String requestTopic,
    @NotBlank String statusTopic,
    @NotNull Duration syncBudget,
    @NotNull Duration sessionGrace,
    @NotBlank String defaultLanguage,
    @Positive int maxInvalidAttempts,
    @Valid @NotNull Pin pin,
    Map<String, @Valid Mno> mno,
    Map<String, List<ListItem>> lists) {

  public UssdProperties {
    instanceId = instanceId == null || instanceId.isBlank() ? UUID.randomUUID().toString() : instanceId;
    mno = Map.copyOf(mno == null ? Map.of() : mno);
    lists = Map.copyOf(lists == null ? Map.of() : lists);
  }

  /** Transport of one MNO (spec 9.2 transport SPI). */
  public enum Transport {
    SMPP,
    HTTP
  }

  /** What happens to a screen longer than the MNO's maximum. */
  public enum Overflow {
    TRUNCATE,
    PAGE
  }

  /**
   * @param keyId the core's key id, forwarded with every ciphertext
   * @param publicKey X.509 PEM of the core's public key (not a secret; delivered like one)
   */
  public record Pin(@NotBlank String keyId, String publicKey) {}

  /**
   * Per-MNO configuration ({@code mw.ussd.mno.<gp|robi|teletalk|bl>}, spec 9.2).
   *
   * @param maxLengthGsm screen limit for GSM-7 text (182)
   * @param maxLengthUcs2 screen limit for UCS-2 text such as Bangla (70)
   * @param sessionTimeout the MNO's session limit (120-180 s, TBC per MNO)
   * @param continueFlag value of the HTTP response flag that keeps the session open
   * @param endFlag value of the HTTP response flag that ends it
   * @param operator mw-adapter-telco's partnerId for this MNO's subscribers (a top-up of the own
   *     number); blank = the MNO key itself (bl: {@code banglalink})
   */
  public record Mno(
      @NotNull Transport transport,
      List<String> shortCodes,
      @Positive int maxLengthGsm,
      @Positive int maxLengthUcs2,
      @NotNull Duration sessionTimeout,
      @NotNull Overflow overflow,
      @NotBlank String continueFlag,
      @NotBlank String endFlag,
      String operator) {

    public Mno {
      shortCodes = List.copyOf(shortCodes == null ? List.of() : shortCodes);
    }

    /** The top-up operator (telco partnerId) of this MNO, whose key is {@code key}. */
    public String operatorOr(String key) {
      return operator == null || operator.isBlank() ? key : operator;
    }
  }

  /** One entry of a static DYNAMIC list: the stored value and its label. */
  public record ListItem(String value, Text label) {}
}
