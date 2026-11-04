package mfs.mw.app.gateway.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Partner and product of a transaction (contract {@code PartnerRef} of {@code mw.txn.request.v1}). */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PartnerRef(String partnerId, String productCode) {}
