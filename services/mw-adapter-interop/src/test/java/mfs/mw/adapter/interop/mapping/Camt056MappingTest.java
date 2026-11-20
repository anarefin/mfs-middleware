package mfs.mw.adapter.interop.mapping;

import static org.assertj.core.api.Assertions.assertThat;

import mfs.mw.adapter.interop.dto.Money;
import mfs.mw.adapter.interop.dto.iso.NpsbResolution;
import mfs.mw.adapter.interop.dto.iso.NpsbReturn;
import org.junit.jupiter.api.Test;

/**
 * Ticket 121b: the return path: camt.056.001.08 (our return request), camt.029.001.09 (the switch's
 * resolution) and pacs.004.001.09 (the return itself) validate against the ISO XSDs and round-trip.
 */
class Camt056MappingTest {

  private final Iso20022Mapper mapper = new Iso20022Mapper(new Iso20022Schemas());

  @Test
  void returnRequestRoundTrip() {
    String xml = mapper.camt056(IsoSamples.returnRequest());

    new Iso20022Schemas().validate(IsoMessage.CAMT_056, xml);
    assertThat(xml).contains("<Cd>CUST</Cd>").contains("Ccy=\"BDT\">1500.50</");
    assertThat(mapper.readCamt056(xml)).isEqualTo(IsoSamples.returnRequest());
  }

  @Test
  void resolutionRoundTrip() {
    for (String conf : new String[] {"CNCL", "RJCR"}) {
      NpsbResolution resolution =
          new NpsbResolution("RES-0001", IsoSamples.AT, "CASE-0001", conf, IsoSamples.E2E, "TX-0001", "DBBLBDDH", "BITSBDDH");
      String xml = mapper.camt029(resolution);
      new Iso20022Schemas().validate(IsoMessage.CAMT_029, xml);
      assertThat(mapper.readCamt029(xml)).isEqualTo(resolution);
    }
  }

  @Test
  void paymentReturnRoundTrip() {
    NpsbReturn ret = new NpsbReturn(
        "RTR-MSG-0001", IsoSamples.AT, "RTR-0001", "MSG-20261120-0001", IsoSamples.E2E, "TX-0001",
        new Money(150_050, "BDT"), IsoSamples.transfer().settlementDate(), "CUST");
    String xml = mapper.pacs004(ret);

    new Iso20022Schemas().validate(IsoMessage.PACS_004, xml);
    assertThat(mapper.readPacs004(xml)).isEqualTo(ret);
  }
}
