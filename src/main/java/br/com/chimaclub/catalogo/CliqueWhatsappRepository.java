package br.com.chimaclub.catalogo;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CliqueWhatsappRepository extends JpaRepository<CliqueWhatsapp, Long> {

    long countByProdutoId(java.util.UUID produtoId);
}
