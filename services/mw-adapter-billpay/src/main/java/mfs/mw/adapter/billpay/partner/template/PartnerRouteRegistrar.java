package mfs.mw.adapter.billpay.partner.template;

import java.util.HashMap;
import java.util.Map;
import mfs.mw.adapter.billpay.partner.config.PartnerConfigStore;
import org.apache.camel.CamelContext;
import org.apache.camel.builder.TemplatedRouteBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Keeps one templated route per partner instance in step with the {@link PartnerConfigStore}:
 * creates it from its template with {@code TemplatedRouteBuilder} when a partner appears,
 * re-creates it when its template changes, removes it when the partner is deleted. Call {@link
 * #sync()} once the Camel context has started (the templates are defined at start-up); after
 * that the store's change listener calls it.
 */
public final class PartnerRouteRegistrar {

  private static final Logger log = LoggerFactory.getLogger(PartnerRouteRegistrar.class);

  private final CamelContext camel;
  private final PartnerConfigStore store;
  private final Map<String, String> routes = new HashMap<>();
  private volatile boolean started;

  public PartnerRouteRegistrar(CamelContext camel, PartnerConfigStore store) {
    this.camel = camel;
    this.store = store;
    store.onChange(() -> {
      if (started) {
        sync();
      }
    });
  }

  /** Brings the routes in line with the store. */
  public synchronized void sync() {
    started = true;
    Map<String, String> wanted = store.templates();
    for (Map.Entry<String, String> e : Map.copyOf(routes).entrySet()) {
      if (!e.getValue().equals(wanted.get(e.getKey()))) {
        remove(e.getKey());
      }
    }
    wanted.forEach(
        (id, template) -> {
          if (!routes.containsKey(id)) {
            add(id, template);
          }
        });
  }

  private void add(String id, String template) {
    try {
      TemplatedRouteBuilder.builder(camel, template)
          .routeId(PartnerTemplates.routeId(id))
          .parameter(PartnerRouteTemplates.PARAM, id)
          .add();
      routes.put(id, template);
      log.atInfo()
          .addKeyValue("event", "partner.route.added")
          .addKeyValue("partnerInstance", id)
          .addKeyValue("template", template)
          .log("partner route created from template");
    } catch (RuntimeException e) {
      log.atError()
          .addKeyValue("event", "partner.route.failed")
          .addKeyValue("partnerInstance", id)
          .addKeyValue("template", template)
          .addKeyValue("errorClass", e.getClass().getName())
          .log("partner route could not be created");
    }
  }

  private void remove(String id) {
    String routeId = PartnerTemplates.routeId(id);
    try {
      camel.getRouteController().stopRoute(routeId);
      camel.removeRoute(routeId);
    } catch (Exception e) {
      log.atWarn()
          .addKeyValue("event", "partner.route.remove_failed")
          .addKeyValue("partnerInstance", id)
          .addKeyValue("errorClass", e.getClass().getName())
          .log("partner route could not be removed");
    }
    routes.remove(id);
    log.atInfo().addKeyValue("event", "partner.route.removed").addKeyValue("partnerInstance", id).log("partner route removed");
  }
}
