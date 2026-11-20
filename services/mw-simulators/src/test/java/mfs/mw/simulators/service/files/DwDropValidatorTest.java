package mfs.mw.simulators.service.files;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import mfs.mw.simulators.service.SeedFiles;
import mfs.mw.simulators.service.files.DwDropValidator.Verdict;
import mfs.mw.simulators.util.Hashing;
import org.junit.jupiter.api.Test;

/**
 * The Data Warehouse drop check (ticket 123, FRS EXT-17): a control file is ACCEPTED when every file it lists is
 * there with its row count, SHA-256 and the header of its type; otherwise REJECTED with one reason per problem.
 */
class DwDropValidatorTest {

  private static final Map<String, List<String>> SCHEMAS = Map.of("transactions", List.of("txn_id", "amount_minor"));

  private final DwDropValidator validator = new DwDropValidator(SCHEMAS);
  private final Map<String, byte[]> files = new HashMap<>();

  private static byte[] b(String s) {
    return s.getBytes(StandardCharsets.UTF_8);
  }

  private byte[] control(String name, int rows, byte[] content) {
    return b("{\"date\":\"2026-11-20\",\"files\":[{\"name\":\"" + name + "\",\"rows\":" + rows + ",\"sha256\":\""
        + Hashing.sha256Hex(content) + "\"}]}");
  }

  @Test
  void aCompleteDropIsAccepted() {
    byte[] csv = b("txn_id,amount_minor\nT1,100\nT2,200\n");
    files.put("transactions_2026-11-20.csv", csv);

    Verdict v = validator.validate(control("transactions_2026-11-20.csv", 2, csv), files::get);

    assertThat(v.status()).isEqualTo("ACCEPTED");
    assertThat(v.reasons()).isEmpty();
    assertThat(v.files()).containsEntry("transactions_2026-11-20.csv", "OK");
  }

  @Test
  void aWrongRowCountIsFlagged() {
    byte[] csv = b("txn_id,amount_minor\nT1,100\n");
    files.put("transactions_2026-11-20.csv", csv);

    Verdict v = validator.validate(control("transactions_2026-11-20.csv", 3, csv), files::get);

    assertThat(v.status()).isEqualTo("REJECTED");
    assertThat(v.reasons()).containsExactly("ROW_COUNT transactions_2026-11-20.csv: control 3, file 1");
  }

  @Test
  void aChecksumAMissingFileAWrongHeaderAndAnUnknownTypeAreEachFlagged() {
    byte[] csv = b("txn_id,amount\nT1,100\n");
    files.put("transactions_2026-11-20.csv", b("txn_id,amount_minor\nT9,1\n"));
    assertThat(validator.validate(control("transactions_2026-11-20.csv", 1, csv), files::get).reasons())
        .containsExactly("CHECKSUM transactions_2026-11-20.csv");

    files.put("transactions_2026-11-20.csv", csv);
    assertThat(validator.validate(control("transactions_2026-11-20.csv", 1, csv), files::get).reasons())
        .containsExactly("SCHEMA transactions_2026-11-20.csv: header txn_id,amount, expected txn_id,amount_minor");

    assertThat(validator.validate(control("refunds_2026-11-20.csv", 1, csv), files::get).reasons())
        .containsExactly("MISSING_FILE refunds_2026-11-20.csv");
    files.put("refunds_2026-11-20.csv", csv);
    assertThat(validator.validate(control("refunds_2026-11-20.csv", 1, csv), files::get).reasons())
        .containsExactly("UNKNOWN_TYPE refunds_2026-11-20.csv");
  }

  @Test
  void anUnreadableControlFileIsRejected() {
    assertThat(validator.validate(b("not json"), files::get).reasons()).containsExactly("BAD_CONTROL");
  }

  @Test
  void theSeededSchemasCoverTheFourDailyFiles() {
    assertThat(SeedFiles.dwSchemas("v1")).containsKeys("transactions", "wallet_snapshot", "notifications", "recon");
  }
}
