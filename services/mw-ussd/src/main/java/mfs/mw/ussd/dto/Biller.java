package mfs.mw.ussd.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One biller of mw-adapter-billpay's catalogue (contract {@code api/mw-adapter-billpay/
 * billpay.v1.openapi.yaml}) and the aggregator ({@code partnerId}) that serves it.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Biller(String code, String name, String category, String partnerId) {}
