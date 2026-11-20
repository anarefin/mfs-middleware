package mfs.mw.adapter.interop.mapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import mfs.mw.adapter.interop.dto.iso.NpsbTransfer;
import mfs.mw.adapter.interop.exception.MwException;
import org.junit.jupiter.api.Test;

/** Ticket 121b: canonical transfer -> pacs.008.001.08 validates against the ISO XSD, and back losslessly. */
class Pacs008MappingTest {

  private final Iso20022Mapper mapper = new Iso20022Mapper(new Iso20022Schemas());

  @Test
  void transferBecomesASchemaValidPacs008() {
    String xml = mapper.pacs008(IsoSamples.transfer());

    assertThat(xml)
        .contains("urn:iso:std:iso:20022:tech:xsd:pacs.008.001.08")
        .contains("<EndToEndId>0123456789ABCDEF0123456789ABCDEF</EndToEndId>")
        .contains("Ccy=\"BDT\">1500.50</")
        .contains("<SttlmMtd>CLRG</SttlmMtd>");
    // validated again here, independently of the mapper's own check
    new Iso20022Schemas().validate(IsoMessage.PACS_008, xml);
  }

  @Test
  void pacs008RoundTripIsLossless() {
    NpsbTransfer back = mapper.readPacs008(mapper.pacs008(IsoSamples.transfer()));

    assertThat(back).isEqualTo(IsoSamples.transfer());
  }

  @Test
  void aPacs008ThatBreaksTheSchemaIsRefused() {
    String xml = mapper.pacs008(IsoSamples.transfer()).replace("<NbOfTxs>1</NbOfTxs>", "<NbOfTxs>one</NbOfTxs>");

    assertThatThrownBy(() -> mapper.readPacs008(xml))
        .isInstanceOf(MwException.class)
        .extracting("code")
        .isEqualTo("MW-PTR-NPSB-INVALID_MESSAGE");
  }

  @Test
  void aDoctypeIsRefusedBeforeParsing() {
    String xml = "<!DOCTYPE d [<!ENTITY x \"y\">]>"
        + mapper.pacs008(IsoSamples.transfer()).replaceFirst("<\\?xml[^>]*\\?>", "");

    assertThatThrownBy(() -> mapper.readPacs008(xml))
        .isInstanceOf(MwException.class)
        .extracting("code")
        .isEqualTo("MW-PTR-NPSB-INVALID_MESSAGE");
  }

  @Test
  void aMessageOfAnotherTypeIsRefused() {
    String pacs002 = mapper.pacs002(IsoSamples.accepted());

    assertThatThrownBy(() -> mapper.readPacs008(pacs002))
        .isInstanceOf(MwException.class)
        .extracting("code")
        .isEqualTo("MW-PTR-NPSB-INVALID_MESSAGE");
  }
}
