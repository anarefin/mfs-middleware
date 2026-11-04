package mfs.mw.simulators.service;

import mfs.mw.simulators.dto.OcsMode;
import mfs.mw.simulators.util.Sensitive;
import mfs.mw.simulators.util.SensitiveType;

/** The real outcome of a top-up, as the OCS keeps it whatever the caller saw. */
public record TopupRecord(
    String transactionId,
    @Sensitive(SensitiveType.MSISDN) String msisdn,
    String amount,
    String status,
    String code,
    String message,
    String ocsRef,
    OcsMode mode) {}
