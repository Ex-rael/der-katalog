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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

/**
 * O caminho completo que a dona da loja percorre para pôr um produto no ar:
 * entrar, cadastrar, enviar foto, publicar, e ver a peça no catálogo.
 *
 * É o teste que responde "dá para usar isto?". Os outros provam regras
 * isoladas; este prova que elas se encaixam.
 */
@AutoConfigureMockMvc
class CadastroPeloPainelTest extends BancoDeTesteBase {

    private static final String LOGIN = "/admin/login";
    private static final String NOVO = "/admin/produtos/novo";
    private static final String EMAIL = "dona@chimaclub.com.br";
    private static final String SENHA = "uma senha longa o bastante";
    private static final String MADEIRA = "01920000-0000-7000-8000-000000000001";

    @Autowired MockMvc mvc;
    @Autowired UsuarioAdminRepository usuarios;
    @Autowired PasswordEncoder codificador;
    @Autowired ProdutoRepository produtos;
    @Autowired ProdutoFotoRepository fotos;

    private NavegadorDeTeste painel;

    @BeforeEach
    void entrar() throws Exception {
        usuarios.save(new UsuarioAdmin(EMAIL, "Dona", codificador.encode(SENHA)));
        painel = new NavegadorDeTeste(mvc, PORTA_ADMIN_DE_TESTE);
        painel.enviar(LOGIN, LOGIN, "username", EMAIL, "password", SENHA);
    }

    /** Uma foto de verdade: JPEG colorido, do tamanho de uma foto de produto. */
    private static MockMultipartFile fotoDeProduto(String nome) throws Exception {
        BufferedImage imagem = new BufferedImage(1200, 1500, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = imagem.createGraphics();
        g.setColor(new Color(0x84, 0x43, 0x1D));
        g.fillRect(0, 0, 1200, 1500);
        g.dispose();

        ByteArrayOutputStream saida = new ByteArrayOutputStream();
        ImageIO.write(imagem, "jpg", saida);
        return new MockMultipartFile("arquivos", nome, "image/jpeg", saida.toByteArray());
    }

    private UUID cadastrar(String nome, String preco, String unidades) throws Exception {
        painel.enviar(NOVO, NOVO,
                "nome", nome,
                "precoEmReais", preco,
                "unidades", unidades,
                "categoriaId", MADEIRA,
                "versao", "0",
                "descricao", "Peça feita à mão, com acabamento em resina.");

        return produtos.findAll().stream()
                .filter(p -> p.getNome().equals(nome))
                .findFirst().orElseThrow().getId();
    }

    private void enviarFoto(UUID produtoId, String arquivo) throws Exception {
        String token = painel.tokenDe("/admin/produtos/" + produtoId);
        mvc.perform(multipart("/admin/produtos/" + produtoId + "/fotos")
                .file(fotoDeProduto(arquivo))
                .param("_csrf", token)
                .session(painel.getSessao())
                .with(r -> { r.setLocalPort(PORTA_ADMIN_DE_TESTE); return r; }));
    }

    // ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("o caminho completo: cadastrar, enviar foto, publicar e ver no catálogo")
    void caminhoCompletoDoCadastro() throws Exception {
        UUID id = cadastrar("Cuia Verde Musgo em madeira", "94,90", "4");

        Produto recemCriado = produtos.findById(id).orElseThrow();
        assertThat(recemCriado.getPrecoCentavos()).isEqualTo(9490L);
        assertThat(recemCriado.getSlug()).isEqualTo("cuia-verde-musgo-em-madeira");
        assertThat(recemCriado.isPublicado())
                .as("produto novo nasce rascunho: nada vai ao ar por engano")
                .isFalse();

        // Sem foto, publicar precisa ser recusado.
        painel.enviar("/admin/produtos/" + id, "/admin/produtos/" + id + "/publicar",
                "publicar", "true");
        assertThat(produtos.findById(id).orElseThrow().isPublicado())
                .as("§6.3: publicar exige ao menos uma foto")
                .isFalse();

        enviarFoto(id, "cuia-verde.jpg");
        assertThat(fotos.countByProdutoId(id)).isEqualTo(1);
        assertThat(fotos.findByProdutoIdOrderByOrdemAsc(id).getFirst().isPrincipal())
                .as("§6.5: a primeira foto vira principal sozinha")
                .isTrue();

        painel.enviar("/admin/produtos/" + id, "/admin/produtos/" + id + "/publicar",
                "publicar", "true");
        assertThat(produtos.findById(id).orElseThrow().isPublicado()).isTrue();

        // E agora o cliente enxerga.
        String home = mvc.perform(get("/")).andReturn().getResponse().getContentAsString();
        assertThat(home)
                .contains("Cuia Verde Musgo em madeira")
                .contains("94,90");

        var pagina = mvc.perform(get("/produto/cuia-verde-musgo-em-madeira")).andReturn().getResponse();
        assertThat(pagina.getStatus()).isEqualTo(200);
        assertThat(pagina.getContentAsString())
                .contains("Cuia Verde Musgo em madeira")
                .contains("4 unidades disponíveis")
                .contains("Comprar pelo WhatsApp");
    }

    @Test
    @DisplayName("a foto enviada vira três arquivos servidos pelo catálogo")
    void fotoViraTresArquivosServidos() throws Exception {
        UUID id = cadastrar("Cuia com foto de verdade", "89,90", "1");
        enviarFoto(id, "foto.jpg");

        ProdutoFoto foto = fotos.findByProdutoIdOrderByOrdemAsc(id).getFirst();

        assertThat(foto.getArquivoMini()).matches("[0-9a-f-]{36}-mini\\.(webp|jpg)");
        assertThat(foto.getArquivo()).matches("[0-9a-f-]{36}-media\\.(webp|jpg)");
        assertThat(foto.getLargura()).isEqualTo(1200);

        // E o catálogo público serve os arquivos gerados.
        for (String arquivo : new String[]{foto.getArquivoMini(), foto.getArquivo()}) {
            assertThat(mvc.perform(get("/fotos/" + arquivo)).andReturn().getResponse().getStatus())
                    .as("arquivo %s", arquivo)
                    .isEqualTo(200);
        }
    }

    @Test
    @DisplayName("editar preço e unidades reflete no catálogo")
    void edicaoRefleteNoCatalogo() throws Exception {
        UUID id = cadastrar("Cuia a reajustar", "89,90", "3");
        enviarFoto(id, "foto.jpg");
        painel.enviar("/admin/produtos/" + id, "/admin/produtos/" + id + "/publicar", "publicar", "true");

        long versao = produtos.findById(id).orElseThrow().getVersao();
        painel.enviar("/admin/produtos/" + id, "/admin/produtos/" + id,
                "nome", "Cuia a reajustar",
                "precoEmReais", "119,90",
                "unidades", "1",
                "categoriaId", MADEIRA,
                "slug", "cuia-a-reajustar",
                "versao", String.valueOf(versao),
                "publicado", "true");

        String pagina = mvc.perform(get("/produto/cuia-a-reajustar"))
                           .andReturn().getResponse().getContentAsString();

        assertThat(pagina).contains("119,90").contains("Última unidade");
    }

    @Test
    @DisplayName("duplicar cria um rascunho que a dona completa e publica")
    void duplicarCriaRascunhoUsavel() throws Exception {
        UUID original = cadastrar("Cuia azul escuro em madeira", "89,90", "2");
        enviarFoto(original, "azul.jpg");
        painel.enviar("/admin/produtos/" + original, "/admin/produtos/" + original + "/publicar",
                "publicar", "true");

        painel.enviar("/admin/produtos", "/admin/produtos/" + original + "/duplicar");

        Produto copia = produtos.findAll().stream()
                .filter(p -> p.getNome().contains("cópia"))
                .findFirst().orElseThrow();

        assertThat(copia.getPrecoCentavos()).isEqualTo(8990L);
        assertThat(copia.isPublicado()).isFalse();
        assertThat(fotos.countByProdutoId(copia.getId())).isZero();

        // A dona ajusta o nome e envia a foto certa.
        painel.enviar("/admin/produtos/" + copia.getId(), "/admin/produtos/" + copia.getId(),
                "nome", "Cuia verde escuro em madeira",
                "precoEmReais", "89,90",
                "unidades", "2",
                "categoriaId", MADEIRA,
                "slug", copia.getSlug(),
                "versao", String.valueOf(copia.getVersao()));
        enviarFoto(copia.getId(), "verde.jpg");
        painel.enviar("/admin/produtos/" + copia.getId(),
                "/admin/produtos/" + copia.getId() + "/publicar", "publicar", "true");

        String home = mvc.perform(get("/")).andReturn().getResponse().getContentAsString();
        assertThat(home)
                .contains("Cuia azul escuro em madeira")
                .contains("Cuia verde escuro em madeira");
    }

    @Test
    @DisplayName("reordenar as fotos muda a ordem do carrossel na página")
    void reordenarMudaOCarrossel() throws Exception {
        UUID id = cadastrar("Cuia de três fotos", "89,90", "1");
        enviarFoto(id, "um.jpg");
        enviarFoto(id, "dois.jpg");
        enviarFoto(id, "tres.jpg");
        painel.enviar("/admin/produtos/" + id, "/admin/produtos/" + id + "/publicar", "publicar", "true");

        var antes = fotos.findByProdutoIdOrderByOrdemAsc(id);
        UUID ultima = antes.getLast().getId();

        painel.enviar("/admin/produtos/" + id,
                "/admin/produtos/" + id + "/fotos/" + ultima + "/mover", "direcao", "cima");

        assertThat(fotos.findByProdutoIdOrderByOrdemAsc(id))
                .extracting(ProdutoFoto::getId)
                .containsExactly(antes.get(0).getId(), ultima, antes.get(1).getId());
    }

    @Test
    @DisplayName("o filtro por categoria separa madeira de porongo na lista")
    void filtroPorCategoriaFunciona() throws Exception {
        cadastrar("Cuia de madeira aqui", "89,90", "1");

        painel.enviar(NOVO, NOVO,
                "nome", "Cuia de porongo ali",
                "precoEmReais", "99,90",
                "unidades", "1",
                "categoriaId", "01920000-0000-7000-8000-000000000002",
                "versao", "0");

        String soMadeira = painel.corpoDe("/admin/produtos?categoriaId=" + MADEIRA);

        assertThat(soMadeira).contains("Cuia de madeira aqui");
        assertThat(soMadeira).doesNotContain("Cuia de porongo ali");
    }

    @Test
    @DisplayName("marcar destaque faz a peça aparecer na seção de destaques")
    void destaqueApareceNaHome() throws Exception {
        UUID id = cadastrar("Cuia destacada", "89,90", "2");
        enviarFoto(id, "foto.jpg");

        long versao = produtos.findById(id).orElseThrow().getVersao();
        painel.enviar("/admin/produtos/" + id, "/admin/produtos/" + id,
                "nome", "Cuia destacada",
                "precoEmReais", "89,90",
                "unidades", "2",
                "categoriaId", MADEIRA,
                "slug", "cuia-destacada",
                "versao", String.valueOf(versao),
                "destaque", "true",
                "publicado", "true");

        assertThat(produtos.findById(id).orElseThrow().isDestaque()).isTrue();
        assertThat(mvc.perform(get("/")).andReturn().getResponse().getContentAsString())
                .contains("Destaques");
    }

    @Test
    @DisplayName("trocar o endereço do produto muda o link, e o antigo deixa de responder")
    void trocarOEnderecoMudaOLink() throws Exception {
        UUID id = cadastrar("Cuia de endereço trocado", "89,90", "1");
        enviarFoto(id, "foto.jpg");
        painel.enviar("/admin/produtos/" + id, "/admin/produtos/" + id + "/publicar", "publicar", "true");

        assertThat(mvc.perform(get("/produto/cuia-de-endereco-trocado"))
                      .andReturn().getResponse().getStatus()).isEqualTo(200);

        long versao = produtos.findById(id).orElseThrow().getVersao();
        painel.enviar("/admin/produtos/" + id, "/admin/produtos/" + id,
                "nome", "Cuia de endereço trocado",
                "precoEmReais", "89,90",
                "unidades", "1",
                "categoriaId", MADEIRA,
                "slug", "cuia-especial-2026",
                "versao", String.valueOf(versao),
                "publicado", "true");

        assertThat(mvc.perform(get("/produto/cuia-especial-2026"))
                      .andReturn().getResponse().getStatus()).isEqualTo(200);
        assertThat(mvc.perform(get("/produto/cuia-de-endereco-trocado"))
                      .andReturn().getResponse().getStatus())
                .as("é por isso que o aviso na tela diz que trocar quebra links já compartilhados")
                .isEqualTo(404);
    }

    @Test
    @DisplayName("o catálogo filtra por categoria pela URL pública")
    void catalogoFiltraPorCategoriaNaUrl() throws Exception {
        UUID madeira = cadastrar("Cuia de madeira pública", "89,90", "1");
        enviarFoto(madeira, "m.jpg");
        painel.enviar("/admin/produtos/" + madeira, "/admin/produtos/" + madeira + "/publicar",
                "publicar", "true");

        painel.enviar(NOVO, NOVO,
                "nome", "Cuia de porongo pública",
                "precoEmReais", "99,90", "unidades", "1",
                "categoriaId", "01920000-0000-7000-8000-000000000002", "versao", "0");
        UUID porongo = produtos.findAll().stream()
                .filter(p -> p.getNome().contains("porongo pública"))
                .findFirst().orElseThrow().getId();
        enviarFoto(porongo, "p.jpg");
        painel.enviar("/admin/produtos/" + porongo, "/admin/produtos/" + porongo + "/publicar",
                "publicar", "true");

        String soMadeira = mvc.perform(get("/").param("categoria", "cuias-em-madeira"))
                              .andReturn().getResponse().getContentAsString();

        assertThat(soMadeira).contains("Cuia de madeira pública");
        assertThat(soMadeira).doesNotContain("Cuia de porongo pública");
    }
}
