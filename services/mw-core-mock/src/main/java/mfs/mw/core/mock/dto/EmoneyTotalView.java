package mfs.mw.core.mock.dto;

import java.time.Instant;
import java.util.List;

/**
 * REC-07 {@code GET /core/v1/reports/emoney-total} (contract {@code EmoneyTotal}, ticket 127c): the balance of every
 * e-money wallet (every type but SYSTEM, held money included), split by type, as the core read it at {@code asOf}.
 */
public record EmoneyTotalView(Instant asOf, Amount total, long walletCount, List<TypeTotal> byType) {

  public EmoneyTotalView {
    byType = List.copyOf(byType);
  }

  /** One wallet type's share. */
  public record TypeTotal(String type, long walletCount, Amount total) {}
}
