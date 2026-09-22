package br.com.chimaclub.migracao;

import br.com.chimaclub.BancoDeTesteBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A migração é código, e é revisada como código. Estes testes não conferem
 * apenas que ela roda: conferem as decisões de modelagem que o documento
 * toma, porque é nelas que um erro passa despercebido por meses.
 */
class MigracaoV1Test extends BancoDeTesteBase {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    @DisplayName("cria todas as tabelas do modelo")
    void criaTodasAsTabelas() {
        List<String> tabelas = jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'",
                String.class);

        assertThat(tabelas).contains(
                "categoria", "produto", "produto_foto",
                "usuario_admin", "evento_auditoria", "configuracao", "clique_whatsapp");
    }

    @Test
    @DisplayName("imutavel_unaccent é IMMUTABLE, senão o índice de busca não poderia existir")
    void funcaoDeBuscaEhImutavel() {
        String volatilidade = jdbc.queryForObject(
                "SELECT provolatile FROM pg_proc WHERE proname = 'imutavel_unaccent'",
                String.class);

        // 'i' = immutable, 's' = stable, 'v' = volatile
        assertThat(volatilidade)
                .as("unaccent() é STABLE e o PostgreSQL a recusa em índice")
                .isEqualTo("i");
    }

    @Test
    @DisplayName("remove acento e caixa, para que 'Cuía Gold' e 'cuia gold' se encontrem")
    void normalizaAcentoECaixa() {
        String normalizado = jdbc.queryForObject(
                "SELECT imutavel_unaccent(lower(?))", String.class, "Cuía Gold");

        assertThat(normalizado).isEqualTo("cuia gold");
    }

    @Test
    @DisplayName("preço negativo é recusado pelo banco, não apenas pela aplicação")
    void recusaPrecoNegativo() {
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO produto (id, nome, slug, preco_centavos)
                VALUES (gen_random_uuid(), 'Teste', 'teste-preco-negativo', -1)
                """))
                .hasMessageContaining("produto_preco_positivo");
    }

    @Test
    @DisplayName("nome só de espaços é recusado pelo banco")
    void recusaNomeVazio() {
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO produto (id, nome, slug, preco_centavos)
                VALUES (gen_random_uuid(), '   ', 'teste-nome-vazio', 8990)
                """))
                .hasMessageContaining("produto_nome_nao_vazio");
    }

    @Test
    @DisplayName("só existe uma foto principal por produto")
    void impedeDuasFotosPrincipais() {
        jdbc.update("""
                INSERT INTO produto (id, nome, slug, preco_centavos)
                VALUES ('01920000-0000-7000-8000-0000000000aa', 'Teste foto', 'teste-foto', 8990)
                """);

        String inserirFoto = """
                INSERT INTO produto_foto
                  (id, produto_id, arquivo, arquivo_mini, largura, altura, bytes, tipo_mime, principal)
                VALUES (gen_random_uuid(), '01920000-0000-7000-8000-0000000000aa',
                        ?, ?, 1200, 1500, 1024, 'image/webp', true)
                """;

        jdbc.update(inserirFoto, "a-media.webp", "a-mini.webp");

        assertThatThrownBy(() -> jdbc.update(inserirFoto, "b-media.webp", "b-mini.webp"))
                .hasMessageContaining("idx_foto_principal_unica");
    }

    @Test
    @DisplayName("excluir o produto leva junto as suas fotos")
    void excluirProdutoRemoveFotos() {
        jdbc.update("""
                INSERT INTO produto (id, nome, slug, preco_centavos)
                VALUES ('01920000-0000-7000-8000-0000000000bb', 'Teste cascata', 'teste-cascata', 8990)
                """);
        jdbc.update("""
                INSERT INTO produto_foto
                  (id, produto_id, arquivo, arquivo_mini, largura, altura, bytes, tipo_mime)
                VALUES (gen_random_uuid(), '01920000-0000-7000-8000-0000000000bb',
                        'c-media.webp', 'c-mini.webp', 1200, 1500, 1024, 'image/webp')
                """);

        jdbc.update("DELETE FROM produto WHERE id = '01920000-0000-7000-8000-0000000000bb'");

        Integer fotos = jdbc.queryForObject("""
                SELECT count(*) FROM produto_foto
                 WHERE produto_id = '01920000-0000-7000-8000-0000000000bb'
                """, Integer.class);

        assertThat(fotos).isZero();
    }
}
