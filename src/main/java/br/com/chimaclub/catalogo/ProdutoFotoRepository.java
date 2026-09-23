package br.com.chimaclub.catalogo;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProdutoFotoRepository extends JpaRepository<ProdutoFoto, UUID> {

    List<ProdutoFoto> findByProdutoIdOrderByOrdemAsc(UUID produtoId);

    Optional<ProdutoFoto> findByProdutoIdAndPrincipalTrue(UUID produtoId);

    long countByProdutoId(UUID produtoId);
}
