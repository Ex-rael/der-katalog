package br.com.chimaclub.catalogo.dto;

import br.com.chimaclub.catalogo.ProdutoFoto;

import java.util.List;
import java.util.UUID;

/** O que a página do produto mostra. */
public record ProdutoDetalhe(
        UUID id,
        String nome,
        String slug,
        String descricao,
        long precoCentavos,
        int unidades,
        UUID categoriaId,
        String categoriaNome,
        List<ProdutoFoto> fotos) {

    public boolean esgotado() {
        return unidades <= 0;
    }

    public ProdutoFoto principal() {
        return fotos.stream()
                    .filter(ProdutoFoto::isPrincipal)
                    .findFirst()
                    .orElse(fotos.isEmpty() ? null : fotos.getFirst());
    }

    /**
     * "3 unidades disponíveis", "Última unidade", "Esgotado" — as três formas
     * que a §4.2 pede.
     */
    public String disponibilidade() {
        if (unidades <= 0) {
            return "Esgotado";
        }
        if (unidades == 1) {
            return "Última unidade";
        }
        return unidades + " unidades disponíveis";
    }
}
