package mfs.mw.adapter.interop.mapping;

import static org.assertj.core.api.Assertions.assertThat;

import mfs.mw.adapter.interop.dto.iso.NpsbStatus;
import org.junit.jupiter.api.Test;

/**
 * Ticket 121b: pacs.002.001.10 (status report) and pacs.028.001.03 (status request) validate against
 * the ISO XSDs and round-trip losslessly; a hand-written switch pacs.002 reads as expected.
 */
class Pacs002MappingTest {

  private final Iso20022Mapper mapper = new Iso20022Mapper(new Iso20022Schemas());

  @Test
  void acceptedAndRejectedStatusRoundTrip() {
    for (NpsbStatus status : new NpsbStatus[] {IsoSamples.accepted(), IsoSamples.rejected()}) {
      String xml = mapper.pacs002(status);
      new Iso20022Schemas().validate(IsoMessage.PACS_002, xml);
      assertThat(mapper.readPacs002(xml)).isEqualTo(status);
    }
  }

  @Test
  void aSwitchPacs002IsRead() {
    String xml = """
        <Document xmlns="urn:iso:std:iso:20022:tech:xsd:pacs.002.001.10">
          <FIToFIPmtStsRpt>
            <GrpHdr><MsgId>NPSB-ST-9</MsgId><CreDtTm>2026-11-20T09:15:30Z</CreDtTm></GrpHdr>
            <TxInfAndSts>
              <OrgnlGrpInf><OrgnlMsgId>MSG-1</OrgnlMsgId><OrgnlMsgNmId>pacs.008.001.08</OrgnlMsgNmId></OrgnlGrpInf>
              <OrgnlEndToEndId>BITS01</OrgnlEndToEndId>
              <OrgnlTxId>TX-1</OrgnlTxId>
              <TxSts>RJCT</TxSts>
              <StsRsnInf><Rsn><Cd>AC04</Cd></Rsn></StsRsnInf>
            </TxInfAndSts>
          </FIToFIPmtStsRpt>
        </Document>
        """;

    NpsbStatus status = mapper.readPacs002(xml);

    assertThat(status.status()).isEqualTo("RJCT");
    assertThat(status.reasonCode()).isEqualTo("AC04");
    assertThat(status.originalEndToEndId()).isEqualTo("BITS01");
    assertThat(status.originalMsgId()).isEqualTo("MSG-1");
  }

  @Test
  void statusRequestRoundTrip() {
    String xml = mapper.pacs028(IsoSamples.statusRequest());

    new Iso20022Schemas().validate(IsoMessage.PACS_028, xml);
    assertThat(xml).contains("urn:iso:std:iso:20022:tech:xsd:pacs.028.001.03");
    assertThat(mapper.readPacs028(xml)).isEqualTo(IsoSamples.statusRequest());
  }
}
