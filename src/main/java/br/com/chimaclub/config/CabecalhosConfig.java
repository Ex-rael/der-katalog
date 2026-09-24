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
     *
     * <p><b>A única exceção: https://api.whatsapp.com em form-action.</b> O botão de
     * compra envia um formulário para o próprio site, que registra o clique
     * (§1.3) e responde 302 para o WhatsApp. A especificação da CSP aplica
     * form-action tanto ao endereço do envio quanto ao destino do
     * redirecionamento que vier dele, então 'self' sozinho fazia o navegador
     * bloquear a ida para o WhatsApp — sem mensagem na página, só um aviso
     * no console.
     *
     * <p>Poderia ter sido resolvido trocando o botão por um link direto para
     * o wa.me, e aí a CSP ficaria sem exceção nenhuma. Não foi, de propósito:
     * o link direto tira o clique do servidor, e com ele a métrica de procura
     * que a loja usa para decidir o que repor. A exceção é de uma diretiva,
     * de um destino, e nada além dela muda.
     *
     * <p>A fonte é api.whatsapp.com, e não wa.me, porque form-action vale em
     * <i>cada</i> salto do redirecionamento e o wa.me não é o fim da linha:
     * ele responde 302 para o api.whatsapp.com. Liberar só o wa.me fazia o
     * navegador barrar o segundo salto — foi o que aconteceu em produção,
     * com o agravante de que a mensagem de erro aponta a URL do próprio
     * site, porque a CSP omite o destino do redirecionamento nos relatórios
     * para não vazá-lo. O serviço passou a redirecionar direto ao destino
     * final: um salto, uma origem.
     *
     * <p>O endereço vai sem caminho. Escrever
     * https://api.whatsapp.com/send?phone=555199... pareceria mais restrito
     * e não seria: a CSP ignora o caminho de uma fonte quando a URL chega
     * por redirecionamento, justamente para não vazar para onde ele levou.
     * O caminho seria enfeite, e ainda obrigaria este cabeçalho a ser
     * remontado a cada requisição, porque o número da loja vive na
     * configuração e muda pelo painel. O que impede o destino de ser outro
     * não é a CSP: é o WhatsappService, onde a base é constante, o número
     * vem da configuração com tudo que não for dígito removido, e nada da
     * requisição entra na URL.
     */
    public static final String POLITICA_DE_CONTEUDO = String.join("; ",
            "default-src 'none'",
            "img-src 'self' data:",
            "style-src 'self'",
            "font-src 'self'",
            "script-src 'self'",
            "connect-src 'self'",
            "form-action 'self' https://api.whatsapp.com",
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
