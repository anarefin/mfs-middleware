package mfs.mw.adapter.billpay.dto;

import java.util.List;

/**
 * {@code GET /internal/v1/partners/kamelet-usage} (contract {@code billpay.v1}, ticket 83): every
 * Kamelet of the library with the live partner route versions that call it.
 */
public record KameletUsageView(List<Kamelet> kamelets) {

  public KameletUsageView {
    kamelets = List.copyOf(kamelets);
  }

  public record Kamelet(String name, List<Route> routes) {
    public Kamelet {
      routes = List.copyOf(routes);
    }
  }

  public record Route(String partnerId, long version) {}
}
