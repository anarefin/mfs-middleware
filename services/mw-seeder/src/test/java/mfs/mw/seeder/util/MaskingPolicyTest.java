package mfs.mw.seeder.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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

  private static final String MASKED_PAYER =
      "Payer[msisdn=0171****789, name=R., nid=null, account=null, encryptedPin=null,"
          + " channel=APP]";

  private static Payer payer() {
    return new Payer("01712345789", "Rahim Uddin", null, null, null, "APP");
  }

  @Test
  void describeMasksRecordsInsideCollections() {
    assertThat(MaskingPolicy.describe(List.of(payer(), payer())))
        .isEqualTo("[" + MASKED_PAYER + ", " + MASKED_PAYER + "]");
    assertThat(MaskingPolicy.describe(Set.of(payer()))).isEqualTo("[" + MASKED_PAYER + "]");
  }

  @Test
  void describeMasksMapKeysAndValues() {
    Map<Object, Object> map = new LinkedHashMap<>();
    map.put("payer", payer());
    map.put(payer(), "to 01812345678");
    assertThat(MaskingPolicy.describe(map))
        .isEqualTo("{payer=" + MASKED_PAYER + ", " + MASKED_PAYER + "=to 0181****678}");
  }

  @Test
  void describeMasksOptionalContents() {
    assertThat(MaskingPolicy.describe(Optional.of(payer())))
        .isEqualTo("Optional[" + MASKED_PAYER + "]");
    assertThat(MaskingPolicy.describe(Optional.empty())).isEqualTo("Optional.empty");
  }

  @Test
  void describeMasksObjectArrayElements() {
    assertThat(MaskingPolicy.describe(new Payer[] {payer(), null}))
        .isEqualTo("[" + MASKED_PAYER + ", null]");
    assertThat(MaskingPolicy.describe(new Object[] {payer(), "to 01812345678"}))
        .isEqualTo("[" + MASKED_PAYER + ", to 0181****678]");
  }

  /** Element by element, a char[] or byte[] would show an MSISDN or PIN one digit at a time. */
  @Test
  void describeShowsOnlyTypeAndLengthOfPrimitiveArrays() {
    assertThat(MaskingPolicy.describe("01712345789".toCharArray())).isEqualTo("char[11]");
    assertThat(MaskingPolicy.describe(new byte[256])).isEqualTo("byte[256]");
    assertThat(MaskingPolicy.describe(new long[] {1990123456789L, 7L})).isEqualTo("long[2]");
    assertThat(MaskingPolicy.describe(new Object[] {new int[] {1, 2, 3}})).isEqualTo("[int[3]]");
  }

  record Batch(List<Payer> payers, Map<String, Optional<Payer>> byRef, String note) {}

  @Test
  void describeMasksRecordsNestedInContainersInsideRecords() {
    Batch batch =
        new Batch(List.of(payer()), Map.of("r1", Optional.of(payer())), "for 01712345789");
    assertThat(MaskingPolicy.describe(batch))
        .isEqualTo(
            "Batch[payers=[" + MASKED_PAYER + "], byRef={r1=Optional[" + MASKED_PAYER + "]},"
                + " note=for 0171****789]")
        .doesNotContain("Rahim", "Uddin");
  }

  /** Iterables that are not collections (Path, JsonNode) keep their own text, scrubbed. */
  @Test
  void describeKeepsTheTextOfIterablesThatAreNotCollections() {
    assertThat(MaskingPolicy.describe(java.nio.file.Path.of("in", "01712345789.csv")))
        .isEqualTo("in/0171****789.csv");
  }

  @Test
  void describeSurvivesSelfReferencingContainers() {
    List<Object> list = new ArrayList<>();
    list.add(payer());
    list.add(list);
    assertThat(MaskingPolicy.describe(list)).isEqualTo("[" + MASKED_PAYER + ", (this)]");
  }

  @Test
  void scrubMasksMsisdnsInFreeText() {
    assertThat(MaskingPolicy.scrub("payer 01712345789 to +8801812345678, amount 50000"))
        .isEqualTo("payer 0171****789 to +880181****678, amount 50000");
  }

  /** MSISDNs written with single spaces or dashes between digit groups (ticket 50). */
  @Test
  void scrubMasksMsisdnsWrittenWithSeparators() {
    assertThat(
            MaskingPolicy.scrub(
                "a 01711-234567 b 0171 123 4567 c +880 1711-234567 d 880-1711-234567"
                    + " e 01711 234 567."))
        .isEqualTo(
            "a 0171****567 b 0171****567 c +880171****567 d 880171****567 e 0171****567.")
        .doesNotContain("234567", "123 4567", "1711-234");
  }

  @Test
  void scrubMasksSeparatedMsisdnsGluedToWords() {
    assertThat(MaskingPolicy.scrub("msisdn=01711-234567, to:0171 123 4567;"))
        .isEqualTo("msisdn=0171****567, to:0171****567;");
  }

  /** Separators never join numbers that are not MSISDNs: amounts, dates and times stay. */
  @Test
  void scrubLeavesAmountsDatesAndTimesWithSeparatorsAlone() {
    String text =
        "amount 1 500 000 BDT 50000 fee 12.50 total 1,500.00 on 2026-11-04 at 10:15:30"
            + " ts 2026-11-04T10:15:30.123Z due 04-11-2026 01-11-2026 13 11 2026 15:45"
            + " range 10-20 page 1 of 3 retry 2 attempt 3 qty 100 200 300";
    assertThat(MaskingPolicy.scrub(text)).isEqualTo(text);
  }

  /** Two separators in a row or other punctuation do not make a run of groups an MSISDN. */
  @Test
  void scrubDoesNotJoinGroupsAcrossDoubleSeparators() {
    String text = "codes 0171  1234567 and 0171--1234567 and 0171/123/4567 and 0171,123,4567";
    assertThat(MaskingPolicy.scrub(text)).isEqualTo(text);
  }

  /**
   * An MSISDN split over UUID-style groups, or joined by a single {@code -}, {@code .}, {@code _},
   * {@code :} or space anywhere between its 11 digits (with or without 880 / +880), is masked.
   */
  @Test
  void scrubMasksMsisdnsSplitByAnySingleSeparator() {
    assertThat(
            MaskingPolicy.scrub(
                "a 0171-2345-678 b 01712-345678 c 0171.2345.678 d 0171_2345_678 e 0171:2345:678"
                    + " f 0171 2345 678 g 8801-7123-45678 h +880-1712-345-678 i 0-1-7-1-2-3-4-5-6-7-8"))
        .isEqualTo(
            "a 0171****678 b 0171****678 c 0171****678 d 0171****678 e 0171****678"
                + " f 0171****678 g 880171****678 h +880171****678 i 0171****678");
  }

  /** A crafted UUID that spreads an MSISDN over its groups (no 6-digit run) is scrubbed. */
  @Test
  void scrubMasksAnMsisdnHiddenInAValidUuid() {
    assertThat(MaskingPolicy.scrub("aaaa0171-2345-678a-8bcd-ef0123abcdef"))
        .isEqualTo("aaaa0171****678a-8bcd-ef0123abcdef");
    assertThat(MaskingPolicy.scrub("abcdef01-7123-4567-8abc-def012345abc"))
        .isEqualTo("abcdef0171****678abc-def012345abc");
  }

  /** The wider separators still leave amounts, dates, times, versions and random UUIDs alone. */
  @Test
  void scrubWithWiderSeparatorsLeavesAmountsDatesTimesAndUuidsAlone() {
    String text =
        "amount 1.500.000 BDT 0.15 fee 12_50 on 2026.11.04 at 01:15:30 ts 2026-11-04T01:35:45.123Z"
            + " v1.13.4 ip 10.13.45.67 date 01.11.2026 01_11_2026 time 01:59:59.999"
            + " ids f47ac10b-58cc-4372-a567-0e02b2c3d479 0190a7c4-1b2e-7c3d-8e4f-5a6b7c8d9e0f"
            + " 9b2c6e3a-4f1d-4c8b-a2e7-3d5f6a7b8c9d";
    assertThat(MaskingPolicy.scrub(text)).isEqualTo(text);
  }

  /** Bangladesh NIDs are 10, 13 or 17 digits: last 3 kept, like the NID rule. */
  @Test
  void scrubMasksNidsInFreeText() {
    assertThat(MaskingPolicy.scrub("nid 1990123456789, old 19901234567890123, smart 2345678901."))
        .isEqualTo("nid ****789, old ****123, smart ****901.");
  }

  /** Other runs of 11 or more digits are treated as account numbers: last 4 kept. */
  @Test
  void scrubMasksAccountNumbersInFreeText() {
    assertThat(MaskingPolicy.scrub("from 0011223344556677 to account=123456789012345"))
        .isEqualTo("from ****6677 to account=****2345");
  }

  /** A number glued to a word, `_` or `-` is still a number: only digits bound the run. */
  @Test
  void scrubMasksNumbersGluedToIdentifiers() {
    assertThat(
            MaskingPolicy.scrub(
                "acct_0011223344556677 ref-1990123456789 A123456789012345 nid2345678901"
                    + " key abc12345678901 intl +4412345678901"))
        .isEqualTo(
            "acct_****6677 ref-****789 A****2345 nid****901 key abc****8901 intl +****901");
  }

  /** Runs longer than 20 digits are not let through: masked as account numbers, last 4 kept. */
  @Test
  void scrubMasksRunsLongerThanTwentyDigits() {
    assertThat(
            MaskingPolicy.scrub(
                "a 123456789012345678901 b 123456789012345678901234567890"
                    + " acct_123456789012345678901 ref-123456789012345678901234567890x"))
        .isEqualTo("a ****8901 b ****7890 acct_****8901 ref-****7890x");
  }

  @Test
  void scrubMasksMsisdnsGluedToIdentifiers() {
    assertThat(MaskingPolicy.scrub("msisdn01712345789 acct_01812345678 ref-8801912345678"))
        .isEqualTo("msisdn0171****789 acct_0181****678 ref-880191****678");
  }

  /**
   * Free text keeps digit-only bounds even inside a letter-and-digit token: a token bound would
   * let {@code nid2345678901} or {@code wallet01712345789} through, and over-masking a hex id in
   * a message costs only readability (system ids are exempted by the log formatter's id fields
   * instead).
   */
  @Test
  void scrubMasksDigitRunsInsideAlphanumericTokens() {
    assertThat(MaskingPolicy.scrub("abc6948355579fa wallet01712345789x nid2345678901z"))
        .isEqualTo("abc****579fa wallet0171****789x nid****901z");
  }

  @Test
  void scrubLeavesUuidsIntactWhereverTheySit() {
    String text =
        "txn=0190a7c4-1b2e-7c3d-8e4f-5a6b7c8d9e0f ref-f47ac10b-58cc-4372-a567-0e02b2c3d479,"
            + " id_9b2c6e3a-4f1d-4c8b-a2e7-3d5f6a7b8c9d.";
    assertThat(MaskingPolicy.scrub(text)).isEqualTo(text);
  }

  /**
   * Free text has no UUID or id-shape exemption: a caller can craft an id that hides an NID or
   * MSISDN, so a digit run inside a UUID is masked like any other (about one random UUID in 40 is
   * cut; accepted). System ids are exempted only in the log id fields and the audit resource.
   */
  @Test
  void scrubMasksDigitRunsInsideUuids() {
    assertThat(MaskingPolicy.scrub("txn/01a1aa26-3303-7169-8282-0e6132984047"))
        .isEqualTo("txn/01a1aa26-3303-7169-8282-0e****047");
    assertThat(
            MaskingPolicy.scrub(
                "txn/01a1aa26-3303-7169-8282-0e6132984047 nid 2345678901234 to 01712345789"))
        .isEqualTo("txn/01a1aa26-3303-7169-8282-0e****047 nid ****234 to 0171****789");
    assertThat(MaskingPolicy.scrub("ref=12345678-1234-1234-1234-001711234567 ok"))
        .isEqualTo("ref=12345678-1234-1234-1234-****4567 ok");
  }

  /** A dashed MSISDN across the groups of a UUID-shaped token is masked too. */
  @Test
  void scrubMasksMsisdnsSpreadOverUuidGroups() {
    assertThat(MaskingPolicy.scrub("id 01712345-678a-4bcd-8ef0-abcdefabcdef"))
        .isEqualTo("id 0171****678a-4bcd-8ef0-abcdefabcdef");
  }

  @Test
  void scrubLeavesTraceAndSpanIdsIntact() {
    String text =
        "traceparent=00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"
            + " traceId=4bf92f3577b34da6a3ce929d0e0e4736 spanId=00f067aa0ba902b7";
    assertThat(MaskingPolicy.scrub(text)).isEqualTo(text);
  }

  /** Hex shape alone does not protect: an id hiding a 10+ digit run is scrubbed like any text. */
  @Test
  void scrubMasksHexIdsThatHideLongNumbers() {
    assertThat(
            MaskingPolicy.scrub(
                "spanId=a012345678901234 traceId=4bf92f3577b34da6a3ce90123456789a"
                    + " traceparent=00-4bf92f3577b34da6a3ce90123456789a-a0123456789012bc-01"))
        .isEqualTo(
            "spanId=a****1234 traceId=4bf92f3577b34da6a3ce****6789a"
                + " traceparent=00-4bf92f3577b34da6a3ce****6789a-a****012bc-01");
  }

  @Test
  void scrubStillMasksNumbersNextToProtectedIds() {
    assertThat(
            MaskingPolicy.scrub(
                "0190a7c4-1b2e-7c3d-8e4f-5a6b7c8d9e0f-01712345789 00f067aa0ba902b7_1990123456789"))
        .isEqualTo("0190a7c4-1b2e-7c3d-8e4f-5a6b7c8d9e0f-0171****789 00f067aa0ba902b7_****789");
  }

  @Test
  void scrubLeavesShortNumbersAndIdentifiersAlone() {
    String text = "amount 100000000 attempt 3 txn 0190a7c4-1b2e-7c3d-8e4f-5a6b7c8d9e0f";
    assertThat(MaskingPolicy.scrub(text)).isEqualTo(text);
  }

  // --- party ids (ticket 23): masked by the party's type -------------------------------------

  record Party(String type, @Sensitive(SensitiveType.PARTY_ID) String id) {}

  @Test
  void aWalletIdIsMaskedAsAnMsisdn() {
    assertThat(MaskingPolicy.describe(new Party("WALLET", "01711000001")))
        .isEqualTo("Party[type=WALLET, id=0171****001]");
    assertThat(MaskingPolicy.describe(new Party("MSISDN", "01711000001"))).contains("id=0171****001");
  }

  @Test
  void aBankAccountKeepsOnlyItsLastFourDigits() {
    assertThat(MaskingPolicy.describe(new Party("BANK_ACCOUNT", "1234567890123")))
        .isEqualTo("Party[type=BANK_ACCOUNT, id=****0123]");
    assertThat(MaskingPolicy.describe(new Party("BILLER_ACCOUNT", "DESCO-1234567"))).contains("id=****4567");
    assertThat(MaskingPolicy.describe(new Party("CARD_TOKEN", "tok_abcdef1234"))).contains("id=****1234");
  }

  @Test
  void anIdOfAnUnknownOrMissingTypeGetsTheStrictestRule() {
    assertThat(MaskingPolicy.describe(new Party(null, "01711000001"))).contains("id=****0001");
    assertThat(MaskingPolicy.describe(new Party("SOMETHING", "1234567890"))).contains("id=****7890");
    assertThat(MaskingPolicy.mask(SensitiveType.PARTY_ID, "1234567890123")).isEqualTo("****0123");
  }

  @Test
  void aWalletTypedIdThatIsNotAnMsisdnIsMaskedAsAnAccount() {
    assertThat(MaskingPolicy.describe(new Party("WALLET", "SETTLEMENT-0000123"))).contains("id=****0123");
  }
}
