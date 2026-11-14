package mfs.mw.checkout.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import mfs.mw.checkout.util.Sensitive;
import mfs.mw.checkout.util.SensitiveType;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * {@code mw.checkout.*}: web checkout (spec 9.3).
 *
 * @param instanceId value of the {@code mw-reply-to} header and suffix of the reply consumer
 *     group; blank means a random id per start
 * @param sessionTtl how long a new session can be paid (15 min, spec 9.3)
 * @param otpMaxSends OTPs one session may ask mw-auth for (mw-auth also limits the wallet's OTPs
 *     per window; ticket 72)
 * @param replyBudget how long a pay request waits for the final status before it answers 202
 * @param pageBaseUrl the hosted checkout page; {@code checkoutUrl} = this + the pay token
 * @param merchants checkout merchants by APISIX consumer name (the {@code hmac-auth} consumer)
 */
@Validated
@ConfigurationProperties("mw.checkout")
public record CheckoutProperties(
    String instanceId,
    @NotBlank String requestTopic,
    @NotBlank String statusTopic,
    @NotNull Duration sessionTtl,
    @Positive int otpMaxSends,
    @NotNull Duration replyBudget,
    @NotBlank String pageBaseUrl,
    Map<String, @Valid Merchant> merchants) {

  public CheckoutProperties {
    instanceId = instanceId == null || instanceId.isBlank() ? UUID.randomUUID().toString() : instanceId;
    merchants = Map.copyOf(merchants == null ? Map.of() : merchants);
  }

  /** The merchant behind an APISIX consumer name, or null. */
  public Merchant merchant(String id) {
    return id == null ? null : merchants.get(id);
  }

  /**
   * One checkout merchant.
   *
   * @param wallet the merchant wallet the customer pays (MSISDN form, wallet type MERCHANT)
   * @param name shown on the hosted page and in the OTP SMS
   * @param secret signs the redirect result (and the IPN, ticket 32); from OpenBao only
   */
  public record Merchant(
      @NotBlank String wallet,
      @NotBlank String name,
      @Sensitive(SensitiveType.SECRET) String secret) {

    @Override
    public String toString() {
      return "Merchant[wallet=" + wallet + ", name=" + name + ", secret=****]";
    }
  }
}
