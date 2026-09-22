package br.com.chimaclub.migracao;

import br.com.chimaclub.BancoDeTesteBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A consulta de busca precisa usar imutavel_unaccent, a mesma função do
 * índice. Escrever unaccent(...) devolveria exatamente o mesmo resultado,
 * mas com varredura sequencial — e ninguém perceberia até o catálogo
 * crescer. Daí um teste que lê o plano de execução, e não só a resposta.
 */
class IndiceDeBuscaTest extends BancoDeTesteBase {

    @Autowired
    JdbcTemplate jdbc;

    /** A consulta da §3.4 do documento: trecho contíguo ou aproximação. */
    private static final String CONSULTA = """
            SELECT p.id FROM produto p
             WHERE p.excluido_em IS NULL
               AND p.publicado
               AND (
                     imutavel_unaccent(lower(p.nome)) LIKE '%' || imutavel_unaccent(lower(?)) || '%'
                  OR similarity(imutavel_unaccent(lower(p.nome)), imutavel_unaccent(lower(?))) > 0.25
               )
            """;

    @Test
    @DisplayName("o planejador consegue usar o índice gin em vez de varredura sequencial")
    void usaOIndiceGin() {
        for (int i = 0; i < 50; i++) {
            jdbc.update("""
                    INSERT INTO produto (id, nome, slug, preco_centavos, publicado)
                    VALUES (?, ?, ?, 8990, true)
                    """, UUID.randomUUID(), "Cuia número " + i, "cuia-numero-" + i);
        }

        // Com poucas linhas o planejador prefere varredura sequencial, e está
        // certo: é mais barata. Desligá-la revela se o índice é sequer
        // aplicável à consulta, que é o que este teste precisa saber.
        jdbc.execute("SET enable_seqscan = off");
        List<String> plano = jdbc.queryForList(
                "EXPLAIN SELECT p.id FROM produto p WHERE imutavel_unaccent(lower(p.nome)) LIKE ?",
                String.class, "%cuia%");

        assertThat(String.join("\n", plano))
                .as("o índice existe mas não é aplicável à consulta escrita")
                .contains("idx_produto_busca_nome");
    }

    @Test
    @DisplayName("encontra 'gold', 'Gold' e 'cuia gold' no mesmo produto")
    void encontraIndependenteDeCaixaEAcento() {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO produto (id, nome, slug, preco_centavos, publicado)
                VALUES (?, 'Cuía Gold em madeira', 'cuia-gold-em-madeira', 8990, true)
                """, id);

        for (String termo : List.of("gold", "Gold", "GOLD", "cuia gold", "Cuía Gold")) {
            List<UUID> achados = jdbc.queryForList(CONSULTA, UUID.class, termo, termo);

            assertThat(achados).as("busca por '%s'", termo).contains(id);
        }
    }

    @Test
    @DisplayName("produto despublicado ou excluído não aparece na busca")
    void naoDevolveProdutoForaDoCatalogo() {
        UUID despublicado = UUID.randomUUID();
        UUID excluido = UUID.randomUUID();

        jdbc.update("""
                INSERT INTO produto (id, nome, slug, preco_centavos, publicado)
                VALUES (?, 'Cuia Snow rascunho', 'cuia-snow-rascunho', 8990, false)
                """, despublicado);
        jdbc.update("""
                INSERT INTO produto (id, nome, slug, preco_centavos, publicado, excluido_em)
                VALUES (?, 'Cuia Snow apagada', 'cuia-snow-apagada', 8990, true, now())
                """, excluido);

        List<UUID> achados = jdbc.queryForList(CONSULTA, UUID.class, "snow", "snow");

        assertThat(achados).doesNotContain(despublicado, excluido);
    }
}
