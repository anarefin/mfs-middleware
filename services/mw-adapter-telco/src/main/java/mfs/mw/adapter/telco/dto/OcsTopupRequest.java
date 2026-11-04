package mfs.mw.adapter.telco.dto;

import mfs.mw.adapter.telco.util.Sensitive;
import mfs.mw.adapter.telco.util.SensitiveType;

/** Body of {@code POST /ocs/v1/topup} (amount: decimal string in major units, e.g. "20.00"). */
public record OcsTopupRequest(
    String transactionId, @Sensitive(SensitiveType.MSISDN) String msisdn, String amount) {}
