package mfs.mw.adapter.telco.partner.template;

import mfs.mw.adapter.telco.partner.InboundCall;
import mfs.mw.adapter.telco.partner.InboundResult;
import mfs.mw.adapter.telco.partner.PartnerCall;
import mfs.mw.adapter.telco.partner.PartnerInstanceNotFoundException;
import mfs.mw.adapter.telco.partner.PartnerResponse;
import mfs.mw.adapter.telco.partner.PartnerResponse.NotSentReason;
import mfs.mw.adapter.telco.partner.config.PartnerConfigStore;
import org.apache.camel.CamelExecutionException;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.component.direct.DirectConsumerNotAvailableException;

/**
 * The framework's entry point for services: resolves the partner instance serving a partnerId
 * (its own config, else the default) and sends the call through that instance's templated route.
 * Synchronous: call it from a worker thread (the virtual-thread executor), never from a Kafka
 * consumer thread.
 */
public class PartnerGateway {

  private final PartnerConfigStore store;
  private final ProducerTemplate producer;

  public PartnerGateway(PartnerConfigStore store, ProducerTemplate producer) {
    this.store = store;
    this.producer = producer;
  }

  /** Sends {@code call}; an outcome is always returned, never thrown. */
  public PartnerResponse call(PartnerCall call) {
    String instance = store.resolve(call.partnerId()).map(PartnerInstance::id).orElse(null);
    if (instance == null) {
      return new PartnerResponse.NotSent(NotSentReason.DISABLED);
    }
    try {
      return producer.requestBody(PartnerTemplates.endpoint(instance), call, PartnerResponse.class);
    } catch (CamelExecutionException e) {
      if (e.getCause() instanceof DirectConsumerNotAvailableException) {
        // route being re-created after a template change: nothing was sent
        return new PartnerResponse.NotSent(NotSentReason.DISABLED);
      }
      throw e.getCause() instanceof RuntimeException r ? r : e;
    }
  }

  /** Verifies and maps a callback for {@code call.partnerId()} (async-callback partners). */
  public InboundResult inbound(InboundCall call) {
    String instance =
        store.resolve(call.partnerId())
            .map(PartnerInstance::id)
            .orElseThrow(() -> new PartnerInstanceNotFoundException(call.partnerId()));
    try {
      return producer.requestBody(PartnerTemplates.endpoint(instance), call, InboundResult.class);
    } catch (CamelExecutionException e) {
      throw e.getCause() instanceof RuntimeException r ? r : e;
    }
  }
}
