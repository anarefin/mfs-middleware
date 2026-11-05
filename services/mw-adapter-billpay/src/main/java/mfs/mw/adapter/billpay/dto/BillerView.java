package mfs.mw.adapter.billpay.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One biller of the catalogue and the aggregator ({@code partnerId}) that serves it. {@code
 * category} is optional and left out when absent (the contract's {@code Biller.category} is a
 * string, never null).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BillerView(String code, String name, String category, String partnerId) {}
