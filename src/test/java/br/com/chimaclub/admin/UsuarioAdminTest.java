package br.com.chimaclub.admin;

import br.com.chimaclub.BancoDeTesteBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class UsuarioAdminTest extends BancoDeTesteBase {

    @Autowired UsuarioAdminRepository usuarios;
    @Autowired PasswordEncoder codificador;

    @Test
    @DisplayName("a senha é guardada com BCrypt custo 12, nunca em claro")
    void senhaGuardadaComBcrypt12() {
        UsuarioAdmin admin = new UsuarioAdmin("dona@chimaclub.com.br", "Dona da loja",
                codificador.encode("uma senha bem longa de teste"));
        usuarios.save(admin);

        String hash = usuarios.findByEmailIgnoreCase("dona@chimaclub.com.br").orElseThrow().getSenhaHash();

        assertThat(hash).startsWith("{bcrypt}$2a$12$");
        assertThat(hash).doesNotContain("uma senha bem longa de teste");
        assertThat(codificador.matches("uma senha bem longa de teste", hash)).isTrue();
    }

    @Test
    @DisplayName("o e-mail é encontrado independente de caixa")
    void emailIgnoraCaixa() {
        usuarios.save(new UsuarioAdmin("Outra@ChimaClub.com.br", "Dona",
                codificador.encode("senha longa de teste")));

        assertThat(usuarios.findByEmailIgnoreCase("outra@chimaclub.com.br")).isPresent();
    }

    @Test
    @DisplayName("cinco falhas bloqueiam a conta por quinze minutos")
    void cincoFalhasBloqueiam() {
        UsuarioAdmin admin = new UsuarioAdmin("bloqueio@chimaclub.com.br", "Teste",
                codificador.encode("senha longa de teste"));

        for (int i = 0; i < 4; i++) {
            admin.registrarFalha();
            assertThat(admin.estaBloqueado()).as("após %d falhas", i + 1).isFalse();
        }

        admin.registrarFalha();

        assertThat(admin.estaBloqueado()).isTrue();
        assertThat(admin.getBloqueadoAte()).isAfter(Instant.now().plusSeconds(14 * 60));
    }

    @Test
    @DisplayName("o login bem-sucedido zera o contador de falhas")
    void sucessoZeraContador() {
        UsuarioAdmin admin = new UsuarioAdmin("zera@chimaclub.com.br", "Teste",
                codificador.encode("senha longa de teste"));
        admin.registrarFalha();
        admin.registrarFalha();

        admin.registrarSucesso();

        assertThat(admin.getFalhasLogin()).isZero();
        assertThat(admin.estaBloqueado()).isFalse();
        assertThat(admin.getUltimoLoginEm()).isNotNull();
    }

    @Test
    @DisplayName("passado o prazo, o bloqueio deixa de valer sozinho")
    void bloqueioExpira() {
        UsuarioAdmin admin = new UsuarioAdmin("expira@chimaclub.com.br", "Teste",
                codificador.encode("senha longa de teste"));
        for (int i = 0; i < 5; i++) {
            admin.registrarFalha();
        }
        assertThat(admin.estaBloqueado()).isTrue();

        admin.bloquearAte(Instant.now().minusSeconds(1));

        assertThat(admin.estaBloqueado()).isFalse();
    }

    @Test
    @DisplayName("usuário inativo não autentica, mesmo com a senha certa")
    void usuarioInativoNaoAutentica(@Autowired UsuarioAdminDetailsService detalhes) {
        UsuarioAdmin admin = new UsuarioAdmin("inativo@chimaclub.com.br", "Teste",
                codificador.encode("senha longa de teste"));
        admin.desativar();
        usuarios.save(admin);

        assertThat(detalhes.loadUserByUsername("inativo@chimaclub.com.br").isEnabled()).isFalse();
    }
}
