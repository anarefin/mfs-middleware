package mfs.mw.adapter.telco.partner.config;

import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * mTLS client contexts (ticket 87), static config only ({@code mw.partners.tls.contexts}): a
 * config-changed record can name one of its own partner's contexts ({@code mw-http-call}
 * {@code sslContextRef}) but never add one or supply key material.
 *
 * @param contexts name ({@code [a-z0-9-]}) -> the partner that may use it; its client keystore is
 *     read once at startup from that partner's own OpenBao secret ({@code <name>-keystore}, base64
 *     PKCS#12, {@code <name>-keystore-password}, and with {@code truststore: true} {@code <name>-truststore} and {@code
 *     <name>-truststore-password}) and registered as the {@code SSLContextParameters} bean {@code
 *     mwTls-<name>}
 */
@ConfigurationProperties("mw.partners.tls")
public record PartnerTlsProperties(Map<String, Context> contexts) {

  public PartnerTlsProperties {
    contexts = contexts == null ? Map.of() : Map.copyOf(contexts);
  }

  /**
   * @param partnerId the only partner whose route may name this context
   * @param truststore true: the server is checked against the context's own trust store ({@code
   *     <name>-truststore}), and a context whose trust store cannot be read is not registered
   *     (never a fall-back to the JVM trust store); false or absent: the JVM trust store
   */
  public record Context(String partnerId, Boolean truststore) {
    public Context {
      truststore = Boolean.TRUE.equals(truststore);
    }
  }
}
