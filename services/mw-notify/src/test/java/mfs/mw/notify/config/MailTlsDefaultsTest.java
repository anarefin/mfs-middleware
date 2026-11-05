package mfs.mw.notify.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.PropertySourcesPropertyResolver;
import org.springframework.core.io.ClassPathResource;

/**
 * The shipped SMTP settings (spec 10.2, 18): with no environment override the client refuses a
 * server without STARTTLS (no downgrade to plain text) and checks the server's identity. Only an
 * explicit dev override (the module's compose.yml, the GreenMail tests) turns STARTTLS off.
 */
class MailTlsDefaultsTest {

  @Test
  void theShippedDefaultRequiresStartTlsAndChecksTheServerIdentity() throws Exception {
    List<PropertySource<?>> yaml =
        new YamlPropertySourceLoader().load("shipped", new ClassPathResource("application.yml"));
    MutablePropertySources sources = new MutablePropertySources();
    yaml.forEach(sources::addLast);
    // Only the shipped file: placeholders fall back to their defaults, as with no env var set.
    PropertySourcesPropertyResolver resolver = new PropertySourcesPropertyResolver(sources);

    assertThat(resolver.getProperty("spring.mail.properties.mail.smtp.starttls.enable")).isEqualTo("true");
    assertThat(resolver.getProperty("spring.mail.properties.mail.smtp.starttls.required")).isEqualTo("true");
    assertThat(resolver.getProperty("spring.mail.properties.mail.smtp.ssl.checkserveridentity")).isEqualTo("true");
  }
}
