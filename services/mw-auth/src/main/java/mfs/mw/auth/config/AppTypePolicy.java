package mfs.mw.auth.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Per app type (ticket 106, FRS CH-APP-02, CH-APP-03, CH-APP-04, CH-APP-07): access-token life,
 * bound devices, concurrent sessions, idle timeout, and how many wrong PINs lock the PIN for how
 * long the first time. A type names only the fields it changes; the rest come from {@code defaults}.
 * The customer keeps today's PIN ladder (3 tries, then 1 h). Every other app type uses the defaults
 * (5 tries, then 24 h). Level 2 and the decay stay on {@link PinProperties}.
 */
@Validated
@ConfigurationProperties("mw.auth.app-types")
public record AppTypePolicy(@Valid Settings defaults, @Valid Map<String, Settings> types) {

  public AppTypePolicy {
    if (defaults == null || !defaults.complete()) {
      throw new IllegalArgumentException("mw.auth.app-types.defaults must set every field");
    }
    Map<String, Settings> upper = new LinkedHashMap<>();
    if (types != null) {
      types.forEach((k, v) -> upper.put(k.toUpperCase(Locale.ROOT), v == null ? new Settings(null, null, null, null, null, null) : v));
    }
    types = Map.copyOf(upper);
  }

  /** The settings of a wallet type; an unknown type gets the defaults. */
  public Settings of(String walletType) {
    String key = walletType == null ? "" : walletType.toUpperCase(Locale.ROOT);
    Settings over = types.get(key);
    return over == null ? defaults : defaults.overlay(over);
  }

  /**
   * @param accessTtl how long an access token lives
   * @param maxDevices bound devices a wallet of this type may keep (the newest stay)
   * @param maxSessions live sessions at once (the next login ends the oldest)
   * @param idleTimeout sliding idle timeout of a session
   * @param pinTries wrong PINs before the first lock
   * @param pinLock how long that first lock lasts
   */
  public record Settings(
      Duration accessTtl,
      @Min(1) Integer maxDevices,
      @Min(1) Integer maxSessions,
      Duration idleTimeout,
      @Min(1) Integer pinTries,
      Duration pinLock) {

    boolean complete() {
      return accessTtl != null && maxDevices != null && maxSessions != null && idleTimeout != null
          && pinTries != null && pinLock != null;
    }

    /** {@code over} wins wherever it names a field. */
    Settings overlay(Settings over) {
      return new Settings(
          over.accessTtl != null ? over.accessTtl : accessTtl,
          over.maxDevices != null ? over.maxDevices : maxDevices,
          over.maxSessions != null ? over.maxSessions : maxSessions,
          over.idleTimeout != null ? over.idleTimeout : idleTimeout,
          over.pinTries != null ? over.pinTries : pinTries,
          over.pinLock != null ? over.pinLock : pinLock);
    }
  }
}
