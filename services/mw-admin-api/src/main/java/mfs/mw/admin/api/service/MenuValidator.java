package mfs.mw.admin.api.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import mfs.mw.admin.api.dto.Menu;
import mfs.mw.admin.api.dto.MenuNode;
import mfs.mw.admin.api.dto.MenuOption;
import mfs.mw.admin.api.dto.NodeType;
import mfs.mw.admin.api.dto.Text;
import mfs.mw.admin.api.dto.ValidatorSpec;

/**
 * The maker-time check of a USSD menu tree (ticket 39, spec 9.2): a copy of mw-ussd's {@code
 * service/MenuValidator} (module isolation: copied, not shared; keep the two aligned) plus complete
 * texts. Every reference resolves, every node has what its type needs, a PIN input leads straight
 * to an ACTION and is never stored, and every text a subscriber can see (node texts, option
 * labels, validator errors, the engine messages) has both {@code bn} and {@code en}. mw-ussd checks
 * the same again when the published version arrives.
 */
public final class MenuValidator {

  static final Set<String> VALIDATORS =
      Set.of(ValidatorSpec.MSISDN, ValidatorSpec.AMOUNT, ValidatorSpec.PIN, ValidatorSpec.NUMERIC, ValidatorSpec.REGEX);

  /** Engine messages every menu carries (mw-ussd {@code dto/Menu}). */
  static final List<String> MESSAGES =
      List.of(Menu.INVALID, Menu.TOO_MANY_ATTEMPTS, Menu.EXPIRED, Menu.PROCESSING, Menu.UNAVAILABLE, Menu.MORE);

  private static final Set<String> BASE = Set.of("creditParty", "amount");

  /** mw-ussd's flows and the canonical fields each needs (its {@code TxnRequestMapper.REQUIRED_FIELDS}). */
  static final Map<String, Set<String>> FLOW_FIELDS =
      Map.of(
          "P2P", BASE,
          "CASH_OUT", BASE,
          "TOPUP", Set.of("creditParty", "amount", "partnerId", "productCode"),
          "BILL_PAY", Set.of("creditParty", "amount", "partnerId", "billerCode"));

  /** mw-ussd's INPUT lookups and their arguments (its {@code Lookups.ARGUMENTS}). */
  static final Map<String, Set<String>> LOOKUPS = Map.of("bill", Set.of("billerCode", "billNumber"));

  private MenuValidator() {}

  /** The problems found; empty when the menu may be published. */
  public static List<String> problems(Menu menu) {
    List<String> p = new ArrayList<>();
    Map<String, MenuNode> nodes = menu.nodes();
    if (menu.menuId() == null || menu.menuId().isBlank() || menu.version() < 1) {
      p.add("menuId and a version >= 1 are required");
    }
    if (menu.start() == null || !nodes.containsKey(menu.start())) {
      p.add("start node " + menu.start() + " does not exist");
    }
    for (String m : MESSAGES) {
      if (!complete(menu.messages().get(m))) {
        p.add("message " + m + " needs both bn and en");
      }
    }
    nodes.forEach((id, n) -> check(id, n, nodes, p));
    return List.copyOf(p);
  }

  private static void check(String id, MenuNode n, Map<String, MenuNode> nodes, List<String> p) {
    if (n.type() == null) {
      p.add(id + ": type is required");
      return;
    }
    texts(id, n, p);
    switch (n.type()) {
      case MENU, CONFIRM -> {
        if (n.options().isEmpty()) {
          p.add(id + ": options are required");
        }
        for (MenuOption o : n.options()) {
          ref(id, "option " + o.key(), o.next(), nodes, p);
        }
      }
      case INPUT -> {
        ValidatorSpec v = n.validator();
        if (v == null || !VALIDATORS.contains(v.type())) {
          p.add(id + ": a validator of " + new TreeSet<>(VALIDATORS) + " is required");
          return;
        }
        if (ValidatorSpec.REGEX.equals(v.type()) && !compiles(v.pattern())) {
          p.add(id + ": regex pattern does not compile");
        }
        ref(id, "next", n.next(), nodes, p);
        if (v.isPin()) {
          if (n.store() != null) {
            p.add(id + ": a PIN input is never stored");
          }
          MenuNode next = nodes.get(n.next());
          if (next != null && next.type() != NodeType.ACTION) {
            p.add(id + ": a PIN input must lead straight to an ACTION");
          }
        } else if (n.store() == null || n.store().isBlank()) {
          p.add(id + ": store is required");
        }
        if (n.lookup() != null) {
          if (v.isPin() || !LOOKUPS.containsKey(n.lookup())) {
            p.add(id + ": lookup must be one of " + new TreeSet<>(LOOKUPS.keySet()));
          } else {
            missing(id, "lookup " + n.lookup(), LOOKUPS.get(n.lookup()), n, p);
          }
        }
      }
      case ACTION -> {
        if (!FLOW_FIELDS.containsKey(n.flow())) {
          p.add(id + ": flow must be one of " + new TreeSet<>(FLOW_FIELDS.keySet()));
        } else {
          missing(id, "flow " + n.flow(), FLOW_FIELDS.get(n.flow()), n, p);
        }
        ref(id, "onSuccess", n.onSuccess(), nodes, p);
        ref(id, "onFailure", n.onFailure(), nodes, p);
      }
      case DYNAMIC -> {
        if (n.source() == null || n.store() == null) {
          p.add(id + ": source and store are required");
        }
        ref(id, "next", n.next(), nodes, p);
      }
      case END -> {
        // text checked below
      }
      default -> p.add(id + ": unknown type");
    }
  }

  /** Every node but an ACTION shows a text; each text, label and error in both languages. */
  private static void texts(String id, MenuNode n, List<String> p) {
    if (n.type() != NodeType.ACTION) {
      if (n.text() == null) {
        p.add(id + ": text is required");
      } else if (!complete(n.text())) {
        p.add(id + ": text needs both bn and en");
      }
    } else if (n.text() != null && !complete(n.text())) {
      p.add(id + ": text needs both bn and en");
    }
    for (MenuOption o : n.options()) {
      if (!complete(o.label())) {
        p.add(id + ": option " + o.key() + " label needs both bn and en");
      }
    }
    if (n.validator() != null && n.validator().error() != null && !complete(n.validator().error())) {
      p.add(id + ": validator error needs both bn and en");
    }
  }

  private static boolean complete(Text t) {
    return t != null && t.bn() != null && !t.bn().isBlank() && t.en() != null && !t.en().isBlank();
  }

  private static void missing(String id, String what, Set<String> needed, MenuNode n, List<String> p) {
    Set<String> absent = new TreeSet<>(needed);
    absent.removeAll(n.mapping().keySet());
    if (!absent.isEmpty()) {
      p.add(id + ": " + what + " needs a mapping for " + absent);
    }
  }

  private static void ref(String id, String what, String target, Map<String, MenuNode> nodes, List<String> p) {
    if (target == null || !nodes.containsKey(target)) {
      p.add(id + ": " + what + " points to missing node " + target);
    }
  }

  private static boolean compiles(String pattern) {
    try {
      Pattern.compile(pattern);
      return true;
    } catch (RuntimeException e) {
      return false;
    }
  }
}
