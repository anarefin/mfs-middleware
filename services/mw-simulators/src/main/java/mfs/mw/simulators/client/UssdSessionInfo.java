package mfs.mw.simulators.client;

/**
 * The two layouts of {@code its_session_info} (tag 0x1383, two octets) the USSD gateway speaks.
 * {@code EOS_BIT0} is the SMPP layout (sequence in bits 7..1, end of session in bit 0): session 42,
 * sequence 5 is {@code 2A 0A}, and {@code 2A 0B} when the session ends. {@code EOS_BIT7} puts the
 * end flag in bit 7: {@code 2A 05} and {@code 2A 85}. The same octets as mw-ussd's adapters.
 */
final class UssdSessionInfo {

  private UssdSessionInfo() {}

  record Fields(int session, int sequence, boolean end) {}

  static byte[] pack(String packing, int session, int sequence, boolean end) {
    int flags = switch (packing) {
      case "EOS_BIT0" -> ((sequence & 0x7F) << 1) | (end ? 1 : 0);
      case "EOS_BIT7" -> (end ? 0x80 : 0) | (sequence & 0x7F);
      default -> throw new IllegalArgumentException("unknown USSD session-info packing " + packing);
    };
    return new byte[] {(byte) session, (byte) flags};
  }

  static Fields read(String packing, byte[] info) {
    int flags = info[1] & 0xFF;
    int session = info[0] & 0xFF;
    return switch (packing) {
      case "EOS_BIT0" -> new Fields(session, flags >> 1, (flags & 1) == 1);
      case "EOS_BIT7" -> new Fields(session, flags & 0x7F, (flags & 0x80) != 0);
      default -> throw new IllegalArgumentException("unknown USSD session-info packing " + packing);
    };
  }
}
