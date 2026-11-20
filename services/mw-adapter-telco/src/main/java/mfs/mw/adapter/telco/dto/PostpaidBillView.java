package mfs.mw.adapter.telco.dto;

/** A fetched postpaid bill (contract {@code PostpaidBill}): the outstanding and the minimum due. */
public record PostpaidBillView(Money outstanding, Money minimum) {}
