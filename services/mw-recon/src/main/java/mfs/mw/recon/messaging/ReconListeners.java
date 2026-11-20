package mfs.mw.recon.messaging;

import mfs.mw.recon.dto.CoreLedgerEvent;
import mfs.mw.recon.dto.PartnerReconEvent;
import mfs.mw.recon.dto.TxnStatusEvent;
import mfs.mw.recon.service.ReconService;
import mfs.mw.recon.service.ReconTopics;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Projections and partner settlement lines (ticket 126, ADR D27). A record that does not parse
 * fails and goes to the retry topics. Listeners stay off when {@code mw.recon.consumers-enabled}
 * is false.
 */
@Component
public class ReconListeners {

  private final ReconService recon;
  private final JsonMapper json;

  public ReconListeners(ReconService recon, JsonMapper json) {
    this.recon = recon;
    this.json = json;
  }

  @KafkaListener(id = "reconPartner", topics = ReconTopics.PARTNER_RECON, groupId = "${spring.kafka.consumer.group-id}",
      autoStartup = "${mw.recon.consumers-enabled:true}")
  public void partner(ConsumerRecord<String, String> record, Acknowledgment ack) {
    PartnerReconEvent event = json.readValue(record.value(), PartnerReconEvent.class);
    recon.onPartnerLine(event, partnerEventId(record, event));
    ack.acknowledge();
  }

  @KafkaListener(id = "reconTxn", topics = ReconTopics.TXN_STATUS, groupId = "${spring.kafka.consumer.group-id}",
      autoStartup = "${mw.recon.consumers-enabled:true}")
  public void txn(ConsumerRecord<String, String> record, Acknowledgment ack) {
    TxnStatusEvent event = json.readValue(record.value(), TxnStatusEvent.class);
    recon.onTxn(event, txnEventId(event));
    ack.acknowledge();
  }

  @KafkaListener(id = "reconWallet", topics = ReconTopics.WALLET_EVENTS, groupId = "${spring.kafka.consumer.group-id}",
      autoStartup = "${mw.recon.consumers-enabled:true}")
  public void wallet(ConsumerRecord<String, String> record, Acknowledgment ack) {
    recon.onLedger(json.readValue(record.value(), CoreLedgerEvent.class));
    ack.acknowledge();
  }

  @KafkaListener(id = "reconPayment", topics = ReconTopics.PAYMENT_EVENTS, groupId = "${spring.kafka.consumer.group-id}",
      autoStartup = "${mw.recon.consumers-enabled:true}")
  public void payment(ConsumerRecord<String, String> record, Acknowledgment ack) {
    recon.onLedger(json.readValue(record.value(), CoreLedgerEvent.class));
    ack.acknowledge();
  }

  /** The adapter's line key. A later status of the same txn is a different key, so it still projects. */
  static String partnerEventId(ConsumerRecord<String, String> record, PartnerReconEvent event) {
    String key = MwHeaders.text(record.headers().lastHeader(MwHeaders.IDEMPOTENCY_KEY));
    if (key != null && !key.isBlank()) {
      return key;
    }
    return "recon/" + event.partnerId() + "/" + event.fileId() + "#" + event.lineNumber();
  }

  static String txnEventId(TxnStatusEvent event) {
    return event.txnId() + "|" + event.status() + "|" + event.updatedAt();
  }
}
