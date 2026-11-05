package mfs.mw.notify.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import mfs.mw.notify.exception.TemplateException;
import org.junit.jupiter.api.Test;

/** Mustache-style, logic-less templates (spec 10.3), with params masked first (18.3). */
class TemplateRendererTest {

  private final TemplateRenderer renderer = new TemplateRenderer(ParamMasker.defaults());

  @Test
  void rendersAnEnglishTemplate() {
    String text =
        renderer.render(
            "You sent Tk {{amount}} to {{ counterparty }}. TrxID {{txnId}}",
            Map.of("amount", "500.00", "counterparty", "0181****002", "txnId", "T1"));

    assertThat(text).isEqualTo("You sent Tk 500.00 to 0181****002. TrxID T1");
  }

  @Test
  void rendersABanglaTemplate() {
    String text =
        renderer.render(
            "আপনি {{counterparty}} নম্বরে ৳{{amount}} পাঠিয়েছেন।",
            Map.of("amount", "500.00", "counterparty", "0181****002"));

    assertThat(text).isEqualTo("আপনি 0181****002 নম্বরে ৳500.00 পাঠিয়েছেন।");
  }

  @Test
  void masksPersonalParamsBeforeRendering() {
    String text =
        renderer.render(
            "{{receiver}} / {{account}} / {{nid}} / {{name}} / {{otp}}",
            Map.of(
                "receiver", "01811000002",
                "account", "1234567890123",
                "nid", "19901234567890123",
                "name", "Rahim Uddin",
                "otp", "482913"));

    assertThat(text).isEqualTo("0181****002 / ****0123 / ****123 / R. / 482913");
  }

  @Test
  void anAlreadyMaskedValueStaysTheSame() {
    assertThat(renderer.render("{{receiver}}", Map.of("receiver", "0181****002"))).isEqualTo("0181****002");
  }

  @Test
  void aMissingParamFailsTheRendering() {
    assertThatThrownBy(() -> renderer.render("Tk {{amount}}", Map.of()))
        .isInstanceOf(TemplateException.class)
        .hasMessageContaining("amount");
  }

  @Test
  void sectionsAndOtherLogicAreRefused() {
    assertThatThrownBy(() -> renderer.render("{{#vip}}hi{{/vip}}", Map.of("vip", "x")))
        .isInstanceOf(TemplateException.class);
  }

  @Test
  void paramValuesAreNotThemselvesExpanded() {
    assertThat(renderer.render("{{a}}", Map.of("a", "{{b}}", "b", "x"))).isEqualTo("{{b}}");
  }

  @Test
  void escapesParamsInAnHtmlTemplateButNotTheTemplateItself() {
    String html =
        renderer.renderHtml(
            "<p>Bill <b>{{biller}}</b> paid. TrxID {{txnId}}</p>",
            Map.of("biller", "A&B <Gas> \"Co\" 'Ltd'", "txnId", "T1"));

    assertThat(html).isEqualTo("<p>Bill <b>A&amp;B &lt;Gas&gt; &quot;Co&quot; &#39;Ltd&#39;</b> paid. TrxID T1</p>");
  }

  @Test
  void anHtmlTemplateStillMasksAndRefusesMissingParams() {
    assertThat(renderer.renderHtml("<i>{{counterparty}}</i>", Map.of("counterparty", "01811000002")))
        .isEqualTo("<i>0181****002</i>");
    assertThatThrownBy(() -> renderer.renderHtml("<i>{{amount}}</i>", Map.of()))
        .isInstanceOf(TemplateException.class);
  }

  @Test
  void aCounterpartyTheRuleEngineAlreadyMaskedAsAnAccountIsKept() {
    // RuleEngine masks the other party by its type: a bank or biller account keeps its last 4.
    assertThat(renderer.render("{{counterparty}}", Map.of("counterparty", "****7890"))).isEqualTo("****7890");
    assertThat(renderer.render("{{counterparty}}", Map.of("counterparty", "0171****001"))).isEqualTo("0171****001");
  }
}
