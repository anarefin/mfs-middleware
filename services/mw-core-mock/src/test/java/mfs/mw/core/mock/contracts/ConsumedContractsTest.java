package mfs.mw.core.mock.contracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.stream.Stream;
import mfs.mw.core.mock.dto.CoreCommand;
import mfs.mw.core.mock.exception.MwException;
import mfs.mw.core.mock.mapper.CoreCommandMapper;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import tools.jackson.databind.json.JsonMapper;

/**
 * Every {@code consumes.events} entry of {@code contracts.yml} (ticket 53): each valid contract
 * sample of the command type goes through the listener's own parsing, {@link CoreCommandMapper}
 * (envelope, then the payload method {@code CoreCommandService} picks for the type), with Spring
 * Boot's default JsonMapper (the module configures none). No Spring context, no Docker.
 *
 * <p>The consumer does not validate against the schema: the mapper only refuses what it cannot
 * act on (malformed JSON, incomplete envelope, missing parties / amount / PIN where one is
 * required, missing holdId or originalTxnId). So only the invalid samples breaking those checks
 * are asserted rejected ({@link #REJECTED_BY_MAPPER}); the others (a decimal amount, a PIN without
 * keyId, a missing expiresAt, an empty queryCommandId) are accepted by the mapper and handled
 * by the service rules, which is not a contract check, so no assertion is invented for them.
 */
class ConsumedContractsTest {

  static final JsonMapper JSON = JsonMapper.builder().build();
  static final CoreCommandMapper MAPPER = new CoreCommandMapper(JSON);

  /** Command type -> the payload read CoreCommandService does for it (on top of the envelope). */
  static final Map<String, BiConsumer<CoreCommandMapper, CoreCommand>> PAYLOAD_READS =
      Map.of(
          "ValidateTransaction", CoreCommandMapper::transfer,
          "PostTransfer", CoreCommandMapper::transfer,
          "HoldFunds", CoreCommandMapper::transfer,
          "CommitHold", CoreCommandMapper::hold,
          "ReleaseHold", CoreCommandMapper::hold,
          "CreditWallet", CoreCommandMapper::credit,
          "ReverseTransaction", CoreCommandMapper::reverse,
          "QueryTransaction", CoreCommandMapper::query,
          "UnlockWallet", CoreCommandMapper::unlock);

  /** Invalid samples (folder/file under samples/core.command.v1) the mapper itself refuses. */
  static final List<String> REJECTED_BY_MAPPER =
      List.of(
          "ValidateTransaction/invalid-validate-without-pin.json",
          "PostTransfer/invalid-post-transfer-without-pin.json",
          "HoldFunds/invalid-hold-funds-without-pin.json",
          "ReleaseHold/invalid-release-without-hold-id.json",
          "CreditWallet/invalid-credit-without-credit-party.json",
          "ReverseTransaction/invalid-reverse-without-original.json",
          "UnlockWallet/invalid-unlock-msisdn-subject.json");

  static CoreCommand read(Path sample) throws Exception {
    CoreCommand cmd = MAPPER.command(Files.readString(sample));
    BiConsumer<CoreCommandMapper, CoreCommand> payload = PAYLOAD_READS.get(cmd.commandType());
    assertThat(payload).as("payload read for %s", cmd.commandType()).isNotNull();
    payload.accept(MAPPER, cmd);
    return cmd;
  }

  @TestFactory
  Stream<DynamicTest> everyValidSampleOfAConsumedCommandDeserialises() {
    return ContractManifest.consumedEvents().stream()
        .flatMap(
            entry -> {
              List<Path> valid = ContractSchemas.samples(ContractManifest.samplesDir(entry), "valid");
              assertThat(valid).as("valid samples of %s", entry).isNotEmpty();
              return valid.stream()
                  .map(
                      sample ->
                          DynamicTest.dynamicTest(
                              entry + " " + sample.getFileName(),
                              () -> {
                                CoreCommand cmd = read(sample);
                                assertThat(cmd.commandType()).isEqualTo(ContractManifest.messageType(entry));
                                assertThat(cmd.txnId()).isNotBlank();
                              }));
            });
  }

  @Test
  void everyConsumedEntryHasAPayloadRead() {
    assertThat(ContractManifest.consumedEvents())
        .extracting(ContractManifest::messageType)
        .containsExactlyInAnyOrderElementsOf(PAYLOAD_READS.keySet());
  }

  @TestFactory
  Stream<DynamicTest> invalidSamplesTheMapperChecksAreRejected() {
    String base = "events/mw-orchestrator/samples/core.command.v1/";
    return REJECTED_BY_MAPPER.stream()
        .map(
            name ->
                DynamicTest.dynamicTest(
                    name,
                    () -> {
                      Path sample = ContractSchemas.contractsDir().resolve(base + name);
                      assertThat(sample).isRegularFile();
                      assertThatThrownBy(() -> read(sample)).isInstanceOf(MwException.class);
                    }));
  }
}
