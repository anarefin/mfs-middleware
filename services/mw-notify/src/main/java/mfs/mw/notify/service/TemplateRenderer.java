package mfs.mw.notify.service;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import mfs.mw.notify.exception.TemplateException;

/**
 * Logic-less, Mustache-style rendering (spec 10.3): {@code {{name}}} (spaces allowed inside the
 * braces) is replaced by the masked param. Sections, partials, comments and other tags are
 * refused, a placeholder without a param fails the rendering, and values are inserted literally
 * (never expanded again). {@link #render} inserts values as they are (SMS and the text part of an
 * email); {@link #renderHtml} HTML-escapes every value (the HTML part of an email, spec 10.2) and
 * keeps the template's own markup.
 */
public class TemplateRenderer {

  private static final Pattern TAG = Pattern.compile("\\{\\{\\s*([^}]*?)\\s*}}");
  private static final Pattern NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_]*");

  private final ParamMasker masker;

  public TemplateRenderer(ParamMasker masker) {
    this.masker = masker;
  }

  public String render(String body, Map<String, String> params) {
    return render(body, params, false);
  }

  /** As {@link #render}, with every param value HTML-escaped. */
  public String renderHtml(String body, Map<String, String> params) {
    return render(body, params, true);
  }

  private String render(String body, Map<String, String> params, boolean html) {
    Map<String, String> masked = masker.mask(params == null ? Map.of() : params);
    Matcher m = TAG.matcher(body);
    StringBuilder out = new StringBuilder(body.length());
    while (m.find()) {
      String name = m.group(1);
      if (!NAME.matcher(name).matches()) {
        throw new TemplateException("unsupported template tag: " + name);
      }
      String value = masked.get(name);
      if (value == null) {
        throw new TemplateException("missing template param: " + name);
      }
      m.appendReplacement(out, Matcher.quoteReplacement(html ? escapeHtml(value) : value));
    }
    m.appendTail(out);
    return out.toString();
  }

  static String escapeHtml(String value) {
    StringBuilder out = new StringBuilder(value.length() + 16);
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      switch (c) {
        case '&' -> out.append("&amp;");
        case '<' -> out.append("&lt;");
        case '>' -> out.append("&gt;");
        case '"' -> out.append("&quot;");
        case '\'' -> out.append("&#39;");
        default -> out.append(c);
      }
    }
    return out.toString();
  }
}
