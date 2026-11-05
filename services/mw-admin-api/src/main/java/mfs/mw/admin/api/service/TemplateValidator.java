package mfs.mw.admin.api.service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import mfs.mw.admin.api.dto.TemplateChangeBody;

/**
 * The maker-time check of a notify template version (ticket 39, spec 10.3). mw-notify renders
 * logic-less {@code {{name}}} placeholders only (its {@code TemplateRenderer} refuses sections), so
 * anything else in braces is refused here, before a checker sees it: sections, partials, comments,
 * triple braces, spaces, unbalanced braces. EMAIL needs a subject; SMS takes neither subject nor
 * HTML. Which params a template may use is mw-notify's (rules); a missing one fails the message
 * there.
 */
public final class TemplateValidator {

  private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{(.*?)}}");
  private static final Pattern NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,63}");
  private static final Pattern SENDER = Pattern.compile("[A-Za-z0-9 ]{1,11}");

  private TemplateValidator() {}

  public static List<String> problems(String channel, TemplateChangeBody t) {
    List<String> p = new ArrayList<>();
    if (t.body() == null || t.body().isBlank()) {
      p.add("body is required");
    } else {
      placeholders("body", t.body(), p);
    }
    if ("EMAIL".equals(channel)) {
      if (t.subject() == null || t.subject().isBlank()) {
        p.add("subject is required for EMAIL");
      } else {
        placeholders("subject", t.subject(), p);
      }
      if (t.htmlBody() != null) {
        placeholders("htmlBody", t.htmlBody(), p);
      }
    } else {
      if (t.subject() != null) {
        p.add("subject is for EMAIL only");
      }
      if (t.htmlBody() != null) {
        p.add("htmlBody is for EMAIL only");
      }
    }
    if (t.senderId() != null && !SENDER.matcher(t.senderId()).matches()) {
      p.add("senderId must be 1-11 letters, digits or spaces");
    }
    return List.copyOf(p);
  }

  private static void placeholders(String field, String text, List<String> p) {
    Matcher m = PLACEHOLDER.matcher(text);
    StringBuilder rest = new StringBuilder();
    int last = 0;
    while (m.find()) {
      if (!NAME.matcher(m.group(1)).matches()) {
        p.add(field + ": placeholder " + m.group() + " is not a plain {{name}}");
      }
      rest.append(text, last, m.start());
      last = m.end();
    }
    rest.append(text.substring(last));
    if (rest.indexOf("{{") >= 0 || rest.indexOf("}}") >= 0) {
      p.add(field + ": unbalanced {{ or }}");
    }
  }
}
