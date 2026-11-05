package mfs.mw.app.gateway.config;

import mfs.mw.app.gateway.filter.CachedBodyFilter;
import mfs.mw.app.gateway.filter.CachedBodyRequest;
import mfs.mw.app.gateway.filter.SubmitSchemaInterceptor;
import mfs.mw.app.gateway.service.SubmitBodySchema;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import tools.jackson.databind.json.JsonMapper;

/**
 * The submit body schema check (ticket 29, formerly APISIX request-validation): {@code /app/**}
 * POST bodies (at most 64 KiB, else 413) are kept in memory ({@link CachedBodyFilter}) so the schema check, the device
 * signature and the controller all read the same bytes; the check runs after the JWT and before
 * the device gate ({@code DeviceBindingConfig}), as it did at the edge.
 */
@Configuration(proxyBeanMethods = false)
public class SubmitSchemaConfig implements WebMvcConfigurer {

  /** Before the device gate (registered with the default order 0). */
  static final int ORDER = -10;

  private final SubmitBodySchema schema;

  public SubmitSchemaConfig(JsonMapper json) {
    this.schema = new SubmitBodySchema(json);
  }

  /** Servlet contexts only: the error resolver it answers 413 through is a web MVC bean. */
  @Bean
  @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
  FilterRegistrationBean<CachedBodyFilter> cachedBodyFilter(
      @Qualifier("handlerExceptionResolver") HandlerExceptionResolver errors) {
    FilterRegistrationBean<CachedBodyFilter> r =
        new FilterRegistrationBean<>(new CachedBodyFilter(errors, CachedBodyRequest.MAX_BODY));
    r.addUrlPatterns("/app/*");
    return r;
  }

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry.addInterceptor(new SubmitSchemaInterceptor(schema)).addPathPatterns("/app/v1/transactions").order(ORDER);
  }
}
