package br.com.chimaclub.catalogo;

import br.com.chimaclub.BancoDeTesteBase;
import br.com.chimaclub.catalogo.dto.ProdutoForm;
import br.com.chimaclub.catalogo.service.CatalogoService;
import br.com.chimaclub.catalogo.service.FotoService;
import br.com.chimaclub.catalogo.service.ProdutoService;
import br.com.chimaclub.comum.RegraDeNegocioException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * As seis funcionalidades que o documento de projeto pede e que a primeira
 * implementação deixou de fora. Reunidas aqui porque foram achadas juntas,
 * numa auditoria contra o spec.
 */
class LacunasDoPainelTest extends BancoDeTesteBase {

    private static final UUID MADEIRA = UUID.fromString("01920000-0000-7000-8000-000000000001");
    private static final UUID PORONGO = UUID.fromString("01920000-0000-7000-8000-000000000002");

    @Autowired ProdutoService servico;
    @Autowired FotoService fotoService;
    @Autowired CatalogoService catalogo;
    @Autowired ProdutoRepository produtos;
    @Autowired ProdutoFotoRepository fotos;

    private UUID criar(String nome, UUID categoria) {
        ProdutoForm form = new ProdutoForm();
        form.setNome(nome);
        form.setPrecoEmReais("89,90");
        form.setUnidades(3);
        form.setCategoriaId(categoria);
        form.setDescricao("Peça feita à mão.");
        return servico.criar(form, null, null);
    }

    private UUID darFoto(UUID produtoId) {
        Produto produto = produtos.findById(produtoId).orElseThrow();
        UUID base = UUID.randomUUID();
        ProdutoFoto foto = new ProdutoFoto(produto, base + "-media.webp", base + "-mini.webp",
                1200, 1500, 1000, "image/webp");
        foto.setPrincipal(fotos.countByProdutoId(produtoId) == 0);
        foto.setOrdem((int) fotos.countByProdutoId(produtoId));
        return fotos.save(foto).getId();
    }

    private ProdutoForm formularioDe(UUID id) {
        Produto produto = produtos.findById(id).orElseThrow();
        ProdutoForm form = new ProdutoForm();
        form.setNome(produto.getNome());
        form.setPrecoEmReais(br.com.chimaclub.comum.Preco.paraCampo(produto.getPrecoCentavos()));
        form.setUnidades(produto.getUnidades());
        form.setVersao(produto.getVersao());
        form.setSlug(produto.getSlug());
        if (produto.getCategoria() != null) {
            form.setCategoriaId(produto.getCategoria().getId());
        }
        return form;
    }

    // ───────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("1. Duplicar produto (§4.3)")
    class Duplicar {

        @Test
        @DisplayName("copia os dados e gera um slug novo")
        void copiaOsDados() {
            UUID original = criar("Cuia azul escuro em madeira", MADEIRA);

            UUID copia = servico.duplicar(original, null, null);

            Produto copiado = produtos.findById(copia).orElseThrow();
            Produto antigo = produtos.findById(original).orElseThrow();

            assertThat(copiado.getId()).isNotEqualTo(original);
            assertThat(copiado.getPrecoCentavos()).isEqualTo(antigo.getPrecoCentavos());
            assertThat(copiado.getDescricao()).isEqualTo(antigo.getDescricao());
            assertThat(copiado.getCategoria().getId()).isEqualTo(MADEIRA);
            assertThat(copiado.getSlug())
                    .as("dois produtos não podem disputar o mesmo endereço")
                    .isNotEqualTo(antigo.getSlug());
        }

        @Test
        @DisplayName("a cópia nasce como rascunho, nunca publicada")
        void copiaNasceComoRascunho() {
            UUID original = criar("Cuia publicada", MADEIRA);
            darFoto(original);
            servico.publicar(original, true, null, null);

            UUID copia = servico.duplicar(original, null, null);

            assertThat(produtos.findById(copia).orElseThrow().isPublicado())
                    .as("uma cópia publicada sozinha apareceria no catálogo com o nome errado")
                    .isFalse();
        }

        @Test
        @DisplayName("a cópia NÃO leva as fotos do original")
        void copiaNaoLevaAsFotos() {
            UUID original = criar("Cuia azul escuro em madeira", MADEIRA);
            darFoto(original);
            darFoto(original);

            UUID copia = servico.duplicar(original, null, null);

            assertThat(fotos.countByProdutoId(copia))
                    .as("duplicar serve para criar a peça irmã, de outra cor — "
                        + "levar a foto junto poria a foto errada no produto novo")
                    .isZero();
            assertThat(fotos.countByProdutoId(original))
                    .as("e o original não pode perder nada")
                    .isEqualTo(2);
        }

        @Test
        @DisplayName("o nome da cópia avisa que é cópia")
        void nomeDaCopiaAvisa() {
            UUID copia = servico.duplicar(criar("Cuia Gold em madeira", MADEIRA), null, null);

            assertThat(produtos.findById(copia).orElseThrow().getNome())
                    .as("no meio de dezessete cuias parecidas, duas com o mesmo nome confundem")
                    .contains("Cuia Gold em madeira")
                    .contains("cópia");
        }

        @Test
        @DisplayName("não duplica produto excluído")
        void naoDuplicaExcluido() {
            UUID id = criar("Cuia sumida", MADEIRA);
            servico.excluir(id, null, null);

            assertThatThrownBy(() -> servico.duplicar(id, null, null))
                    .isInstanceOf(RegraDeNegocioException.class);
        }
    }

    // ───────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("2. Reordenar fotos (§4.3, §5.2)")
    class Reordenar {

        @Test
        @DisplayName("mover para cima troca a foto de lugar com a anterior")
        void moverParaCima() {
            UUID produto = criar("Cuia com fotos", MADEIRA);
            UUID a = darFoto(produto), b = darFoto(produto), c = darFoto(produto);

            fotoService.mover(produto, b, "cima", null, null);

            assertThat(fotos.findByProdutoIdOrderByOrdemAsc(produto))
                    .extracting(ProdutoFoto::getId)
                    .containsExactly(b, a, c);
        }

        @Test
        @DisplayName("mover para baixo troca com a seguinte")
        void moverParaBaixo() {
            UUID produto = criar("Cuia com fotos", MADEIRA);
            UUID a = darFoto(produto), b = darFoto(produto), c = darFoto(produto);

            fotoService.mover(produto, a, "baixo", null, null);

            assertThat(fotos.findByProdutoIdOrderByOrdemAsc(produto))
                    .extracting(ProdutoFoto::getId)
                    .containsExactly(b, a, c);
        }

        @Test
        @DisplayName("mover a primeira para cima não faz nada, e não quebra")
        void moverNoLimiteNaoQuebra() {
            UUID produto = criar("Cuia com fotos", MADEIRA);
            UUID a = darFoto(produto), b = darFoto(produto);

            fotoService.mover(produto, a, "cima", null, null);
            fotoService.mover(produto, b, "baixo", null, null);

            assertThat(fotos.findByProdutoIdOrderByOrdemAsc(produto))
                    .extracting(ProdutoFoto::getId)
                    .containsExactly(a, b);
        }

        @Test
        @DisplayName("a reordenação em lote grava a ordem inteira")
        void reordenaEmLote() {
            UUID produto = criar("Cuia com fotos", MADEIRA);
            UUID a = darFoto(produto), b = darFoto(produto), c = darFoto(produto);

            fotoService.reordenar(produto, List.of(c, a, b), null, null);

            assertThat(fotos.findByProdutoIdOrderByOrdemAsc(produto))
                    .extracting(ProdutoFoto::getId)
                    .containsExactly(c, a, b);
        }

        @Test
        @DisplayName("não reordena foto de outro produto passando o identificador dele")
        void naoReordenaFotoDeOutroProduto() {
            UUID um = criar("Cuia um", MADEIRA);
            UUID dois = criar("Cuia dois", MADEIRA);
            UUID fotoDoUm = darFoto(um);
            darFoto(dois);

            assertThatThrownBy(() -> fotoService.mover(dois, fotoDoUm, "cima", null, null))
                    .as("o identificador da foto sozinho não pode autorizar a operação")
                    .isInstanceOf(RegraDeNegocioException.class);
        }

        @Test
        @DisplayName("direção desconhecida é recusada")
        void direcaoDesconhecidaEhRecusada() {
            UUID produto = criar("Cuia com fotos", MADEIRA);
            UUID a = darFoto(produto);

            assertThatThrownBy(() -> fotoService.mover(produto, a, "diagonal", null, null))
                    .isInstanceOf(RegraDeNegocioException.class);
        }
    }

    // ───────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("4. Trocar o slug pelo painel (§6.2)")
    class TrocarSlug {

        @Test
        @DisplayName("o slug muda quando informado explicitamente")
        void slugMudaQuandoInformado() {
            UUID id = criar("Cuia Gold", MADEIRA);

            ProdutoForm form = formularioDe(id);
            form.setSlug("cuia-dourada-especial");
            servico.alterar(id, form, null, null);

            assertThat(produtos.findById(id).orElseThrow().getSlug())
                    .isEqualTo("cuia-dourada-especial");
        }

        @Test
        @DisplayName("o slug informado é normalizado, não aceito como veio")
        void slugInformadoEhNormalizado() {
            UUID id = criar("Cuia Gold", MADEIRA);

            ProdutoForm form = formularioDe(id);
            form.setSlug("  Cuía DOURADA / especial!  ");
            servico.alterar(id, form, null, null);

            assertThat(produtos.findById(id).orElseThrow().getSlug())
                    .as("o slug vai para a URL pública; o alfabeto de saída é restrito")
                    .isEqualTo("cuia-dourada-especial");
        }

        @Test
        @DisplayName("slug já usado por outro produto é recusado")
        void slugRepetidoEhRecusado() {
            UUID primeiro = criar("Cuia primeira", MADEIRA);
            UUID segundo = criar("Cuia segunda", MADEIRA);
            String slugDoPrimeiro = produtos.findById(primeiro).orElseThrow().getSlug();

            ProdutoForm form = formularioDe(segundo);
            form.setSlug(slugDoPrimeiro);

            assertThatThrownBy(() -> servico.alterar(segundo, form, null, null))
                    .isInstanceOf(RegraDeNegocioException.class)
                    .hasMessageContaining("endereço");
        }

        @Test
        @DisplayName("deixar o slug igual não é tratado como conflito consigo mesmo")
        void slugIgualNaoEhConflito() {
            UUID id = criar("Cuia Gold", MADEIRA);
            ProdutoForm form = formularioDe(id);

            servico.alterar(id, form, null, null);

            assertThat(produtos.findById(id).orElseThrow().getSlug()).isEqualTo("cuia-gold");
        }

        @Test
        @DisplayName("a troca de slug vai para a auditoria, porque quebra links já compartilhados")
        void trocaDeSlugVaiParaAuditoria(@Autowired br.com.chimaclub.admin.EventoAuditoriaRepository eventos) {
            UUID id = criar("Cuia Gold", MADEIRA);

            ProdutoForm form = formularioDe(id);
            form.setSlug("outro-endereco");
            servico.alterar(id, form, null, null);

            String detalhes = eventos.findAll().stream()
                    .map(e -> String.valueOf(e.getDetalhes())).reduce("", String::concat);

            assertThat(detalhes).contains("cuia-gold").contains("outro-endereco");
        }
    }

    // ───────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("5 e 6. Categoria e destaque no catálogo público (§5.1, §3.3)")
    class CatalogoPublico {

        private UUID publicar(String nome, UUID categoria, boolean destaque) {
            UUID id = criar(nome, categoria);
            darFoto(id);
            if (destaque) {
                Produto p = produtos.findById(id).orElseThrow();
                p.setDestaque(true);
                produtos.saveAndFlush(p);
            }
            servico.publicar(id, true, null, null);
            return id;
        }

        @Test
        @DisplayName("filtrar por categoria devolve só o que é daquela categoria")
        void filtraPorCategoria() {
            publicar("Cuia de madeira", MADEIRA, false);
            publicar("Cuia de porongo", PORONGO, false);

            assertThat(catalogo.porCategoria("cuias-em-madeira"))
                    .extracting(dto -> dto.slug())
                    .containsExactly("cuia-de-madeira");
        }

        @Test
        @DisplayName("categoria inexistente devolve lista vazia, não o catálogo inteiro")
        void categoriaInexistenteDevolveVazio() {
            publicar("Cuia qualquer", MADEIRA, false);

            assertThat(catalogo.porCategoria("nao-existe"))
                    .as("devolver tudo seria pior que devolver nada: esconde o engano")
                    .isEmpty();
        }

        @Test
        @DisplayName("os destaques são separados do resto")
        void destaquesSaoSeparados() {
            publicar("Cuia comum", MADEIRA, false);
            publicar("Cuia destacada", MADEIRA, true);

            assertThat(catalogo.destaques())
                    .extracting(dto -> dto.slug())
                    .containsExactly("cuia-destacada");
        }

        @Test
        @DisplayName("destaque de produto despublicado não aparece")
        void destaqueDespublicadoNaoAparece() {
            UUID id = publicar("Cuia recolhida", MADEIRA, true);
            servico.publicar(id, false, null, null);

            assertThat(catalogo.destaques()).isEmpty();
        }

        @Test
        @DisplayName("sem nenhum destaque, a lista é vazia e a home não mostra a seção")
        void semDestaquesListaVazia() {
            publicar("Cuia comum", MADEIRA, false);

            assertThat(catalogo.destaques()).isEmpty();
        }
    }
}
