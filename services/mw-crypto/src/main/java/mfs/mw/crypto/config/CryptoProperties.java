package mfs.mw.crypto.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * {@code mw.crypto}: the HSM gateway (spec 18.8, D23).
 *
 * @param defaultProvider the provider new keys are generated in ({@code pkcs11})
 * @param bootstrapKeys dev only: create the missing key of every {@code bootstrapPurposes} entry on
 *     start ({@code scheduler/KeyBootstrap}); production keys come only from the key ceremony
 *     (ticket 75), so the default is false
 * @param bootstrapPurposes the purposes the bootstrap makes sure exist, one key each
 * @param bootstrapPeriod how often the bootstrap re-checks (it creates only what is missing)
 * @param hsmProbePeriod how often {@code scheduler/HsmProbeJob} checks the HSM ({@code crypto.hsm.up})
 * @param pkcs11 the PKCS#11 provider
 */
@Validated
@ConfigurationProperties("mw.crypto")
public record CryptoProperties(
    @NotBlank String defaultProvider,
    boolean bootstrapKeys,
    List<String> bootstrapPurposes,
    @NotNull Duration bootstrapPeriod,
    @NotNull Duration hsmProbePeriod,
    @Valid @NotNull Pkcs11 pkcs11) {

  public CryptoProperties {
    bootstrapPurposes = List.copyOf(bootstrapPurposes == null ? List.of() : bootstrapPurposes);
  }

  /**
   * The JDK SunPKCS11 provider against one token (spec 18.8). In dev and test the library is the
   * p11-kit proxy, which reaches the softhsm container's {@code mw-crypto} token over TCP and shows
   * only that one slot (deploy/softhsm/README.md).
   *
   * @param library absolute path of the PKCS#11 library
   * @param slotListIndex the token's slot in the library's slot list
   * @param userPin the token's user PIN, from OpenBao {@code secret/mw-crypto} (key {@code
   *     mw.crypto.pkcs11.user-pin}); never logged, never in a config file
   * @param maxSessions at most this many HSM operations at once (the session pool)
   * @param acquireTimeout how long a request waits for a free session before it fails closed
   * @param callTimeout how long one HSM call may take before the request fails closed (a hung
   *     native call keeps its session until it returns, so a hung HSM soon has no free session left
   *     and every request fails at once)
   */
  public record Pkcs11(
      @NotBlank String library,
      @Min(0) int slotListIndex,
      String userPin,
      @Min(1) int maxSessions,
      @NotNull Duration acquireTimeout,
      @NotNull Duration callTimeout) {

    public Pkcs11 {
      userPin = userPin == null ? "" : userPin;
    }

    @Override
    public String toString() {
      return "Pkcs11[library=" + library + ", slotListIndex=" + slotListIndex + ", userPin=****"
          + ", maxSessions=" + maxSessions + ", acquireTimeout=" + acquireTimeout + ", callTimeout=" + callTimeout
          + "]";
    }
  }
}
