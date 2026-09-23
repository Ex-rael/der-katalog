package br.com.chimaclub.admin;

import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UsuarioAdminDetailsService implements UserDetailsService {

    private final UsuarioAdminRepository usuarios;

    public UsuarioAdminDetailsService(UsuarioAdminRepository usuarios) {
        this.usuarios = usuarios;
    }

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String email) {
        UsuarioAdmin usuario = usuarios.findByEmailIgnoreCase(email)
                // A mensagem é genérica de propósito: ela chega ao manipulador
                // de falha, que responde sempre a mesma coisa ao usuário.
                .orElseThrow(() -> new UsernameNotFoundException("credencial inválida"));

        return User.withUsername(usuario.getEmail())
                .password(usuario.getSenhaHash())
                .roles("ADMIN")
                .disabled(!usuario.isAtivo())
                .accountLocked(usuario.estaBloqueado())
                .build();
    }
}
