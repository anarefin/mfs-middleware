package mfs.mw.ussd.service;

import java.util.List;
import mfs.mw.ussd.config.UssdProperties;
import mfs.mw.ussd.dto.Text;
import org.springframework.stereotype.Component;

/**
 * Lookups behind DYNAMIC nodes, by source name: {@code billers} is mw-adapter-billpay's live
 * catalogue (value and label = the biller code, short enough for a 70-character Bangla screen);
 * any other source is a static list from configuration ({@code mw.ussd.lists.<source>}).
 */
@Component
public class DynamicLists {

  public static final String BILLERS = "billers";

  private final UssdProperties props;
  private final BillDirectory bills;

  public DynamicLists(UssdProperties props, BillDirectory bills) {
    this.props = props;
    this.bills = bills;
  }

  public List<UssdProperties.ListItem> items(String source) {
    if (BILLERS.equals(source)) {
      return bills.billers().stream()
          .map(b -> new UssdProperties.ListItem(b.code(), new Text(b.code(), b.code())))
          .toList();
    }
    return props.lists().getOrDefault(source, List.of());
  }
}
