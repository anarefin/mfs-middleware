package mfs.mw.portal.api;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.testcontainers.DockerClientFactory;

/**
 * Skips every {@code *IT} test class when Docker is not available, so {@code ./gradlew build} on a
 * machine without Docker still passes with the Testcontainers tests reported as skipped (the
 * convention: any test that needs Docker is a {@code *IT}, root CLAUDE.md).
 *
 * <p>Registered for the whole test run through JUnit's extension auto-detection ({@code
 * META-INF/services/org.junit.jupiter.api.extension.Extension}, switched on in {@code
 * junit-platform.properties}), so no test class needs an annotation. It plays the part of {@code
 * @Testcontainers(disabledWithoutDocker = true)}, which only works on classes that carry it. Docker
 * is probed once per JVM (Testcontainers caches the answer) and only for {@code *IT} classes.
 */
public class DockerAvailableCondition implements ExecutionCondition {

  @Override
  public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
    Class<?> testClass = context.getTestClass().orElse(null);
    if (testClass == null) {
      return ConditionEvaluationResult.enabled("not a test class");
    }
    Class<?> outer = testClass;
    while (outer.getEnclosingClass() != null) {
      outer = outer.getEnclosingClass();
    }
    if (!outer.getSimpleName().endsWith("IT")) {
      return ConditionEvaluationResult.enabled("needs no Docker");
    }
    return DockerClientFactory.instance().isDockerAvailable()
        ? ConditionEvaluationResult.enabled("Docker is available")
        : ConditionEvaluationResult.disabled("Docker is not available: " + outer.getSimpleName() + " skipped");
  }
}
