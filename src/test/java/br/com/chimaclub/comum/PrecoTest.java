package br.com.chimaclub.comum;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PrecoTest {

    @ParameterizedTest(name = "\"{0}\" vira {1} centavos")
    @CsvSource({
            "'89,90',     8990",
            "'89,9',      8990",
            "'90',        9000",
            "'0,01',         1",
            "'0',            0",
            "'1.234,56', 123456",
            "'R$ 89,90',  8990",
            "' 89,90 ',   8990"
    })
    @DisplayName("converte o que a dona da loja escreveria")
    void converteFormatosUsuais(String digitado, long esperado) {
        assertThat(Preco.paraCentavos(digitado)).isEqualTo(esperado);
    }

    @ParameterizedTest
    @ValueSource(strings = {"muito barato", "89.90.90", "abc", "", "   ", "8,9,9", "1.23,45", "89,"})
    @DisplayName("recusa o que não é preço, em vez de virar zero em silêncio")
    void recusaEntradaInvalida(String digitado) {
        assertThatThrownBy(() -> Preco.paraCentavos(digitado))
                .isInstanceOf(RegraDeNegocioException.class);
    }

    @Test
    @DisplayName("recusa ponto como separador decimal em vez de multiplicar o preço por cem")
    void recusaPontoComoSeparadorDecimal() {
        // "89.90" digitado por quem esperava ponto decimal. Aceitar o ponto
        // como separador de milhar transformaria isto em R$ 8.990,00 — cem
        // vezes o preço, sem ninguém perceber.
        assertThatThrownBy(() -> Preco.paraCentavos("89.90"))
                .isInstanceOf(RegraDeNegocioException.class)
                .hasMessageContaining("vírgula");

        assertThatThrownBy(() -> Preco.paraCentavos("1.5"))
                .isInstanceOf(RegraDeNegocioException.class);
    }

    @Test
    @DisplayName("recusa preço negativo e casas decimais demais")
    void recusaValoresForaDaFaixa() {
        assertThatThrownBy(() -> Preco.paraCentavos("-10,00"))
                .isInstanceOf(RegraDeNegocioException.class);
        assertThatThrownBy(() -> Preco.paraCentavos("89,909"))
                .isInstanceOf(RegraDeNegocioException.class)
                .hasMessageContaining("casas decimais");
    }

    @Test
    @DisplayName("nenhum centavo se perde em cem conversões de ida e volta")
    void naoPerdeCentavoNaIdaEVolta() {
        long soma = 0;
        for (int i = 0; i < 100; i++) {
            soma += Preco.paraCentavos(Preco.paraCampo(8990));
        }

        assertThat(soma)
                .as("com double, cem somas de 89,90 já divergiriam")
                .isEqualTo(899000L);
    }

    @Test
    @DisplayName("exibe no formato do Brasil")
    void exibeNoFormatoBrasileiro() {
        assertThat(Preco.formatar(8990)).contains("89,90");
        assertThat(Preco.formatar(123456)).contains("1.234,56");
        assertThat(Preco.paraCampo(8990)).isEqualTo("89,90");
    }
}
