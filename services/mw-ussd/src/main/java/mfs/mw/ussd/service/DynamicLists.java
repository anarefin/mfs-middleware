package mfs.mw.ussd.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import mfs.mw.ussd.config.UssdProperties;
import mfs.mw.ussd.dto.Biller;
import mfs.mw.ussd.dto.Text;
import org.springframework.stereotype.Component;

/**
 * Lookups behind DYNAMIC nodes, by source name:
 *
 * <ul>
 *   <li>{@code biller-categories}: the categories of mw-adapter-billpay's live catalogue that have
 *       at least one biller, in {@link #CATEGORY_ORDER} (any other category after them, by code),
 *       value = the category code, label from {@code mw.ussd.biller-categories.<CODE>} (else the
 *       code);
 *   <li>{@code billers}: the live catalogue (value and label = the biller code, short enough for a
 *       70-character Bangla screen), only the session input {@code category}'s billers when it is
 *       set;
 *   <li>any other source: a static list from configuration ({@code mw.ussd.lists.<source>}).
 * </ul>
 *
 * The lists are built from the same cached catalogue on render and on pick, so a number means the
 * same item within a step.
 */
@Component
public class DynamicLists {

  public static final String BILLERS = "billers";
  public static final String BILLER_CATEGORIES = "biller-categories";

  /** The session input the {@code billers} source is filtered by (the store of {@code bp.category}). */
  public static final String CATEGORY_INPUT = "category";

  /** The fixed order of the biller categories on the handset. */
  public static final List<String> CATEGORY_ORDER = List.of("ELECTRICITY", "WATER", "GAS", "TELECOM", "INTERNET");

  private final UssdProperties props;
  private final BillDirectory bills;

  public DynamicLists(UssdProperties props, BillDirectory bills) {
    this.props = props;
    this.bills = bills;
  }

  /** The items of {@code source} for a session whose collected inputs are {@code inputs}. */
  public List<UssdProperties.ListItem> items(String source, Map<String, String> inputs) {
    if (BILLERS.equals(source)) {
      String category = inputs == null ? null : inputs.get(CATEGORY_INPUT);
      return bills.billers().stream()
          .filter(b -> category == null || category.isBlank() || category.equals(b.category()))
          .map(b -> new UssdProperties.ListItem(b.code(), new Text(b.code(), b.code())))
          .toList();
    }
    if (BILLER_CATEGORIES.equals(source)) {
      return categories();
    }
    return props.lists().getOrDefault(source, List.of());
  }

  private List<UssdProperties.ListItem> categories() {
    TreeSet<String> present = new TreeSet<>();
    bills.billers().stream().map(Biller::category).filter(Objects::nonNull).filter(c -> !c.isBlank())
        .forEach(present::add);
    List<String> ordered = new ArrayList<>();
    for (String c : CATEGORY_ORDER) {
      if (present.remove(c)) {
        ordered.add(c);
      }
    }
    ordered.addAll(present);
    return ordered.stream()
        .map(c -> new UssdProperties.ListItem(c, props.billerCategories().getOrDefault(c, new Text(c, c))))
        .toList();
  }
}
