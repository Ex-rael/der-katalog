package br.com.chimaclub.config;

import br.com.chimaclub.BancoDeTesteBase;
import br.com.chimaclub.NavegadorDeTeste;
import br.com.chimaclub.admin.UsuarioAdmin;
import br.com.chimaclub.admin.UsuarioAdminRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

@AutoConfigureMockMvc
class ActuatorEPaginaDeErroTest extends BancoDeTesteBase {

    @Autowired MockMvc mvc;
    @Autowired UsuarioAdminRepository usuarios;
    @Autowired PasswordEncoder codificador;

    private NavegadorDeTeste naPortaAdmin;

    @BeforeEach
    void preparar() {
        usuarios.save(new UsuarioAdmin("dona@chimaclub.com.br", "Dona",
                codificador.encode("uma senha longa o bastante")));
        naPortaAdmin = new NavegadorDeTeste(mvc, PORTA_ADMIN_DE_TESTE);
    }

    @Test
    @DisplayName("env, heapdump e threaddump não existem, em porta nenhuma")
    void pontosPerigososNaoExistem() throws Exception {
        String[] perigosos = {
                "/actuator/env", "/actuator/heapdump", "/actuator/threaddump",
                "/actuator/beans", "/actuator/configprops", "/actuator/mappings",
                "/actuator/loggers", "/actuator/metrics", "/actuator/shutdown"
        };

        for (String ponto : perigosos) {
            // Pela porta pública: negado pela cadeia, antes de tudo.
            assertThat(mvc.perform(get(ponto)).andReturn().getResponse().getStatus())
                    .as("%s pela porta pública", ponto)
                    .isIn(401, 403, 404);

            // Pela porta administrativa: não existe, porque não foi exposto.
            assertThat(naPortaAdmin.abrir(ponto).getResponse().getStatus())
                    .as("%s pela porta administrativa — env entregaria a senha do banco "
                        + "e a chave do TOTP em texto", ponto)
                    .isIn(302, 401, 403, 404);
        }
    }

    @Test
    @DisplayName("o health responde na porta administrativa, sem detalhe para o anônimo")
    void healthRespondeSemDetalheParaAnonimo() throws Exception {
        var resposta = naPortaAdmin.abrir("/actuator/health").getResponse();

        assertThat(resposta.getStatus()).isEqualTo(200);
        assertThat(resposta.getContentAsString())
                .as("para quem não está autenticado, o health diz só se a aplicação responde")
                .contains("UP")
                .doesNotContainIgnoringCase("postgres")
                .doesNotContainIgnoringCase("diskSpace")
                .doesNotContainIgnoringCase("validationQuery")
                .doesNotContainIgnoringCase("jdbc:");
    }

    @Test
    @DisplayName("o health não é alcançável pela porta pública")
    void healthNaoEhAlcancavelPelaPortaPublica() throws Exception {
        assertThat(mvc.perform(get("/actuator/health")).andReturn().getResponse().getStatus())
                .as("§A05: o actuator existe só na porta administrativa")
                .isIn(401, 403, 404);
    }

    @Test
    @DisplayName("a página de erro pública é genérica")
    void paginaDeErroEhGenerica() throws Exception {
        String corpo = mvc.perform(get("/produto/nao-existe"))
                          .andReturn().getResponse().getContentAsString();

        assertThat(corpo)
                .as("cada detalhe ajuda quem está sondando, e nenhum ajuda o cliente")
                .doesNotContain("Exception")
                .doesNotContain("br.com.chimaclub")
                .doesNotContainIgnoringCase("springframework")
                .doesNotContainIgnoringCase("tomcat")
                .doesNotContainIgnoringCase("stacktrace")
                .doesNotContain("<!--");
    }

    @Test
    @DisplayName("a página de erro não revela o caminho pedido")
    void paginaDeErroNaoRevelaOCaminho() throws Exception {
        String corpo = mvc.perform(get("/caminho-secreto-inventado"))
                          .andReturn().getResponse().getContentAsString();

        assertThat(corpo)
                .as("ecoar o caminho é refletir entrada do cliente na página")
                .doesNotContain("caminho-secreto-inventado");
    }
}
