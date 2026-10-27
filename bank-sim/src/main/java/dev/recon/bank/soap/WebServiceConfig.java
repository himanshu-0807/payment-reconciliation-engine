package dev.recon.bank.soap;

import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.ws.config.annotation.EnableWs;
import org.springframework.ws.transport.http.MessageDispatcherServlet;
import org.springframework.ws.wsdl.wsdl11.DefaultWsdl11Definition;
import org.springframework.xml.xsd.SimpleXsdSchema;
import org.springframework.xml.xsd.XsdSchema;

/** Serves the SOAP endpoint under /ws; WSDL at /ws/settlement.wsdl. */
@EnableWs
@Configuration
public class WebServiceConfig {

    @Bean
    public ServletRegistrationBean<MessageDispatcherServlet> messageDispatcherServlet(ApplicationContext ctx) {
        MessageDispatcherServlet servlet = new MessageDispatcherServlet();
        servlet.setApplicationContext(ctx);
        servlet.setTransformWsdlLocations(true);
        return new ServletRegistrationBean<>(servlet, "/ws/*");
    }

    @Bean(name = "settlement")
    public DefaultWsdl11Definition settlementWsdl(XsdSchema settlementSchema) {
        DefaultWsdl11Definition wsdl = new DefaultWsdl11Definition();
        wsdl.setPortTypeName("SettlementStatusPort");
        wsdl.setLocationUri("/ws");
        wsdl.setTargetNamespace("http://recon.dev/bank/settlement");
        wsdl.setSchema(settlementSchema);
        return wsdl;
    }

    @Bean
    public XsdSchema settlementSchema() {
        return new SimpleXsdSchema(new ClassPathResource("xsd/bank-settlement.xsd"));
    }
}
