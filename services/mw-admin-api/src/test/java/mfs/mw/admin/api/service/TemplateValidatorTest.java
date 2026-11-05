package mfs.mw.admin.api.service;

import static org.assertj.core.api.Assertions.assertThat;

import mfs.mw.admin.api.dto.TemplateChangeBody;
import org.junit.jupiter.api.Test;

/** Maker-time check of a notify template version (ticket 39, spec 10.3: logic-less {{name}} only). */
class TemplateValidatorTest {

  static TemplateChangeBody sms(String body) {
    return new TemplateChangeBody(body, null, null, null, null, "wording");
  }

  @Test
  void aPlainSmsWithParamsIsFine() {
    assertThat(TemplateValidator.problems("SMS", sms("You have received Tk {{amount}} from {{counterparty}}."))).isEmpty();
  }

  @Test
  void sectionsPartialsAndBrokenPlaceholdersAreRefused() {
    assertThat(TemplateValidator.problems("SMS", sms("{{#vip}}Hi{{/vip}} {{ amount }} {{name")))
        .containsExactlyInAnyOrder(
            "body: placeholder {{#vip}} is not a plain {{name}}",
            "body: placeholder {{/vip}} is not a plain {{name}}",
            "body: placeholder {{ amount }} is not a plain {{name}}",
            "body: unbalanced {{ or }}");
  }

  @Test
  void anEmailNeedsASubjectAndAnSmsTakesNoHtmlOrSubject() {
    assertThat(TemplateValidator.problems("EMAIL", sms("Receipt {{txnId}}"))).containsExactly("subject is required for EMAIL");
    assertThat(TemplateValidator.problems("SMS", new TemplateChangeBody("x", "s", "<p>x</p>", null, null, "r")))
        .containsExactlyInAnyOrder("subject is for EMAIL only", "htmlBody is for EMAIL only");
  }

  @Test
  void aBlankBodyAndABadSenderIdAreRefused() {
    assertThat(TemplateValidator.problems("SMS", new TemplateChangeBody(" ", null, null, "way-too-long-sender", null, "r")))
        .containsExactlyInAnyOrder("body is required", "senderId must be 1-11 letters, digits or spaces");
  }
}
