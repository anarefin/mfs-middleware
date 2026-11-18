package mfs.mw.adapter.bank.partner.route;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * The Kamelet library lint (ADR D26, ticket 82). Camel reads only a Kamelet's name, template,
 * defaults and required list; the metadata this checks is for people and tooling (the admin
 * console, Kaoto): every {@code mw-*} Kamelet parses, is named after its file, has the type label,
 * a title, a description, documented properties (title, description, type), marks secret
 * references as passwords, and calls no endpoint but {@code kamelet:}, {@code http(s)}, {@code
 * json-validator} and its own beans ({@code cxf} only in {@code mw-soap-call}, ticket 85; ticket 86
 * adds {@code sftp}).
 */
class KameletCatalogueTest {

  static final Set<String> ALLOWED_SCHEMES = Set.of("kamelet", "json-validator");
  static final Pattern URI = Pattern.compile("(?m)^\\s*-?\\s*(?:to|uri|toD):[ \\t]*\"?([^\"\\s]+)");

  @SuppressWarnings("unchecked")
  static List<Map<String, Object>> kamelets() throws Exception {
    List<Map<String, Object>> out = new ArrayList<>();
    for (Resource r : new PathMatchingResourcePatternResolver().getResources(KameletCatalogue.LOCATION)) {
      try (InputStream in = r.getInputStream()) {
        Map<String, Object> doc = new Yaml(new SafeConstructor(new LoaderOptions())).load(in);
        doc.put("__file", r.getFilename());
        doc.put("__text", new String(r.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        out.add(doc);
      }
    }
    return out;
  }

  @Test
  void theLibraryHoldsTheKameletsTheRoutesNeed() {
    assertThat(new KameletCatalogue().names())
        .contains("mw-http-call", "mw-map", "mw-classify", "mw-audit", "mw-auth-none", "mw-auth-apikey", "mw-auth-hmac-sha256");
  }

  @Test
  void theSoapKameletShipsInTheLibrary() {
    assertThat(new KameletCatalogue().names()).contains("mw-soap-call");
  }

  @Test
  void theLibraryHoldsTheAuthKameletsOfTicket87() {
    assertThat(new KameletCatalogue().names()).contains("mw-auth-basic", "mw-auth-oauth2-cc", "mw-auth-jws");
  }

  @Test
  @SuppressWarnings("unchecked")
  void everyKameletIsDocumentedLabelledAndMarksItsSecrets() throws Exception {
    List<Map<String, Object>> all = kamelets();
    assertThat(all).isNotEmpty();
    for (Map<String, Object> k : all) {
      String file = (String) k.get("__file");
      Map<String, Object> metadata = (Map<String, Object>) k.get("metadata");
      assertThat(k.get("kind")).as(file).isEqualTo("Kamelet");
      assertThat(file).isEqualTo(metadata.get("name") + ".kamelet.yaml");
      Map<String, Object> labels = (Map<String, Object>) metadata.get("labels");
      assertThat(labels.get("camel.apache.org/kamelet.type")).as(file).isIn("action", "source", "sink");
      Map<String, Object> definition = (Map<String, Object>) ((Map<String, Object>) k.get("spec")).get("definition");
      assertThat((String) definition.get("title")).as(file).isNotBlank();
      assertThat((String) definition.get("description")).as(file).isNotBlank();
      Map<String, Map<String, Object>> props =
          (Map<String, Map<String, Object>>) definition.getOrDefault("properties", Map.of());
      props.forEach((name, p) -> {
        assertThat(p).as(file + " " + name).containsKeys("title", "description", "type");
        if (name.toLowerCase().contains("secret") || name.toLowerCase().contains("password")) {
          assertThat(p.get("format")).as(file + " " + name + " is a secret reference").isEqualTo("password");
          assertThat((List<String>) p.get("x-descriptors")).as(file + " " + name)
              .contains("urn:alm:descriptor:com.tectonic.ui:password");
        }
      });
      for (Object required : (List<Object>) definition.getOrDefault("required", List.of())) {
        assertThat(props).as(file + " required " + required).containsKey((String) required);
      }
    }
  }

  @Test
  void noKameletCallsARawEndpointButHttp() throws Exception {
    for (Map<String, Object> k : kamelets()) {
      Matcher m = URI.matcher((String) k.get("__text"));
      while (m.find()) {
        String uri = m.group(1);
        if (uri.startsWith("{{url}}")) {
          continue; // the partner's http(s) URL, checked against its allowed hosts
        }
        if (uri.startsWith("cxf:")) {
          // ticket 85: cxf only inside mw-soap-call, to the partner's static address, PAYLOAD mode
          assertThat(k.get("__file")).as(uri).isEqualTo("mw-soap-call.kamelet.yaml");
          assertThat(uri).as(uri).startsWith("cxf://{{address}}?dataFormat=PAYLOAD&headerFilterStrategy=#mwSoapHeaderFilter");
          continue;
        }
        if (uri.startsWith("sftp:") && SFTP_KAMELETS.contains(k.get("__file"))) {
          continue; // ticket 86: sftp only inside the two SFTP Kamelets
        }
        String scheme = uri.substring(0, Math.max(uri.indexOf(':'), 0));
        assertThat(scheme).as(k.get("__file") + ": " + uri).isIn(ALLOWED_SCHEMES);
      }
    }
  }

  /** Ticket 86: the only Kamelets that may name the {@code sftp} component. */
  static final Set<String> SFTP_KAMELETS = Set.of("mw-sftp-source.kamelet.yaml", "mw-sftp-sink.kamelet.yaml");

  @Test
  @SuppressWarnings("unchecked")
  void theSftpBatchKameletsAreTypedSourceSinkAndAction() throws Exception {
    Map<String, String> types = new java.util.HashMap<>();
    for (Map<String, Object> k : kamelets()) {
      Map<String, Object> metadata = (Map<String, Object>) k.get("metadata");
      types.put((String) metadata.get("name"),
          (String) ((Map<String, Object>) metadata.get("labels")).get("camel.apache.org/kamelet.type"));
    }
    assertThat(types)
        .containsEntry("mw-sftp-source", "source").containsEntry("mw-sftp-sink", "sink").containsEntry("mw-recon", "action");
  }

  /**
   * Ticket 86: strict host key checking against the partner's own known hosts (never the user's
   * file, never written), a file kept until the outbox commit (move / moveFailed, never delete), a
   * JDBC idempotent repository keyed on name, size and modification time, a bounded poll and a
   * streamed download.
   */
  @Test
  void theSftpKameletsCheckTheHostKeyAndTheSourceNeverDeletes() throws Exception {
    String source = null;
    for (Map<String, Object> k : kamelets()) {
      if (!SFTP_KAMELETS.contains(k.get("__file"))) {
        continue;
      }
      String text = (String) k.get("__text");
      assertThat(text).as((String) k.get("__file"))
          .contains("strictHostKeyChecking=yes", "useUserKnownHostsFile=false", "autoCreateKnownHostsFile=false",
              "knownHosts={{knownHosts}}")
          .doesNotContain("delete=true", "strictHostKeyChecking=no");
      if ("mw-sftp-source.kamelet.yaml".equals(k.get("__file"))) {
        source = text;
      }
    }
    assertThat(source).isNotNull().contains(
        "idempotent=true", "idempotentKey={{partnerId}}/${file:name}-${file:size}-${file:modified}",
        "idempotentRepository=#mwSftpFiles", "readLock=changed", "maxMessagesPerPoll={{maxMessagesPerPoll}}",
        "move=.done", "moveFailed=.error", "streamDownload=true", "stepwise=false", "disconnect=true");
  }
}
