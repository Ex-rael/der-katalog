package br.com.chimaclub.config;

import br.com.chimaclub.BancoDeTesteBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * O teste que sustenta a decisão central de arquitetura: o painel
 * administrativo não é alcançável por quem chega pela porta pública, que é a
 * única publicada pelo Tailscale Funnel.
 *
 * Ele sobe a aplicação em portas reais e fala HTTP de verdade, com o cliente
 * do próprio JDK. MockMvc não serviria: não tem porta, e sem porta não há
 * como distinguir as duas cadeias de segurança, que é justamente o que
 * precisa ser provado. O cliente do JDK ainda mantém o teste imune a
 * mudanças de API do cliente de teste do Spring.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class IsolamentoDoPainelTest extends BancoDeTesteBase {

    @LocalServerPort
    int portaPublica;

    @Value("${app.porta-admin}")
    int portaAdmin;

    private static final HttpClient CLIENTE = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private HttpResponse<String> cabecalhoDe(int porta, String caminho)
            throws IOException, InterruptedException {
        HttpRequest pedido = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + porta + caminho))
                .timeout(Duration.ofSeconds(10))
                .method("HEAD", HttpRequest.BodyPublishers.noBody())
                .build();
        return CLIENTE.send(pedido, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> pega(int porta, String caminho) throws IOException, InterruptedException {
        HttpRequest pedido = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + porta + caminho))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        return CLIENTE.send(pedido, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    @DisplayName("o catálogo público responde na porta pública")
    void catalogoRespondeNaPortaPublica() throws Exception {
        HttpResponse<String> resposta = pega(portaPublica, "/saude");

        assertThat(resposta.statusCode()).isEqualTo(200);
        assertThat(resposta.body()).isEqualTo("ok");
    }

    @Test
    @DisplayName("o painel NÃO é alcançável pela porta pública — item central do §A01")
    void painelNaoRespondeNaPortaPublica() throws Exception {
        HttpResponse<String> resposta = pega(portaPublica, "/admin/painel");

        assertThat(resposta.statusCode())
                .as("quem chega pela internet não pode nem ver a tela de login")
                .isIn(403, 404);
    }

    @Test
    @DisplayName("nem a tela de login vaza pela porta pública")
    void loginNaoRespondeNaPortaPublica() throws Exception {
        HttpResponse<String> resposta = pega(portaPublica, "/admin/login");

        assertThat(resposta.statusCode()).isIn(403, 404);
        assertThat(resposta.body())
                .doesNotContainIgnoringCase("senha")
                .doesNotContainIgnoringCase("password");
    }

    @Test
    @DisplayName("o actuator NÃO é alcançável pela porta pública")
    void actuatorNaoRespondeNaPortaPublica() throws Exception {
        HttpResponse<String> resposta = pega(portaPublica, "/actuator/health");

        assertThat(resposta.statusCode()).isIn(403, 404);
    }

    @Test
    @DisplayName("o painel existe na porta administrativa, mas exige autenticação")
    void painelExigeAutenticacaoNaPortaAdmin() throws Exception {
        HttpResponse<String> resposta = pega(portaAdmin, "/admin/painel");

        assertThat(resposta.statusCode())
                .as("na tailnet a resposta é a exigência de autenticação, não 404")
                .isIn(401, 302);
    }

    @Test
    @DisplayName("nenhuma resposta revela o servidor nem a sua versão")
    void naoRevelaVersaoDoServidor() throws Exception {
        HttpResponse<String> resposta = pega(portaPublica, "/saude");

        assertThat(resposta.headers().firstValue("Server")).isEmpty();
        assertThat(resposta.headers().firstValue("X-Powered-By")).isEmpty();
    }

    @Test
    @DisplayName("NENHUMA rota do painel é alcançável pela porta pública")
    void nenhumaRotaDoPainelVazaPelaPortaPublica() throws Exception {
        // Uma asserção por rota criada no painel. Uma rota nova que esqueça
        // de entrar nesta lista continua protegida pelo denyAll da cadeia
        // pública; esta lista existe para que a proteção seja verificada, e
        // não apenas presumida.
        String[] rotasDoPainel = {
                "/admin", "/admin/", "/admin/login", "/admin/logout",
                "/admin/painel", "/admin/produtos", "/admin/produtos/novo",
                "/admin/configuracao", "/admin/auditoria",
                "/actuator", "/actuator/health", "/actuator/env", "/actuator/heapdump"
        };

        for (String rota : rotasDoPainel) {
            HttpResponse<String> resposta = pega(portaPublica, rota);

            assertThat(resposta.statusCode())
                    .as("rota %s pela porta pública", rota)
                    .isIn(403, 404);
            assertThat(resposta.body())
                    .as("corpo da resposta a %s", rota)
                    .doesNotContainIgnoringCase("senha")
                    .doesNotContainIgnoringCase("password")
                    .doesNotContainIgnoringCase("chima club");
        }
    }

    @Test
    @DisplayName("a rota de fotos responde pela porta pública, porque o catálogo precisa dela")
    void fotosRespondemPelaPortaPublica() throws Exception {
        // 404 e não 403: a rota existe e está liberada; o arquivo é que não.
        assertThat(pega(portaPublica, "/fotos/inexistente.webp").statusCode()).isEqualTo(404);
    }

    @Test
    @DisplayName("a foto servida traz cache eterno e tipo fixo")
    void fotoTrazCacheEterno(@Autowired br.com.chimaclub.midia.ArmazenamentoFotos armazenamento)
            throws Exception {
        java.util.UUID base = java.util.UUID.randomUUID();
        armazenamento.gravar(base, java.util.Map.of("mini", new byte[]{1, 2, 3, 4}), "webp");

        HttpResponse<String> resposta = pega(portaPublica, "/fotos/" + base + "-mini.webp");

        assertThat(resposta.statusCode()).isEqualTo(200);
        assertThat(resposta.headers().firstValue("Cache-Control").orElse(""))
                .as("o nome carrega um UUID novo a cada upload, então o conteúdo "
                    + "de um nome nunca muda e o cache pode ser eterno")
                .contains("max-age=31536000")
                .contains("immutable");
        assertThat(resposta.headers().firstValue("X-Content-Type-Options"))
                .contains("nosniff");
    }

    @Test
    @DisplayName("HEAD é aceito nas rotas públicas, como GET")
    void aceitaHeadNasRotasPublicas(@Autowired br.com.chimaclub.midia.ArmazenamentoFotos armazenamento)
            throws Exception {
        java.util.UUID base = java.util.UUID.randomUUID();
        armazenamento.gravar(base, java.util.Map.of("mini", new byte[]{1, 2, 3, 4}), "webp");

        for (String rota : new String[]{"/saude", "/fotos/" + base + "-mini.webp"}) {
            assertThat(cabecalhoDe(portaPublica, rota).statusCode())
                    .as("navegador, proxy e cache consultam com HEAD antes de baixar; "
                        + "negar faz o recurso parecer indisponível — rota %s", rota)
                    .isEqualTo(200);
        }
    }

    @Test
    @DisplayName("HEAD no painel continua negado pela porta pública")
    void headNaoAbreBrechaNoPainel() throws Exception {
        for (String rota : new String[]{"/admin/produtos", "/admin/login", "/actuator/health"}) {
            assertThat(cabecalhoDe(portaPublica, rota).statusCode())
                    .as("liberar HEAD não pode virar porta dos fundos — rota %s", rota)
                    .isIn(403, 404);
        }
    }

    @Test
    @DisplayName("rota pública desconhecida é negada, não explorada")
    void rotaDesconhecidaEhNegada() throws Exception {
        HttpResponse<String> resposta = pega(portaPublica, "/qualquer-coisa-inexistente");

        assertThat(resposta.statusCode()).isIn(403, 404);
    }
}
