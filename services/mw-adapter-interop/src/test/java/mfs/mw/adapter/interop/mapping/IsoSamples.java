package mfs.mw.adapter.interop.mapping;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import mfs.mw.adapter.interop.dto.Money;
import mfs.mw.adapter.interop.dto.iso.NpsbParty;
import mfs.mw.adapter.interop.dto.iso.NpsbReturnRequest;
import mfs.mw.adapter.interop.dto.iso.NpsbStatus;
import mfs.mw.adapter.interop.dto.iso.NpsbStatusRequest;
import mfs.mw.adapter.interop.dto.iso.NpsbTransfer;

/** Fixed canonical NPSB messages for the mapping tests (obviously fake data). */
public final class IsoSamples {

  static final OffsetDateTime AT = OffsetDateTime.of(2026, 11, 20, 9, 15, 30, 0, ZoneOffset.UTC);
  static final String E2E = "NPSB0123456789ABCDEF0123456789ABC";

  public static NpsbTransfer transfer() {
    return new NpsbTransfer(
        "MSG-20261120-0001",
        AT,
        E2E,
        "TX-0001",
        new Money(150_050, "BDT"),
        LocalDate.of(2026, 11, 20),
        new NpsbParty("Rahim Uddin", "01712345678", "BITSBDDH"),
        new NpsbParty("Karim Ahmed", "1234567890123", "DBBLBDDH"));
  }

  static NpsbStatus accepted() {
    return new NpsbStatus("NPSB-ST-0001", AT, "MSG-20261120-0001", E2E, "TX-0001", "ACSC", null, "NPSB-REF-77");
  }

  static NpsbStatus rejected() {
    return new NpsbStatus("NPSB-ST-0002", AT, "MSG-20261120-0001", E2E, "TX-0001", "RJCT", "AC04", null);
  }

  static NpsbStatusRequest statusRequest() {
    return new NpsbStatusRequest("MSG-20261120-0002", AT, "MSG-20261120-0001", E2E, "TX-0001", "BITSBDDH");
  }

  static NpsbReturnRequest returnRequest() {
    return new NpsbReturnRequest(
        "CASE-0001", AT, "MSG-20261120-0001", E2E, "TX-0001", new Money(150_050, "BDT"), "CUST", "BITSBDDH",
        "DBBLBDDH");
  }

  private IsoSamples() {}
}
