package br.com.chimaclub.comum;

import java.text.Normalizer;
import java.util.Locale;

/**
 * Transforma o nome do produto no trecho que vai para a URL.
 *
 * O resultado sai restrito a letras minúsculas sem acento, dígitos e hífen.
 * Não é só estética: o slug entra na URL pública, e limitar o alfabeto de
 * saída elimina de uma vez barra, ponto-ponto, sinal de interrogação, e
 * sinal de menor — ou seja, travessia de caminho, injeção de parâmetro e
 * HTML na URL.
 */
public final class Slugify {

    /** A coluna slug tem 160 caracteres. */
    private static final int LIMITE = 160;
    private static final String QUANDO_NAO_SOBRA_NADA = "produto";

    private Slugify() {
    }

    public static String de(String texto) {
        if (texto == null || texto.isBlank()) {
            return QUANDO_NAO_SOBRA_NADA;
        }

        String semAcento = Normalizer.normalize(texto, Normalizer.Form.NFD)
                                     .replaceAll("\\p{M}", "");

        String slug = semAcento.toLowerCase(Locale.ROOT)
                               .replaceAll("[^a-z0-9]+", "-")
                               .replaceAll("^-+|-+$", "");

        if (slug.length() > LIMITE) {
            slug = slug.substring(0, LIMITE).replaceAll("-+$", "");
        }
        return slug.isEmpty() ? QUANDO_NAO_SOBRA_NADA : slug;
    }
}
