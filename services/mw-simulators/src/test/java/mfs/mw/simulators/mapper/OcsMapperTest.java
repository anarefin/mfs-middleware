package mfs.mw.simulators.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import mfs.mw.simulators.dto.SimMode;
import mfs.mw.simulators.dto.OcsResponse;
import mfs.mw.simulators.dto.OcsTopupRequest;
import mfs.mw.simulators.service.TopupRecord;
import org.junit.jupiter.api.Test;

class OcsMapperTest {

  private final OcsMapper mapper = new OcsMapper();
  private final OcsTopupRequest req = new OcsTopupRequest("T-1", "01712345678", "100.00");

  @Test
  void successMapsToCodeZero() {
    TopupRecord r = mapper.toRecord(req, SimMode.SUCCESS, "OCS-1");
    OcsResponse res = mapper.toResponse(r);
    assertThat(res.status()).isEqualTo("SUCCESS");
    assertThat(res.code()).isEqualTo("0000");
    assertThat(res.ocsRef()).isEqualTo("OCS-1");
    assertThat(res.transactionId()).isEqualTo("T-1");
  }

  @Test
  void timeoutServerErrorAndMalformedAreAppliedTopups() {
    assertThat(mapper.toRecord(req, SimMode.TIMEOUT, "R").status()).isEqualTo("SUCCESS");
    assertThat(mapper.toRecord(req, SimMode.SERVER_ERROR_AFTER_SEND, "R").status()).isEqualTo("SUCCESS");
    assertThat(mapper.toRecord(req, SimMode.MALFORMED, "R").status()).isEqualTo("SUCCESS");
  }

  @Test
  void businessFailureAndUnknownCodeAreFailures() {
    TopupRecord bf = mapper.toRecord(req, SimMode.BUSINESS_FAILURE, "R");
    assertThat(bf.status()).isEqualTo("FAILED");
    assertThat(bf.code()).isEqualTo("2001");
    TopupRecord unk = mapper.toRecord(req, SimMode.UNKNOWN_CODE, "R");
    assertThat(unk.status()).isEqualTo("FAILED");
    assertThat(unk.code()).isEqualTo("9876");
  }

  @Test
  void notFoundResponse() {
    OcsResponse res = mapper.notFound("T-9");
    assertThat(res.status()).isEqualTo("NOT_FOUND");
    assertThat(res.transactionId()).isEqualTo("T-9");
  }
}
