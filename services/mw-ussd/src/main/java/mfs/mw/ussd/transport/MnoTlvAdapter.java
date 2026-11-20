package mfs.mw.ussd.transport;

import java.util.Arrays;
import java.util.Optional;
import org.jsmpp.bean.OptionalParameter;

/**
 * Reads and writes one MNO's USSD TLVs (CH-USSD-03). Every MNO uses the same 3GPP tags,
 * {@code ussd_service_op} {@value #SERVICE_OP} and {@code its_session_info} {@value #SESSION_INFO}.
 * The only wire difference is how the two octets of {@code its_session_info} are packed: the session
 * number, then either the SMPP end-of-session flag in bit 0 ({@link Packing#EOS_BIT0}, GP and
 * Banglalink) or in bit 7 ({@link Packing#EOS_BIT7}, Robi and Teletalk). Selected by
 * {@code mw.ussd.mno.<mno>.tlv-profile} (the MNO key when the profile is blank).
 */
public abstract class MnoTlvAdapter {

  /** 3GPP / SMPP optional parameter {@code ussd_service_op}. */
  public static final int SERVICE_OP = 0x0501;
  /** 3GPP / SMPP optional parameter {@code its_session_info}. */
  public static final int SESSION_INFO = 0x1383;

  /** Where the end-of-session flag sits in the second octet of {@code its_session_info}. */
  public enum Packing {
    /** Sequence in bits 7..1, end of session in bit 0 (SMPP optional-parameter layout). */
    EOS_BIT0,
    /** Sequence in bits 6..0, end of session in bit 7. */
    EOS_BIT7
  }

  /**
   * What one PDU's TLVs said.
   *
   * @param serviceOp {@code ussd_service_op}
   * @param sessionNumber the session octet
   * @param sequence the sequence packed into the second octet
   * @param endOfSession the end flag of that octet
   */
  public record Fields(int serviceOp, int sessionNumber, int sequence, boolean endOfSession) {}

  private final String profile;
  private final Packing packing;

  protected MnoTlvAdapter(String profile, Packing packing) {
    this.profile = profile;
    this.packing = packing;
  }

  /** {@code mw.ussd.mno.<mno>.tlv-profile} value this adapter serves. */
  public final String profile() {
    return profile;
  }

  public final Packing packing() {
    return packing;
  }

  /** The two TLVs of one {@code deliver_sm} or {@code submit_sm}. */
  public final OptionalParameter[] write(int serviceOp, int sessionNumber, int sequence, boolean endOfSession) {
    return new OptionalParameter[] {
        new OptionalParameter.OctetString((short) SERVICE_OP, new byte[] {(byte) serviceOp}),
        new OptionalParameter.OctetString((short) SESSION_INFO, sessionInfo(sessionNumber, sequence, endOfSession))
    };
  }

  /** The session-info value octets (tag and length are not included). */
  public final byte[] sessionInfo(int sessionNumber, int sequence, boolean endOfSession) {
    int flags = switch (packing) {
      case EOS_BIT0 -> ((sequence & 0x7F) << 1) | (endOfSession ? 1 : 0);
      case EOS_BIT7 -> (endOfSession ? 0x80 : 0) | (sequence & 0x7F);
    };
    return new byte[] {(byte) sessionNumber, (byte) flags};
  }

  /** The fields of a PDU, or empty when either TLV is missing or short. */
  public final Optional<Fields> read(OptionalParameter[] params) {
    byte[] op = value(params, SERVICE_OP);
    byte[] info = value(params, SESSION_INFO);
    if (op == null || op.length < 1 || info == null || info.length < 2) {
      return Optional.empty();
    }
    int flags = info[1] & 0xFF;
    int sequence = switch (packing) {
      case EOS_BIT0 -> flags >> 1;
      case EOS_BIT7 -> flags & 0x7F;
    };
    boolean end = switch (packing) {
      case EOS_BIT0 -> (flags & 1) == 1;
      case EOS_BIT7 -> (flags & 0x80) != 0;
    };
    return Optional.of(new Fields(op[0] & 0xFF, info[0] & 0xFF, sequence, end));
  }

  private static byte[] value(OptionalParameter[] params, int tag) {
    if (params == null) {
      return null;
    }
    for (OptionalParameter p : params) {
      if ((p.tag & 0xFFFF) == tag) {
        byte[] all = p.serialize();
        return Arrays.copyOfRange(all, 4, all.length);
      }
    }
    return null;
  }
}
