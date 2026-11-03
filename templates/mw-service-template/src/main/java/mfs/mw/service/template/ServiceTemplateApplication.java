package mfs.mw.service.template;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/** The only class in the root package (spec 4.4). */
@SpringBootApplication
@ConfigurationPropertiesScan
public class ServiceTemplateApplication {

  public static void main(String[] args) {
    SpringApplication.run(ServiceTemplateApplication.class, args);
  }
}
