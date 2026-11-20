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
 * APISIX validates no body): {@code /app/**} POST bodies (at most 64 KiB, an onboarding image 3 MiB; else 413) are kept in
 * memory ({@link CachedBodyFilter}) so the schema check, the device signature and the controller
 * all read the same bytes; each check runs after the JWT and before the device gate ({@code
 * DeviceBindingConfig}). The device API bodies are checked by mw-auth (ticket 68). A new JSON
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
        new FilterRegistrationBean<>(new CachedBodyFilter(errors, CachedBodyRequest.MAX_BODY, CachedBodyRequest.MAX_DOCUMENT_BODY));
    r.addUrlPatterns("/app/*");
    return r;
  }

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    add(registry, BodySchema.TRANSACTION, false, "/app/v1/transactions");
    add(registry, BodySchema.BALANCE, false, "/app/v1/balance");
    add(registry, BodySchema.FETCH_BILL, false, "/app/v1/billers/*/fetch-bill");
    add(registry, BodySchema.PUSH_TOKEN, false, "/app/v1/notifications/push-token");
    add(registry, BodySchema.PREVIEW, false, "/app/v1/*/preview");
    add(registry, BodySchema.CONFIRM, false, "/app/v1/*/confirm");
    add(registry, BodySchema.SAVE_BILLER, false, "/app/v1/saved-billers");
    add(registry, BodySchema.SAVE_FAVOURITE, false, "/app/v1/favourites");
    add(registry, BodySchema.LINK_BANK_ACCOUNT, false, "/app/v1/bank-accounts");
    add(registry, BodySchema.CARD_SESSION, false, "/app/v1/add-money/card/sessions");
    add(registry, BodySchema.ATM_TOKEN, false, "/app/v1/atm/tokens");
    add(registry, BodySchema.ONBOARDING_APPLICATION, false, "/app/v1/onboarding/applications");
    add(registry, BodySchema.ONBOARDING_DOCUMENT, false, CachedBodyFilter.DOCUMENTS);
  }

  private void add(InterceptorRegistry registry, String schema, boolean optional, String path) {
    registry
        .addInterceptor(new BodySchemaInterceptor(new BodySchema(json, schema), optional))
        .addPathPatterns(path)
        .order(ORDER);
  }
}
