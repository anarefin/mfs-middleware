package mfs.mw.adapter.remit;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/** The only class in the root package (spec 4.4). */
@SpringBootApplication
@ConfigurationPropertiesScan
public class AdapterRemitApplication {

  public static void main(String[] args) {
    SpringApplication.run(AdapterRemitApplication.class, args);
  }
}
