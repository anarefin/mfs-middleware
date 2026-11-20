package mfs.mw.adapter.telco.dto;

/** An amount in poisha (contract {@code Money}). */
public record Money(long valueMinor, String currency) {}
