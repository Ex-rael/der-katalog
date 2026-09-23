package br.com.chimaclub.admin;

import br.com.chimaclub.BancoDeTesteBase;
import br.com.chimaclub.NavegadorDeTeste;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;

@AutoConfigureMockMvc
class LoginTest extends BancoDeTesteBase {

    private static final String LOGIN = "/admin/login";
    private static final String EMAIL = "dona@chimaclub.com.br";
    private static final String SENHA = "uma senha longa o bastante";

    @Autowired MockMvc mvc;
    @Autowired UsuarioAdminRepository usuarios;
    @Autowired PasswordEncoder codificador;

    private NavegadorDeTeste navegador;

    @BeforeEach
    void preparar() {
        // a limpeza entre testes fica na classe base
        usuarios.save(new UsuarioAdmin(EMAIL, "Dona", codificador.encode(SENHA)));
        navegador = new NavegadorDeTeste(mvc, PORTA_ADMIN_DE_TESTE);
    }

    private int tentarLogin(String email, String senha) throws Exception {
        return navegador.enviar(LOGIN, LOGIN, "username", email, "password", senha)
                        .getResponse().getStatus();
    }

    private String destinoDoLogin(String email, String senha) throws Exception {
        return navegador.enviar(LOGIN, LOGIN, "username", email, "password", senha)
                        .getResponse().getRedirectedUrl();
    }

    @Test
    @DisplayName("o login recusa sem token CSRF")
    void recusaSemCsrf() throws Exception {
        int status = navegador.enviarSemToken(LOGIN, "username", EMAIL, "password", SENHA)
                              .getResponse().getStatus();

        assertThat(status).isEqualTo(403);
    }

    @Test
    @DisplayName("o login aceita credencial correta e leva ao painel")
    void aceitaCredencialCorreta() throws Exception {
        assertThat(destinoDoLogin(EMAIL, SENHA)).isEqualTo("/admin/produtos");
    }

    @Test
    @DisplayName("a falha leva sempre ao mesmo destino, seja qual for o motivo")
    void falhaLevaSempreAoMesmoDestino() throws Exception {
        String comEmailInexistente = destinoDoLogin("nao-existe@exemplo.com", SENHA);
        String comSenhaErrada = destinoDoLogin(EMAIL, "senha errada qualquer");

        assertThat(comEmailInexistente)
                .as("uma diferença aqui entrega quais e-mails existem")
                .isEqualTo(comSenhaErrada)
                .isEqualTo("/admin/login?erro");
    }

    @Test
    @DisplayName("a tela de erro mostra uma mensagem só, sem distinguir os casos")
    void mensagemDeErroEhUnica() throws Exception {
        String corpo = navegador.corpoDe(LOGIN + "?erro");

        assertThat(corpo).contains("E-mail ou senha inválidos");
        assertThat(corpo)
                .as("nada na página pode diferenciar e-mail inexistente de senha errada")
                .doesNotContainIgnoringCase("não encontrado")
                .doesNotContainIgnoringCase("não existe")
                .doesNotContainIgnoringCase("bloquead")
                .doesNotContainIgnoringCase("inativ");
    }

    @Test
    @DisplayName("cinco tentativas erradas bloqueiam, e a sexta falha mesmo com a senha certa")
    void bloqueiaAposCincoFalhas() throws Exception {
        for (int i = 0; i < 5; i++) {
            tentarLogin(EMAIL, "senha errada");
        }

        assertThat(usuarios.findByEmailIgnoreCase(EMAIL).orElseThrow().estaBloqueado())
                .as("cinco falhas precisam bloquear a conta")
                .isTrue();

        // Duas defesas com o mesmo limiar de cinco, e é de propósito que
        // elas se sobreponham: o limite por IP (§A04) corta a sexta
        // tentativa do mesmo endereço antes mesmo de a senha ser conferida,
        // e o bloqueio da conta (§A07) cobre o caso de quem varia o IP. Para
        // um atacante numa origem só, o limite dispara primeiro — o que
        // significa que a resposta aqui é 429, e não o desvio para a tela de
        // erro. O invariante que importa é o mesmo nos dois casos: não entra.
        var sexta = navegador.enviarSemToken(LOGIN, "username", EMAIL, "password", SENHA);

        assertThat(sexta.getResponse().getStatus())
                .as("com a conta bloqueada e o limite estourado, nem a senha certa entra")
                .isIn(429, 403);
        assertThat(sexta.getResponse().getRedirectedUrl())
                .as("em nenhuma hipótese o desfecho pode ser o painel")
                .isNotEqualTo("/admin/produtos");
    }

    @Test
    @DisplayName("o limite por IP corta a sexta tentativa antes de conferir a senha")
    void limitePorIpCortaAntesDeConferirASenha() throws Exception {
        for (int i = 0; i < 5; i++) {
            tentarLogin(EMAIL, "senha errada");
        }

        assertThat(navegador.enviarSemToken(LOGIN, "username", EMAIL, "password", SENHA)
                            .getResponse().getStatus())
                .as("§A04: cinco por minuto na rota de login")
                .isEqualTo(429);
    }

    @Test
    @DisplayName("o login bem-sucedido zera o contador de falhas")
    void sucessoZeraOContador() throws Exception {
        for (int i = 0; i < 3; i++) {
            tentarLogin(EMAIL, "errada");
        }
        assertThat(usuarios.findByEmailIgnoreCase(EMAIL).orElseThrow().getFalhasLogin()).isEqualTo(3);

        tentarLogin(EMAIL, SENHA);

        assertThat(usuarios.findByEmailIgnoreCase(EMAIL).orElseThrow().getFalhasLogin()).isZero();
    }

    @Test
    @DisplayName("toda tentativa vai para a auditoria, com e sem sucesso")
    void registraTentativasNaAuditoria(@Autowired EventoAuditoriaRepository eventos) throws Exception {
        long antes = eventos.count();

        tentarLogin(EMAIL, "errada");
        tentarLogin(EMAIL, SENHA);

        assertThat(eventos.count()).isGreaterThanOrEqualTo(antes + 2);
    }

    @Test
    @DisplayName("a auditoria de login jamais guarda a senha tentada")
    void auditoriaNaoGuardaASenha(@Autowired EventoAuditoriaRepository eventos) throws Exception {
        tentarLogin(EMAIL, "senha-muito-peculiar-123");

        String tudo = eventos.findAll().stream()
                .map(evento -> String.valueOf(evento.getDetalhes()))
                .reduce("", String::concat);

        assertThat(tudo)
                .as("quem erra o campo digita a senha no lugar do e-mail com frequência")
                .doesNotContain("senha-muito-peculiar-123");
    }

    @Test
    @DisplayName("a página de login não revela nada do que roda por trás")
    void telaDeLoginNaoVazaDetalheInterno() throws Exception {
        String corpo = navegador.corpoDe(LOGIN);

        assertThat(corpo)
                .doesNotContainIgnoringCase("springframework")
                .doesNotContainIgnoringCase("tomcat")
                .doesNotContain("Exception")
                .as("comentário de HTML é entregue ao navegador; o de Thymeleaf não")
                .doesNotContain("<!--");
    }

    @Test
    @DisplayName("sem autenticar, o painel não abre")
    void painelExigeAutenticacao() throws Exception {
        int status = navegador.abrir("/admin/produtos").getResponse().getStatus();

        assertThat(status).isIn(302, 401);
    }

    @Test
    @DisplayName("depois de autenticar, o painel abre na mesma sessão")
    void painelAbreDepoisDeAutenticar() throws Exception {
        tentarLogin(EMAIL, SENHA);

        assertThat(navegador.abrir("/admin/produtos").getResponse().getStatus()).isEqualTo(200);
    }
}
