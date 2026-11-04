package mfs.mw.app.gateway.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import mfs.mw.app.gateway.util.Sensitive;
import mfs.mw.app.gateway.util.SensitiveType;

/** A fetched bill: the amount due of {@code billNumber} at {@code billerCode}. */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Bill(
    String billerCode,
    @Sensitive(SensitiveType.ACCOUNT) String billNumber,
    Amount amount,
    String dueDate,
    String billPeriod) {}
