package mfs.mw.seeder.scheduler;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Starts the seed run once the context is up ({@code mw.seeder.run-on-startup}, default true). A
 * failed step throws out of here, so {@code SeederApplication} exits 1; a finished run lets it exit 0.
 */
@Component
@ConditionalOnProperty(name = "mw.seeder.run-on-startup", havingValue = "true", matchIfMissing = true)
public class SeedRunOnStartup implements ApplicationRunner {

  private final SeedRunner runner;

  public SeedRunOnStartup(SeedRunner runner) {
    this.runner = runner;
  }

  @Override
  public void run(ApplicationArguments args) {
    runner.runAll();
  }
}
