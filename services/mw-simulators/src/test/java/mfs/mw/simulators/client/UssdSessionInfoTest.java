package mfs.mw.simulators.client;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The worked session-info octets (session 42, sequence 5) each MNO port packs. */
class UssdSessionInfoTest {

  @Test
  void bit0IsTheSmppLayout() {
    assertThat(UssdSessionInfo.pack("EOS_BIT0", 42, 5, false)).containsExactly(0x2A, 0x0A);
    assertThat(UssdSessionInfo.pack("EOS_BIT0", 42, 5, true)).containsExactly(0x2A, 0x0B);
    assertThat(UssdSessionInfo.read("EOS_BIT0", new byte[] {0x2A, 0x0B}))
        .isEqualTo(new UssdSessionInfo.Fields(42, 5, true));
  }

  @Test
  void bit7KeepsTheEndFlagInTheHighBit() {
    assertThat(UssdSessionInfo.pack("EOS_BIT7", 42, 5, false)).containsExactly(0x2A, 0x05);
    assertThat(UssdSessionInfo.pack("EOS_BIT7", 42, 5, true)).containsExactly(0x2A, 0x85);
    assertThat(UssdSessionInfo.read("EOS_BIT7", new byte[] {0x2A, (byte) 0x85}))
        .isEqualTo(new UssdSessionInfo.Fields(42, 5, true));
  }
}
