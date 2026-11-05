package mfs.mw.admin.api.service;

import mfs.mw.admin.api.mapper.ContentChangeMapper;
import org.springframework.stereotype.Component;

/** Notify template versions ({@code notify_template}, ticket 39). */
@Component
public class TemplateChangeHandler extends ContentChangeHandler {

  public TemplateChangeHandler(ContentChangeService content) {
    super(content);
  }

  @Override
  public String resourceType() {
    return ContentChangeMapper.NOTIFY_TEMPLATE;
  }
}
