package mfs.mw.simulators.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import mfs.mw.simulators.dto.Biller;
import org.junit.jupiter.api.Test;

/** The simulators' seed files (ticket 103, FRS MCK-08): the aggregator's ten billers come from seed/v1/billers.csv. */
class SeedFilesTest {

  @Test
  void theBillersOfVersionOne() {
    var billers = SeedFiles.billers("v1");

    assertThat(billers).hasSize(10);
    assertThat(billers.stream().filter(b -> b.protocol() == Biller.Protocol.REST).map(Biller::code))
        .containsExactly("BREB", "CWASA", "DESCO", "DPDC", "TITAS", "WASA");
    assertThat(billers.stream().filter(b -> b.protocol() == Biller.Protocol.SOAP).map(Biller::code))
        .containsExactly("BTCL", "JGTDSL", "KGDCL", "LINK3");
    assertThat(billers.get(0)).isEqualTo(
        new Biller("BREB", "Bangladesh Rural Electrification Board (Palli Bidyut)", "ELECTRICITY", Biller.Protocol.REST));
    assertThat(BillAggregatorService.BILLERS).isEqualTo(billers);
  }

  @Test
  void theBanksAndSeededAccountsOfVersionOne() {
    var banks = SeedFiles.bankProfiles("v1");

    assertThat(banks).hasSize(20);
    assertThat(banks.getFirst()).isEqualTo(
        new mfs.mw.simulators.dto.BankProfile("dbbl", "Dutch-Bangla Bank", mfs.mw.simulators.dto.BankProfile.Protocol.REST,
            "hmac-sha256"));
    assertThat(banks.stream().filter(b -> b.protocol() == mfs.mw.simulators.dto.BankProfile.Protocol.SOAP))
        .hasSize(8);
    assertThat(SeedFiles.bankAccounts("v1")).anySatisfy(a -> {
      assertThat(a.bank()).isEqualTo("*");
      assertThat(a.accountNumber()).isEqualTo("1234567890");
    });
  }

  @Test
  void aMissingVersionFailsLoudly() {
    assertThatThrownBy(() -> SeedFiles.billers("v404")).hasMessageContaining("seed/v404/billers.csv");
  }
}
