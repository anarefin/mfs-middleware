package mfs.mw.notify.messaging;

import mfs.mw.notify.client.DeliveryReportHandler;
import mfs.mw.notify.dto.DeliveryReport;
import mfs.mw.notify.service.ReceiptService;
import org.springframework.stereotype.Component;

/** Hands SMSC delivery receipts (spec 10.2) from the SMPP binds to {@link ReceiptService}. */
@Component
public class SmppReceiptListener implements DeliveryReportHandler {

  private final ReceiptService receipts;

  public SmppReceiptListener(ReceiptService receipts) {
    this.receipts = receipts;
  }

  @Override
  public void onReport(DeliveryReport report) {
    receipts.onReceipt(report);
  }
}
