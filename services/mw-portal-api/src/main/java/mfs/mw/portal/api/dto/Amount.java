package mfs.mw.portal.api.dto;

/** Money in minor units (poisha) with its ISO 4217 currency (spec 5.1). */
public record Amount(long valueMinor, String currency) {}
