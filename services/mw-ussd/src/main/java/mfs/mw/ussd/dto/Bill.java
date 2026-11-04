package mfs.mw.ussd.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import mfs.mw.ussd.util.Sensitive;
import mfs.mw.ussd.util.SensitiveType;

/** A fetched bill: the amount due of {@code billNumber} at {@code billerCode}. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Bill(
    String billerCode,
    @Sensitive(SensitiveType.ACCOUNT) String billNumber,
    Amount amount,
    String dueDate,
    String billPeriod) {}
