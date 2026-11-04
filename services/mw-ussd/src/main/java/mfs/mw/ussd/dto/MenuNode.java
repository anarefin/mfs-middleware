package mfs.mw.ussd.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Map;

/**
 * One node of a menu tree (spec 9.2). Which fields apply depends on {@code type}: options for
 * MENU and CONFIRM, validator/store/next for INPUT, flow/mapping/onSuccess/onFailure for ACTION,
 * source/store/next for DYNAMIC, text only for END.
 *
 * @param mapping ACTION: canonical field ({@code creditParty}, {@code amount}) to input name
 * @param source DYNAMIC: the name of the lookup that lists the options
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
    String source) {

  public MenuNode {
    options = options == null ? List.of() : List.copyOf(options);
    mapping = mapping == null ? Map.of() : Map.copyOf(mapping);
  }
}
