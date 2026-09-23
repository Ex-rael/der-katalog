package br.com.chimaclub.config;

import br.com.chimaclub.BancoDeTesteBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@AutoConfigureMockMvc
class LimiteDeRequisicoesTest extends BancoDeTesteBase {

    @Autowired MockMvc mvc;
    @Autowired LimiteDeRequisicoes limite;
    @Autowired IpDeOrigem ipDeOrigem;

    @BeforeEach
    void zerarOsBaldes() {
        limite.limpar();
    }

    /** Faz a requisição parecer vir de um endereço de internet específico. */
    private MockHttpServletRequestBuilder de(MockHttpServletRequestBuilder builder, String ip) {
        return builder.with(requisicao -> {
            requisicao.setRemoteAddr(ip);
            return requisicao;
        });
    }

    private int statusDe(MockHttpServletRequestBuilder builder) throws Exception {
        return mvc.perform(builder).andReturn().getResponse().getStatus();
    }

    @Test
    @DisplayName("passado o limite, a rota pública responde 429 com Retry-After")
    void passadoOLimiteResponde429() throws Exception {
        for (int i = 0; i < LimiteDeRequisicoes.Faixa.PAGINA.quantidade(); i++) {
            assertThat(statusDe(de(get("/"), "203.0.113.10")))
                    .as("requisição %d ainda deve passar", i + 1)
                    .isNotEqualTo(429);
        }

        var resposta = mvc.perform(de(get("/"), "203.0.113.10")).andReturn().getResponse();

        assertThat(resposta.getStatus()).isEqualTo(429);
        assertThat(resposta.getHeader("Retry-After"))
                .as("um cliente bem comportado precisa saber quanto esperar")
                .isEqualTo("60");
    }

    @Test
    @DisplayName("o login tem limite próprio, bem mais apertado")
    void loginTemLimiteMaisApertado() throws Exception {
        for (int i = 0; i < LimiteDeRequisicoes.Faixa.LOGIN.quantidade(); i++) {
            assertThat(statusDe(de(post("/admin/login"), "203.0.113.11"))).isNotEqualTo(429);
        }

        assertThat(statusDe(de(post("/admin/login"), "203.0.113.11")))
                .as("cinco tentativas por minuto é o que a §A04 pede")
                .isEqualTo(429);
    }

    @Test
    @DisplayName("IPs diferentes têm baldes diferentes")
    void ipsDiferentesTemBaldesDiferentes() throws Exception {
        for (int i = 0; i <= LimiteDeRequisicoes.Faixa.PAGINA.quantidade(); i++) {
            statusDe(de(get("/"), "203.0.113.20"));
        }
        assertThat(statusDe(de(get("/"), "203.0.113.20"))).isEqualTo(429);

        assertThat(statusDe(de(get("/"), "203.0.113.21")))
                .as("um visitante abusivo não pode derrubar o catálogo para os outros")
                .isNotEqualTo(429);
    }

    @Test
    @DisplayName("as fotos e o estático não entram no limite das páginas")
    void recursosNaoEntramNoLimiteDasPaginas() throws Exception {
        // Uma visita à home pede perto de trinta arquivos. Se caíssem na
        // faixa das páginas, a segunda visita legítima já seria barrada.
        for (int i = 0; i < LimiteDeRequisicoes.Faixa.PAGINA.quantidade() + 20; i++) {
            assertThat(statusDe(de(get("/css/chimaclub.css"), "203.0.113.30")))
                    .as("recurso estático %d", i + 1)
                    .isNotEqualTo(429);
        }

        assertThat(statusDe(de(get("/"), "203.0.113.30")))
                .as("e a página continua disponível para o mesmo visitante")
                .isNotEqualTo(429);
    }

    @Test
    @DisplayName("a resposta de limite não vaza detalhe nenhum")
    void respostaDeLimiteNaoVazaNada() throws Exception {
        for (int i = 0; i <= LimiteDeRequisicoes.Faixa.PAGINA.quantidade(); i++) {
            statusDe(de(get("/"), "203.0.113.40"));
        }

        String corpo = mvc.perform(de(get("/"), "203.0.113.40"))
                          .andReturn().getResponse().getContentAsString();

        assertThat(corpo)
                .as("informar o limite e o que resta ajudaria a calibrar o abuso")
                .doesNotContain("60")
                .doesNotContain("bucket")
                .doesNotContainIgnoringCase("springframework")
                .doesNotContain("Exception");
    }

    @Test
    @DisplayName("conexão direta: o endereço do socket é o que vale")
    void conexaoDiretaUsaOSocket() {
        MockHttpServletRequest requisicao = new MockHttpServletRequest();
        requisicao.setRemoteAddr("198.51.100.7");
        requisicao.addHeader("X-Forwarded-For", "1.2.3.4");

        assertThat(ipDeOrigem.de(requisicao))
                .as("o cabeçalho é texto que o cliente controla; de fora do "
                    + "intermediário conhecido, ele não vale nada")
                .isEqualTo("198.51.100.7");
    }

    @Test
    @DisplayName("atrás do intermediário conhecido, vale o endereço que ele escreveu")
    void atrasDoIntermediarioUsaOCabecalho() {
        MockHttpServletRequest requisicao = new MockHttpServletRequest();
        requisicao.setRemoteAddr("127.0.0.1");
        requisicao.addHeader("X-Forwarded-For", "198.51.100.8");

        assertThat(ipDeOrigem.de(requisicao))
                .as("atrás do Funnel todo mundo chega de 127.0.0.1, e um balde "
                    + "compartilhado seria esgotado por um visitante só")
                .isEqualTo("198.51.100.8");
    }

    @Test
    @DisplayName("um X-Forwarded-For forjado pelo cliente é ignorado")
    void cabecalhoForjadoEhIgnorado() {
        // O cliente manda um valor; o intermediário acrescenta o endereço
        // real à direita. Ler da direita para a esquerda descarta o forjado.
        MockHttpServletRequest requisicao = new MockHttpServletRequest();
        requisicao.setRemoteAddr("127.0.0.1");
        requisicao.addHeader("X-Forwarded-For", "9.9.9.9, 198.51.100.9");

        assertThat(ipDeOrigem.de(requisicao))
                .as("senão bastaria variar o cabeçalho para nunca cair no mesmo balde")
                .isEqualTo("198.51.100.9");
    }

    @Test
    @DisplayName("cabeçalho ausente ou vazio atrás do intermediário não quebra nada")
    void cabecalhoAusenteNaoQuebra() {
        MockHttpServletRequest semCabecalho = new MockHttpServletRequest();
        semCabecalho.setRemoteAddr("127.0.0.1");

        MockHttpServletRequest vazio = new MockHttpServletRequest();
        vazio.setRemoteAddr("127.0.0.1");
        vazio.addHeader("X-Forwarded-For", "   ");

        assertThat(ipDeOrigem.de(semCabecalho)).isEqualTo("127.0.0.1");
        assertThat(ipDeOrigem.de(vazio)).isEqualTo("127.0.0.1");
    }

    @Test
    @DisplayName("forjar o cabeçalho não contorna o limite")
    void forjarOCabecalhoNaoContornaOLimite() throws Exception {
        // Cada requisição vem com um X-Forwarded-For diferente, mas de uma
        // conexão direta — o cabeçalho é descartado e todas caem no mesmo balde.
        for (int i = 0; i <= LimiteDeRequisicoes.Faixa.PAGINA.quantidade(); i++) {
            mvc.perform(de(get("/").header("X-Forwarded-For", "10.0.0." + (i % 250)), "203.0.113.50"));
        }

        assertThat(statusDe(de(get("/").header("X-Forwarded-For", "10.0.0.251"), "203.0.113.50")))
                .as("variar o cabeçalho não pode dar balde novo a cada requisição")
                .isEqualTo(429);
    }
}
