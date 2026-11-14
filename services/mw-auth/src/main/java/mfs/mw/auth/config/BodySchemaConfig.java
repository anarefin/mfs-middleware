package mfs.mw.auth.config;

import mfs.mw.auth.filter.BodySchemaInterceptor;
import mfs.mw.auth.filter.CachedBodyFilter;
import mfs.mw.auth.filter.CachedBodyRequest;
import mfs.mw.auth.service.BodySchema;
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
 * The body schema checks of every {@code auth.v1} JSON body (M3 gate decision A6, copied from
 * mw-app-gateway with the device API, ticket 68; APISIX validates no body): {@code /auth/**} POST
 * bodies (at most 64 KiB, else 413) are kept in memory ({@link CachedBodyFilter}) so the schema
 * check, the device signature and the controller all read the same bytes; each check runs after
 * the JWT. {@code POST /auth/v1/sessions/logout} takes no body and has no schema. A new JSON body
 * endpoint needs its derived schema registered here.
 */
@Configuration(proxyBeanMethods = false)
public class BodySchemaConfig implements WebMvcConfigurer {

  /** Before any other interceptor. */
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
    r.addUrlPatterns("/auth/*");
    return r;
  }

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    add(registry, BodySchema.DEVICE_OTP, false, "/auth/v1/otp");
    add(registry, BodySchema.DEVICE_BINDING, false, "/auth/v1/devices");
    add(registry, BodySchema.LOGIN, false, "/auth/v1/sessions");
    add(registry, BodySchema.REFRESH, false, "/auth/v1/tokens/refresh");
    add(registry, BodySchema.SET_PIN, false, "/auth/v1/pin");
  }

  private void add(InterceptorRegistry registry, String schema, boolean optional, String path) {
    registry
        .addInterceptor(new BodySchemaInterceptor(new BodySchema(json, schema), optional))
        .addPathPatterns(path)
        .order(ORDER);
  }
}
