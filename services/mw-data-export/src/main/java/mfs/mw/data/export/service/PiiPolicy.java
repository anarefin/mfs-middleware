package mfs.mw.data.export.service;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import mfs.mw.data.export.config.PiiPolicyProperties;
import mfs.mw.data.export.exception.UntaggedFieldException;
import mfs.mw.data.export.util.MaskingPolicy;
import mfs.mw.data.export.util.SensitiveType;
import org.springframework.stereotype.Component;

/**
 * Applies the configured tag of every present field before it is published or written to a file
 * (ticket 128). A scope with no entry, or a field with no tag, is refused. Null values are left
 * out, so an optional field that is absent needs no tag.
 */
@Component
public class PiiPolicy {

  private static final Pattern MSISDN = Pattern.compile("^(?:\\+?880|0)1[3-9]\\d{8}$");

  private final Map<String, Map<String, PiiAction>> scopes;
  private final FieldTokeniser tokens;

  public PiiPolicy(PiiPolicyProperties properties, FieldTokeniser tokens) {
    this.scopes = properties.scopes();
    this.tokens = tokens;
  }

  /** A copy of {@code fields} with each present value cleared, masked or tokenised. */
  public Map<String, Object> apply(String scope, Map<String, Object> fields) {
    Map<String, PiiAction> tags = scopes.getOrDefault(scope, Map.of());
    Map<String, Object> out = new LinkedHashMap<>();
    for (Map.Entry<String, Object> field : fields.entrySet()) {
      if (field.getValue() == null) {
        continue;
      }
      PiiAction action = tags.get(field.getKey());
      if (action == null) {
        throw new UntaggedFieldException(scope, field.getKey());
      }
      out.put(field.getKey(), switch (action) {
        case CLEAR -> field.getValue();
        case MASK -> mask(field.getKey(), String.valueOf(field.getValue()));
        case TOKENISE -> tokens.tokenise(domain(field.getKey(), String.valueOf(field.getValue())),
            String.valueOf(field.getValue()));
      });
    }
    return out;
  }

  /** The mw-crypto domain for a tokenised field, from its name and, failing that, its shape. */
  static String domain(String field, String value) {
    String name = field.toLowerCase(Locale.ROOT);
    if (name.contains("nid")) {
      return "NID";
    }
    if (name.contains("account")) {
      return "ACCOUNT";
    }
    if (name.contains("token") || name.contains("msisdn") || name.contains("wallet")
        || name.contains("payer") || name.contains("payee") || name.contains("recipient")) {
      return "MSISDN";
    }
    if (MSISDN.matcher(value).matches()) {
      return "MSISDN";
    }
    return "ACCOUNT";
  }

  private static String mask(String field, String value) {
    if (MSISDN.matcher(value).matches()) {
      return MaskingPolicy.mask(SensitiveType.MSISDN, value);
    }
    String name = field.toLowerCase(Locale.ROOT);
    if (name.contains("nid")) {
      return MaskingPolicy.mask(SensitiveType.NID, value);
    }
    if (name.contains("name")) {
      return MaskingPolicy.mask(SensitiveType.NAME, value);
    }
    return MaskingPolicy.mask(SensitiveType.ACCOUNT, value);
  }
}
