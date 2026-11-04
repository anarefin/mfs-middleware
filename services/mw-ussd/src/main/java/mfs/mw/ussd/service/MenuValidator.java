package mfs.mw.ussd.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import mfs.mw.ussd.dto.Menu;
import mfs.mw.ussd.dto.MenuNode;
import mfs.mw.ussd.dto.MenuOption;
import mfs.mw.ussd.dto.NodeType;
import mfs.mw.ussd.dto.ValidatorSpec;
import mfs.mw.ussd.mapper.TxnRequestMapper;

/**
 * Checks a menu tree before it is used (spec 9.2): every reference resolves, every node has what
 * its type needs, and a PIN input leads straight to an ACTION and is never stored, so the PIN is
 * forwarded once on receipt and never kept in the session.
 */
public final class MenuValidator {

  private static final Set<String> VALIDATORS =
      Set.of(ValidatorSpec.MSISDN, ValidatorSpec.AMOUNT, ValidatorSpec.PIN, ValidatorSpec.NUMERIC, ValidatorSpec.REGEX);

  private MenuValidator() {}

  /** The problems found; empty when the menu is usable. */
  public static List<String> problems(Menu menu) {
    List<String> p = new ArrayList<>();
    Map<String, MenuNode> nodes = menu.nodes();
    if (menu.menuId() == null || menu.menuId().isBlank() || menu.version() < 1) {
      p.add("menuId and a version >= 1 are required");
    }
    if (menu.start() == null || !nodes.containsKey(menu.start())) {
      p.add("start node " + menu.start() + " does not exist");
    }
    nodes.forEach((id, n) -> check(id, n, nodes, p));
    return List.copyOf(p);
  }

  public static Menu requireValid(Menu menu) {
    List<String> p = problems(menu);
    if (!p.isEmpty()) {
      throw new IllegalArgumentException("menu " + menu.menuId() + " v" + menu.version() + ": " + p);
    }
    return menu;
  }

  private static void check(String id, MenuNode n, Map<String, MenuNode> nodes, List<String> p) {
    if (n.type() == null) {
      p.add(id + ": type is required");
      return;
    }
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
          p.add(id + ": a validator of " + VALIDATORS + " is required");
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
          if (v.isPin() || !Lookups.ARGUMENTS.containsKey(n.lookup())) {
            p.add(id + ": lookup must be one of " + new TreeSet<>(Lookups.ARGUMENTS.keySet()));
          } else {
            missing(id, "lookup " + n.lookup(), Lookups.ARGUMENTS.get(n.lookup()), n, p);
          }
        }
      }
      case ACTION -> {
        if (!TxnRequestMapper.FLOWS.contains(n.flow())) {
          p.add(id + ": flow must be one of " + new TreeSet<>(TxnRequestMapper.FLOWS));
        } else {
          missing(id, "flow " + n.flow(), TxnRequestMapper.REQUIRED_FIELDS.get(n.flow()), n, p);
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
        if (n.text() == null) {
          p.add(id + ": text is required");
        }
      }
      default -> p.add(id + ": unknown type");
    }
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
