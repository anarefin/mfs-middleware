package mfs.mw.adapter.billpay.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import mfs.mw.adapter.billpay.util.Sensitive;
import mfs.mw.adapter.billpay.util.SensitiveType;

/** A fetched bill: the amount due of {@code billNumber} at {@code billerCode}. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BillView(
    String billerCode,
    @Sensitive(SensitiveType.ACCOUNT) String billNumber,
    Money amount,
    String dueDate,
    String billPeriod) {}
