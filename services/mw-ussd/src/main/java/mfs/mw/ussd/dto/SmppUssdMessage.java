package mfs.mw.ussd.dto;

import mfs.mw.ussd.util.Sensitive;
import mfs.mw.ussd.util.SensitiveType;

/**
 * One USSD PDU on an SMPP bind (spec 9.2): a {@code deliver_sm} from the MNO or a {@code
 * submit_sm} to it, with the {@code ussd_service_op} (0x0501) and {@code its_session_info}
 * (0x1383: session number, then sequence number in bits 7..1 and end-of-session in bit 0).
 *
 * @param bind the bind it came in on or must go out on (a session's responses use the bind that
 *     received it)
 * @param address the subscriber's address as the MNO sent it (source of a deliver_sm, destination
 *     of a submit_sm)
 * @param serviceCode the short code (destination of a deliver_sm, source of a submit_sm)
 * @param text what the subscriber typed or the screen; may be a PIN, so never logged
 */
public record SmppUssdMessage(
    String mno,
    String bind,
    @Sensitive(SensitiveType.MSISDN) String address,
    String serviceCode,
    int serviceOp,
    int sessionNumber,
    int sequence,
    boolean endOfSession,
    @Sensitive(SensitiveType.SECRET) String text) {

  /** ussd_service_op: PSSR indication, the subscriber dialled a code (session begins). */
  public static final int PSSR_INDICATION = 1;
  /** ussd_service_op: USSR request, a screen that expects an answer. */
  public static final int USSR_REQUEST = 2;
  /** ussd_service_op: PSSR response, the final screen. */
  public static final int PSSR_RESPONSE = 17;
  /** ussd_service_op: USSR confirm, the subscriber's answer to a USSR request. */
  public static final int USSR_CONFIRM = 18;
}
