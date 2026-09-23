package br.com.chimaclub.publico;

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
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

@AutoConfigureMockMvc
class HomeTest extends BancoDeTesteBase {

    private static final UUID MADEIRA = UUID.fromString("01920000-0000-7000-8000-000000000001");

    @Autowired MockMvc mvc;
    @Autowired ProdutoService produtoService;
    @Autowired ProdutoRepository produtos;
    @Autowired ProdutoFotoRepository fotos;

    private UUID publicar(String nome, String preco, int unidades) {
        ProdutoForm form = new ProdutoForm();
        form.setNome(nome);
        form.setPrecoEmReais(preco);
        form.setUnidades(unidades);
        form.setCategoriaId(MADEIRA);
        UUID id = produtoService.criar(form, null, null);

        Produto produto = produtos.findById(id).orElseThrow();
        ProdutoFoto foto = new ProdutoFoto(produto,
                UUID.randomUUID() + "-media.webp", UUID.randomUUID() + "-mini.webp",
                1200, 1500, 50_000, "image/webp");
        foto.setPrincipal(true);
        fotos.save(foto);

        produtoService.publicar(id, true, null, null);
        return id;
    }

    private String corpoDe(String caminho) throws Exception {
        return mvc.perform(get(caminho)).andReturn().getResponse().getContentAsString();
    }

    @Test
    @DisplayName("a home abre sem sessão e mostra os produtos publicados")
    void homeAbreSemSessao() throws Exception {
        publicar("Cuia Gold em madeira", "89,90", 3);

        var resposta = mvc.perform(get("/")).andReturn().getResponse();

        assertThat(resposta.getStatus()).isEqualTo(200);
        assertThat(resposta.getContentAsString())
                .contains("Cuia Gold em madeira")
                .contains("89,90");
    }

    @Test
    @DisplayName("a home traz a identidade da marca")
    void homeTrazAIdentidade() throws Exception {
        String corpo = corpoDe("/");

        assertThat(corpo)
                .contains("/img/logo-chimaclub.png")
                .contains("/css/chimaclub.css")
                .contains("Seu tempo de qualidade merece artefatos à altura");
    }

    @Test
    @DisplayName("a home não mostra rascunho nem produto excluído")
    void homeEscondeOQueNaoEstaPublicado() throws Exception {
        publicar("Cuia publicada", "89,90", 1);

        ProdutoForm rascunho = new ProdutoForm();
        rascunho.setNome("Cuia em rascunho");
        rascunho.setPrecoEmReais("89,90");
        rascunho.setUnidades(1);
        produtoService.criar(rascunho, null, null);

        UUID excluida = publicar("Cuia removida", "89,90", 1);
        produtoService.excluir(excluida, null, null);

        String corpo = corpoDe("/");

        assertThat(corpo).contains("Cuia publicada");
        assertThat(corpo)
                .as("o cliente não pode ver o que a loja ainda não publicou")
                .doesNotContain("Cuia em rascunho")
                .doesNotContain("Cuia removida");
    }

    @Test
    @DisplayName("produto sem unidades aparece como Esgotado e continua clicável")
    void esgotadoApareceEContinuaClicavel() throws Exception {
        publicar("Cuia esgotada", "89,90", 0);

        String corpo = corpoDe("/");

        assertThat(corpo).contains("Esgotado");
        assertThat(corpo)
                .as("§6.4: zero unidades não esconde o produto")
                .contains("/produto/cuia-esgotada");
    }

    @Test
    @DisplayName("última unidade é anunciada na grade")
    void ultimaUnidadeApareceNaGrade() throws Exception {
        publicar("Cuia derradeira", "89,90", 1);

        assertThat(corpoDe("/")).contains("Última unidade");
    }

    @Test
    @DisplayName("os produtos vêm agrupados pela moldura da categoria")
    void produtosVemAgrupadosPorCategoria() throws Exception {
        publicar("Cuia agrupada", "89,90", 1);

        assertThat(corpoDe("/"))
                .contains("Cuias em madeira")
                .contains("class=\"categoria\"")
                .contains("class=\"moldura\"");
    }

    @Test
    @DisplayName("a busca por ?q= funciona sem JavaScript, devolvendo a página inteira")
    void buscaFuncionaSemJavaScript() throws Exception {
        publicar("Cuia Gold em madeira", "89,90", 1);
        publicar("Cuia Snow em madeira", "89,90", 1);

        String corpo = corpoDe("/?q=gold");

        assertThat(corpo)
                .as("sem JavaScript o formulário faz um GET comum e a página volta filtrada")
                .contains("<!DOCTYPE html>")
                .contains("Cuia Gold em madeira")
                .doesNotContain("Cuia Snow em madeira");
    }

    @Test
    @DisplayName("o HTMX recebe só o fragmento da grade, sem a página em volta")
    void htmxRecebeApenasOFragmento() throws Exception {
        publicar("Cuia Gold em madeira", "89,90", 1);

        String fragmento = mvc.perform(get("/busca").param("q", "gold").header("HX-Request", "true"))
                              .andReturn().getResponse().getContentAsString();

        assertThat(fragmento)
                .contains("Cuia Gold em madeira")
                .contains("id=\"grade\"");
        assertThat(fragmento)
                .as("trocar só o bloco é o ponto de usar HTMX")
                .doesNotContain("<!DOCTYPE")
                .doesNotContain("<head>")
                .doesNotContain("logo-chimaclub");
    }

    @Test
    @DisplayName("/busca sem o cabeçalho do HTMX devolve a página inteira")
    void buscaSemHtmxDevolveAPaginaInteira() throws Exception {
        publicar("Cuia Gold em madeira", "89,90", 1);

        assertThat(corpoDe("/busca?q=gold"))
                .as("quem chegar nesse endereço direto precisa ver uma página, não um pedaço")
                .contains("<!DOCTYPE html>")
                .contains("Cuia Gold em madeira");
    }

    @Test
    @DisplayName("o termo de busca volta escapado para a tela")
    void termoDeBuscaEhEscapado() throws Exception {
        // O termo vai por .param, com o valor já decodificado. Embutir a
        // forma percent-encoded na URL do MockMvc não funciona: ele a passa
        // adiante como texto literal, e o teste mediria o escape de "%3C" em
        // vez do escape de "<".
        String corpo = mvc.perform(get("/").param("q", "<script>alert(1)</script>"))
                          .andReturn().getResponse().getContentAsString();

        assertThat(corpo)
                .as("o termo é entrada do cliente e reaparece na página, no campo e no aviso")
                .doesNotContain("<script>alert(1)</script>")
                .contains("&lt;script&gt;");
    }

    @Test
    @DisplayName("aspas no termo não escapam do atributo value do campo")
    void aspasNoTermoNaoEscapamDoAtributo() throws Exception {
        String corpo = mvc.perform(get("/").param("q", "\" onfocus=\"alert(1)"))
                          .andReturn().getResponse().getContentAsString();

        assertThat(corpo)
                .as("sem escape de aspas, o termo fecharia o atributo e viraria evento")
                .doesNotContain("onfocus=\"alert(1)\"")
                .contains("&quot;");
    }

    @Test
    @DisplayName("busca sem resultado explica, em vez de mostrar página vazia")
    void buscaSemResultadoExplica() throws Exception {
        publicar("Cuia Gold em madeira", "89,90", 1);

        assertThat(corpoDe("/?q=bicicleta"))
                .contains("Nada encontrado")
                .doesNotContain("Cuia Gold em madeira");
    }

    @Test
    @DisplayName("toda imagem da home tem texto alternativo")
    void todaImagemTemTextoAlternativo() throws Exception {
        publicar("Cuia com foto", "89,90", 1);

        String corpo = corpoDe("/");

        java.util.regex.Matcher imagens =
                java.util.regex.Pattern.compile("<img\\b[^>]*>").matcher(corpo);
        while (imagens.find()) {
            assertThat(imagens.group())
                    .as("requisito não negociável da §7")
                    .contains("alt=");
        }
    }

    @Test
    @DisplayName("a home não revela nada do que roda por trás")
    void homeNaoVazaDetalheInterno() throws Exception {
        publicar("Cuia comum", "89,90", 1);

        assertThat(corpoDe("/"))
                .doesNotContainIgnoringCase("springframework")
                .doesNotContainIgnoringCase("tomcat")
                .doesNotContain("Exception")
                .as("comentário de HTML seria entregue ao visitante")
                .doesNotContain("<!--");
    }
}
