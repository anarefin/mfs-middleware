package mfs.mw.adapter.atm.config;

import java.util.Objects;
import mfs.mw.adapter.atm.controller.AtmSoap;
import mfs.mw.adapter.atm.controller.AtmSoapEndpoint;
import mfs.mw.adapter.atm.exception.SoapFaultResolver;
import mfs.mw.adapter.atm.security.WsSecurityInterceptor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.ws.server.EndpointInterceptor;
import org.springframework.ws.server.MessageDispatcher;
import org.springframework.ws.server.endpoint.mapping.PayloadRootAnnotationMethodEndpointMapping;
import org.springframework.ws.soap.server.endpoint.interceptor.PayloadValidatingInterceptor;
import org.springframework.ws.transport.http.MessageDispatcherServlet;
import org.springframework.ws.wsdl.wsdl11.DefaultWsdl11Definition;
import org.springframework.xml.xsd.SimpleXsdSchema;
import org.springframework.xml.xsd.XsdSchema;

/**
 * The inbound ATM SOAP servlet (ticket 120a, FRS EXT-16, PLT-050) on Spring WS, as mw-app-gateway's
 * SOAP facade (ticket 108). The WSDL is generated from the contract XSD (copied into the jar) at
 * {@code /partner/ucbatm/ws/v1/atm.wsdl}. Interceptors in order: the WS-Security UsernameToken
 * check, then schema validation of the request. No payload logging interceptor: a body carries an
 * MSISDN and the one-time token.
 */
@Configuration(proxyBeanMethods = false)
public class AtmWebServiceConfig {

  static final String XSD = "xsd/atm-soap.v1.xsd";

  @Bean
  WsSecurityInterceptor atmWsSecurity(AtmInboundProperties properties) {
    return new WsSecurityInterceptor(properties);
  }

  @Bean
  PayloadValidatingInterceptor atmSchemaValidation() {
    PayloadValidatingInterceptor interceptor = new PayloadValidatingInterceptor();
    interceptor.setSchema(new ClassPathResource(XSD));
    interceptor.setValidateRequest(true);
    interceptor.setValidateResponse(false);
    return interceptor;
  }

  /** Registers the endpoint by instance (a scan could run before the endpoint bean exists). */
  @Bean
  PayloadRootAnnotationMethodEndpointMapping atmSoapMapping(
      AtmSoapEndpoint endpoint, WsSecurityInterceptor security, PayloadValidatingInterceptor validation) {
    PayloadRootAnnotationMethodEndpointMapping mapping = new PayloadRootAnnotationMethodEndpointMapping() {
      @Override
      protected void initApplicationContext() {
        registerMethods(endpoint);
      }
    };
    mapping.setInterceptors(new EndpointInterceptor[] {security, validation});
    return mapping;
  }

  @Bean(name = MessageDispatcherServlet.DEFAULT_MESSAGE_RECEIVER_BEAN_NAME)
  MessageDispatcher messageReceiver(
      PayloadRootAnnotationMethodEndpointMapping atmSoapMapping, SoapFaultResolver faults) {
    Objects.requireNonNull(atmSoapMapping);
    Objects.requireNonNull(faults);
    return new MessageDispatcher();
  }

  @Bean
  @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
  ServletRegistrationBean<MessageDispatcherServlet> atmSoapServlet(ApplicationContext ctx) {
    MessageDispatcherServlet servlet = new MessageDispatcherServlet();
    servlet.setApplicationContext(ctx);
    servlet.setTransformWsdlLocations(true);
    ServletRegistrationBean<MessageDispatcherServlet> registration =
        new ServletRegistrationBean<>(servlet, AtmSoap.PATH, AtmSoap.PATH + "/*");
    registration.setName("atmSoap");
    registration.setLoadOnStartup(1);
    return registration;
  }

  @Bean
  XsdSchema atmSoapSchema() {
    return new SimpleXsdSchema(new ClassPathResource(XSD));
  }

  /** Bean name {@code atm}: the servlet publishes it as {@code /partner/ucbatm/ws/v1/atm.wsdl}. */
  @Bean(name = "atm")
  DefaultWsdl11Definition atmWsdl(XsdSchema atmSoapSchema) {
    DefaultWsdl11Definition wsdl = new DefaultWsdl11Definition();
    wsdl.setPortTypeName("AtmPort");
    wsdl.setLocationUri(AtmSoap.PATH);
    wsdl.setTargetNamespace(AtmSoap.NAMESPACE);
    wsdl.setSchema(atmSoapSchema);
    wsdl.setRequestSuffix("Request");
    wsdl.setResponseSuffix("Response");
    return wsdl;
  }
}
