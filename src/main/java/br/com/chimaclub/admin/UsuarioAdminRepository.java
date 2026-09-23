package br.com.chimaclub.admin;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface UsuarioAdminRepository extends JpaRepository<UsuarioAdmin, UUID> {

    Optional<UsuarioAdmin> findByEmailIgnoreCase(String email);

    boolean existsByEmailIgnoreCase(String email);
}
