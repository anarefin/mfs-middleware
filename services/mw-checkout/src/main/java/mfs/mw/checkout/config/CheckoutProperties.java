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
 * @param otpTtl how long an OTP is valid (2 min, the OTP default expiry of mw-notify)
 * @param otpMaxAttempts wrong OTPs before the OTP is void and a new one must be requested
 * @param otpMaxSends OTPs one session may send
 * @param replyBudget how long a pay request waits for the final status before it answers 202
 * @param pageBaseUrl the hosted checkout page; {@code checkoutUrl} = this + the pay token
 * @param pin the core's PIN key, handed to the hosted page so it can encrypt the PIN
 * @param merchants checkout merchants by APISIX consumer name (the {@code hmac-auth} consumer)
 */
@Validated
@ConfigurationProperties("mw.checkout")
public record CheckoutProperties(
    String instanceId,
    @NotBlank String requestTopic,
    @NotBlank String statusTopic,
    @NotBlank String notifyTopic,
    @NotNull Duration sessionTtl,
    @NotNull Duration otpTtl,
    @Positive int otpMaxAttempts,
    @Positive int otpMaxSends,
    @NotNull Duration replyBudget,
    @NotBlank String pageBaseUrl,
    @Valid @NotNull Pin pin,
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
   * @param keyId the core's key id, forwarded with every ciphertext
   * @param publicKey X.509 PEM (or Base64 DER) of the core's public key; not a secret
   */
  public record Pin(@NotBlank String keyId, String publicKey) {}

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
