package br.com.chimaclub.migracao;

import br.com.chimaclub.BancoDeTesteBase;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * O critério de aceite exige que a migração rode em base limpa e em base com
 * dados. As outras classes cobrem a base limpa, porque cada execução começa
 * do zero; esta cobre a base já povoada.
 */
class MigracaoIncrementalTest extends BancoDeTesteBase {

    @Autowired JdbcTemplate jdbc;
    @Autowired Flyway flyway;

    @Test
    @DisplayName("revalidar as migrações sobre base povoada não perde dado nem falha")
    void migraSobreBaseComDados() {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO produto (id, nome, slug, preco_centavos, publicado)
                VALUES (?, 'Produto anterior', 'produto-anterior', 8990, true)
                """, id);

        // validate() confere que nenhum arquivo de migração já aplicado foi
        // editado depois do fato — o erro mais comum e mais silencioso do
        // Flyway, porque a base existente continua funcionando e só uma
        // instalação nova revela a divergência.
        assertThatCode(() -> {
            flyway.validate();
            flyway.migrate();
        }).doesNotThrowAnyException();

        Integer restantes = jdbc.queryForObject(
                "SELECT count(*) FROM produto WHERE id = ?", Integer.class, id);

        assertThat(restantes).isEqualTo(1);
    }

    @Test
    @DisplayName("o clean do Flyway está desativado: não há como apagar o catálogo por engano")
    void cleanEstaDesativado() {
        assertThatCode(flyway::clean)
                .as("um clean acidental apagaria o catálogo inteiro (§A08)")
                .hasMessageContaining("clean");
    }
}
