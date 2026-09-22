package br.com.chimaclub.migracao;

import br.com.chimaclub.BancoDeTesteBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MigracaoV2Test extends BancoDeTesteBase {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    @DisplayName("semeia a configuração da loja")
    void semeiaConfiguracao() {
        String numero = jdbc.queryForObject(
                "SELECT valor FROM configuracao WHERE chave = 'whatsapp_numero'", String.class);

        assertThat(numero).isEqualTo("5551989250481");
    }

    @Test
    @DisplayName("as categorias têm identificador fixo, para a migração ser determinística")
    void categoriasTemIdFixo() {
        String id = jdbc.queryForObject(
                "SELECT id::text FROM categoria WHERE slug = 'cuias-em-madeira'", String.class);

        assertThat(id).isEqualTo("01920000-0000-7000-8000-000000000001");
    }

    @Test
    @DisplayName("os identificadores semeados são versão 7, não versão 4")
    void identificadoresSaoVersao7() {
        // O 15º caractere do texto do UUID carrega o número da versão:
        // 01920000-0000-7000-... → o '7' logo depois do terceiro hífen.
        List<String> versoes = jdbc.queryForList(
                "SELECT substring(id::text, 15, 1) FROM categoria", String.class);

        assertThat(versoes).isNotEmpty().allMatch("7"::equals);
    }
}
