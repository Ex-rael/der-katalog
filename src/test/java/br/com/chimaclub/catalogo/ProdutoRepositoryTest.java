package br.com.chimaclub.catalogo;

import br.com.chimaclub.BancoDeTesteBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProdutoRepositoryTest extends BancoDeTesteBase {

    @Autowired ProdutoRepository produtos;
    @Autowired TransactionTemplate transacao;

    @Test
    @DisplayName("grava e relê um produto com preço em centavos")
    void gravaERele() {
        UUID id = produtos.save(new Produto("Cuia Gold em madeira", "cuia-gold-jpa", 8990L)).getId();

        Produto lido = produtos.findById(id).orElseThrow();

        assertThat(lido.getPrecoCentavos()).isEqualTo(8990L);
        assertThat(lido.isPublicado()).isFalse();
        assertThat(lido.getVersao()).isZero();
    }

    @Test
    @DisplayName("duas abas do painel não se sobrescrevem em silêncio")
    void bloqueioOtimistaRecusaSegundaGravacao() {
        UUID id = produtos.save(new Produto("Cuia Snow", "cuia-snow-jpa", 8990L)).getId();

        Produto abaA = transacao.execute(s -> produtos.findById(id).orElseThrow());
        Produto abaB = transacao.execute(s -> produtos.findById(id).orElseThrow());

        abaA.setPrecoCentavos(9990L);
        transacao.execute(s -> produtos.saveAndFlush(abaA));

        abaB.setPrecoCentavos(7990L);
        assertThatThrownBy(() -> transacao.execute(s -> produtos.saveAndFlush(abaB)))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);

        assertThat(produtos.findById(id).orElseThrow().getPrecoCentavos())
                .as("a gravação recusada não pode ter alterado nada")
                .isEqualTo(9990L);
    }

    @Test
    @DisplayName("o identificador gerado é versão 7, e não versão 4")
    void identificadorEhVersao7() {
        UUID id = produtos.save(new Produto("Cuia Pink", "cuia-pink-jpa", 8990L)).getId();

        assertThat(id.version()).isEqualTo(7);
    }

    @Test
    @DisplayName("identificadores sucessivos crescem, porque o v7 é ordenado no tempo")
    void identificadoresSaoOrdenadosNoTempo() {
        UUID primeiro = produtos.save(new Produto("Cuia A", "cuia-a-jpa", 8990L)).getId();
        UUID segundo = produtos.save(new Produto("Cuia B", "cuia-b-jpa", 8990L)).getId();

        assertThat(primeiro.toString()).isLessThan(segundo.toString());
    }

    @Test
    @DisplayName("produto excluído logicamente não é encontrado pelo slug")
    void exclusaoLogicaEscondeDaConsulta() {
        Produto produto = produtos.save(new Produto("Cuia Bronze", "cuia-bronze-jpa", 8990L));

        assertThat(produtos.findBySlugAndExcluidoEmIsNull("cuia-bronze-jpa")).isPresent();

        produto.setExcluidoEm(java.time.Instant.now());
        produtos.saveAndFlush(produto);

        assertThat(produtos.findBySlugAndExcluidoEmIsNull("cuia-bronze-jpa")).isEmpty();
        assertThat(produtos.findById(produto.getId()))
                .as("exclusão é lógica: a linha continua no banco")
                .isPresent();
    }

    @Test
    @DisplayName("as categorias semeadas pela V2 são lidas pelo mapeamento")
    void leCategoriasSemeadas(@Autowired CategoriaRepository categorias) {
        Categoria madeira = categorias
                .findById(UUID.fromString("01920000-0000-7000-8000-000000000001"))
                .orElseThrow();

        assertThat(madeira.getNome()).isEqualTo("Cuias em madeira");
        assertThat(madeira.getSlug()).isEqualTo("cuias-em-madeira");
    }
}
