package mfs.mw.notify.service;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import mfs.mw.notify.exception.TemplateException;

/**
 * Logic-less, Mustache-style rendering (spec 10.3): {@code {{name}}} (spaces allowed inside the
 * braces) is replaced by the masked param. Sections, partials, comments and other tags are
 * refused, a placeholder without a param fails the rendering, and values are inserted literally
 * (never expanded again). No HTML escaping: the output is SMS text.
 */
public class TemplateRenderer {

  private static final Pattern TAG = Pattern.compile("\\{\\{\\s*([^}]*?)\\s*}}");
  private static final Pattern NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_]*");

  private final ParamMasker masker;

  public TemplateRenderer(ParamMasker masker) {
    this.masker = masker;
  }

  public String render(String body, Map<String, String> params) {
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
      m.appendReplacement(out, Matcher.quoteReplacement(value));
    }
    m.appendTail(out);
    return out.toString();
  }
}
