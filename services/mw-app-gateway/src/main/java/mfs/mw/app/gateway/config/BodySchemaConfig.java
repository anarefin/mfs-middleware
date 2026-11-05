package mfs.mw.app.gateway.config;

import mfs.mw.app.gateway.filter.BodySchemaInterceptor;
import mfs.mw.app.gateway.filter.CachedBodyFilter;
import mfs.mw.app.gateway.filter.CachedBodyRequest;
import mfs.mw.app.gateway.service.BodySchema;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import tools.jackson.databind.json.JsonMapper;

/**
 * The body schema checks of every {@code app.v1} JSON body (ticket 29, M3 gate decision A6;
 * APISIX validates no body): {@code /app/**} POST bodies (at most 64 KiB, else 413) are kept in
 * memory ({@link CachedBodyFilter}) so the schema check, the device signature and the controller
 * all read the same bytes; each check runs after the JWT and before the device gate ({@code
 * DeviceBindingConfig}). {@code POST /app/v1/sessions} takes no body and has no schema. A new JSON
 * body endpoint needs its derived schema registered here.
 */
@Configuration(proxyBeanMethods = false)
public class BodySchemaConfig implements WebMvcConfigurer {

  /** Before the device gate (registered with the default order 0). */
  static final int ORDER = -10;

  private final JsonMapper json;

  public BodySchemaConfig(JsonMapper json) {
    this.json = json;
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
    add(registry, BodySchema.TRANSACTION, false, "/app/v1/transactions");
    add(registry, BodySchema.DEVICE_OTP, true, "/app/v1/devices/otp");
    add(registry, BodySchema.DEVICE_BINDING, false, "/app/v1/devices");
    add(registry, BodySchema.FETCH_BILL, false, "/app/v1/billers/*/fetch-bill");
  }

  private void add(InterceptorRegistry registry, String schema, boolean optional, String path) {
    registry
        .addInterceptor(new BodySchemaInterceptor(new BodySchema(json, schema), optional))
        .addPathPatterns(path)
        .order(ORDER);
  }
}
