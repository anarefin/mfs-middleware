package mfs.mw.ussd.dto;

import mfs.mw.ussd.util.Sensitive;
import mfs.mw.ussd.util.SensitiveType;

/**
 * One USSD step as the engine sees it, whatever the transport (SMPP or HTTP).
 *
 * @param input what the customer typed (the service code on BEGIN); may be a PIN, so never logged
 */
public record UssdRequest(
    String mno,
    String sessionId,
    @Sensitive(SensitiveType.MSISDN) String msisdn,
    UssdEvent event,
    @Sensitive(SensitiveType.SECRET) String input) {}
