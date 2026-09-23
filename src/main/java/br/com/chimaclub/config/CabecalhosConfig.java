package br.com.chimaclub.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.header.writers.XXssProtectionHeaderWriter;
import org.springframework.stereotype.Component;

/**
 * Os cabeçalhos de segurança do §A05, aplicados às duas cadeias.
 *
 * Reunidos aqui, e não repetidos em cada cadeia, porque cabeçalho esquecido
 * numa delas é exatamente o tipo de descuido que ninguém percebe: a página
 * continua funcionando, e a proteção simplesmente não está lá.
 */
@Component
public class CabecalhosConfig {

    /**
     * A política do §A05, palavra por palavra.
     *
     * default-src 'none' é o ponto de partida: nada é permitido, e cada tipo
     * de recurso precisa ser liberado explicitamente. Uma diretiva nova que
     * alguém esqueça de acrescentar falha fechada, que é como deve ser.
     *
     * Nenhuma diretiva leva 'unsafe-inline'. A Fase 3 hospedou fontes, HTMX
     * e o script do carrossel localmente, e os estilos em linha dos
     * templates viraram classes, justamente para que esta linha pudesse ser
     * escrita sem exceção nenhuma.
     *
     * frame-ancestors 'none' impede que o catálogo seja embutido em outro
     * site — a defesa contra clickjacking que o X-Frame-Options também dá,
     * mantido por compatibilidade com navegador antigo.
     */
    public static final String POLITICA_DE_CONTEUDO = String.join("; ",
            "default-src 'none'",
            "img-src 'self' data:",
            "style-src 'self'",
            "font-src 'self'",
            "script-src 'self'",
            "connect-src 'self'",
            "form-action 'self'",
            "frame-ancestors 'none'",
            "base-uri 'none'",
            "object-src 'none'",
            "manifest-src 'self'");

    private final boolean emProducao;

    public CabecalhosConfig(@Value("${app.hsts-ligado:false}") boolean hstsLigado) {
        this.emProducao = hstsLigado;
    }

    /**
     * Aplica os cabeçalhos a uma cadeia.
     *
     * O HSTS fica desligado fora de produção de propósito. Anunciá-lo sobre
     * http local faria o navegador exigir https daquele host por um ano, e
     * quem administra a loja ficaria sem acesso ao painel na própria máquina
     * — com pouca gente sabendo desfazer isso.
     */
    public void aplicar(HttpSecurity http) throws Exception {
        http.headers(cabecalhos -> {
            cabecalhos.contentSecurityPolicy(csp -> csp.policyDirectives(POLITICA_DE_CONTEUDO));

            cabecalhos.referrerPolicy(referrer ->
                    referrer.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN));

            cabecalhos.frameOptions(Customizer.withDefaults());       // DENY
            cabecalhos.contentTypeOptions(Customizer.withDefaults()); // nosniff

            cabecalhos.crossOriginOpenerPolicy(coop -> coop.policy(
                    org.springframework.security.web.header.writers.CrossOriginOpenerPolicyHeaderWriter
                            .CrossOriginOpenerPolicy.SAME_ORIGIN));
            cabecalhos.crossOriginResourcePolicy(corp -> corp.policy(
                    org.springframework.security.web.header.writers.CrossOriginResourcePolicyHeaderWriter
                            .CrossOriginResourcePolicy.SAME_ORIGIN));

            // Nenhum recurso da aplicação pede geolocalização, câmera,
            // microfone ou pagamento. Negá-los explicitamente impede que um
            // script injetado consiga pedi-los em nome do site.
            cabecalhos.permissionsPolicyHeader(permissoes -> permissoes.policy(
                    "geolocation=(), camera=(), microphone=(), payment=(), usb=(), magnetometer=()"));

            // O X-XSS-Protection dos navegadores antigos causava mais problema
            // que solução, e hoje está removido de todos. Desligado por escrito
            // para deixar claro que é decisão, não esquecimento.
            cabecalhos.xssProtection(xss -> xss.headerValue(
                    XXssProtectionHeaderWriter.HeaderValue.DISABLED));

            if (emProducao) {
                cabecalhos.httpStrictTransportSecurity(hsts -> hsts
                        .includeSubDomains(true)
                        .maxAgeInSeconds(31_536_000L));
            } else {
                cabecalhos.httpStrictTransportSecurity(hsts -> hsts.disable());
            }
        });
    }
}
