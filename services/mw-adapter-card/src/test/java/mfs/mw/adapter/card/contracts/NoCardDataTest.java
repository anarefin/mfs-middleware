package mfs.mw.adapter.card.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import mfs.mw.adapter.card.dto.PartnerReply;
import mfs.mw.adapter.card.dto.PartnerRequest;
import org.junit.jupiter.api.Test;

/**
 * FL-07 (ticket 118): card data never enters the middleware. No field of a contract this adapter
 * produces or consumes (the schemas, their payload folders and samples) and no field of its message
 * DTOs is a card number, CVV or expiry; the adapter only ever handles the acquirer's session id and
 * transient token.
 */
class NoCardDataTest {

  /** A JSON property name or record component that would carry card data. */
  static final Pattern CARD_FIELD = Pattern.compile("(?i)^(pan|card_?number|cardNo|cvv2?|cvc2?|cvn|expiry\\w*|expiration\\w*|exp_?(month|year))$");

  static final Pattern JSON_KEY = Pattern.compile("\"([A-Za-z0-9_]+)\"\\s*:");

  @Test
  void theCardFieldPatternCatchesCardDataAndNotTokens() {
    for (String bad : List.of("pan", "cardNumber", "card_number", "cvv", "CVC", "expiryMonth", "exp_year")) {
      assertThat(CARD_FIELD.matcher(bad).matches()).as(bad).isTrue();
    }
    for (String ok : List.of("transientToken", "sessionId", "partnerRef", "company", "expiresAt")) {
      assertThat(CARD_FIELD.matcher(ok).matches()).as(ok).isFalse();
    }
  }

  @Test
  void noContractThisAdapterUsesHasACardDataField() throws IOException {
    ContractManifest m = ContractManifest.load();
    List<String> entries = new ArrayList<>();
    entries.addAll(m.producedEvents());
    entries.addAll(m.consumedEvents());
    Path root = ContractSchemas.contractsDir();
    List<String> found = new ArrayList<>();
    Set<Path> seen = new HashSet<>();
    for (String e : entries) {
      ContractManifest.Entry entry = ContractManifest.Entry.of(e);
      Path schema = root.resolve(entry.path());
      Path payloads = schema.resolveSibling(entry.topic());
      Path samples = root.resolve(entry.samplesDir());
      for (Path p : List.of(schema, payloads, samples)) {
        if (!Files.exists(p)) {
          continue;
        }
        try (Stream<Path> files = Files.walk(p)) {
          for (Path f : files.filter(f -> f.toString().endsWith(".json")).toList()) {
            if (!seen.add(f)) {
              continue;
            }
            Matcher k = JSON_KEY.matcher(Files.readString(f));
            while (k.find()) {
              if (CARD_FIELD.matcher(k.group(1)).matches()) {
                found.add(root.relativize(f) + ": " + k.group(1));
              }
            }
          }
        }
      }
    }
    assertThat(seen).isNotEmpty();
    assertThat(found).as("card data fields in contracts").isEmpty();
  }

  @Test
  void noMessageDtoHasACardDataField() {
    List<String> found = new ArrayList<>();
    walk(PartnerRequest.class, found, new HashSet<>());
    walk(PartnerReply.class, found, new HashSet<>());
    assertThat(found).as("card data fields in DTOs").isEmpty();
  }

  private static void walk(Class<?> type, List<String> found, Set<Class<?>> seen) {
    if (!type.isRecord() || !seen.add(type)) {
      return;
    }
    for (RecordComponent c : type.getRecordComponents()) {
      if (CARD_FIELD.matcher(c.getName()).matches()) {
        found.add(type.getSimpleName() + "." + c.getName());
      }
      walk(c.getType(), found, seen);
      Type g = c.getGenericType();
      if (g instanceof ParameterizedType pt) {
        for (Type a : pt.getActualTypeArguments()) {
          if (a instanceof Class<?> ac) {
            walk(ac, found, seen);
          }
        }
      }
    }
  }
}
