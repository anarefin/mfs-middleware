package mfs.mw.adapter.bank.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** The owner binding of a linked bank account (M9 review, FRS FL-08): the bank's holder name against the KYC name. */
class HolderNameMatchTest {

  @ParameterizedTest(name = "[{index}] {0} ~ {1}")
  @CsvSource(delimiter = '|', textBlock = """
      Rahim Uddin             | Rahim Uddin
      Rahim Uddin             | RAHIM  UDDIN
      Rahim Uddin             | Md. Rahim Uddin
      Rahim Uddin             | MD RAHIM UDDIN
      Rahim Uddin             | Mohammad Rahim Uddin
      Karima Begum            | Mst. Karima Begum
      Karima Begum            | Mrs. Karima Begum
      Rahim Uddin             | Uddin; Rahim
      Rahim Uddin             | Rahim Uddin Ahmed
      Rahim Uddin Ahmed       | Rahim Uddin
      Mizanur Rahman          | Mizanur Rahaman
      Kamal Hossain           | Kamal Hosain
      Dr. Kamal Hossain       | Kamal Hossain
      Abdul Karim             | Abdul-Karim
      Rahim                   | Rahim
      """)
  void theSamePersonMatches(String kyc, String bank) {
    assertThat(HolderNameMatch.matches(kyc, bank)).isTrue();
  }

  @ParameterizedTest(name = "[{index}] {0} !~ {1}")
  @CsvSource(delimiter = '|', textBlock = """
      Rahim Uddin             | Karima Begum
      Rahim Uddin             | Test Customer
      Rahim Uddin             | Rahim
      Rahim Uddin             | Uddin
      Rahim Uddin             | Karim Uddin
      Rahim Uddin             | R. Uddin
      Rahim                   | Karim
      Rahim Uddin             | Md.
      Md.                     | Md.
      Kamal Hossain           | Jamal Hossain
      """)
  void anotherPersonDoesNotMatch(String kyc, String bank) {
    assertThat(HolderNameMatch.matches(kyc, bank)).isFalse();
  }

  @ParameterizedTest
  @CsvSource(nullValues = "NULL", value = {"NULL, Rahim Uddin", "Rahim Uddin, NULL", "'', Rahim Uddin", "'  ', ''"})
  void aMissingNameNeverMatches(String kyc, String bank) {
    assertThat(HolderNameMatch.matches(kyc, bank)).isFalse();
  }
}
