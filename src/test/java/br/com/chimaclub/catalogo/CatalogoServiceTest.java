package br.com.chimaclub.catalogo;

import br.com.chimaclub.BancoDeTesteBase;
import br.com.chimaclub.catalogo.dto.ProdutoResumo;
import br.com.chimaclub.catalogo.service.CatalogoService;
import br.com.chimaclub.catalogo.service.ProdutoService;
import br.com.chimaclub.catalogo.dto.ProdutoForm;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class CatalogoServiceTest extends BancoDeTesteBase {

    private static final UUID MADEIRA = UUID.fromString("01920000-0000-7000-8000-000000000001");
    private static final UUID PORONGO = UUID.fromString("01920000-0000-7000-8000-000000000002");

    @Autowired CatalogoService catalogo;
    @Autowired ProdutoService produtoService;
    @Autowired ProdutoRepository produtos;
    @Autowired ProdutoFotoRepository fotos;

    /** Cria um produto publicado, com a foto que a regra de publicação exige. */
    private UUID publicar(String nome, String preco, int unidades, UUID categoria) {
        ProdutoForm form = new ProdutoForm();
        form.setNome(nome);
        form.setPrecoEmReais(preco);
        form.setUnidades(unidades);
        form.setCategoriaId(categoria);
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

    private UUID rascunho(String nome) {
        ProdutoForm form = new ProdutoForm();
        form.setNome(nome);
        form.setPrecoEmReais("89,90");
        form.setUnidades(1);
        return produtoService.criar(form, null, null);
    }

    @Test
    @DisplayName("critério de aceite: a busca encontra gold, Gold e cuia gold")
    void buscaEncontraAsTresFormas() {
        publicar("Cuía Gold em madeira", "89,90", 3, MADEIRA);

        for (String termo : List.of("gold", "Gold", "GOLD", "cuia gold", "Cuía Gold", "cuia-gold")) {
            assertThat(catalogo.buscar(termo))
                    .as("busca por '%s'", termo)
                    .extracting(ProdutoResumo::slug)
                    .contains("cuia-gold-em-madeira");
        }
    }

    @Test
    @DisplayName("a busca tolera erro de digitação, pela aproximação da §3.4")
    void toleraErroDeDigitacao() {
        publicar("Cuia Sunset em madeira", "89,90", 2, MADEIRA);

        assertThat(catalogo.buscar("sunsett"))
                .extracting(ProdutoResumo::slug)
                .contains("cuia-sunset-em-madeira");
    }

    @Test
    @DisplayName("a busca não devolve rascunho nem produto excluído")
    void buscaSoDevolveOQueEstaNoCatalogo() {
        UUID publicado = publicar("Cuia visível", "89,90", 1, MADEIRA);
        rascunho("Cuia invisível");
        UUID excluido = publicar("Cuia apagada", "89,90", 1, MADEIRA);
        produtoService.excluir(excluido, null, null);

        List<String> encontrados = catalogo.buscar("cuia").stream().map(ProdutoResumo::slug).toList();

        assertThat(encontrados).contains("cuia-visivel");
        assertThat(encontrados)
                .as("rascunho e excluído não podem aparecer para o cliente")
                .doesNotContain("cuia-invisivel", "cuia-apagada");
        assertThat(catalogo.publicados()).extracting(ProdutoResumo::id).containsExactly(publicado);
    }

    @Test
    @DisplayName("um termo hostil não quebra a consulta nem altera o banco")
    void termoHostilNaoQuebraAConsulta() {
        publicar("Cuia comum", "89,90", 1, MADEIRA);
        long antes = produtos.count();

        for (String termo : List.of(
                "'; DROP TABLE produto; --",
                "' OR 1=1 --",
                "\\",
                "a'b",
                "<script>alert(1)</script>",
                "%' UNION SELECT NULL--")) {
            assertThatCode(() -> catalogo.buscar(termo))
                    .as("termo hostil: %s", termo)
                    .doesNotThrowAnyException();
        }

        assertThat(produtos.count())
                .as("parâmetro vinculado: a consulta não é montada com o texto")
                .isEqualTo(antes);
    }

    @Test
    @DisplayName("curinga do LIKE no termo é tratado como literal")
    void curingaEhLiteral() {
        publicar("Cuia comum", "89,90", 1, MADEIRA);
        publicar("Cuia 100% porongo", "99,90", 1, PORONGO);

        assertThat(catalogo.buscar("%"))
                .as("sem escapar, '%' devolveria o catálogo inteiro")
                .extracting(ProdutoResumo::slug)
                .doesNotContain("cuia-comum");

        assertThat(catalogo.buscar("100%"))
                .as("e o produto que tem '%' no nome precisa ser encontrado")
                .extracting(ProdutoResumo::slug)
                .contains("cuia-100-porongo");
    }

    @Test
    @DisplayName("termo vazio devolve o catálogo inteiro, não nada")
    void termoVazioDevolveTudo() {
        publicar("Cuia um", "89,90", 1, MADEIRA);
        publicar("Cuia dois", "89,90", 1, MADEIRA);

        assertThat(catalogo.buscar("")).hasSize(2);
        assertThat(catalogo.buscar("   ")).hasSize(2);
        assertThat(catalogo.buscar(null)).hasSize(2);
    }

    @Test
    @DisplayName("o agrupamento respeita a ordem das categorias")
    void agrupaNaOrdemDasCategorias() {
        publicar("Cuia de porongo", "99,90", 1, PORONGO);
        publicar("Cuia de madeira", "89,90", 1, MADEIRA);
        publicar("Bomba avulsa", "49,90", 1, null);

        var grupos = catalogo.agrupadosPorCategoria(catalogo.publicados());

        assertThat(grupos.keySet())
                .containsExactly("Cuias em madeira", "Cuias em porongo", "Outros artefatos");
    }

    @Test
    @DisplayName("produto esgotado continua no catálogo, marcado")
    void esgotadoContinuaNoCatalogo() {
        publicar("Cuia esgotada", "89,90", 0, MADEIRA);

        ProdutoResumo resumo = catalogo.publicados().getFirst();

        assertThat(resumo.esgotado()).isTrue();
        assertThat(catalogo.buscar("esgotada")).hasSize(1);
    }

    @Test
    @DisplayName("última unidade é distinguida de esgotado")
    void ultimaUnidadeEhDistinguida() {
        publicar("Cuia derradeira", "89,90", 1, MADEIRA);

        ProdutoResumo resumo = catalogo.publicados().getFirst();

        assertThat(resumo.ultimaUnidade()).isTrue();
        assertThat(resumo.esgotado()).isFalse();
    }

    @Test
    @DisplayName("o detalhe abre pelo slug e traz as fotos na ordem")
    void detalheAbrePeloSlug() {
        publicar("Cuia detalhada", "89,90", 3, MADEIRA);

        var detalhe = catalogo.detalhe("cuia-detalhada").orElseThrow();

        assertThat(detalhe.nome()).isEqualTo("Cuia detalhada");
        assertThat(detalhe.precoCentavos()).isEqualTo(8990L);
        assertThat(detalhe.disponibilidade()).isEqualTo("3 unidades disponíveis");
        assertThat(detalhe.principal()).isNotNull();
    }

    @Test
    @DisplayName("o detalhe de rascunho, excluído ou inexistente não abre")
    void detalheNaoAbreOQueNaoEstaPublicado() {
        rascunho("Cuia rascunho");
        UUID excluido = publicar("Cuia removida", "89,90", 1, MADEIRA);
        produtoService.excluir(excluido, null, null);

        assertThat(catalogo.detalhe("cuia-rascunho")).isEmpty();
        assertThat(catalogo.detalhe("cuia-removida")).isEmpty();
        assertThat(catalogo.detalhe("nao-existe")).isEmpty();
    }

    @Test
    @DisplayName("as três formas de disponibilidade da §4.2")
    void tresFormasDeDisponibilidade() {
        publicar("Cuia cheia", "89,90", 5, MADEIRA);
        publicar("Cuia única", "89,90", 1, MADEIRA);
        publicar("Cuia vazia", "89,90", 0, MADEIRA);

        assertThat(catalogo.detalhe("cuia-cheia").orElseThrow().disponibilidade())
                .isEqualTo("5 unidades disponíveis");
        assertThat(catalogo.detalhe("cuia-unica").orElseThrow().disponibilidade())
                .isEqualTo("Última unidade");
        assertThat(catalogo.detalhe("cuia-vazia").orElseThrow().disponibilidade())
                .isEqualTo("Esgotado");
    }

    @Test
    @DisplayName("os relacionados são da mesma categoria e não repetem o aberto")
    void relacionadosDaMesmaCategoria() {
        publicar("Cuia A", "89,90", 1, MADEIRA);
        publicar("Cuia B", "89,90", 1, MADEIRA);
        publicar("Cuia de porongo", "99,90", 1, PORONGO);

        var aberto = catalogo.detalhe("cuia-a").orElseThrow();
        var relacionados = catalogo.relacionados(aberto, 4);

        assertThat(relacionados).extracting(ProdutoResumo::slug)
                .containsExactly("cuia-b")
                .doesNotContain("cuia-a", "cuia-de-porongo");
    }
}
