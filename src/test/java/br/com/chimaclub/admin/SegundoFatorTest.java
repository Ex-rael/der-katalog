package br.com.chimaclub.admin;

import br.com.chimaclub.BancoDeTesteBase;
import br.com.chimaclub.NavegadorDeTeste;
import br.com.chimaclub.admin.totp.ServicoTotp;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * O segundo fator com a exigência ligada, como em produção.
 *
 * O caso que mais importa aqui não é a tela bonita: é a sessão pela metade —
 * autenticada pela senha, sem o código conferido — não alcançar rota nenhuma
 * do painel. É a diferença entre ter segundo fator e ter uma tela de segundo
 * fator que dá para pular trocando a URL.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = "app.segundo-fator-obrigatorio=true")
class SegundoFatorTest extends BancoDeTesteBase {

    private static final String LOGIN = "/admin/login";
    private static final String EMAIL = "dona@chimaclub.com.br";
    private static final String SENHA = "uma senha longa o bastante";

    @Autowired MockMvc mvc;
    @Autowired UsuarioAdminRepository usuarios;
    @Autowired PasswordEncoder codificador;
    @Autowired ServicoTotp totp;

    private NavegadorDeTeste navegador;
    private UsuarioAdmin admin;

    @BeforeEach
    void preparar() {
        admin = usuarios.save(new UsuarioAdmin(EMAIL, "Dona", codificador.encode(SENHA)));
        navegador = new NavegadorDeTeste(mvc, PORTA_ADMIN_DE_TESTE);
    }

    private void autenticarPelaSenha() throws Exception {
        navegador.enviar(LOGIN, LOGIN, "username", EMAIL, "password", SENHA);
    }

    private String ativarSegundoFator() {
        String segredo = totp.gerarSegredo();
        totp.ativar(admin, segredo);
        usuarios.save(admin);
        return segredo;
    }

    @Test
    @DisplayName("sem segundo fator cadastrado, o painel leva à tela de ativação")
    void semSegundoFatorCadastradoLevaAAtivacao() throws Exception {
        autenticarPelaSenha();

        assertThat(navegador.abrir("/admin/produtos").getResponse().getRedirectedUrl())
                .isEqualTo("/admin/totp/conferir");
        assertThat(navegador.abrir("/admin/totp/conferir").getResponse().getRedirectedUrl())
                .as("quem não ativou não tem o que conferir")
                .isEqualTo("/admin/totp/ativar");
    }

    @Test
    @DisplayName("a sessão pela metade não alcança rota nenhuma do painel")
    void sessaoPelaMetadeNaoAlcancaNada() throws Exception {
        ativarSegundoFator();
        autenticarPelaSenha();

        String[] rotas = {
                "/admin/produtos", "/admin/produtos/novo",
                "/admin/configuracao", "/admin/auditoria", "/admin/painel"
        };

        for (String rota : rotas) {
            assertThat(navegador.abrir(rota).getResponse().getRedirectedUrl())
                    .as("a senha sozinha não pode abrir %s", rota)
                    .isEqualTo("/admin/totp/conferir");
        }
    }

    @Test
    @DisplayName("com o código certo, o painel abre")
    void comOCodigoCertoOPainelAbre() throws Exception {
        String segredo = ativarSegundoFator();
        autenticarPelaSenha();

        var resposta = navegador.enviar("/admin/totp/conferir", "/admin/totp/conferir",
                "codigo", totp.codigoAgora(segredo));

        assertThat(resposta.getResponse().getRedirectedUrl()).isEqualTo("/admin/produtos");
        assertThat(navegador.abrir("/admin/produtos").getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("código errado não abre o painel")
    void codigoErradoNaoAbre() throws Exception {
        ativarSegundoFator();
        autenticarPelaSenha();

        var resposta = navegador.enviar("/admin/totp/conferir", "/admin/totp/conferir",
                "codigo", "000000");

        assertThat(resposta.getResponse().getRedirectedUrl()).isEqualTo("/admin/totp/conferir?erro");
        assertThat(navegador.abrir("/admin/produtos").getResponse().getRedirectedUrl())
                .isEqualTo("/admin/totp/conferir");
    }

    @Test
    @DisplayName("a falha no segundo fator vai para a auditoria")
    void falhaNoSegundoFatorVaiParaAuditoria(@Autowired EventoAuditoriaRepository eventos) throws Exception {
        ativarSegundoFator();
        autenticarPelaSenha();

        navegador.enviar("/admin/totp/conferir", "/admin/totp/conferir", "codigo", "000000");

        String tudo = eventos.findAll().stream()
                .map(evento -> evento.getAcao() + String.valueOf(evento.getDetalhes()))
                .reduce("", String::concat);

        assertThat(tudo).contains("segundo_fator");
    }

    @Test
    @DisplayName("a tela de código não revela nada sobre o motivo da falha")
    void telaDeCodigoNaoRevelaNada() throws Exception {
        ativarSegundoFator();
        autenticarPelaSenha();

        String corpo = navegador.corpoDe("/admin/totp/conferir?erro");

        assertThat(corpo).contains("Código inválido");
        assertThat(corpo)
                .doesNotContainIgnoringCase("relógio")
                .doesNotContainIgnoringCase("expirad")
                .doesNotContainIgnoringCase("reutiliz")
                .doesNotContain("<!--");
    }

    @Test
    @DisplayName("a ativação só grava o segredo depois de a pessoa apresentar um código dele")
    void ativacaoExigeProvaDeCadastro() throws Exception {
        autenticarPelaSenha();
        navegador.abrir("/admin/totp/ativar");

        var recusa = navegador.enviar("/admin/totp/ativar", "/admin/totp/ativar", "codigo", "000000");

        assertThat(recusa.getResponse().getRedirectedUrl()).isEqualTo("/admin/totp/ativar?erro");
        assertThat(usuarios.findByEmailIgnoreCase(EMAIL).orElseThrow().isTotpAtivo())
                .as("gravar antes da prova deixaria a conta com um fator que ninguém consegue apresentar")
                .isFalse();
    }

    @Test
    @DisplayName("o código QR só existe para quem tem ativação em curso")
    void codigoQrSoParaQuemTemAtivacaoEmCurso() throws Exception {
        autenticarPelaSenha();

        // Sem passar pela tela de ativação, não há segredo pendente na sessão.
        assertThat(navegador.abrir("/admin/totp/qr.png").getResponse().getStatus()).isEqualTo(404);

        navegador.abrir("/admin/totp/ativar");
        var qr = navegador.abrir("/admin/totp/qr.png").getResponse();

        assertThat(qr.getStatus()).isEqualTo(200);
        assertThat(qr.getContentType()).isEqualTo("image/png");
        assertThat(qr.getHeader("Cache-Control"))
                .as("o segredo não pode ficar no cache do navegador")
                .contains("no-store");
    }

    @Test
    @DisplayName("sem sessão, as telas do segundo fator não abrem")
    void semSessaoAsTelasNaoAbrem() throws Exception {
        for (String rota : new String[]{"/admin/totp/ativar", "/admin/totp/conferir", "/admin/totp/qr.png"}) {
            assertThat(navegador.abrir(rota).getResponse().getStatus())
                    .as("o segredo aparece em claro nestas telas — rota %s", rota)
                    .isIn(302, 401, 403, 404);
        }
    }

    @Test
    @DisplayName("as telas do segundo fator não existem pela porta pública")
    void telasNaoExistemPelaPortaPublica() throws Exception {
        NavegadorDeTeste pelaPortaPublica = new NavegadorDeTeste(mvc, 8080);

        for (String rota : new String[]{"/admin/totp/ativar", "/admin/totp/qr.png"}) {
            assertThat(pelaPortaPublica.abrir(rota).getResponse().getStatus())
                    .as("rota %s pela porta pública", rota)
                    .isIn(403, 404);
        }
    }
}
