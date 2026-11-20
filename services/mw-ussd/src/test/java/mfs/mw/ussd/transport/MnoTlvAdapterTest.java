package mfs.mw.ussd.transport;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import mfs.mw.ussd.transport.MnoTlvAdapter.Fields;
import org.jsmpp.bean.OptionalParameter;
import org.junit.jupiter.api.Test;

/**
 * Each MNO adapter round-trips {@code ussd_service_op} (0x0501) and {@code its_session_info}
 * (0x1383). The worked example is session 42, sequence 5: GP and Banglalink pack the SMPP
 * end-of-session flag in bit 0 ({@code 2A 0A} / {@code 2A 0B}); Robi and Teletalk pack it in bit 7
 * ({@code 2A 05} / {@code 2A 85}).
 */
class MnoTlvAdapterTest {

  @Test
  void grameenphoneAndBanglalinkPackTheEndFlagInBit0() {
    assertBit0(new GpTlvAdapter(), "gp");
    assertBit0(new BanglalinkTlvAdapter(), "bl");
  }

  @Test
  void robiAndTeletalkPackTheEndFlagInBit7() {
    assertBit7(new RobiTlvAdapter(), "robi");
    assertBit7(new TeletalkTlvAdapter(), "teletalk");
  }

  @Test
  void aShortSessionInfoIsNotASession() {
    OptionalParameter[] onlyOp = new OptionalParameter[] {
        new OptionalParameter.OctetString((short) MnoTlvAdapter.SERVICE_OP, new byte[] {1})
    };
    assertThat(new GpTlvAdapter().read(onlyOp)).isEmpty();
    assertThat(new RobiTlvAdapter().read(new OptionalParameter[] {
        new OptionalParameter.OctetString((short) MnoTlvAdapter.SERVICE_OP, new byte[] {18}),
        new OptionalParameter.OctetString((short) MnoTlvAdapter.SESSION_INFO, new byte[] {0x2A})
    })).isEmpty();
  }

  @Test
  void theTagsAreThe3gppOptionalParameters() {
    assertThat(MnoTlvAdapter.SERVICE_OP).isEqualTo(0x0501);
    assertThat(MnoTlvAdapter.SESSION_INFO).isEqualTo(0x1383);
    assertThat(MnoTlvAdapter.SERVICE_OP).isEqualTo(OptionalParameter.Tag.USSD_SERVICE_OP.code() & 0xFFFF);
    assertThat(MnoTlvAdapter.SESSION_INFO).isEqualTo(OptionalParameter.Tag.ITS_SESSION_INFO.code() & 0xFFFF);
  }

  private static void assertBit0(MnoTlvAdapter adapter, String profile) {
    assertThat(adapter.profile()).isEqualTo(profile);
    assertRoundTrip(adapter, false, new byte[] {0x2A, 0x0A}, 5, false);
    assertRoundTrip(adapter, true, new byte[] {0x2A, 0x0B}, 5, true);
  }

  private static void assertBit7(MnoTlvAdapter adapter, String profile) {
    assertThat(adapter.profile()).isEqualTo(profile);
    assertRoundTrip(adapter, false, new byte[] {0x2A, 0x05}, 5, false);
    assertRoundTrip(adapter, true, new byte[] {0x2A, (byte) 0x85}, 5, true);
    // A GP end-of-session octet is not Robi's: 0x0B is sequence 11 with the session still open.
    Fields foreign = adapter.read(new OptionalParameter[] {
        new OptionalParameter.OctetString((short) MnoTlvAdapter.SERVICE_OP, new byte[] {1}),
        new OptionalParameter.OctetString((short) MnoTlvAdapter.SESSION_INFO, new byte[] {0x2A, 0x0B})
    }).orElseThrow();
    assertThat(foreign.sequence()).isEqualTo(11);
    assertThat(foreign.endOfSession()).isFalse();
  }

  private static void assertRoundTrip(MnoTlvAdapter adapter, boolean end, byte[] sessionInfo, int sequence,
      boolean endAgain) {
    OptionalParameter[] written = adapter.write(1, 42, 5, end);
    assertThat(written).hasSize(2);
    assertThat(written[0].tag & 0xFFFF).isEqualTo(0x0501);
    assertThat(written[1].tag & 0xFFFF).isEqualTo(0x1383);
    assertThat(value(written[0])).containsExactly(1);
    assertThat(value(written[1])).containsExactly(sessionInfo[0], sessionInfo[1]);
    Fields read = adapter.read(written).orElseThrow();
    assertThat(read).isEqualTo(new Fields(1, 42, sequence, endAgain));
  }

  private static byte[] value(OptionalParameter parameter) {
    byte[] all = parameter.serialize();
    return Arrays.copyOfRange(all, 4, all.length);
  }
}
