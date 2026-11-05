package mfs.mw.admin.api.service;

import mfs.mw.admin.api.mapper.ContentChangeMapper;
import org.springframework.stereotype.Component;

/** USSD menu versions ({@code ussd_menu}, ticket 39). */
@Component
public class UssdMenuChangeHandler extends ContentChangeHandler {

  public UssdMenuChangeHandler(ContentChangeService content) {
    super(content);
  }

  @Override
  public String resourceType() {
    return ContentChangeMapper.USSD_MENU;
  }
}
