package mfs.mw.recon.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** mw-recon reports a class. It produces no command topic and no core write API (ticket 126). */
class ContractsDoNotMoveMoneyTest {

  @Test
  void contractsListNoCommandTopicAndNoCoreWrite() throws Exception {
    String yaml = Files.readString(Path.of("contracts.yml"));

    assertThat(yaml).doesNotContain("command.v1");
    assertThat(yaml).doesNotContain("core.wallet.command");
    assertThat(yaml).doesNotContain("core.payment.command");
    assertThat(yaml).doesNotContain("/core/v1");
    assertThat(yaml).contains("mw.recon.result.v1");
    assertThat(yaml).contains("mw.partner.recon.v1");
    assertThat(yaml).contains("mw.txn.status.v1");
    assertThat(yaml).contains("TransferPosted");
  }
}
