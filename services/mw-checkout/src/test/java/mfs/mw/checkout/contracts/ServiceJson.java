package mfs.mw.checkout.contracts;

import tools.jackson.databind.json.JsonMapper;

/**
 * The JsonMapper this service runs with, built by hand: the Spring Boot 4 auto-configured bean
 * ({@code JacksonAutoConfiguration}) is Jackson 3's defaults plus {@code findAndAddModules}, as
 * {@code application.yml} sets no {@code spring.jackson.*} and the service registers no Jackson
 * customizer or module. {@code EventPublisher}, {@code OutboxWriter}, the Kafka listeners, the
 * HTTP clients and Spring MVC all use that one bean.
 */
public final class ServiceJson {

  public static final JsonMapper MAPPER =
      JsonMapper.builder().findAndAddModules(ServiceJson.class.getClassLoader()).build();

  private ServiceJson() {}
}
