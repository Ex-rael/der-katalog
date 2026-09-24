package br.com.chimaclub.config;

import br.com.chimaclub.BancoDeTesteBase;
import br.com.chimaclub.catalogo.Produto;
import br.com.chimaclub.catalogo.ProdutoFoto;
import br.com.chimaclub.catalogo.ProdutoFotoRepository;
import br.com.chimaclub.catalogo.ProdutoRepository;
import br.com.chimaclub.catalogo.dto.ProdutoForm;
import br.com.chimaclub.catalogo.service.ProdutoService;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Os cabeçalhos do §A05, conferidos sobre porta real.
 *
 * Sobre porta real, e não em MockMvc, por experiência: na Fase 2 um problema
 * de cabeçalho de cache apareceu e sumiu conforme o caminho testado. Cabeçalho
 * é coisa da resposta HTTP, e a única medida confiável é ler a resposta HTTP.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CabecalhosDeSegurancaTest extends BancoDeTesteBase {

    @LocalServerPort int portaPublica;
    @Value("${app.porta-admin}") int portaAdmin;

    @Autowired ProdutoService produtoService;
    @Autowired ProdutoRepository produtos;
    @Autowired ProdutoFotoRepository fotos;

    private static final HttpClient CLIENTE = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private HttpResponse<String> pega(int porta, String caminho) throws IOException, InterruptedException {
        return CLIENTE.send(HttpRequest.newBuilder()
                        .uri(URI.create("http://127.0.0.1:" + porta + caminho))
                        .timeout(Duration.ofSeconds(10)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private String cabecalho(HttpResponse<String> resposta, String nome) {
        return resposta.headers().firstValue(nome).orElse("");
    }

    private void publicarUmProduto() {
        ProdutoForm form = new ProdutoForm();
        form.setNome("Cuia de cabeçalho");
        form.setPrecoEmReais("89,90");
        form.setUnidades(1);
        UUID id = produtoService.criar(form, null, null);

        Produto produto = produtos.findById(id).orElseThrow();
        ProdutoFoto foto = new ProdutoFoto(produto, UUID.randomUUID() + "-media.webp",
                UUID.randomUUID() + "-mini.webp", 1200, 1500, 1000, "image/webp");
        foto.setPrincipal(true);
        fotos.save(foto);
        produtoService.publicar(id, true, null, null);
    }

    @Test
    @DisplayName("toda resposta pública traz os cabeçalhos do §A05")
    void respostaPublicaTrazOsCabecalhos() throws Exception {
        publicarUmProduto();

        for (String rota : new String[]{"/", "/produto/cuia-de-cabecalho", "/saude", "/nao-existe"}) {
            HttpResponse<String> resposta = pega(portaPublica, rota);

            assertThat(cabecalho(resposta, "Content-Security-Policy")).as("CSP em %s", rota).isNotEmpty();
            assertThat(cabecalho(resposta, "X-Content-Type-Options")).as("nosniff em %s", rota).isEqualTo("nosniff");
            assertThat(cabecalho(resposta, "X-Frame-Options")).as("frame em %s", rota).isEqualTo("DENY");
            assertThat(cabecalho(resposta, "Referrer-Policy"))
                    .as("referrer em %s", rota).isEqualTo("strict-origin-when-cross-origin");
            assertThat(cabecalho(resposta, "Permissions-Policy"))
                    .as("permissions em %s", rota)
                    .contains("geolocation=()").contains("camera=()")
                    .contains("microphone=()").contains("payment=()");
            assertThat(cabecalho(resposta, "Cross-Origin-Opener-Policy"))
                    .as("COOP em %s", rota).isEqualTo("same-origin");
            assertThat(cabecalho(resposta, "Cross-Origin-Resource-Policy"))
                    .as("CORP em %s", rota).isEqualTo("same-origin");
        }
    }

    @Test
    @DisplayName("a porta administrativa também traz os cabeçalhos")
    void portaAdministrativaTambemTrazOsCabecalhos() throws Exception {
        HttpResponse<String> resposta = pega(portaAdmin, "/admin/login");

        assertThat(cabecalho(resposta, "Content-Security-Policy")).isNotEmpty();
        assertThat(cabecalho(resposta, "X-Frame-Options")).isEqualTo("DENY");
    }

    @Test
    @DisplayName("a CSP não permite script nem estilo em linha")
    void cspNaoPermiteInline() throws Exception {
        String csp = cabecalho(pega(portaPublica, "/"), "Content-Security-Policy");

        assertThat(csp)
                .as("unsafe-inline desfaz boa parte do que a CSP existe para impedir")
                .doesNotContain("unsafe-inline")
                .doesNotContain("unsafe-eval")
                .doesNotContain("unsafe-hashes");
    }

    @Test
    @DisplayName("a CSP não libera curinga, e o único externo é o wa.me")
    void cspNaoLiberaDominioExterno() throws Exception {
        String csp = cabecalho(pega(portaPublica, "/"), "Content-Security-Policy");

        assertThat(csp)
                .as("curinga em CSP é o mesmo que não ter CSP")
                .doesNotContain("*");

        assertThat(csp)
                .as("http sem s liberaria o destino a quem estiver no meio do caminho")
                .doesNotContain("http://");

        // A guarda original era "nenhum https:// em lugar nenhum". Ela
        // deixou de valer quando o wa.me entrou, e o que a substitui precisa
        // ser mais apertada, não mais frouxa: conta quantas fontes externas
        // existem, e em que diretiva. Só afrouxar o assert transformaria
        // esta exceção na porta por onde as próximas entram sem ninguém ver.
        assertThat(csp.split("https://", -1).length - 1)
                .as("uma única fonte externa na política inteira: %s", csp)
                .isEqualTo(1);

        for (String diretiva : csp.split(";")) {
            String limpa = diretiva.trim();
            if (limpa.contains("https://")) {
                assertThat(limpa)
                        .as("a fonte externa só pode estar em form-action")
                        .isEqualTo("form-action 'self' https://wa.me");
            }
        }
    }

    @Test
    @DisplayName("form-action libera o wa.me, e nada além dele")
    void formActionLiberaSomenteOWhatsapp() throws Exception {
        String csp = cabecalho(pega(portaPublica, "/"), "Content-Security-Policy");

        // O botão de compra envia para o próprio site, que responde 302 para
        // o WhatsApp. A CSP cobra form-action do destino do redirecionamento
        // também, então sem esta fonte o navegador barra a ida — em silêncio
        // na página, só com aviso no console.
        assertThat(csp).contains("form-action 'self' https://wa.me;");

        assertThat(csp)
                .as("sem caminho de propósito: a CSP ignora caminho vindo de "
                    + "redirecionamento, então o caminho seria enfeite")
                .doesNotContain("wa.me/");
    }

    @Test
    @DisplayName("a liberação do wa.me vale também na porta administrativa")
    void formActionNoPainel() throws Exception {
        // O CabecalhosConfig é compartilhado pelas duas cadeias. A fonte a
        // mais não muda nada para o painel — os formulários dele enviam para
        // o próprio site — mas o teste registra que foi conferido, e pega o
        // dia em que alguém separar as políticas e esquecer uma das duas.
        String csp = cabecalho(pega(portaAdmin, "/admin/login"), "Content-Security-Policy");

        assertThat(csp).isEqualTo(
                cabecalho(pega(portaPublica, "/"), "Content-Security-Policy"));
    }

    @Test
    @DisplayName("a CSP traz as diretivas que o §A05 lista")
    void cspTrazAsDiretivasDoPlano() throws Exception {
        String csp = cabecalho(pega(portaPublica, "/"), "Content-Security-Policy");

        assertThat(csp)
                .contains("default-src 'none'")
                .contains("frame-ancestors 'none'")
                .contains("base-uri 'none'")
                .contains("form-action 'self'")
                .contains("script-src 'self'")
                .contains("style-src 'self'")
                .contains("font-src 'self'")
                .contains("img-src 'self'")
                .contains("connect-src 'self'");
    }

    @Test
    @DisplayName("o catálogo funciona sob a própria CSP: nada em linha no HTML")
    void catalogoFuncionaSobAPropriaCsp() throws Exception {
        publicarUmProduto();

        for (String rota : new String[]{"/", "/produto/cuia-de-cabecalho"}) {
            String html = pega(portaPublica, rota).body();

            assertThat(html)
                    .as("um <style> em linha seria bloqueado pela própria política — rota %s", rota)
                    .doesNotContain("<style");
            assertThat(html)
                    .as("script inline seria bloqueado — rota %s", rota)
                    .doesNotContainPattern("<script(?![^>]*\\ssrc=)[^>]*>");
            assertThat(html)
                    .as("atributo de evento é script em linha com outro nome — rota %s", rota)
                    .doesNotContainPattern("\\son(click|load|error|focus|mouseover)\\s*=");
        }
    }

    @Test
    @DisplayName("em desenvolvimento não há HSTS, porque não há TLS")
    void semHstsEmDesenvolvimento() throws Exception {
        assertThat(cabecalho(pega(portaPublica, "/"), "Strict-Transport-Security"))
                .as("anunciar HSTS sobre http local travaria o acesso ao painel na máquina")
                .isEmpty();
    }

    @Test
    @DisplayName("nenhuma resposta anuncia o servidor")
    void nenhumaRespostaAnunciaOServidor() throws Exception {
        for (String rota : new String[]{"/", "/saude", "/nao-existe"}) {
            HttpResponse<String> resposta = pega(portaPublica, rota);

            assertThat(resposta.headers().firstValue("Server")).as("rota %s", rota).isEmpty();
            assertThat(resposta.headers().firstValue("X-Powered-By")).as("rota %s", rota).isEmpty();
        }
    }
}
