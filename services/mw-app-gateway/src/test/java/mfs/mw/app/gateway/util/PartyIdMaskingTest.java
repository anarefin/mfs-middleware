package mfs.mw.app.gateway.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Party ids are masked by the party's type (ticket 23, spec 18.3): a wallet or mobile number as an
 * MSISDN, a bank, biller or card account as an account number (last 4 only), and an id of an
 * unknown type by the strictest rule.
 */
class PartyIdMaskingTest {

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
