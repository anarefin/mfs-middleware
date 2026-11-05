package mfs.mw.ussd.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Map;

/**
 * One node of a menu tree (spec 9.2). Which fields apply depends on {@code type}: options for
 * MENU and CONFIRM, validator/store/next for INPUT, flow/mapping/onSuccess/onFailure for ACTION,
 * source/store/next for DYNAMIC, text only for END.
 *
 * @param mapping ACTION: canonical field ({@code creditParty}, {@code amount}, {@code partnerId},
 *     {@code productCode}, {@code billerCode}) to input name, or a literal written {@code =VALUE};
 *     INPUT with a {@code lookup}: the lookup's argument to input name
 * @param source DYNAMIC: the name of the lookup that lists the options
 * @param lookup INPUT: a read-only lookup run once the input is valid ({@code bill}, see {@code
 *     service/Lookups}), whose values join the inputs; a refusal asks again with its message
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MenuNode(
    NodeType type,
    Text text,
    List<MenuOption> options,
    ValidatorSpec validator,
    String store,
    String next,
    String flow,
    Map<String, String> mapping,
    String onSuccess,
    String onFailure,
    String source,
    String lookup) {

  public MenuNode {
    options = options == null ? List.of() : List.copyOf(options);
    mapping = mapping == null ? Map.of() : Map.copyOf(mapping);
  }
}
