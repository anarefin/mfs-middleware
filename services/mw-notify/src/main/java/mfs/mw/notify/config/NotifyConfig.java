package mfs.mw.notify.config;

import mfs.mw.notify.service.ParamMasker;
import mfs.mw.notify.service.RuleEngine;
import mfs.mw.notify.service.TemplateRenderer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Rendering and rule beans (spec 10.3): template renderer with the built-in param masking, rule engine. */
@Configuration(proxyBeanMethods = false)
public class NotifyConfig {

  @Bean
  public TemplateRenderer templateRenderer() {
    return new TemplateRenderer(ParamMasker.defaults());
  }

  @Bean
  public RuleEngine ruleEngine(NotifyProperties props) {
    return new RuleEngine(props.defaultLocale());
  }
}
