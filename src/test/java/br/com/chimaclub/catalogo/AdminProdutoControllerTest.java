package br.com.chimaclub.catalogo;

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

@AutoConfigureMockMvc
class AdminProdutoControllerTest extends BancoDeTesteBase {

    private static final String LOGIN = "/admin/login";
    private static final String LISTA = "/admin/produtos";
    private static final String NOVO = "/admin/produtos/novo";
    private static final String EMAIL = "dona@chimaclub.com.br";
    private static final String SENHA = "uma senha longa o bastante";

    @Autowired MockMvc mvc;
    @Autowired UsuarioAdminRepository usuarios;
    @Autowired PasswordEncoder codificador;
    @Autowired ProdutoRepository produtos;

    private NavegadorDeTeste navegador;

    @BeforeEach
    void preparar() {
        usuarios.save(new UsuarioAdmin(EMAIL, "Dona", codificador.encode(SENHA)));
        navegador = new NavegadorDeTeste(mvc, PORTA_ADMIN_DE_TESTE);
    }

    private void entrar() throws Exception {
        navegador.enviar(LOGIN, LOGIN, "username", EMAIL, "password", SENHA);
    }

    private void cadastrar(String nome, String preco) throws Exception {
        navegador.enviar(NOVO, NOVO, "nome", nome, "precoEmReais", preco, "unidades", "1", "versao", "0");
    }

    @Test
    @DisplayName("sem sessão, nenhuma tela do painel abre")
    void semSessaoNadaAbre() throws Exception {
        for (String caminho : new String[]{LISTA, NOVO, "/admin/configuracao", "/admin/auditoria"}) {
            assertThat(navegador.abrir(caminho).getResponse().getStatus())
                    .as("acesso sem sessão a %s", caminho)
                    .isIn(302, 401, 403);
        }
    }

    @Test
    @DisplayName("com sessão, a lista abre")
    void comSessaoAbre() throws Exception {
        entrar();

        assertThat(navegador.abrir(LISTA).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("gravar sem token CSRF é recusado, mesmo autenticado")
    void gravarSemCsrfEhRecusado() throws Exception {
        entrar();

        int status = navegador.enviarSemToken(NOVO,
                "nome", "Cuia sem token", "precoEmReais", "89,90", "unidades", "1")
                .getResponse().getStatus();

        assertThat(status).isEqualTo(403);
        assertThat(produtos.count()).as("nada pode ter sido gravado").isZero();
    }

    @Test
    @DisplayName("o cadastro grava e redireciona para a edição")
    void cadastraProduto() throws Exception {
        entrar();

        cadastrar("Cuia Gold em madeira", "89,90");

        assertThat(produtos.findBySlugAndExcluidoEmIsNull("cuia-gold-em-madeira")).isPresent();
    }

    @Test
    @DisplayName("script no nome sai escapado na lista, não executa")
    void xssNoNomeSaiEscapado() throws Exception {
        entrar();
        cadastrar("<script>alert(1)</script>", "89,90");

        String corpo = navegador.corpoDe(LISTA);

        assertThat(corpo)
                .as("Thymeleaf escapa por padrão; th:utext desfaria isso")
                .doesNotContain("<script>alert(1)</script>")
                .contains("&lt;script&gt;");
    }

    @Test
    @DisplayName("script na descrição também sai escapado")
    void xssNaDescricaoSaiEscapado() throws Exception {
        entrar();
        navegador.enviar(NOVO, NOVO,
                "nome", "Cuia com descrição",
                "precoEmReais", "89,90",
                "unidades", "1",
                "versao", "0",
                "descricao", "<img src=x onerror=alert(1)>");

        var produto = produtos.findBySlugAndExcluidoEmIsNull("cuia-com-descricao").orElseThrow();
        String corpo = navegador.corpoDe("/admin/produtos/" + produto.getId());

        assertThat(corpo)
                .doesNotContain("<img src=x onerror=alert(1)>")
                .contains("&lt;img");
    }

    @Test
    @DisplayName("descrição além de 4000 caracteres não é gravada")
    void descricaoLongaNaoEhGravada() throws Exception {
        entrar();

        navegador.enviar(NOVO, NOVO,
                "nome", "Cuia de descrição longa",
                "precoEmReais", "89,90",
                "unidades", "1",
                "versao", "0",
                "descricao", "x".repeat(4001));

        assertThat(produtos.count())
                .as("o limite da validação espelha o CHECK da migração")
                .isZero();
    }

    @Test
    @DisplayName("preço inválido volta com mensagem e não grava")
    void precoInvalidoNaoGrava() throws Exception {
        entrar();

        cadastrar("Cuia de preço torto", "muito barato");

        assertThat(produtos.count()).isZero();
    }

    @Test
    @DisplayName("publicar sem foto é recusado com mensagem, não com erro interno")
    void publicarSemFotoEhRecusado() throws Exception {
        entrar();
        cadastrar("Cuia sem foto nenhuma", "89,90");
        var produto = produtos.findBySlugAndExcluidoEmIsNull("cuia-sem-foto-nenhuma").orElseThrow();

        navegador.enviar("/admin/produtos/" + produto.getId(),
                "/admin/produtos/" + produto.getId() + "/publicar", "publicar", "true");

        assertThat(produtos.findById(produto.getId()).orElseThrow().isPublicado()).isFalse();
    }

    @Test
    @DisplayName("excluir é lógico: some da lista, fica no banco")
    void excluirEhLogico() throws Exception {
        entrar();
        cadastrar("Cuia a sumir", "89,90");
        var produto = produtos.findBySlugAndExcluidoEmIsNull("cuia-a-sumir").orElseThrow();

        navegador.enviar(LISTA, "/admin/produtos/" + produto.getId() + "/excluir");

        assertThat(produtos.findById(produto.getId()).orElseThrow().getExcluidoEm()).isNotNull();
        assertThat(navegador.corpoDe(LISTA)).doesNotContain("Cuia a sumir");
    }

    @Test
    @DisplayName("nenhuma tela do painel revela versão de framework ou rastro de pilha")
    void painelNaoVazaDetalheInterno() throws Exception {
        entrar();
        cadastrar("Cuia comum", "89,90");
        var produto = produtos.findBySlugAndExcluidoEmIsNull("cuia-comum").orElseThrow();

        for (String caminho : new String[]{LISTA, NOVO, "/admin/produtos/" + produto.getId()}) {
            assertThat(navegador.corpoDe(caminho))
                    .as("tela %s", caminho)
                    .doesNotContainIgnoringCase("springframework")
                    .doesNotContainIgnoringCase("org.hibernate")
                    .doesNotContainIgnoringCase("tomcat")
                    .doesNotContain("Exception");
        }
    }

    @Test
    @DisplayName("identificador inexistente não revela se o produto já existiu")
    void identificadorInexistenteNaoVazaNada() throws Exception {
        entrar();

        var resposta = navegador.abrir("/admin/produtos/" + java.util.UUID.randomUUID());

        assertThat(resposta.getResponse().getStatus()).isIn(302, 404);
        assertThat(resposta.getResponse().getContentAsString()).doesNotContain("Exception");
    }
}
