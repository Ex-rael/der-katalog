package br.com.chimaclub.config;

import org.apache.catalina.connector.Connector;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.tomcat.TomcatConnectorCustomizer;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Um único processo, dois conectores HTTP: 8080 para o catálogo público, que
 * atravessa o Tailscale Funnel, e 8081 para o painel, que não atravessa.
 */
@Configuration
public class PortasConfig {

    /**
     * O endereço de bind vem de configuração: 127.0.0.1 no perfil dev, que
     * roda direto no host, e 0.0.0.0 no perfil prod, que roda em contêiner.
     * Dentro do contêiner, 127.0.0.1 é o loopback do próprio contêiner:
     * ligar nele deixaria a porta inalcançável pela publicação do Docker, e
     * o painel simplesmente não responderia. Em produção o confinamento é
     * feito pelo "127.0.0.1:8081:8081" do Compose, pelo UFW e pelo
     * Tailscale — nunca por este endereço.
     */
    @Bean
    public WebServerFactoryCustomizer<TomcatServletWebServerFactory> conectorAdmin(
            @Value("${app.porta-admin:8081}") int portaAdmin,
            @Value("${app.endereco-bind:127.0.0.1}") String enderecoBind) {
        return factory -> {
            Connector admin = new Connector("org.apache.coyote.http11.Http11NioProtocol");
            admin.setPort(portaAdmin);
            admin.setProperty("address", enderecoBind);
            admin.setAllowTrace(false);
            // No Spring Boot 4 o método chama-se addAdditionalConnectors;
            // era addAdditionalTomcatConnectors na linha 3.x.
            factory.addAdditionalConnectors(admin);
        };
    }

    /** TRACE desabilitado em todos os conectores, inclusive o público (§A01). */
    @Bean
    public TomcatConnectorCustomizer desabilitaTrace() {
        return conector -> conector.setAllowTrace(false);
    }
}
