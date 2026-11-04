package mfs.mw.orchestrator.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Masking rules, spec 18.3 (SEC-10). Expected values are the spec's own examples. */
class MaskingPolicyTest {

  @Test
  void msisdnKeepsFirstFourAndLastThree() {
    assertThat(MaskingPolicy.mask(SensitiveType.MSISDN, "01712345789")).isEqualTo("0171****789");
  }

  @Test
  void msisdnWithCountryCodeHidesTheSameFourDigits() {
    assertThat(MaskingPolicy.mask(SensitiveType.MSISDN, "+8801712345789"))
        .isEqualTo("+880171****789");
  }

  @Test
  void nidKeepsLastThreeOnly() {
    assertThat(MaskingPolicy.mask(SensitiveType.NID, "1990123456789")).isEqualTo("****789");
  }

  @Test
  void accountKeepsLastFourOnly() {
    assertThat(MaskingPolicy.mask(SensitiveType.ACCOUNT, "0011223344556677")).isEqualTo("****6677");
  }

  @Test
  void nameKeepsFirstInitialOnly() {
    assertThat(MaskingPolicy.mask(SensitiveType.NAME, "Rahim Uddin")).isEqualTo("R.");
    assertThat(MaskingPolicy.mask(SensitiveType.NAME, "  karim")).isEqualTo("K.");
  }

  @Test
  void secretIsAlwaysFullyRedacted() {
    assertThat(MaskingPolicy.mask(SensitiveType.SECRET, "kid-1:BASE64CIPHERTEXT==")).isEqualTo("****");
  }

  @Test
  void shortOrEmptyValuesNeverLeakDigits() {
    assertThat(MaskingPolicy.mask(SensitiveType.MSISDN, "12345")).isEqualTo("****");
    assertThat(MaskingPolicy.mask(SensitiveType.NID, "12")).isEqualTo("****");
    assertThat(MaskingPolicy.mask(SensitiveType.ACCOUNT, "")).isEqualTo("");
    assertThat(MaskingPolicy.mask(SensitiveType.NAME, null)).isNull();
  }

  record Payer(
      @Sensitive(SensitiveType.MSISDN) String msisdn,
      @Sensitive(SensitiveType.NAME) String name,
      @Sensitive(SensitiveType.NID) String nid,
      @Sensitive(SensitiveType.ACCOUNT) String account,
      @Sensitive(SensitiveType.SECRET) String encryptedPin,
      String channel) {}

  @Test
  void describeMasksEverySensitiveRecordComponent() {
    Payer payer =
        new Payer("01712345789", "Rahim Uddin", "1990123456789", "0011223344556677", "c1ph3r", "USSD");
    assertThat(MaskingPolicy.describe(payer))
        .isEqualTo(
            "Payer[msisdn=0171****789, name=R., nid=****789, account=****6677,"
                + " encryptedPin=****, channel=USSD]");
  }

  record Wrapper(Payer payer, int count) {}

  @Test
  void describeMasksNestedRecords() {
    Payer payer = new Payer("01712345789", "Rahim", null, null, null, "APP");
    assertThat(MaskingPolicy.describe(new Wrapper(payer, 2)))
        .isEqualTo(
            "Wrapper[payer=Payer[msisdn=0171****789, name=R., nid=null, account=null,"
                + " encryptedPin=null, channel=APP], count=2]");
  }

  @Test
  void scrubMasksMsisdnsInFreeText() {
    assertThat(MaskingPolicy.scrub("payer 01712345789 to +8801812345678, amount 50000"))
        .isEqualTo("payer 0171****789 to +880181****678, amount 50000");
  }

  /** Bangladesh NIDs are 10, 13 or 17 digits: last 3 kept, like the NID rule. */
  @Test
  void scrubMasksNidsInFreeText() {
    assertThat(MaskingPolicy.scrub("nid 1990123456789, old 19901234567890123, smart 2345678901."))
        .isEqualTo("nid ****789, old ****123, smart ****901.");
  }

  /** Other 11 to 20 digit runs are treated as account numbers: last 4 kept. */
  @Test
  void scrubMasksAccountNumbersInFreeText() {
    assertThat(MaskingPolicy.scrub("from 0011223344556677 to account=123456789012345"))
        .isEqualTo("from ****6677 to account=****2345");
  }

  @Test
  void scrubLeavesShortNumbersAndIdentifiersAlone() {
    String text =
        "amount 100000000 attempt 3 txn 0190a7c4-0000-7000-8000-000000000001 key abc12345678901";
    assertThat(MaskingPolicy.scrub(text)).isEqualTo(text);
  }

  @Test
  void configChangeActorsAreMaskedAsNames() {
    assertThat(
            MaskingPolicy.describe(
                new mfs.mw.orchestrator.dto.ConfigChangedEvent(
                    1, "partner/gp", null, null, 3, null, null, "rahim.uddin", "karim.ali", "c-1")))
        .contains("changedBy=R.", "approvedBy=K.")
        .doesNotContain("rahim", "karim");
  }
}
