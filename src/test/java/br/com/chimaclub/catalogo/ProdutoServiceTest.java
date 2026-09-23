package br.com.chimaclub.catalogo;

import br.com.chimaclub.BancoDeTesteBase;
import br.com.chimaclub.catalogo.dto.ProdutoForm;
import br.com.chimaclub.catalogo.service.ProdutoService;
import br.com.chimaclub.comum.RegraDeNegocioException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProdutoServiceTest extends BancoDeTesteBase {

    @Autowired ProdutoService servico;
    @Autowired ProdutoRepository produtos;
    @Autowired ProdutoFotoRepository fotos;

    private static final UUID CATEGORIA_MADEIRA =
            UUID.fromString("01920000-0000-7000-8000-000000000001");

    private ProdutoForm formulario(String nome, String precoEmReais) {
        ProdutoForm form = new ProdutoForm();
        form.setNome(nome);
        form.setPrecoEmReais(precoEmReais);
        form.setUnidades(3);
        return form;
    }

    private UUID criar(String nome, String preco) {
        return servico.criar(formulario(nome, preco), null, null);
    }

    /** Dá ao produto a foto que a regra de publicação exige. */
    private void darUmaFotoA(UUID produtoId) {
        Produto produto = produtos.findById(produtoId).orElseThrow();
        ProdutoFoto foto = new ProdutoFoto(produto,
                UUID.randomUUID() + "-media.webp", UUID.randomUUID() + "-mini.webp",
                1200, 1500, 50_000, "image/webp");
        foto.setPrincipal(true);
        fotos.save(foto);
    }

    @Test
    @DisplayName("o preço em reais com vírgula vira centavos exatos")
    void convertePrecoParaCentavos() {
        UUID id = criar("Cuia Gold", "89,90");

        assertThat(produtos.findById(id).orElseThrow().getPrecoCentavos()).isEqualTo(8990L);
    }

    @Test
    @DisplayName("um preço que não é número é recusado, não vira zero")
    void recusaPrecoInvalido() {
        assertThatThrownBy(() -> criar("Cuia estranha", "muito barato"))
                .isInstanceOf(RegraDeNegocioException.class);

        assertThat(produtos.count()).as("nada foi gravado").isZero();
    }

    @Test
    @DisplayName("nome repetido ganha sufixo numérico, sem estourar a chave única")
    void resolveColisaoDeSlug() {
        criar("Cuia Gold em madeira", "89,90");
        UUID segundo = criar("Cuia Gold em madeira", "89,90");
        UUID terceiro = criar("Cuia Gold em madeira", "89,90");

        assertThat(produtos.findById(segundo).orElseThrow().getSlug())
                .isEqualTo("cuia-gold-em-madeira-2");
        assertThat(produtos.findById(terceiro).orElseThrow().getSlug())
                .isEqualTo("cuia-gold-em-madeira-3");
    }

    @Test
    @DisplayName("publicar exige preço maior que zero")
    void publicacaoExigePreco() {
        UUID semPreco = criar("Cuia sem preço", "0");
        darUmaFotoA(semPreco);

        assertThatThrownBy(() -> servico.publicar(semPreco, true, null, null))
                .isInstanceOf(RegraDeNegocioException.class)
                .hasMessageContaining("preço");
    }

    @Test
    @DisplayName("publicar exige ao menos uma foto")
    void publicacaoExigeFoto() {
        UUID semFoto = criar("Cuia sem foto", "89,90");

        assertThatThrownBy(() -> servico.publicar(semFoto, true, null, null))
                .isInstanceOf(RegraDeNegocioException.class)
                .hasMessageContaining("foto");
    }

    @Test
    @DisplayName("com foto e preço, a publicação passa")
    void publicaQuandoCumpreAsCondicoes() {
        UUID id = criar("Cuia completa", "89,90");
        darUmaFotoA(id);

        assertThatCode(() -> servico.publicar(id, true, null, null)).doesNotThrowAnyException();
        assertThat(produtos.findById(id).orElseThrow().isPublicado()).isTrue();
    }

    @Test
    @DisplayName("o slug não muda quando o nome é editado, para não quebrar link já compartilhado")
    void slugNaoMudaSozinho() {
        UUID id = criar("Cuia Gold", "89,90");
        String slugOriginal = produtos.findById(id).orElseThrow().getSlug();

        ProdutoForm alteracao = formulario("Cuia Gold Edição Nova", "99,90");
        alteracao.setVersao(produtos.findById(id).orElseThrow().getVersao());
        servico.alterar(id, alteracao, null, null);

        Produto depois = produtos.findById(id).orElseThrow();
        assertThat(depois.getSlug()).isEqualTo(slugOriginal);
        assertThat(depois.getNome()).isEqualTo("Cuia Gold Edição Nova");
        assertThat(depois.getPrecoCentavos()).isEqualTo(9990L);
    }

    @Test
    @DisplayName("versão divergente recusa a gravação em vez de sobrescrever")
    void versaoDivergenteRecusaGravacao() {
        UUID id = criar("Cuia disputada", "89,90");

        ProdutoForm atrasado = formulario("Cuia disputada", "10,00");
        atrasado.setVersao(99L);

        assertThatThrownBy(() -> servico.alterar(id, atrasado, null, null))
                .isInstanceOf(RegraDeNegocioException.class)
                .hasMessageContaining("alterado em outro lugar");

        assertThat(produtos.findById(id).orElseThrow().getPrecoCentavos())
                .as("a gravação recusada não pode ter mudado nada")
                .isEqualTo(8990L);
    }

    @Test
    @DisplayName("a exclusão é lógica: a linha fica, some do catálogo")
    void exclusaoEhLogica() {
        UUID id = criar("Cuia a excluir", "89,90");
        darUmaFotoA(id);
        servico.publicar(id, true, null, null);

        servico.excluir(id, null, null);

        Produto depois = produtos.findById(id).orElseThrow();
        assertThat(depois.getExcluidoEm()).isNotNull();
        assertThat(depois.isPublicado()).as("excluído não pode continuar publicado").isFalse();
        assertThat(produtos.findBySlugAndExcluidoEmIsNull("cuia-a-excluir")).isEmpty();
    }

    @Test
    @DisplayName("produto excluído não pode ser editado nem republicado")
    void excluidoNaoEhAlcancavel() {
        UUID id = criar("Cuia sumida", "89,90");
        servico.excluir(id, null, null);

        assertThatThrownBy(() -> servico.buscarAtivo(id))
                .isInstanceOf(RegraDeNegocioException.class);
        assertThatThrownBy(() -> servico.publicar(id, true, null, null))
                .isInstanceOf(RegraDeNegocioException.class);
    }

    @Test
    @DisplayName("a categoria é ligada quando informada")
    void ligaCategoria() {
        ProdutoForm form = formulario("Cuia com categoria", "89,90");
        form.setCategoriaId(CATEGORIA_MADEIRA);

        UUID id = servico.criar(form, null, null);

        // getId() num proxy preguiçoso não exige carga, e ler o slug
        // exigiria — com open-in-view desligado de propósito, a sessão já
        // fechou aqui. Conferir o identificador prova a ligação do mesmo
        // jeito, sem afrouxar a configuração para o teste passar.
        assertThat(produtos.findById(id).orElseThrow().getCategoria().getId())
                .isEqualTo(CATEGORIA_MADEIRA);
    }

    @Test
    @DisplayName("categoria inexistente é recusada, não ignorada em silêncio")
    void recusaCategoriaInexistente() {
        ProdutoForm form = formulario("Cuia órfã", "89,90");
        form.setCategoriaId(UUID.randomUUID());

        assertThatThrownBy(() -> servico.criar(form, null, null))
                .isInstanceOf(RegraDeNegocioException.class)
                .hasMessageContaining("Categoria");
    }

    @Test
    @DisplayName("toda alteração vai para a auditoria com o preço anterior")
    void auditaAlteracaoDePreco(@Autowired br.com.chimaclub.admin.EventoAuditoriaRepository eventos) {
        UUID id = criar("Cuia auditada", "89,90");

        ProdutoForm alteracao = formulario("Cuia auditada", "129,90");
        alteracao.setVersao(produtos.findById(id).orElseThrow().getVersao());
        servico.alterar(id, alteracao, null, null);

        String detalhes = eventos.findAll().stream()
                .filter(evento -> "PRODUTO_ALTERADO".equals(evento.getAcao()))
                .map(evento -> String.valueOf(evento.getDetalhes()))
                .reduce("", String::concat);

        assertThat(detalhes)
                .as("saber o preço anterior é o que permite desfazer um erro de digitação")
                .contains("8990")
                .contains("12990");
    }
}
