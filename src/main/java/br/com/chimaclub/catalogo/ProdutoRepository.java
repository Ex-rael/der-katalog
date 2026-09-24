package br.com.chimaclub.catalogo;

import br.com.chimaclub.catalogo.dto.ProdutoResumo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProdutoRepository extends JpaRepository<Produto, UUID> {

    /** Toda consulta do catálogo filtra por excluido_em: a exclusão é lógica. */
    Optional<Produto> findBySlugAndExcluidoEmIsNull(String slug);

    boolean existsBySlug(String slug);

    /**
     * A grade da home: publicados, com a foto principal, na ordem de
     * exibição. A foto entra por LEFT JOIN para que um produto sem foto
     * ainda apareça no painel — na home ele não chega, porque a regra de
     * publicação exige foto.
     */
    @Query(value = """
            SELECT p.id, p.nome, p.slug, p.preco_centavos, p.unidades,
                   f.arquivo_mini, f.texto_alt,
                   p.categoria_id, c.nome AS categoria_nome
              FROM produto p
              LEFT JOIN produto_foto f ON f.produto_id = p.id AND f.principal
              LEFT JOIN categoria c ON c.id = p.categoria_id
             WHERE p.excluido_em IS NULL
               AND p.publicado
             ORDER BY c.ordem NULLS LAST, p.ordem, p.criado_em DESC
             LIMIT 200
            """, nativeQuery = true)
    List<ProdutoResumo> listarPublicados();

    /** Os destacados, na mesma forma da grade. */
    @Query(value = """
            SELECT p.id, p.nome, p.slug, p.preco_centavos, p.unidades,
                   f.arquivo_mini, f.texto_alt,
                   p.categoria_id, c.nome AS categoria_nome
              FROM produto p
              LEFT JOIN produto_foto f ON f.produto_id = p.id AND f.principal
              LEFT JOIN categoria c ON c.id = p.categoria_id
             WHERE p.excluido_em IS NULL
               AND p.publicado
               AND p.destaque
             ORDER BY p.ordem, p.criado_em DESC
             LIMIT 12
            """, nativeQuery = true)
    List<ProdutoResumo> listarDestaques();

    /**
     * A consulta da §3.4 do documento de projeto.
     *
     * Duas condições somadas: LIKE acha o trecho contíguo, a aproximação por
     * trigrama acha o resto — é o que faz "sunsett" e "madera" chegarem ao
     * produto certo. Ambas passam por imutavel_unaccent, a mesma função do
     * índice idx_produto_busca_nome; escrever unaccent() aqui devolveria o
     * mesmo resultado por varredura sequencial.
     *
     * word_similarity e não similarity, corrigindo a §3.4 do documento.
     * similarity compara as duas cadeias inteiras e divide pelos trigramas
     * da união, então um termo curto contra um nome longo afunda: medido
     * neste banco, similarity('cuia sunset em madeira','sunsett') dá 0,24 —
     * logo abaixo do limite de 0,25 que o documento propunha. Isso é pior
     * que falhar sempre, porque funcionaria em nomes curtos e falharia em
     * silêncio nos longos. word_similarity compara o termo com o melhor
     * trecho do nome, e dá 0,75 no mesmo caso.
     *
     * O limite de 0,45 foi escolhido por medição, não por gosto. Com os
     * nomes reais do catálogo: acertos, inclusive com erro de digitação,
     * ficam entre 0,57 e 1,00; termos que não deveriam casar ("erva",
     * "bomba", "xyz") ficam abaixo de 0,20. O vão entre os dois grupos é
     * largo, e 0,45 fica no meio dele.
     *
     * O termo entra como parâmetro vinculado, nunca concatenado. Vem duas
     * vezes: :termoEscapado tem os curingas do LIKE neutralizados, e :termo
     * é o texto cru, porque a comparação por trigrama não interpreta
     * curinga nenhum.
     */
    @Query(value = """
            SELECT p.id, p.nome, p.slug, p.preco_centavos, p.unidades,
                   f.arquivo_mini, f.texto_alt,
                   p.categoria_id, c.nome AS categoria_nome
              FROM produto p
              LEFT JOIN produto_foto f ON f.produto_id = p.id AND f.principal
              LEFT JOIN categoria c ON c.id = p.categoria_id
             WHERE p.excluido_em IS NULL
               AND p.publicado
               AND (
                     imutavel_unaccent(lower(p.nome))
                       LIKE '%' || imutavel_unaccent(lower(:termoEscapado)) || '%' ESCAPE '\\'
                  OR word_similarity(imutavel_unaccent(lower(:termo)),
                                     imutavel_unaccent(lower(p.nome))) > 0.45
               )
             ORDER BY word_similarity(imutavel_unaccent(lower(:termo)),
                                      imutavel_unaccent(lower(p.nome))) DESC,
                      p.ordem, p.criado_em DESC
             LIMIT 60
            """, nativeQuery = true)
    List<ProdutoResumo> buscarPublicados(@Param("termoEscapado") String termoEscapado,
                                         @Param("termo") String termo);
}
