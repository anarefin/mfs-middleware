package mfs.mw.adapter.atm.dto;

/** Integer minor units (poisha) and currency, as the canonical {@code Amount}. */
public record Money(long valueMinor, String currency) {}
