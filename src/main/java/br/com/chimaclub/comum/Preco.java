package br.com.chimaclub.comum;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Conversão entre o preço digitado e os centavos guardados.
 *
 * Centavos em long, nunca ponto flutuante: 89,90 não tem representação
 * exata em binário, e somar duzentas vezes um valor assim rende centavos de
 * diferença. Num catálogo isso seria um preço errado na tela.
 */
public final class Preco {

    private static final Locale BRASIL = Locale.of("pt", "BR");

    /**
     * Dígitos, com ponto apenas como separador de milhar — sempre seguido de
     * exatamente três dígitos — e vírgula apenas como separador decimal, com
     * uma ou duas casas.
     */
    private static final Pattern FORMATO_BRASILEIRO =
            Pattern.compile("^(\\d{1,3}(\\.\\d{3})*|\\d+)(,\\d{1,2})?$");

    /** Distingue "casas decimais demais" de "formato irreconhecível". */
    private static final Pattern CASAS_DEMAIS =
            Pattern.compile("^(\\d{1,3}(\\.\\d{3})*|\\d+),\\d{3,}$");

    private Preco() {
    }

    /**
     * Aceita o que a dona da loja escreveria: "89,90", "89,9", "90",
     * "1.234,56" e "R$ 89,90".
     */
    public static long paraCentavos(String digitado) {
        if (digitado == null || digitado.isBlank()) {
            throw new RegraDeNegocioException("Informe o preço.");
        }

        String limpo = digitado.trim()
                               .replace("R$", "")
                               .replaceAll("\\s", "");

        if (limpo.startsWith("-")) {
            throw new RegraDeNegocioException("O preço não pode ser negativo.");
        }
        if (CASAS_DEMAIS.matcher(limpo).matches()) {
            throw new RegraDeNegocioException("O preço não pode ter mais de duas casas decimais.");
        }
        if (!FORMATO_BRASILEIRO.matcher(limpo).matches()) {
            // Recusar em vez de adivinhar. "89.90" poderia ser 89,90 ou
            // 8.990,00, e errar por cem vezes o preço de um produto é um
            // estrago silencioso: ninguém confere, e ou a venda se perde, ou
            // o prejuízo acontece. O ponto só é aceito como separador de
            // milhar, seguido de exatamente três dígitos.
            throw new RegraDeNegocioException(
                    "Preço inválido. Use vírgula para os centavos, como em 89,90.");
        }

        BigDecimal reais = new BigDecimal(limpo.replace(".", "").replace(',', '.'));

        // UNNECESSARY porque a escala já foi conferida pela expressão: se
        // houvesse arredondamento a fazer, seria erro de programação, não de
        // entrada, e é melhor estourar do que arredondar dinheiro em silêncio.
        return reais.movePointRight(2).setScale(0, RoundingMode.UNNECESSARY).longValueExact();
    }

    /** Devolve "R$ 89,90", no formato do Brasil. */
    public static String formatar(long centavos) {
        return NumberFormat.getCurrencyInstance(BRASIL)
                           .format(BigDecimal.valueOf(centavos).movePointLeft(2));
    }

    /** Devolve "89,90", para preencher o campo do formulário. */
    public static String paraCampo(long centavos) {
        return BigDecimal.valueOf(centavos).movePointLeft(2).setScale(2).toPlainString().replace('.', ',');
    }
}
