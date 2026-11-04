package mfs.mw.ussd.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import mfs.mw.ussd.config.UssdProperties;
import org.junit.jupiter.api.Test;

class ScreenFormatterTest {

  private final ScreenFormatter formatter = new ScreenFormatter();

  private static UssdProperties.Mno mno(int gsm, int ucs2, UssdProperties.Overflow overflow) {
    return new UssdProperties.Mno(
        UssdProperties.Transport.HTTP, List.of("*247#"), gsm, ucs2, java.time.Duration.ofSeconds(120),
        overflow, "CON", "END");
  }

  @Test
  void aScreenThatFitsIsOnePage() {
    assertThat(formatter.pages("Enter amount", mno(182, 70, UssdProperties.Overflow.PAGE), "0 More"))
        .containsExactly("Enter amount");
  }

  @Test
  void latinTextUsesTheGsmLimitAndBanglaTheUcs2Limit() {
    String latin = "a".repeat(30);
    String bangla = "অ".repeat(30);
    UssdProperties.Mno m = mno(40, 20, UssdProperties.Overflow.TRUNCATE);

    assertThat(formatter.pages(latin, m, "0 More")).containsExactly(latin);
    assertThat(formatter.pages(bangla, m, "0 আরও")).containsExactly("অ".repeat(20));
  }

  @Test
  void truncateCutsAtTheMaxLength() {
    String text = "x".repeat(200);
    List<String> pages = formatter.pages(text, mno(182, 70, UssdProperties.Overflow.TRUNCATE), "0 More");

    assertThat(pages).hasSize(1);
    assertThat(pages.getFirst()).hasSize(182);
  }

  @Test
  void pageSplitsIntoScreensWithAMoreOptionExceptTheLast() {
    String text = "line one\nline two\nline three\nline four";
    List<String> pages = formatter.pages(text, mno(20, 10, UssdProperties.Overflow.PAGE), "0 More");

    assertThat(pages).allSatisfy(p -> assertThat(p.length()).isLessThanOrEqualTo(20));
    assertThat(pages.subList(0, pages.size() - 1)).allSatisfy(p -> assertThat(p).endsWith("\n0 More"));
    assertThat(pages.getLast()).doesNotContain("0 More");
    assertThat(String.join(" ", pages.stream().map(p -> p.replace("\n0 More", "")).toList()).replaceAll("\\s", ""))
        .isEqualTo(text.replaceAll("\\s", ""));
  }
}
