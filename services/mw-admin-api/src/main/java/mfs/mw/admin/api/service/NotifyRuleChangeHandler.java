package mfs.mw.admin.api.service;

import mfs.mw.admin.api.mapper.ContentChangeMapper;
import org.springframework.stereotype.Component;

/** Notification rule versions ({@code notify-rule}, ticket 133c). */
@Component
public class NotifyRuleChangeHandler extends ContentChangeHandler {

  public NotifyRuleChangeHandler(ContentChangeService content) {
    super(content);
  }

  @Override
  public String resourceType() {
    return ContentChangeMapper.NOTIFY_RULE;
  }
}
