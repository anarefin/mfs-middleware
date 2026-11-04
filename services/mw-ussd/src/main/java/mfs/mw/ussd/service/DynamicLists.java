package mfs.mw.ussd.service;

import java.util.List;
import mfs.mw.ussd.config.UssdProperties;
import org.springframework.stereotype.Component;

/**
 * Lookups behind DYNAMIC nodes, by source name. So far static lists from configuration
 * ({@code mw.ussd.lists.<source>}); live lookups (billers) plug in here.
 */
@Component
public class DynamicLists {

  private final UssdProperties props;

  public DynamicLists(UssdProperties props) {
    this.props = props;
  }

  public List<UssdProperties.ListItem> items(String source) {
    return props.lists().getOrDefault(source, List.of());
  }
}
