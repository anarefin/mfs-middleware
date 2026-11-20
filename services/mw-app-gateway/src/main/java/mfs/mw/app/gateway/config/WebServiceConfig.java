package mfs.mw.app.gateway.config;

import java.util.Objects;
import mfs.mw.app.gateway.controller.AppSoap;
import mfs.mw.app.gateway.controller.AppSoapEndpoint;
import mfs.mw.app.gateway.exception.SoapFaultResolver;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.ws.server.EndpointInterceptor;
import org.springframework.ws.server.MessageDispatcher;
import org.springframework.ws.server.endpoint.mapping.PayloadRootAnnotationMethodEndpointMapping;
import org.springframework.ws.transport.http.MessageDispatcherServlet;
import org.springframework.ws.wsdl.wsdl11.DefaultWsdl11Definition;
import org.springframework.xml.xsd.SimpleXsdSchema;
import org.springframework.xml.xsd.XsdSchema;

/**
 * The app SOAP servlet (ticket 108). WSDL is generated from the XSD at {@code /app/ws/v1/app.wsdl}.
 * The schema is the contract file copied into the jar. No payload logging interceptor is registered:
 * a SOAP body can carry an MSISDN, and payload logging stays off. {@code X-Correlation-Id} is applied
 * by {@code CorrelationIdFilter} before this servlet runs.
 */
@Configuration(proxyBeanMethods = false)
public class WebServiceConfig {

  /**
   * Registers the facade on the payload-root mapping by instance. A scan from this bean's own
   * startup can run before the endpoint bean is visible, so the mapping is given the endpoint.
   */
  @Bean
  PayloadRootAnnotationMethodEndpointMapping appSoapMapping(
      AppSoapEndpoint endpoint, AppSoapSchemaInterceptor validation) {
    PayloadRootAnnotationMethodEndpointMapping mapping = new PayloadRootAnnotationMethodEndpointMapping() {
      @Override
      protected void initApplicationContext() {
        registerMethods(endpoint);
      }
    };
    mapping.setInterceptors(new EndpointInterceptor[] {validation});
    return mapping;
  }

  /**
   * The servlet looks this bean up by name. It depends on the mapping and the fault resolver so both
   * exist before this dispatcher collects the context's endpoint mappings.
   */
  @Bean(name = MessageDispatcherServlet.DEFAULT_MESSAGE_RECEIVER_BEAN_NAME)
  MessageDispatcher messageReceiver(
      PayloadRootAnnotationMethodEndpointMapping appSoapMapping, SoapFaultResolver faults) {
    Objects.requireNonNull(appSoapMapping);
    Objects.requireNonNull(faults);
    return new MessageDispatcher();
  }

  @Bean
  @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
  ServletRegistrationBean<MessageDispatcherServlet> appSoapServlet(ApplicationContext ctx) {
    MessageDispatcherServlet servlet = new MessageDispatcherServlet();
    servlet.setApplicationContext(ctx);
    servlet.setTransformWsdlLocations(true);
    ServletRegistrationBean<MessageDispatcherServlet> registration =
        new ServletRegistrationBean<>(servlet, "/app/ws/v1", "/app/ws/v1/*");
    registration.setName("appSoap");
    registration.setLoadOnStartup(1);
    return registration;
  }

  @Bean
  XsdSchema appSoapSchema() {
    return new SimpleXsdSchema(new ClassPathResource("xsd/app-soap.v1.xsd"));
  }

  /** Bean name {@code app}: the servlet publishes it as {@code /app/ws/v1/app.wsdl}. */
  @Bean(name = "app")
  DefaultWsdl11Definition appWsdl(XsdSchema appSoapSchema) {
    DefaultWsdl11Definition wsdl = new DefaultWsdl11Definition();
    wsdl.setPortTypeName("AppPort");
    wsdl.setLocationUri("/app/ws/v1");
    wsdl.setTargetNamespace(AppSoap.NAMESPACE);
    wsdl.setSchema(appSoapSchema);
    wsdl.setRequestSuffix("Request");
    wsdl.setResponseSuffix("Response");
    return wsdl;
  }

  @Bean
  AppSoapSchemaInterceptor appSoapSchemaValidation() {
    AppSoapSchemaInterceptor interceptor = new AppSoapSchemaInterceptor();
    interceptor.setSchema(new ClassPathResource("xsd/app-soap.v1.xsd"));
    interceptor.setValidateRequest(true);
    interceptor.setValidateResponse(false);
    return interceptor;
  }
}
