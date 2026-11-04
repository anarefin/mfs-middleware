package mfs.mw.adapter.bank.dto;

/** Integer minor units (poisha) and currency, as the canonical {@code Amount}. */
public record Money(long valueMinor, String currency) {}
