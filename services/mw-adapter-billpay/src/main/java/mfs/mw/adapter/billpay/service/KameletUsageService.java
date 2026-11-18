package mfs.mw.adapter.billpay.service;

import java.util.Comparator;
import java.util.List;
import mfs.mw.adapter.billpay.dto.KameletUsageView;
import mfs.mw.adapter.billpay.partner.config.PartnerConfigStore;
import mfs.mw.adapter.billpay.partner.route.KameletCatalogue;
import mfs.mw.adapter.billpay.partner.route.PartnerRuntime;
import org.springframework.stereotype.Service;

/**
 * Kamelet usage (ADR D26 versioning, ticket 83): for each Kamelet of the shipped library, the live
 * partner route versions that call it ({@code PartnerRuntime.kamelets()}). A Kamelet nobody uses
 * any more may be removed once its {@code -v2} is in place.
 */
@Service
public class KameletUsageService {

  private final KameletCatalogue catalogue;
  private final PartnerConfigStore store;

  public KameletUsageService(KameletCatalogue catalogue, PartnerConfigStore store) {
    this.catalogue = catalogue;
    this.store = store;
  }

  public KameletUsageView usage() {
    List<PartnerRuntime> live =
        store.entries().values().stream()
            .map(PartnerConfigStore.Entry::runtime)
            .filter(r -> r != null)
            .sorted(Comparator.comparing(PartnerRuntime::id))
            .toList();
    return new KameletUsageView(
        catalogue.names().stream()
            .sorted()
            .map(name -> new KameletUsageView.Kamelet(
                name,
                live.stream()
                    .filter(r -> r.kamelets().contains(name))
                    .map(r -> new KameletUsageView.Route(r.id(), r.version()))
                    .toList()))
            .toList());
  }
}
