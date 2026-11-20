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
 * @param sessionGrace added to the MNO session timeout for the Redis TTL (30 s, spec 9.2)
 * @param defaultLanguage {@code bn} or {@code en} at dial-in (no customer profile lookup yet)
 * @param maxInvalidAttempts invalid inputs in a row on one screen before the session ends
 * @param mno per MNO ({@code gp}, {@code robi}, {@code bl}, {@code teletalk})
 * @param lists static lists for DYNAMIC nodes, by source name
 * @param billerCategories bn/en label of each biller category of the DYNAMIC source {@code
 *     biller-categories}, by category code ({@code ELECTRICITY}, ...)
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
    Map<String, @Valid Mno> mno,
    Map<String, List<ListItem>> lists,
    Map<String, Text> billerCategories) {

  public UssdProperties {
    instanceId = instanceId == null || instanceId.isBlank() ? UUID.randomUUID().toString() : instanceId;
    mno = Map.copyOf(mno == null ? Map.of() : mno);
    lists = Map.copyOf(lists == null ? Map.of() : lists);
    billerCategories = Map.copyOf(billerCategories == null ? Map.of() : billerCategories);
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
   * Per-MNO configuration ({@code mw.ussd.mno.<gp|robi|teletalk|bl>}, spec 9.2).
   *
   * @param maxLengthGsm screen limit for GSM-7 text (182)
   * @param maxLengthUcs2 screen limit for UCS-2 text such as Bangla (70)
   * @param sessionTimeout the MNO's overall session limit (120-180 s)
   * @param screenTimeout how long one screen stays open before the next step ends the session (30 s,
   *     CH-USSD-05); the Redis TTL stays the overall timeout plus the session grace
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
      String operator,
      Duration screenTimeout) {

    /**
     * A test MNO with the 30 s screen timeout. A second constructor would stop Spring from binding
     * {@code mw.ussd.mno.*}.
     */
    public static Mno of(
        Transport transport,
        List<String> shortCodes,
        int maxLengthGsm,
        int maxLengthUcs2,
        Duration sessionTimeout,
        Overflow overflow,
        String continueFlag,
        String endFlag,
        String operator) {
      return new Mno(
          transport, shortCodes, maxLengthGsm, maxLengthUcs2, sessionTimeout, overflow, continueFlag, endFlag,
          operator, Duration.ofSeconds(30));
    }

    public Mno {
      shortCodes = List.copyOf(shortCodes == null ? List.of() : shortCodes);
      if (screenTimeout == null) {
        screenTimeout = Duration.ofSeconds(30);
      }
    }

    /** The top-up operator (telco partnerId) of this MNO, whose key is {@code key}. */
    public String operatorOr(String key) {
      return operator == null || operator.isBlank() ? key : operator;
    }
  }

  /** One entry of a static DYNAMIC list: the stored value and its label. */
  public record ListItem(String value, Text label) {}
}
