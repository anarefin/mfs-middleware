package mfs.mw.ussd.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import mfs.mw.ussd.config.UssdProperties;
import mfs.mw.ussd.dto.Bill;
import mfs.mw.ussd.dto.Biller;
import mfs.mw.ussd.dto.Text;
import org.junit.jupiter.api.Test;

/**
 * The DYNAMIC sources behind bill pay: {@code biller-categories} (the categories of the live
 * catalogue, in the fixed order, with their bn/en labels) and {@code billers} (filtered by the
 * session input {@code category}).
 */
class DynamicListsTest {

  static final class Catalogue implements BillDirectory {
    List<Biller> billers = UssdMenuWalkTest.FakeBills.TEN;

    @Override
    public List<Biller> billers() {
      return billers;
    }

    @Override
    public Bill fetch(String billerCode, String billNumber) {
      throw new UnsupportedOperationException();
    }
  }

  final Catalogue catalogue = new Catalogue();
  final DynamicLists lists = new DynamicLists(UssdMenuWalkTest.props(), catalogue);

  static List<String> values(List<UssdProperties.ListItem> items) {
    return items.stream().map(UssdProperties.ListItem::value).toList();
  }

  static List<String> labels(List<UssdProperties.ListItem> items, String lang) {
    return items.stream().map(i -> i.label().in(lang)).toList();
  }

  @Test
  void theCategoriesOfTheCatalogueComeInTheFixedOrderWithTheirLabels() {
    List<UssdProperties.ListItem> items = lists.items(DynamicLists.BILLER_CATEGORIES, Map.of());
    assertThat(values(items)).containsExactly("ELECTRICITY", "WATER", "GAS", "TELECOM", "INTERNET");
    assertThat(labels(items, "en")).containsExactly("Electricity", "Water", "Gas", "Telecom", "Internet");
    assertThat(labels(items, "bn")).containsExactly("বিদ্যুৎ", "পানি", "গ্যাস", "টেলিফোন", "ইন্টারনেট");
  }

  @Test
  void aCategoryWithoutBillersIsLeftOut() {
    catalogue.billers = catalogue.billers.stream()
        .filter(b -> !b.category().equals("TELECOM") && !b.category().equals("WATER"))
        .toList();
    assertThat(values(lists.items(DynamicLists.BILLER_CATEGORIES, Map.of())))
        .containsExactly("ELECTRICITY", "GAS", "INTERNET");
  }

  @Test
  void aCategoryOutsideTheFixedOrderComesLastWithItsCodeAsLabel() {
    catalogue.billers = List.of(
        new Biller("CABLE1", "Cable", "CABLE_TV", "billagg"),
        new Biller("DESCO", "Dhaka Electric Supply Company", "ELECTRICITY", "billagg"),
        new Biller("NOCAT", "No category", null, "billagg"));
    List<UssdProperties.ListItem> items = lists.items(DynamicLists.BILLER_CATEGORIES, Map.of());
    assertThat(values(items)).containsExactly("ELECTRICITY", "CABLE_TV");
    assertThat(labels(items, "bn")).containsExactly("বিদ্যুৎ", "CABLE_TV");
  }

  @Test
  void theBillersAreFilteredByTheChosenCategoryAndKeepTheCatalogueOrder() {
    assertThat(values(lists.items(DynamicLists.BILLERS, Map.of("category", "ELECTRICITY"))))
        .containsExactly("BREB", "DESCO", "DPDC");
    assertThat(values(lists.items(DynamicLists.BILLERS, Map.of("category", "GAS"))))
        .containsExactly("JGTDSL", "KGDCL", "TITAS");
    assertThat(labels(lists.items(DynamicLists.BILLERS, Map.of("category", "WATER")), "bn"))
        .containsExactly("CWASA", "WASA");
    assertThat(values(lists.items(DynamicLists.BILLERS, Map.of("category", "INTERNET")))).containsExactly("LINK3");
  }

  @Test
  void withoutAChosenCategoryEveryBillerIsListed() {
    assertThat(values(lists.items(DynamicLists.BILLERS, Map.of())))
        .containsExactly("BREB", "BTCL", "CWASA", "DESCO", "DPDC", "JGTDSL", "KGDCL", "LINK3", "TITAS", "WASA");
  }

  @Test
  void aStaticListIgnoresTheInputs() {
    assertThat(values(lists.items("operators", Map.of("category", "GAS"))))
        .containsExactly("gp", "robi", "banglalink", "teletalk");
    assertThat(lists.items("nothing", Map.of())).isEmpty();
  }
}
