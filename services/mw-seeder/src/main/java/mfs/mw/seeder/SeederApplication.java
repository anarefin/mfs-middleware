package mfs.mw.seeder;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * The only class in the root package (spec 4.4). One-shot (ticket 57): no web server; the seed run
 * ({@code scheduler/SeedRunOnStartup}) runs during start-up, then the JVM exits 0, or 1 when a step
 * failed. The AOT training run of the image ({@code spring.context.exit=onRefresh}) stops before
 * any runner and exits 0.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class SeederApplication {

  public static void main(String[] args) {
    System.exit(run(args));
  }

  /** Runs the seeder and returns its exit code (tests call this instead of {@link #main}). */
  public static int run(String... args) {
    ConfigurableApplicationContext ctx;
    try {
      ctx = SpringApplication.run(SeederApplication.class, args);
    } catch (SpringApplication.AbandonedRunException e) {
      return 0;
    } catch (RuntimeException e) {
      // SpringApplication has logged the failure (a SeedFailedException names the step) and closed the context
      return 1;
    }
    return SpringApplication.exit(ctx);
  }
}
