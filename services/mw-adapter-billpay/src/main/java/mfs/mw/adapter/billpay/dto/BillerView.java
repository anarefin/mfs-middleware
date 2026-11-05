package mfs.mw.adapter.billpay.dto;

/** One biller of the catalogue and the aggregator ({@code partnerId}) that serves it. */
public record BillerView(String code, String name, String category, String partnerId) {}
