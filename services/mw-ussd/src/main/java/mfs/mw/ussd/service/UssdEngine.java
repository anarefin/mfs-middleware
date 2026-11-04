package mfs.mw.ussd.service;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import mfs.mw.ussd.config.UssdProperties;
import mfs.mw.ussd.dto.EncryptedPin;
import mfs.mw.ussd.dto.ErrorInfo;
import mfs.mw.ussd.dto.FlowOutcome;
import mfs.mw.ussd.dto.FlowRequest;
import mfs.mw.ussd.dto.Menu;
import mfs.mw.ussd.dto.MenuNode;
import mfs.mw.ussd.dto.MenuOption;
import mfs.mw.ussd.dto.NodeType;
import mfs.mw.ussd.dto.Text;
import mfs.mw.ussd.dto.UssdRequest;
import mfs.mw.ussd.dto.UssdResponse;
import mfs.mw.ussd.dto.UssdSession;
import mfs.mw.ussd.repository.UssdSessionStore;
import mfs.mw.ussd.util.PinCipher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The USSD menu engine (spec 9.2): walks a menu tree (MENU, INPUT, CONFIRM, ACTION, DYNAMIC, END)
 * one step per request, whatever the transport. Session state lives in Valkey with TTL = MNO
 * timeout + grace; a PIN input is encrypted with the core's public key on receipt and handed
 * straight to the following ACTION, so it never reaches the session. Screens longer than the
 * MNO's maximum are truncated or paged per MNO configuration.
 */
@Service
public class UssdEngine {

  private static final Logger log = LoggerFactory.getLogger(UssdEngine.class);

  private static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z0-9_.]+)}");
  private static final String UNAVAILABLE_FALLBACK = "Service temporarily unavailable. Please try later.";

  private final MenuCatalog menus;
  private final UssdSessionStore sessions;
  private final InputValidator validator;
  private final ScreenFormatter formatter;
  private final TransactionFlow flow;
  private final PinCipher pins;
  private final DynamicLists lists;
  private final UssdProperties props;
  private final Clock clock;

  public UssdEngine(
      MenuCatalog menus,
      UssdSessionStore sessions,
      InputValidator validator,
      ScreenFormatter formatter,
      TransactionFlow flow,
      PinCipher pins,
      DynamicLists lists,
      UssdProperties props,
      Clock clock) {
    this.menus = menus;
    this.sessions = sessions;
    this.validator = validator;
    this.formatter = formatter;
    this.flow = flow;
    this.pins = pins;
    this.lists = lists;
    this.props = props;
    this.clock = clock;
  }

  /** One step. Never throws: a failure ends the session with "service unavailable". */
  public UssdResponse handle(UssdRequest r) {
    try {
      return switch (r.event()) {
        case BEGIN -> begin(r);
        case CONTINUE -> proceed(r);
        case END, ABORT -> {
          sessions.delete(r.mno(), r.sessionId());
          yield new UssdResponse(r.sessionId(), "", true);
        }
      };
    } catch (RuntimeException e) {
      log.atError()
          .addKeyValue("event", "ussd.step.failed")
          .addKeyValue("mno", r.mno())
          .addKeyValue("errorClass", e.getClass().getName())
          .log("USSD step failed, ending the session");
      return new UssdResponse(r.sessionId(), unavailableText(r.mno()), true);
    }
  }

  private UssdResponse begin(UssdRequest r) {
    Menu menu = menus.active(r.mno());
    UssdSession s =
        new UssdSession(
            r.mno(), r.sessionId(), r.msisdn(), menu.menuId(), menu.version(), props.defaultLanguage(),
            menu.start(), List.of(), Map.of(), 0, List.of(), false, clock.instant());
    log.atInfo()
        .addKeyValue("event", "ussd.session.started")
        .addKeyValue("mno", r.mno())
        .addKeyValue("menuId", menu.menuId())
        .addKeyValue("menuVersion", menu.version())
        .log("USSD session started");
    return enter(menu, s, Map.of(), null);
  }

  private UssdResponse proceed(UssdRequest r) {
    Optional<UssdSession> found =
        sessions.find(r.mno(), r.sessionId()).filter(s -> s.msisdn().equals(r.msisdn()));
    if (found.isEmpty()) {
      Menu menu = menus.active(r.mno());
      return new UssdResponse(r.sessionId(), menu.message(Menu.EXPIRED, props.defaultLanguage()), true);
    }
    UssdSession s = found.get();
    Menu menu = menus.version(s.menuId(), s.menuVersion());
    String input = r.input() == null ? "" : r.input().trim();
    if (!s.pages().isEmpty() && (s.endAfterPages() || input.equals(moreKey(menu, s.language())))) {
      return nextPage(s);
    }
    MenuNode node = menu.node(s.nodeId());
    return switch (node.type()) {
      case MENU, CONFIRM -> choose(menu, s, node, input);
      case DYNAMIC -> pick(menu, s, node, input);
      case INPUT -> input(menu, s, node, input);
      case ACTION, END -> {
        sessions.delete(s.mno(), s.sessionId());
        yield new UssdResponse(s.sessionId(), menu.message(Menu.EXPIRED, s.language()), true);
      }
    };
  }

  private UssdResponse choose(Menu menu, UssdSession s, MenuNode node, String input) {
    MenuOption option =
        node.options().stream().filter(o -> o.key().equals(input)).findFirst().orElse(null);
    if (option == null) {
      return invalid(menu, s, node);
    }
    String lang = option.switchLanguage() ? toggle(s.language()) : s.language();
    return enter(menu, s.at(option.next(), append(s.path(), s.nodeId() + ":" + option.key()), s.inputs(), lang),
        Map.of(), null);
  }

  private UssdResponse pick(Menu menu, UssdSession s, MenuNode node, String input) {
    List<UssdProperties.ListItem> items = lists.items(node.source());
    int index;
    try {
      index = Integer.parseInt(input) - 1;
    } catch (NumberFormatException e) {
      index = -1;
    }
    if (index < 0 || index >= items.size()) {
      return invalid(menu, s, node);
    }
    Map<String, String> inputs = new HashMap<>(s.inputs());
    inputs.put(node.store(), items.get(index).value());
    return enter(menu, s.at(node.next(), append(s.path(), s.nodeId() + ":" + input), inputs, s.language()),
        Map.of(), null);
  }

  private UssdResponse input(Menu menu, UssdSession s, MenuNode node, String input) {
    Optional<String> valid = validator.validate(node.validator(), input, s.msisdn());
    if (valid.isEmpty()) {
      return invalid(menu, s, node);
    }
    List<String> path = append(s.path(), s.nodeId());
    if (node.validator().isPin()) {
      // encrypted on receipt, forwarded once to the ACTION, never stored (spec 9.2)
      EncryptedPin pin = pins.encrypt(valid.get());
      return enter(menu, s.at(node.next(), path, s.inputs(), s.language()), Map.of(), pin);
    }
    Map<String, String> inputs = new HashMap<>(s.inputs());
    inputs.put(node.store(), valid.get());
    return enter(menu, s.at(node.next(), path, inputs, s.language()), Map.of(), null);
  }

  private UssdResponse invalid(Menu menu, UssdSession s, MenuNode node) {
    UssdSession next = s.withInvalidAttempt();
    if (next.invalidAttempts() >= props.maxInvalidAttempts()) {
      sessions.delete(s.mno(), s.sessionId());
      return new UssdResponse(s.sessionId(), menu.message(Menu.TOO_MANY_ATTEMPTS, s.language()), true);
    }
    Text error = node.validator() == null ? null : node.validator().error();
    String prefix = error == null ? menu.message(Menu.INVALID, s.language()) : error.in(s.language());
    return show(menu, next, prefix + "\n" + render(node, next, Map.of()), false);
  }

  /** Arrives at the session's node: runs an ACTION, shows anything else. */
  private UssdResponse enter(Menu menu, UssdSession s, Map<String, String> vars, EncryptedPin pin) {
    MenuNode node = menu.node(s.nodeId());
    if (node.type() == NodeType.ACTION) {
      return act(menu, s, node, pin);
    }
    return show(menu, s, render(node, s, vars), node.type() == NodeType.END);
  }

  private UssdResponse act(Menu menu, UssdSession s, MenuNode node, EncryptedPin pin) {
    Map<String, String> fields = new HashMap<>();
    node.mapping().forEach((field, inputName) -> fields.put(field, s.inputs().get(inputName)));
    FlowOutcome outcome = flow.submit(new FlowRequest(node.flow(), s, String.join(">", s.path()), fields, pin));
    Map<String, String> vars = new HashMap<>();
    if (outcome.txnId() != null) {
      vars.put("txnId", outcome.txnId());
    }
    return switch (outcome.kind()) {
      case COMPLETED -> enter(menu, s.at(node.onSuccess(), s.path(), s.inputs(), s.language()), vars, null);
      case FAILED -> {
        vars.put("error", errorText(outcome.error(), s.language()));
        yield enter(menu, s.at(node.onFailure(), s.path(), s.inputs(), s.language()), vars, null);
      }
      case PROCESSING -> endWith(menu, s, Menu.PROCESSING);
      case UNAVAILABLE -> endWith(menu, s, Menu.UNAVAILABLE);
    };
  }

  private UssdResponse endWith(Menu menu, UssdSession s, String message) {
    return show(menu, s, menu.message(message, s.language()), true);
  }

  /** Sends the first page, keeps the rest (and the session) when the screen is too long. */
  private UssdResponse show(Menu menu, UssdSession s, String text, boolean end) {
    List<String> pages = formatter.pages(text, mno(s.mno()), menu.message(Menu.MORE, s.language()));
    if (pages.size() == 1) {
      if (end) {
        sessions.delete(s.mno(), s.sessionId());
      } else {
        sessions.save(s.withPages(List.of(), false), ttl(s.mno()));
      }
      return new UssdResponse(s.sessionId(), pages.getFirst(), end);
    }
    sessions.save(s.withPages(pages.subList(1, pages.size()), end), ttl(s.mno()));
    return new UssdResponse(s.sessionId(), pages.getFirst(), false);
  }

  private UssdResponse nextPage(UssdSession s) {
    List<String> rest = s.pages();
    List<String> remaining = rest.subList(1, rest.size());
    if (remaining.isEmpty() && s.endAfterPages()) {
      sessions.delete(s.mno(), s.sessionId());
      return new UssdResponse(s.sessionId(), rest.getFirst(), true);
    }
    sessions.save(s.withPages(remaining, s.endAfterPages()), ttl(s.mno()));
    return new UssdResponse(s.sessionId(), rest.getFirst(), false);
  }

  private String render(MenuNode node, UssdSession s, Map<String, String> vars) {
    String lang = s.language();
    StringBuilder t = new StringBuilder(interpolate(node.text() == null ? "" : node.text().in(lang), s.inputs(), vars));
    if (node.type() == NodeType.MENU || node.type() == NodeType.CONFIRM) {
      for (MenuOption o : node.options()) {
        t.append('\n').append(o.key()).append(' ').append(o.label() == null ? "" : o.label().in(lang));
      }
    } else if (node.type() == NodeType.DYNAMIC) {
      List<UssdProperties.ListItem> items = lists.items(node.source());
      for (int i = 0; i < items.size(); i++) {
        t.append('\n').append(i + 1).append(' ').append(items.get(i).label().in(lang));
      }
    }
    return t.toString();
  }

  private static String interpolate(String template, Map<String, String> inputs, Map<String, String> vars) {
    Matcher m = PLACEHOLDER.matcher(template);
    StringBuilder out = new StringBuilder();
    while (m.find()) {
      String v = vars.getOrDefault(m.group(1), inputs.getOrDefault(m.group(1), ""));
      m.appendReplacement(out, Matcher.quoteReplacement(v));
    }
    m.appendTail(out);
    return out.toString();
  }

  private static String errorText(ErrorInfo error, String lang) {
    if (error == null) {
      return "";
    }
    if (Text.BN.equals(lang) && error.messageBn() != null) {
      return error.messageBn();
    }
    return error.message() == null ? error.code() : error.message();
  }

  /** The key that shows the next page: the first word of the menu's "more" text. */
  private static String moreKey(Menu menu, String lang) {
    String more = menu.message(Menu.MORE, lang).trim();
    int sp = more.indexOf(' ');
    return sp < 0 ? more : more.substring(0, sp);
  }

  private String unavailableText(String mno) {
    try {
      return menus.active(mno).message(Menu.UNAVAILABLE, props.defaultLanguage());
    } catch (RuntimeException e) {
      return UNAVAILABLE_FALLBACK;
    }
  }

  private UssdProperties.Mno mno(String mno) {
    UssdProperties.Mno m = props.mno().get(mno);
    if (m == null) {
      throw new IllegalStateException("no configuration for MNO " + mno);
    }
    return m;
  }

  private Duration ttl(String mno) {
    return mno(mno).sessionTimeout().plus(props.sessionGrace());
  }

  private static String toggle(String lang) {
    return Text.EN.equals(lang) ? Text.BN : Text.EN;
  }

  private static List<String> append(List<String> path, String step) {
    List<String> p = new ArrayList<>(path);
    p.add(step);
    return p;
  }
}
