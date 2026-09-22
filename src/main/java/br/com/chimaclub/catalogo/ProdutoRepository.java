package br.com.chimaclub.catalogo;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ProdutoRepository extends JpaRepository<Produto, UUID> {

    /** Toda consulta do catálogo filtra por excluido_em: a exclusão é lógica. */
    Optional<Produto> findBySlugAndExcluidoEmIsNull(String slug);

    boolean existsBySlug(String slug);
}
