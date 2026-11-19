package mfs.mw.adapter.telco.partner;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.camel.spi.PropertiesFunction;

/**
 * The {@code hashicorp} properties function for tests without Vault: Camel finds it before the
 * real one ({@code src/test/resources/META-INF/services/org/apache/camel/properties-function/
 * hashicorp}), so {@code {{hashicorp:secret:<service>/<partner>#<field>}}} reads {@link #VALUES}, else
 * the obviously fake {@code fake-<partner>-<field>-for-tests} (the dev seeds' secrets, the same in the
 * three adapters). Billpay's {@code NewBillerRouteIT} uses the real function against Vault in
 * Testcontainers.
 */
public class FakeVaultFunction implements PropertiesFunction {

  /** {@code <engine>:<path>#<field>} -> value, overriding the default. */
  public static final Map<String, String> VALUES = new ConcurrentHashMap<>();

  private static final Pattern PARTNER_SECRET = Pattern.compile("^secret:[a-z0-9-]+/([a-z0-9-]+)#([a-z0-9-]+)$");

  @Override
  public String getName() {
    return "hashicorp";
  }

  @Override
  public String apply(String remainder) {
    String value = VALUES.get(remainder);
    if (value != null) {
      return value;
    }
    Matcher m = PARTNER_SECRET.matcher(remainder);
    if (!m.matches()) {
      throw new IllegalArgumentException("no fake secret " + remainder);
    }
    return "fake-" + m.group(1) + "-" + m.group(2) + "-for-tests";
  }
}
