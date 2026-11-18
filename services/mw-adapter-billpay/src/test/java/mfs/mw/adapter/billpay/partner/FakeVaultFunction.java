package mfs.mw.adapter.billpay.partner;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.camel.spi.PropertiesFunction;

/**
 * The {@code hashicorp} properties function for tests without OpenBao: Camel finds it before the
 * real one ({@code src/test/resources/META-INF/services/org/apache/camel/properties-function/
 * hashicorp}), so {@code {{hashicorp:secret:<path>#<field>}}} reads {@link #VALUES}. Obviously fake
 * values only. {@code NewBillerRouteIT} uses the real function against OpenBao in Testcontainers.
 */
public class FakeVaultFunction implements PropertiesFunction {

  /** {@code <engine>:<path>#<field>} -> value. */
  public static final Map<String, String> VALUES = new ConcurrentHashMap<>(
      Map.of("secret:mw-adapter-billpay/billagg#hmac-secret", "fake-billagg-hmac-secret-for-tests"));

  @Override
  public String getName() {
    return "hashicorp";
  }

  @Override
  public String apply(String remainder) {
    String value = VALUES.get(remainder);
    if (value == null) {
      throw new IllegalArgumentException("no fake secret " + remainder);
    }
    return value;
  }
}
