package mfs.mw.adapter.atm.partner.auth;

import java.io.ByteArrayInputStream;
import java.security.KeyStore;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import mfs.mw.adapter.atm.partner.config.PartnerTlsProperties;
import org.apache.camel.CamelContext;
import org.apache.camel.support.jsse.KeyManagersParameters;
import org.apache.camel.support.jsse.KeyStoreParameters;
import org.apache.camel.support.jsse.SSLContextParameters;
import org.apache.camel.support.jsse.TrustManagersParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The mTLS client contexts of {@code mw-http-call}'s {@code sslContextRef} (ticket 87): each
 * context of {@link PartnerTlsProperties} is read once when Camel starts (static, D28), from its
 * partner's own Vault secret through {@link PartnerCredentials}, and bound in Camel's registry as
 * the {@code SSLContextParameters} bean {@code mwTls-<name>}. A context that cannot be read is
 * logged ({@code partner.tls_context_unavailable}, never the material) and not bound (also when
 * its configured trust store cannot be read: no fall-back to the JVM trust store): a route
 * naming it is refused, the service stays up. Only the owning partner's route may name a context
 * (route loader and host guard).
 */
@Component
public class PartnerTlsContexts {

  /** Registry bean name prefix. */
  public static final String BEAN_PREFIX = "mwTls-";

  public static final Pattern NAME = Pattern.compile("^[a-z0-9][a-z0-9-]{0,62}$");

  private static final Logger log = LoggerFactory.getLogger(PartnerTlsContexts.class);

  private final PartnerTlsProperties properties;
  private final PartnerCredentials credentials;
  private final Map<String, String> owners = new ConcurrentHashMap<>();

  public PartnerTlsContexts(PartnerTlsProperties properties, PartnerCredentials credentials) {
    this.properties = properties;
    this.credentials = credentials;
  }

  /** Reads and binds every configured context; called once when Camel has started, before the partner routes. */
  public void register(CamelContext camel) {
    properties.contexts().forEach((name, context) -> {
      String partner = context == null ? null : context.partnerId();
      if (!NAME.matcher(name).matches() || partner == null || partner.isBlank()) {
        log.atWarn()
            .addKeyValue("event", "partner.tls_context_unavailable")
            .addKeyValue("tlsContext", name)
            .log("TLS context needs a [a-z0-9-] name and a partner-id; not registered");
        return;
      }
      try {
        SSLContextParameters parameters = parameters(partner, name);
        parameters.setCamelContext(camel);
        parameters.createSSLContext(camel);
        camel.getRegistry().bind(BEAN_PREFIX + name, SSLContextParameters.class, parameters);
        owners.put(name, partner);
        log.atInfo()
            .addKeyValue("event", "partner.tls_context_registered")
            .addKeyValue("tlsContext", name)
            .addKeyValue("partnerId", partner)
            .log("TLS client context registered");
      } catch (Exception e) {
        log.atWarn()
            .addKeyValue("event", "partner.tls_context_unavailable")
            .addKeyValue("tlsContext", name)
            .addKeyValue("partnerId", partner)
            .addKeyValue("errorClass", e.getClass().getName())
            .log("TLS client context could not be read; not registered");
      }
    });
  }

  /** The partner that owns registered context {@code name}. */
  public Optional<String> owner(String name) {
    return Optional.ofNullable(name == null ? null : owners.get(name));
  }

  private SSLContextParameters parameters(String partner, String name) throws Exception {
    String password = credentials.read(partner, name + "-keystore-password");
    KeyStoreParameters keyStore = new KeyStoreParameters();
    keyStore.setKeyStore(load(credentials.read(partner, name + "-keystore"), password));
    KeyManagersParameters keyManagers = new KeyManagersParameters();
    keyManagers.setKeyStore(keyStore);
    keyManagers.setKeyPassword(password);
    SSLContextParameters parameters = new SSLContextParameters();
    parameters.setKeyManagers(keyManagers);
    parameters.setSecureSocketProtocol("TLS");
    if (properties.contexts().get(name).truststore()) {
      // expected: a failed read fails the whole context (never the JVM trust store instead)
      KeyStoreParameters trustStore = new KeyStoreParameters();
      trustStore.setKeyStore(load(
          credentials.read(partner, name + "-truststore"), credentials.read(partner, name + "-truststore-password")));
      TrustManagersParameters trustManagers = new TrustManagersParameters();
      trustManagers.setKeyStore(trustStore);
      parameters.setTrustManagers(trustManagers);
    }
    return parameters;
  }

  private static KeyStore load(String base64, String password) throws Exception {
    KeyStore store = KeyStore.getInstance("PKCS12");
    store.load(new ByteArrayInputStream(Base64.getDecoder().decode(base64.strip())), password.toCharArray());
    return store;
  }
}
