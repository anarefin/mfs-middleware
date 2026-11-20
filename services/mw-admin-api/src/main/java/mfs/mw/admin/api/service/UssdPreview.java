package mfs.mw.admin.api.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import mfs.mw.admin.api.dto.Menu;
import mfs.mw.admin.api.dto.MenuNode;
import mfs.mw.admin.api.dto.MenuOption;
import mfs.mw.admin.api.dto.NodeType;
import mfs.mw.admin.api.dto.Text;
import mfs.mw.admin.api.dto.ValidatorSpec;

/**
 * The server preview of a draft USSD menu (ticket 133b, FRS CH-USSD-06): a pure function, no
 * session. {@link #pages} is a copy of mw-ussd's {@code ScreenFormatter.pages} (module isolation:
 * copied, keep aligned); {@link #walk} answers a scripted input sequence screen by screen with
 * mw-ussd's {@code UssdEngine} rules as the console's browser preview has them ({@code
 * lib/ussd-menu.ts} {@code stepPreview}): {@code 0} (the more key) shows the next page, {@code 00}
 * the previous page, else the previous node. Preview limits: no flow, lookup or core call runs (an
 * ACTION resolves to the requested outcome, a DYNAMIC list has two samples), and a {@code pin} or
 * {@code regex} input accepts any non-blank answer, so no operator-written pattern runs here. A PIN
 * answer is echoed as {@code ****} and never kept.
 */
public final class UssdPreview {

  /** One MNO's screen: the GSM-7 and UCS-2 maximum lengths, TRUNCATE (true) or PAGE. */
  public record ScreenLimits(int maxLengthGsm, int maxLengthUcs2, boolean truncate) {}

  /** What an ACTION reached in the walk returns. */
  public enum Outcome { COMPLETED, FAILED, PROCESSING }

  /**
   * One screen shown: the answer that led to it (null for the dial, {@code ****} for a PIN), the
   * page showing (0-based) of how many, and whether the session ended with it.
   */
  public record Step(String input, String screen, int page, int pages, boolean ended) {}

  public static final String PREVIEW_TXN = "PREVIEW-TXN";
  static final Text DEFAULT_MORE = new Text("0. পরবর্তী", "0. Next");
  /** mw-ussd {@code UssdEngine.BACK_TEXT}. */
  static final Text DEFAULT_BACK = new Text("00. পেছনে", "00. Back");
  static final Text PREVIEW_ERROR = new Text("প্রিভিউ: কোর প্রত্যাখ্যান করেছে", "Preview: the core refused");
  /** mw-ussd {@code mw.ussd.max-invalid-attempts}. */
  static final int MAX_INVALID_ATTEMPTS = 3;
  static final String BACK_INPUT = "00";
  static final String PREVIEW_MSISDN = "01700000000";

  private static final String GSM7 =
      "@£$¥èéùìòÇ\nØø\rÅåΔ_ΦΓΛΩΠΨΣΘΞÆæßÉ !\"#¤%&'()*+,-./0123456789:;<=>?"
          + "¡ABCDEFGHIJKLMNOPQRSTUVWXYZÄÖÑÜ§¿abcdefghijklmnopqrstuvwxyzäöñüà";
  private static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z0-9_]+)}");
  private static final Pattern AMOUNT = Pattern.compile("[0-9]{1,9}(\\.[0-9]{1,2})?");
  private static final Pattern DIGITS = Pattern.compile("[0-9]+");
  private static final Pattern MSISDN = Pattern.compile("(?:\\+?88)?(01[3-9][0-9]{8})");

  private UssdPreview() {}

  // --- paging (mw-ussd ScreenFormatter) ----------------------------------------------------------

  static boolean isGsm7(String text) {
    return text.chars().allMatch(c -> GSM7.indexOf(c) >= 0);
  }

  static int limit(String text, ScreenLimits l) {
    return isGsm7(text) ? l.maxLengthGsm() : l.maxLengthUcs2();
  }

  /**
   * A screen cut to the limit (TRUNCATE) or split into pages (PAGE): every page but the last ends
   * with the {@code more} line and the {@code back} line, the last with the back line. A screen that
   * fits is returned as it is.
   */
  public static List<String> pages(String text, ScreenLimits l, String more, String back) {
    int limit = limit(text, l);
    if (text.length() <= limit) {
      return List.of(text);
    }
    if (l.truncate()) {
      return List.of(text.substring(0, limit));
    }
    String nextSuffix = "\n" + more;
    String backSuffix = back == null || back.isBlank() ? "" : "\n" + back;
    String fullSuffix = nextSuffix + backSuffix;
    int pageLimit = Math.min(limit, limit(text + more + backSuffix, l));
    int room = pageLimit - fullSuffix.length();
    if (room < 1) {
      return List.of(text.substring(0, limit));
    }
    List<String> pages = new ArrayList<>();
    String rest = text;
    while (rest.length() + backSuffix.length() > pageLimit) {
      int cut = breakAt(rest, room);
      pages.add(rest.substring(0, cut) + fullSuffix);
      rest = rest.substring(cut);
      if (!rest.isEmpty() && (rest.charAt(0) == '\n' || rest.charAt(0) == ' ')) {
        rest = rest.substring(1);
      }
      if (rest.isEmpty()) {
        break;
      }
    }
    pages.add(backSuffix.isEmpty() ? rest : rest + backSuffix);
    return List.copyOf(pages);
  }

  private static int breakAt(String text, int room) {
    int nl = text.lastIndexOf('\n', room);
    if (nl > 0) {
      return nl;
    }
    int sp = text.lastIndexOf(' ', room);
    return sp > 0 ? sp : room;
  }

  // --- walk --------------------------------------------------------------------------------------

  /**
   * The screens a subscriber sees dialling {@code menu} in {@code language} and answering {@code
   * inputs} in order; answers after the session ended are ignored. The menu must have passed {@link
   * MenuValidator}.
   */
  public static List<Step> walk(Menu menu, ScreenLimits limits, String language, List<String> inputs, Outcome outcome) {
    Walk w = new Walk(menu, limits, language, outcome);
    w.enter(menu.start(), Map.of(), null);
    for (String raw : inputs) {
      if (w.ended) {
        break;
      }
      w.answer(raw == null ? "" : raw.trim());
    }
    return List.copyOf(w.steps);
  }

  private static final class Walk {
    private final Menu menu;
    private final ScreenLimits limits;
    private final Outcome outcome;
    private final List<Step> steps = new ArrayList<>();
    private final Map<String, String> inputs = new HashMap<>();
    private final List<String> back = new ArrayList<>();
    private String lang;
    private String nodeId;
    private int attempts;
    private List<String> pages = List.of();
    private int page;
    private boolean endAfterPages;
    private boolean ended;

    Walk(Menu menu, ScreenLimits limits, String lang, Outcome outcome) {
      this.menu = menu;
      this.limits = limits;
      this.lang = lang;
      this.outcome = outcome;
    }

    void enter(String id, Map<String, String> vars, String input) {
      MenuNode n = id == null ? null : menu.nodes().get(id);
      if (n == null) {
        show("(no node " + id + ")", input, true);
        return;
      }
      if (n.type() == NodeType.ACTION) {
        if (outcome == Outcome.PROCESSING) {
          show(menu.message(Menu.PROCESSING, lang), input, true);
          return;
        }
        boolean ok = outcome == Outcome.COMPLETED;
        nodeId = ok ? n.onSuccess() : n.onFailure();
        enter(nodeId, ok ? Map.of("txnId", PREVIEW_TXN) : Map.of("error", PREVIEW_ERROR.in(lang)), input);
        return;
      }
      nodeId = id;
      attempts = 0;
      show(render(n, vars), input, n.type() == NodeType.END);
    }

    void answer(String input) {
      if (BACK_INPUT.equals(input)) {
        if (page > 0) {
          atPage(page - 1, input);
          return;
        }
        if (!back.isEmpty()) {
          nodeId = back.removeLast();
          attempts = 0;
          show(render(menu.nodes().get(nodeId), Map.of()), input, false);
          return;
        }
      }
      if (page + 1 < pages.size() && (endAfterPages || input.equals(moreKey()))) {
        atPage(page + 1, input);
        return;
      }
      MenuNode n = menu.nodes().get(nodeId);
      boolean pin = n.type() == NodeType.INPUT && n.validator() != null && n.validator().isPin();
      switch (n.type()) {
        case MENU, CONFIRM -> {
          MenuOption o = n.options().stream().filter(x -> x.key().equals(input)).findFirst().orElse(null);
          if (o == null) {
            invalid(n, input);
            return;
          }
          back.add(nodeId);
          if (o.switchLanguage()) {
            lang = Text.EN.equals(lang) ? Text.BN : Text.EN;
          }
          Map<String, String> own = Map.of("msisdn", PREVIEW_MSISDN);
          Map<String, String> before = Map.copyOf(inputs);
          o.set().forEach((k, v) -> inputs.put(k, interpolate(v, before, own)));
          enter(o.next(), Map.of(), input);
        }
        case DYNAMIC -> {
          int i = DIGITS.matcher(input).matches() && input.length() < 4 ? Integer.parseInt(input) : 0;
          if (i < 1 || i > 2) {
            invalid(n, input);
            return;
          }
          back.add(nodeId);
          inputs.put(n.store(), (n.source() == null ? "item" : n.source()) + "-" + i);
          enter(n.next(), Map.of(), input);
        }
        case INPUT -> {
          String echo = pin ? "****" : input;
          String valid = validate(n.validator(), input);
          if (valid == null) {
            invalid(n, echo);
            return;
          }
          if (!pin) {
            back.add(nodeId);
            inputs.put(n.store(), valid);
          }
          enter(n.next(), Map.of(), echo);
        }
        default -> show(menu.message(Menu.EXPIRED, lang), input, true);
      }
    }

    private void invalid(MenuNode n, String input) {
      attempts++;
      if (attempts >= MAX_INVALID_ATTEMPTS) {
        show(menu.message(Menu.TOO_MANY_ATTEMPTS, lang), input, true);
        return;
      }
      Text error = n.validator() == null ? null : n.validator().error();
      String prefix = error != null ? error.in(lang) : menu.message(Menu.INVALID, lang);
      show(prefix + "\n" + render(n, Map.of()), input, false);
    }

    private String render(MenuNode n, Map<String, String> vars) {
      StringBuilder t = new StringBuilder(interpolate(n.text() == null ? "" : n.text().in(lang), inputs, vars));
      if (n.type() == NodeType.MENU || n.type() == NodeType.CONFIRM) {
        for (MenuOption o : n.options()) {
          t.append('\n').append(o.key()).append(' ').append(o.label() == null ? "" : o.label().in(lang));
        }
      } else if (n.type() == NodeType.DYNAMIC) {
        String source = n.source() == null ? "list" : n.source();
        t.append("\n1 ").append(source).append(" 1\n2 ").append(source).append(" 2");
      }
      return t.toString();
    }

    private void show(String text, String input, boolean end) {
      pages = pages(text, limits, label(Menu.MORE, DEFAULT_MORE), label("back", DEFAULT_BACK));
      page = 0;
      boolean paged = pages.size() > 1;
      ended = end && !paged;
      endAfterPages = end && paged;
      steps.add(new Step(input, pages.getFirst(), 0, pages.size(), ended));
    }

    private void atPage(int to, String input) {
      page = to;
      ended = endAfterPages && to == pages.size() - 1;
      steps.add(new Step(input, pages.get(to), to, pages.size(), ended));
    }

    private String label(String key, Text fallback) {
      String t = menu.message(key, lang);
      return t.isBlank() ? fallback.in(lang) : t;
    }

    /** The first word of the {@code more} line without a trailing dot ({@code 0. Next} -> {@code 0}). */
    private String moreKey() {
      String more = label(Menu.MORE, DEFAULT_MORE).trim();
      String key = more.split(" ")[0];
      return key.endsWith(".") ? key.substring(0, key.length() - 1) : key;
    }
  }

  private static String validate(ValidatorSpec v, String s) {
    if (v == null || s.isEmpty()) {
      return null;
    }
    try {
      return check(v, s);
    } catch (RuntimeException e) {
      return null; // a min / max that is not a number refuses every answer
    }
  }

  private static String check(ValidatorSpec v, String s) {
    switch (v.type()) {
      case ValidatorSpec.MSISDN -> {
        Matcher m = MSISDN.matcher(s.replaceAll("[\\s-]", ""));
        if (!m.matches() || (v.notSelf() && m.group(1).equals(PREVIEW_MSISDN))) {
          return null;
        }
        return m.group(1);
      }
      case ValidatorSpec.AMOUNT -> {
        if (!AMOUNT.matcher(s).matches()) {
          return null;
        }
        BigDecimal a = new BigDecimal(s);
        if (a.signum() <= 0 || (v.min() != null && a.compareTo(new BigDecimal(v.min())) < 0)
            || (v.max() != null && a.compareTo(new BigDecimal(v.max())) > 0)) {
          return null;
        }
        return a.setScale(2, RoundingMode.UNNECESSARY).toPlainString();
      }
      case ValidatorSpec.NUMERIC -> {
        if (!DIGITS.matcher(s).matches()
            || (v.min() != null && s.length() < Integer.parseInt(v.min()))
            || (v.max() != null && s.length() > Integer.parseInt(v.max()))) {
          return null;
        }
        return s;
      }
      default -> {
        return s; // pin, regex: no operator-written pattern runs in the preview
      }
    }
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
}
