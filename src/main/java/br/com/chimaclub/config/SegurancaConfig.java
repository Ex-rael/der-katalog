package br.com.chimaclub.config;

import jakarta.servlet.DispatcherType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

import java.util.Map;

/**
 * Uma cadeia de segurança por porta, casada pela porta de chegada da
 * requisição e não pelo caminho da URL. Assim, mesmo que algum dia uma
 * configuração de roteamento mude, uma requisição a /admin que chegue pela
 * porta pública continua sendo negada: a política é escolhida antes de o
 * caminho ser considerado.
 */
@Configuration
public class SegurancaConfig {

    /**
     * BCrypt custo 12. O DelegatingPasswordEncoder grava o hash com o
     * prefixo "{bcrypt}", o que permite migrar de algoritmo mais tarde sem
     * invalidar as senhas já existentes: o prefixo diz como cada uma foi
     * feita, e a verificação escolhe o codificador certo para cada hash.
     */
    @Bean
    PasswordEncoder codificadorDeSenha() {
        return new DelegatingPasswordEncoder("bcrypt",
                Map.of("bcrypt", new BCryptPasswordEncoder(12)));
    }

    @Bean
    @Order(1)
    SecurityFilterChain cadeiaAdmin(HttpSecurity http,
                                    ManipuladorDeLogin manipulador,
                                    CabecalhosConfig cabecalhos,
                                    @Value("${app.porta-admin:8081}") int portaAdmin) throws Exception {
        cabecalhos.aplicar(http);
        http.securityMatcher(requisicao -> requisicao.getLocalPort() == portaAdmin)
            .authorizeHttpRequests(a -> a
                .dispatcherTypeMatchers(DispatcherType.ERROR, DispatcherType.FORWARD).permitAll()
                .requestMatchers("/admin/login", "/css/**", "/js/**", "/fontes/**", "/img/**").permitAll()
                // O health sem detalhe serve para a verificação do contêiner,
                // que não tem sessão. Os detalhes continuam exigindo login,
                // por causa do show-details: when-authorized.
                .requestMatchers("/actuator/health").permitAll()
                .anyRequest().hasRole("ADMIN"))
            .formLogin(f -> f
                .loginPage("/admin/login")
                .loginProcessingUrl("/admin/login")
                .failureHandler(manipulador)
                .successHandler(manipulador)
                .permitAll())
            .logout(l -> l
                .logoutUrl("/admin/logout")
                .logoutSuccessUrl("/admin/login?saiu")
                .invalidateHttpSession(true)
                .deleteCookies("CHIMASESSION"))
            .sessionManagement(s -> s
                // Sessão nova a cada login: sem isto, um identificador de
                // sessão obtido antes da autenticação continuaria válido
                // depois dela (fixação de sessão).
                .sessionFixation(f -> f.newSession())
                .maximumSessions(2))
            // Sem "lembrar de mim" e sem token de longa duração (§A07).
            .rememberMe(rm -> rm.disable())
            .csrf(Customizer.withDefaults());
        return http.build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain cadeiaPublica(HttpSecurity http, CabecalhosConfig cabecalhos) throws Exception {
        cabecalhos.aplicar(http);
        http.authorizeHttpRequests(a -> a
                // O despacho interno de erro precisa passar, senão toda
                // falha vira 403 em vez do status real: um 404 do catálogo
                // sairia como "proibido", o que confunde quem consome a
                // página e esconde o desfecho verdadeiro.
                .dispatcherTypeMatchers(DispatcherType.ERROR, DispatcherType.FORWARD).permitAll()
                // Negação explícita antes de qualquer liberação: o painel e as
                // métricas não existem para quem vem da internet.
                .requestMatchers("/admin/**", "/actuator/**").denyAll()
                // HEAD junto de GET: navegador, proxy e cache consultam com
                // HEAD antes de baixar, e negar isso faz a imagem parecer
                // indisponível para quem só está conferindo se mudou.
                .requestMatchers(HttpMethod.GET, "/", "/saude", "/busca", "/produto/**",
                                 "/fotos/**", "/css/**", "/js/**", "/fontes/**", "/img/**",
                                 "/sitemap.xml", "/robots.txt").permitAll()
                .requestMatchers(HttpMethod.HEAD, "/", "/saude", "/busca", "/produto/**",
                                 "/fotos/**", "/css/**", "/js/**", "/fontes/**", "/img/**",
                                 "/sitemap.xml", "/robots.txt").permitAll()
                .requestMatchers(HttpMethod.POST, "/produto/*/whatsapp").permitAll()
                // Política padrão: tudo o que não foi liberado acima é negado.
                .anyRequest().denyAll())
            .csrf(c -> c.ignoringRequestMatchers("/produto/*/whatsapp"))
            .anonymous(Customizer.withDefaults());
        return http.build();
    }
}
