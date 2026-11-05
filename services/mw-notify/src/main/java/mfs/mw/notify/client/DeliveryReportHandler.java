package mfs.mw.notify.client;

import mfs.mw.notify.dto.DeliveryReport;

/** Receives the final DLRs the SMPP binds get ({@code deliver_sm}, spec 10.2). */
public interface DeliveryReportHandler {

  /** Throws when the report could not be stored, so the SMSC redelivers it. */
  void onReport(DeliveryReport report);
}
