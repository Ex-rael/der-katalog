package br.com.chimaclub.catalogo.dto;

import java.util.UUID;

/**
 * O que a grade da home precisa de cada produto, e nada além. Evita levar a
 * entidade inteira para o template, onde um acesso preguiçoso a mais
 * dispararia consulta a cada cartão desenhado.
 */
public record ProdutoResumo(
        UUID id,
        String nome,
        String slug,
        long precoCentavos,
        int unidades,
        String arquivoMini,
        String textoAlt,
        UUID categoriaId,
        String categoriaNome) {

    public boolean esgotado() {
        return unidades <= 0;
    }

    public boolean ultimaUnidade() {
        return unidades == 1;
    }

    public boolean temFoto() {
        return arquivoMini != null && !arquivoMini.isBlank();
    }

    /** O alternativo cai para o nome do produto quando ninguém escreveu um. */
    public String alternativoDaFoto() {
        return textoAlt == null || textoAlt.isBlank() ? nome : textoAlt;
    }
}
