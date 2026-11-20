package mfs.mw.data.export;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/** The only class in the root package (spec 4.4). */
@SpringBootApplication
@ConfigurationPropertiesScan
public class DataExportApplication {

  public static void main(String[] args) {
    SpringApplication.run(DataExportApplication.class, args);
  }
}
