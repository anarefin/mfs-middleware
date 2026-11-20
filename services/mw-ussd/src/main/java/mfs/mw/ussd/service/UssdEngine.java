package mfs.mw.ussd.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import mfs.mw.ussd.config.UssdProperties;
import mfs.mw.ussd.dto.Balance;
import mfs.mw.ussd.dto.EncryptedPin;
import mfs.mw.ussd.dto.ErrorInfo;
import mfs.mw.ussd.dto.FlowOutcome;
import mfs.mw.ussd.dto.FlowRequest;
import mfs.mw.ussd.dto.LookupOutcome;
import mfs.mw.ussd.dto.Menu;
import mfs.mw.ussd.dto.MenuNode;
import mfs.mw.ussd.dto.MenuOption;
import mfs.mw.ussd.dto.NodeType;
import mfs.mw.ussd.dto.Text;
import mfs.mw.ussd.dto.UssdRequest;
import mfs.mw.ussd.dto.UssdResponse;
import mfs.mw.ussd.dto.UssdSession;
import mfs.mw.ussd.mapper.TxnRequestMapper;
import mfs.mw.ussd.repository.UssdSessionStore;
import mfs.mw.ussd.util.UssdText;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The USSD menu engine (spec 9.2): walks a menu tree (MENU, INPUT, CONFIRM, ACTION, DYNAMIC, END)
 * one step per request, whatever the transport. Session state lives in Redis with TTL = MNO
 * timeout + grace; a PIN input is encrypted under the mw-crypto transport key on receipt ({@link
 * PinTransport}) and handed straight to the following ACTION, so it never reaches the session.
 * mw-auth checks it before anything is published (ticket 72): a wrong PIN asks for the PIN again
 * with the menu's {@code wrongPin} text (as long as mw-auth answers wrong PIN; its lockout rule
 * decides when that ends), a lock ends the session with {@code pinLocked}, {@code pinBlocked} or
 * {@code walletLocked}. A BALANCE check (ADR D26) fills {@code {balance}} of its onSuccess text with
 * the available balance (never stored in the session); no final answer in time ends with {@code
 * balanceUnavailable}, as no SMS follows a balance check. Screens longer than the MNO's maximum are
 * truncated or paged per MNO configuration.
 */
@Service
public class UssdEngine {

  private static final Logger log = LoggerFactory.getLogger(UssdEngine.class);

  private static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z0-9_.]+)}");
  /** An ACTION mapping value starting with this is a literal, not an input name ({@code =PREPAID}). */
  public static final String LITERAL = "=";
  private static final String UNAVAILABLE_FALLBACK = "Service temporarily unavailable. Please try later.";
  /** The PIN texts when a menu (e.g. one published before ticket 72) carries none. */
  static final Map<String, Text> PIN_TEXTS =
      Map.of(
          Menu.WRONG_PIN,
          new Text("ভুল পিন। আর {triesLeft} বার চেষ্টা করা যাবে।", "Wrong PIN. {triesLeft} tries left."),
          Menu.PIN_LOCKED,
          new Text("পিন সাময়িকভাবে লক। পরে আবার চেষ্টা করুন।", "Your PIN is locked for now. Please try again later."),
          Menu.PIN_BLOCKED,
          new Text("পিন লক। অ্যাপে পিন রিসেট করুন বা কাস্টমার কেয়ারে কল করুন।",
              "Your PIN is locked. Reset it in the app or call customer care."),
          Menu.WALLET_LOCKED,
          new Text("আপনার অ্যাকাউন্ট লক। কাস্টমার কেয়ারে কল করুন।", "Your account is locked. Please call customer care."));
  /**
   * Session input set by the confirm step of a suspected business duplicate (ticket 99, FRS
   * PLT-013): the next submit of the ACTION carries {@code confirmDuplicate}.
   */
  public static final String CONFIRM_DUPLICATE = FlowRequest.CONFIRM_DUPLICATE;
  /** The orchestrator's confirmable business-duplicate code. */
  static final String DUPLICATE_SUSPECTED = "MW-VAL-024";
  /** The {@code duplicateSuspected} text when a menu carries none. */
  static final Text DUPLICATE_TEXT =
      new Text("কিছুক্ষণ আগে একই লেনদেন করেছেন। আবার পাঠাতে পিন দিন।",
          "You sent the same payment moments ago. Enter your PIN to send it again.");
  /** The {@code balanceUnavailable} text when a menu carries none. */
  static final Text BALANCE_UNAVAILABLE_TEXT =
      new Text("ব্যালেন্স জানা যায়নি। আবার চেষ্টা করুন।", "Could not get your balance. Please try again.");
  /** A BEGIN with a code this MNO does not serve: {code} = the MNO's own (first) short code. */
  static final Text WRONG_CODE_TEXT =
      new Text("এই কোডটি এই নেটওয়ার্কে নেই। ডায়াল করুন {code}", "This code is not served on this network. Dial {code}");
  /** The dialled short code does not allow this customer's account type (CH-USSD-08). */
  static final Text TYPE_TEXT =
      new Text("এই কোডটি আপনার অ্যাকাউন্টের জন্য নয়।", "This code is not available for your account.");
  /** Shown with a paged screen when the menu carries no {@code back} text (CH-USSD-06). */
  static final Text BACK_TEXT = new Text("00. পেছনে", "00. Back");
  /** Previous page, or the previous node when this screen's first page is showing. */
  static final String BACK_INPUT = "00";

  private final MenuCatalog menus;
  private final UssdSessionStore sessions;
  private final InputValidator validator;
  private final ScreenFormatter formatter;
  private final TransactionFlow flow;
  private final PinTransport pins;
  private final DynamicLists lists;
  private final Lookups lookups;
  private final UssdProperties props;
  private final Clock clock;
  private final ShortCodeBook shortCodes;
  private final CustomerPrefs prefs;
  private final CoreAccounts accounts;

  public UssdEngine(
      MenuCatalog menus,
      UssdSessionStore sessions,
      InputValidator validator,
      ScreenFormatter formatter,
      TransactionFlow flow,
      PinTransport pins,
      DynamicLists lists,
      Lookups lookups,
      UssdProperties props,
      Clock clock,
      ShortCodeBook shortCodes,
      CustomerPrefs prefs,
      CoreAccounts accounts) {
    this.menus = menus;
    this.sessions = sessions;
    this.validator = validator;
    this.formatter = formatter;
    this.flow = flow;
    this.pins = pins;
    this.lists = lists;
    this.lookups = lookups;
    this.props = props;
    this.clock = clock;
    this.shortCodes = shortCodes;
    this.prefs = prefs;
    this.accounts = accounts;
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
    UssdProperties.Mno config = props.mno().get(r.mno());
    String lang = prefs.language(r.msisdn());
    ShortCode code = null;
    if (config != null && shortCodes.configured(r.mno()) && r.serviceCode() != null && !r.serviceCode().isBlank()) {
      String dialled = r.serviceCode().replaceAll("\\s", "");
      code = shortCodes.find(r.mno(), dialled).orElse(null);
      if (code == null) {
        return refuseCode(r, config, lang);
      }
      ShortCode dialledCode = code;
      Optional<String> type = accounts.type(r.msisdn());
      boolean allowed =
          type.filter(t -> dialledCode.allowedTypes().stream().anyMatch(a -> a.equalsIgnoreCase(t))).isPresent();
      if (!allowed) {
        log.atInfo()
            .addKeyValue("event", "ussd.session.refused")
            .addKeyValue("mno", r.mno())
            .log("USSD session refused: the short code does not allow this account type");
        return new UssdResponse(r.sessionId(), TYPE_TEXT.in(lang), true);
      }
    } else if (config != null && !servesCode(config, r.serviceCode())) {
      return refuseCode(r, config, lang);
    }
    Menu menu = menus.active(r.mno());
    if (code != null && !code.rootMenuId().equals(menu.menuId())) {
      menu = menus.latest(code.rootMenuId());
    }
    UssdSession s =
        new UssdSession(
            r.mno(), r.sessionId(), r.msisdn(), menu.menuId(), menu.version(), lang,
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
    if (timedOut(s)) {
      sessions.delete(s.mno(), s.sessionId());
      return new UssdResponse(s.sessionId(), menu.message(Menu.EXPIRED, s.language()), true);
    }
    String input = r.input() == null ? "" : r.input().trim();
    if (BACK_INPUT.equals(input)) {
      return goBack(menu, s);
    }
    boolean moreLeft = s.pageIndex() + 1 < s.pages().size();
    if (moreLeft && (s.endAfterPages() || input.equals(moreKey(menu, s.language())))) {
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
    if (option.switchLanguage()) {
      prefs.remember(s.msisdn(), lang);
    }
    Map<String, String> inputs = s.inputs();
    if (!option.set().isEmpty()) {
      // {mno} is the session MNO's top-up operator (the telco partnerId, e.g. bl -> banglalink)
      UssdProperties.Mno mno = props.mno().get(s.mno());
      Map<String, String> own =
          Map.of("msisdn", s.msisdn(), "mno", mno == null ? s.mno() : mno.operatorOr(s.mno()));
      inputs = new HashMap<>(s.inputs());
      for (Map.Entry<String, String> e : option.set().entrySet()) {
        inputs.put(e.getKey(), interpolate(e.getValue(), s.inputs(), own));
      }
    }
    return enter(menu, s.forward(option.next(), append(s.path(), s.nodeId() + ":" + option.key()), inputs, lang),
        Map.of(), null);
  }

  private UssdResponse pick(Menu menu, UssdSession s, MenuNode node, String input) {
    List<UssdProperties.ListItem> items = lists.items(node.source(), s.inputs());
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
    return enter(menu, s.forward(node.next(), append(s.path(), s.nodeId() + ":" + input), inputs, s.language()),
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
      // not pushed: a wrong PIN returns to this same input, and 00 from it returns to the screen before it
      return enter(menu, s.at(node.next(), path, s.inputs(), s.language()), Map.of(), pin);
    }
    Map<String, String> inputs = new HashMap<>(s.inputs());
    inputs.put(node.store(), valid.get());
    if (node.lookup() != null) {
      LookupOutcome found = lookups.run(node.lookup(), lookupArgs(node, inputs, s.language()));
      switch (found.kind()) {
        case FOUND -> inputs.putAll(found.values());
        case REFUSED -> {
          return invalid(menu, s, node, errorText(found.error(), s.language()));
        }
        case UNAVAILABLE -> {
          return endWith(menu, s, Menu.UNAVAILABLE);
        }
      }
    }
    return enter(menu, s.forward(node.next(), path, inputs, s.language()), Map.of(), null);
  }

  private UssdResponse invalid(Menu menu, UssdSession s, MenuNode node) {
    Text error = node.validator() == null ? null : node.validator().error();
    String prefix = error == null ? menu.message(Menu.INVALID, s.language()) : error.in(s.language());
    return invalid(menu, s, node, prefix);
  }

  /** Asks the same node again with {@code prefix} above it, or ends after too many attempts. */
  private UssdResponse invalid(Menu menu, UssdSession s, MenuNode node, String prefix) {
    UssdSession next = s.withInvalidAttempt();
    if (next.invalidAttempts() >= props.maxInvalidAttempts()) {
      sessions.delete(s.mno(), s.sessionId());
      return new UssdResponse(s.sessionId(), menu.message(Menu.TOO_MANY_ATTEMPTS, s.language()), true);
    }
    return show(menu, next, prefix + "\n" + render(node, next, Map.of()), false);
  }

  /** Arrives at the session's node: quotes a fee or reads an account screen, runs an ACTION, shows anything else. */
  private UssdResponse enter(Menu menu, UssdSession s, Map<String, String> vars, EncryptedPin pin) {
    MenuNode node = menu.node(s.nodeId());
    if (node.lookup() != null && node.type() != NodeType.INPUT) {
      LookupOutcome found = lookups.run(node.lookup(), lookupArgs(node, s.inputs(), s.language()));
      switch (found.kind()) {
        case FOUND -> {
          Map<String, String> inputs = new HashMap<>(s.inputs());
          inputs.putAll(found.values());
          s = s.at(s.nodeId(), s.path(), inputs, s.language());
        }
        case REFUSED, UNAVAILABLE -> {
          return endWith(menu, s, Menu.UNAVAILABLE);
        }
      }
    }
    if (node.type() == NodeType.ACTION) {
      return act(menu, s, node, pin);
    }
    return show(menu, s, render(node, s, vars), node.type() == NodeType.END);
  }

  /** Lookup arguments: a mapping value {@code =VALUE} is that literal, otherwise the named input. */
  private static Map<String, String> lookupArgs(MenuNode node, Map<String, String> inputs, String language) {
    Map<String, String> args = new HashMap<>();
    node.mapping().forEach((arg, source) -> {
      if (source == null) {
        return;
      }
      String value = source.startsWith(LITERAL) ? source.substring(LITERAL.length()) : inputs.get(source);
      if (value != null) {
        args.put(arg, value);
      }
    });
    args.put("language", language);
    return args;
  }

  private UssdResponse act(Menu menu, UssdSession s, MenuNode node, EncryptedPin pin) {
    Map<String, String> fields = new HashMap<>();
    node.mapping().forEach((field, source) -> {
      String value = source.startsWith(LITERAL) ? source.substring(LITERAL.length()) : s.inputs().get(source);
      if (value != null) {
        fields.put(field, value);
      }
    });
    FlowOutcome outcome = flow.submit(new FlowRequest(node.flow(), s, String.join(">", s.path()), fields, pin));
    Map<String, String> vars = new HashMap<>();
    if (outcome.txnId() != null) {
      vars.put("txnId", outcome.txnId());
    }
    Balance balance = outcome.balance();
    if (balance != null && balance.available() != null && balance.available().valueMinor() != null) {
      // a screen variable only: never in the session's inputs
      vars.put("balance", UssdText.taka(balance.available().valueMinor()));
    }
    boolean balanceCheck = TxnRequestMapper.BALANCE.equals(node.flow());
    return switch (outcome.kind()) {
      case COMPLETED -> enter(menu, s.at(node.onSuccess(), s.path(), s.inputs(), s.language()), vars, null);
      case FAILED -> {
        if (isDuplicatePrompt(outcome.error(), s)) {
          UssdResponse confirm = askToConfirmDuplicate(menu, s);
          if (confirm != null) {
            yield confirm;
          }
        }
        vars.put("error", errorText(outcome.error(), s.language()));
        yield enter(menu, s.at(node.onFailure(), s.path(), s.inputs(), s.language()), vars, null);
      }
      // no SMS follows a balance check: "being processed, result by SMS" would promise one
      case PROCESSING -> balanceCheck ? balanceUnavailable(menu, s) : endWith(menu, s, Menu.PROCESSING);
      case UNAVAILABLE -> balanceCheck ? balanceUnavailable(menu, s) : endWith(menu, s, Menu.UNAVAILABLE);
      case WRONG_PIN -> askPinAgain(menu, s, outcome.triesLeft());
      case LOCKED -> show(menu, s, pinText(menu, lockMessage(outcome.error()), s.language(), Map.of()), true);
    };
  }

  /**
   * Back to the PIN input the ACTION came from (the last step of the path), with the wrong-PIN text
   * above it. Not counted as an invalid input: mw-auth counts wrong PINs and locks.
   */
  private UssdResponse askPinAgain(Menu menu, UssdSession s, String triesLeft) {
    List<String> path = s.path();
    MenuNode pin = path.isEmpty() ? null : menu.nodes().get(path.getLast());
    if (pin == null || pin.type() != NodeType.INPUT || pin.validator() == null || !pin.validator().isPin()) {
      return endWith(menu, s, Menu.UNAVAILABLE);
    }
    UssdSession back = s.at(path.getLast(), path.subList(0, path.size() - 1), s.inputs(), s.language());
    Map<String, String> vars = Map.of("triesLeft", triesLeft == null ? "" : triesLeft);
    String prefix = pinText(menu, Menu.WRONG_PIN, s.language(), vars);
    return show(menu, back, prefix + "\n" + render(pin, back, Map.of()), false);
  }

  private static boolean isDuplicatePrompt(ErrorInfo error, UssdSession s) {
    return error != null && DUPLICATE_SUSPECTED.equals(error.code()) && !s.inputs().containsKey(CONFIRM_DUPLICATE);
  }

  /**
   * The confirm node of a suspected business duplicate (ticket 99): back to the PIN input with the
   * duplicate text above it; the PIN entered there is the user's yes, and that submit carries
   * {@code confirmDuplicate} (the PIN is never stored, so it is asked again). Hanging up is the no.
   * Null when the ACTION did not come from a PIN input (then the error is shown as any other).
   */
  private UssdResponse askToConfirmDuplicate(Menu menu, UssdSession s) {
    List<String> path = s.path();
    MenuNode pin = path.isEmpty() ? null : menu.nodes().get(path.getLast());
    if (pin == null || pin.type() != NodeType.INPUT || pin.validator() == null || !pin.validator().isPin()) {
      return null;
    }
    Map<String, String> inputs = new HashMap<>(s.inputs());
    inputs.put(CONFIRM_DUPLICATE, "true");
    UssdSession back = s.at(path.getLast(), path.subList(0, path.size() - 1), inputs, s.language());
    String t = menu.message(Menu.DUPLICATE_SUSPECTED, s.language());
    if (t.isEmpty()) {
      t = DUPLICATE_TEXT.in(s.language());
    }
    return show(menu, back, t + "\n" + render(pin, back, Map.of()), false);
  }

  private static String lockMessage(ErrorInfo error) {
    String code = error == null ? "" : error.code();
    return switch (code) {
      case "MW-AUTH-041" -> Menu.PIN_LOCKED;
      case "MW-AUTH-042" -> Menu.PIN_BLOCKED;
      default -> Menu.WALLET_LOCKED;
    };
  }

  /** The menu's text for {@code key}, else the built-in one, with {@code vars} filled in. */
  private static String pinText(Menu menu, String key, String lang, Map<String, String> vars) {
    String t = menu.message(key, lang);
    if (t.isEmpty()) {
      t = PIN_TEXTS.get(key).in(lang);
    }
    return interpolate(t, Map.of(), vars);
  }

  /** Ends a balance check without an answer: the menu's {@code balanceUnavailable}, else the built-in one. */
  private UssdResponse balanceUnavailable(Menu menu, UssdSession s) {
    String t = menu.message(Menu.BALANCE_UNAVAILABLE, s.language());
    return show(menu, s, t.isEmpty() ? BALANCE_UNAVAILABLE_TEXT.in(s.language()) : t, true);
  }

  private UssdResponse endWith(Menu menu, UssdSession s, String message) {
    return show(menu, s, menu.message(message, s.language()), true);
  }

  /** Sends the first page, keeps the rest (and the session) when the screen is too long. */
  private UssdResponse show(Menu menu, UssdSession s, String text, boolean end) {
    String more = menu.message(Menu.MORE, s.language());
    String back = backLabel(menu, s);
    List<String> pages = formatter.pages(text, mno(s.mno()), more, back);
    Instant now = clock.instant();
    if (pages.size() == 1) {
      if (end) {
        sessions.delete(s.mno(), s.sessionId());
      } else {
        sessions.save(s.withPages(List.of(), false).atPage(0).shown(now), ttl(s.mno()));
      }
      return new UssdResponse(s.sessionId(), pages.getFirst(), end);
    }
    sessions.save(s.withPages(pages, end).atPage(0).shown(now), ttl(s.mno()));
    return new UssdResponse(s.sessionId(), pages.getFirst(), false);
  }

  private UssdResponse nextPage(UssdSession s) {
    int i = s.pageIndex() + 1;
    List<String> all = s.pages();
    boolean last = i >= all.size() - 1;
    if (last && s.endAfterPages()) {
      sessions.delete(s.mno(), s.sessionId());
      return new UssdResponse(s.sessionId(), all.get(i), true);
    }
    sessions.save(s.atPage(i).shown(clock.instant()), ttl(s.mno()));
    return new UssdResponse(s.sessionId(), all.get(i), false);
  }

  /** Previous page of this screen, else the node the customer came from. */
  private UssdResponse goBack(Menu menu, UssdSession s) {
    if (s.pageIndex() > 0) {
      int i = s.pageIndex() - 1;
      UssdSession prev = s.atPage(i).shown(clock.instant());
      sessions.save(prev, ttl(s.mno()));
      return new UssdResponse(s.sessionId(), prev.pages().get(i), false);
    }
    if (!s.back().isEmpty()) {
      ArrayList<String> stack = new ArrayList<>(s.back());
      String node = stack.remove(stack.size() - 1);
      List<String> path = s.path().isEmpty() ? List.of() : s.path().subList(0, s.path().size() - 1);
      UssdSession prev =
          new UssdSession(
              s.mno(), s.sessionId(), s.msisdn(), s.menuId(), s.menuVersion(), s.language(), node, path,
              s.inputs(), 0, List.of(), false, s.startedAt(), s.screenAt(), List.copyOf(stack), 0);
      return show(menu, prev, render(menu.node(node), prev, Map.of()), false);
    }
    return invalid(menu, s, menu.node(s.nodeId()));
  }

  private boolean timedOut(UssdSession s) {
    UssdProperties.Mno cfg = mno(s.mno());
    Instant now = clock.instant();
    Instant shown = s.screenAt() == null ? s.startedAt() : s.screenAt();
    return !now.isBefore(s.startedAt().plus(cfg.sessionTimeout()))
        || !now.isBefore(shown.plus(cfg.screenTimeout()));
  }

  private String backLabel(Menu menu, UssdSession s) {
    String t = menu.message(Menu.BACK, s.language());
    return t.isEmpty() ? BACK_TEXT.in(s.language()) : t;
  }

  private UssdResponse refuseCode(UssdRequest r, UssdProperties.Mno config, String lang) {
    log.atInfo()
        .addKeyValue("event", "ussd.session.refused")
        .addKeyValue("mno", r.mno())
        .log("USSD session refused: the dialled code is not this MNO's");
    String own = config.shortCodes().isEmpty() ? "" : config.shortCodes().getFirst();
    return new UssdResponse(r.sessionId(), WRONG_CODE_TEXT.in(lang).replace("{code}", own), true);
  }

  private String render(MenuNode node, UssdSession s, Map<String, String> vars) {
    String lang = s.language();
    StringBuilder t = new StringBuilder(interpolate(node.text() == null ? "" : node.text().in(lang), s.inputs(), vars));
    if (node.type() == NodeType.MENU || node.type() == NodeType.CONFIRM) {
      for (MenuOption o : node.options()) {
        t.append('\n').append(o.key()).append(' ').append(o.label() == null ? "" : o.label().in(lang));
      }
    } else if (node.type() == NodeType.DYNAMIC) {
      List<UssdProperties.ListItem> items = lists.items(node.source(), s.inputs());
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

  /** The key that shows the next page: the first word of the menu's "more" text, without a trailing dot. */
  private static String moreKey(Menu menu, String lang) {
    String more = menu.message(Menu.MORE, lang).trim();
    int sp = more.indexOf(' ');
    String key = sp < 0 ? more : more.substring(0, sp);
    return key.endsWith(".") ? key.substring(0, key.length() - 1) : key;
  }

  /**
   * Whether the MNO serves the dialled code: always when it lists no short codes or the MNO sent no
   * code (the contract's {@code serviceCode} is optional); otherwise one of its codes, ignoring
   * whitespace.
   */
  static boolean servesCode(UssdProperties.Mno config, String serviceCode) {
    if (config.shortCodes().isEmpty() || serviceCode == null || serviceCode.isBlank()) {
      return true;
    }
    String dialled = serviceCode.replaceAll("\\s", "");
    return config.shortCodes().stream().anyMatch(c -> c.replaceAll("\\s", "").equals(dialled));
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
