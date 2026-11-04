package mfs.mw.notify.dto;

import mfs.mw.notify.util.Sensitive;
import mfs.mw.notify.util.SensitiveType;

/**
 * One {@code submit_sm}: destination MSISDN, source address (sender ID), {@code data_coding},
 * whether {@code short_message} starts with a UDH (esm_class UDHI), and the encoded bytes.
 */
public record SmsPart(
    @Sensitive(SensitiveType.MSISDN) String destination,
    String source,
    byte dataCoding,
    boolean udhi,
    byte[] shortMessage) {}
