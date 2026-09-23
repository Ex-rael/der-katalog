package br.com.chimaclub.comum;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SlugifyTest {

    @Test
    @DisplayName("remove acento, baixa a caixa e junta com hífen")
    void normalizaONome() {
        assertThat(Slugify.de("Cuía Gold em madeira")).isEqualTo("cuia-gold-em-madeira");
        assertThat(Slugify.de("Cuia em porongo com pérolas brancas"))
                .isEqualTo("cuia-em-porongo-com-perolas-brancas");
        assertThat(Slugify.de("Cuia Lilás com Brilho em madeira"))
                .isEqualTo("cuia-lilas-com-brilho-em-madeira");
    }

    @Test
    @DisplayName("descarta pontuação e espaço repetido")
    void descartaPontuacao() {
        assertThat(Slugify.de("  Cuia   'Sunset' / edição #2!  ")).isEqualTo("cuia-sunset-edicao-2");
    }

    @Test
    @DisplayName("neutraliza tentativa de injeção no slug, que vai para a URL")
    void neutralizaInjecao() {
        assertThat(Slugify.de("<script>alert(1)</script>")).isEqualTo("script-alert-1-script");
        assertThat(Slugify.de("../../etc/passwd")).isEqualTo("etc-passwd");
        assertThat(Slugify.de("cuia?q=1&x=2")).isEqualTo("cuia-q-1-x-2");
    }

    @Test
    @DisplayName("nunca devolve vazio, nem começa ou termina com hífen")
    void nuncaDevolveVazio() {
        assertThat(Slugify.de("!!!")).isNotEmpty();
        assertThat(Slugify.de("")).isNotEmpty();
        assertThat(Slugify.de(null)).isNotEmpty();
        assertThat(Slugify.de("---a---")).isEqualTo("a");
    }

    @Test
    @DisplayName("respeita o limite de 160 caracteres da coluna")
    void respeitaOLimiteDaColuna() {
        String longo = Slugify.de("cuia ".repeat(80));

        assertThat(longo).hasSizeLessThanOrEqualTo(160);
        assertThat(longo).doesNotEndWith("-");
    }
}
