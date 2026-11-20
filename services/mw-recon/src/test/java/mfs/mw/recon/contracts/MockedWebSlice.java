package mfs.mw.recon.contracts;

import java.lang.reflect.Constructor;
import java.lang.reflect.Parameter;
import java.util.Arrays;
import java.util.Comparator;
import org.mockito.Answers;
import org.mockito.Mockito;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.util.ClassUtils;

/**
 * Test wiring for the generated-document tests (ticket 101): the {@code @WebMvcTest} slice with every
 * controller (no {@code controllers} filter) plus springdoc, and every bean of this service the slice
 * scans (controllers, advice, filters, MVC config) built from its constructor with Mockito mocks for
 * all arguments, so the slice needs no per-module {@code @MockitoBean} list. Nothing is called: the
 * document is built from the mappings alone.
 */
@TestConfiguration(proxyBeanMethods = false)
@ImportAutoConfiguration({
  org.springdoc.core.configuration.SpringDocConfiguration.class,
  org.springdoc.core.properties.SpringDocConfigProperties.class,
  // ignores @AuthenticationPrincipal arguments when Spring Security is on the classpath, as at run time
  org.springdoc.core.configuration.SpringDocSecurityConfiguration.class,
  org.springdoc.webmvc.core.configuration.SpringDocWebMvcConfiguration.class,
  org.springdoc.webmvc.ui.SwaggerConfig.class,
  org.springdoc.core.properties.SwaggerUiConfigProperties.class,
  org.springdoc.core.properties.SwaggerUiOAuthProperties.class
})
public class MockedWebSlice {

  private static final String OWN_PACKAGE = "mfs.mw.";

  @Bean
  static BeanDefinitionRegistryPostProcessor mockedConstructorArguments() {
    return new BeanDefinitionRegistryPostProcessor() {
      @Override
      public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) {
        for (String name : registry.getBeanDefinitionNames()) {
          if (registry.getBeanDefinition(name) instanceof AbstractBeanDefinition def
              && def.getBeanClassName() != null
              && def.getBeanClassName().startsWith(OWN_PACKAGE)
              && def.getFactoryMethodName() == null
              && !def.getBeanClassName().contains("$")) {
            Class<?> type = ClassUtils.resolveClassName(def.getBeanClassName(), getClass().getClassLoader());
            if (!type.isAnnotationPresent(TestConfiguration.class)) {
              def.setInstanceSupplier(() -> construct(type));
            }
          }
        }
      }

      @Override
      public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {}
    };
  }

  static Object construct(Class<?> type) {
    Constructor<?> ctor = Arrays.stream(type.getDeclaredConstructors())
        .filter(c -> c.isAnnotationPresent(Autowired.class))
        .findFirst()
        .orElseGet(() -> Arrays.stream(type.getDeclaredConstructors())
            .max(Comparator.comparingInt(Constructor::getParameterCount))
            .orElseThrow());
    Object[] args = Arrays.stream(ctor.getParameters()).map(MockedWebSlice::argument).toArray();
    return BeanUtils.instantiateClass(ctor, args);
  }

  private static Object argument(Parameter p) {
    Class<?> t = p.getType();
    if (t.isPrimitive()) {
      return java.lang.reflect.Array.get(java.lang.reflect.Array.newInstance(t, 1), 0);
    }
    if (t == String.class) {
      return "";
    }
    return Mockito.mock(t, Answers.RETURNS_MOCKS);
  }
}
