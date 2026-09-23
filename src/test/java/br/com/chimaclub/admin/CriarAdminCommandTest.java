package br.com.chimaclub.admin;

import br.com.chimaclub.BancoDeTesteBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * O comando que cria o administrador.
 *
 * Este teste existe por causa de um defeito real: uma versão anterior
 * chamava System.exit de dentro do método transacional, e a transação nunca
 * chegava ao commit. O comando imprimia a senha com ar de sucesso e o
 * administrador não existia no banco — um erro que só aparece quando alguém
 * tenta entrar, e sem nada no log para explicar.
 */
class CriarAdminCommandTest extends BancoDeTesteBase {

    @Autowired CriarAdminCommand comando;
    @Autowired UsuarioAdminRepository usuarios;
    @Autowired PasswordEncoder codificador;

    @Test
    @DisplayName("o administrador é realmente gravado, e a senha impressa é a que funciona")
    void gravaDeVerdadeEASenhaFunciona() {
        Optional<String> senha = comando.criar("dona@chimaclub.com.br", "Dona");

        assertThat(senha).isPresent();

        UsuarioAdmin gravado = usuarios.findByEmailIgnoreCase("dona@chimaclub.com.br").orElseThrow();

        assertThat(gravado.getNome()).isEqualTo("Dona");
        assertThat(codificador.matches(senha.get(), gravado.getSenhaHash()))
                .as("a senha impressa na tela precisa ser a que abre o painel")
                .isTrue();
    }

    @Test
    @DisplayName("a senha sorteada é longa o bastante para o §A07")
    void senhaEhLongaOBastante() {
        String senha = comando.criar("longa@chimaclub.com.br", "Teste").orElseThrow();

        assertThat(senha)
                .as("o §A07 pede no mínimo 12 caracteres; 24 bytes em base64 dão 32")
                .hasSizeGreaterThanOrEqualTo(24);
    }

    @Test
    @DisplayName("duas execuções sorteiam senhas diferentes")
    void senhasDiferentesACadaExecucao() {
        String primeira = comando.criar("um@chimaclub.com.br", "Um").orElseThrow();
        String segunda = comando.criar("dois@chimaclub.com.br", "Dois").orElseThrow();

        assertThat(primeira).isNotEqualTo(segunda);
    }

    @Test
    @DisplayName("e-mail já existente não cria outro nem troca a senha do primeiro")
    void emailExistenteNaoCriaOutro() {
        String original = comando.criar("dona@chimaclub.com.br", "Dona").orElseThrow();
        String hashOriginal = usuarios.findByEmailIgnoreCase("dona@chimaclub.com.br")
                                      .orElseThrow().getSenhaHash();

        assertThat(comando.criar("DONA@chimaclub.com.br", "Outra Dona"))
                .as("a conferência ignora a caixa do e-mail")
                .isEmpty();

        assertThat(usuarios.count()).isEqualTo(1);
        assertThat(usuarios.findByEmailIgnoreCase("dona@chimaclub.com.br").orElseThrow().getSenhaHash())
                .as("um comando repetido por engano não pode trocar a senha de quem já usa o painel")
                .isEqualTo(hashOriginal);
        assertThat(codificador.matches(original, hashOriginal)).isTrue();
    }

    @Test
    @DisplayName("o usuário criado começa sem segundo fator, para ativá-lo no primeiro acesso")
    void comecaSemSegundoFator() {
        comando.criar("nova@chimaclub.com.br", "Nova");

        UsuarioAdmin gravado = usuarios.findByEmailIgnoreCase("nova@chimaclub.com.br").orElseThrow();

        assertThat(gravado.isTotpAtivo()).isFalse();
        assertThat(gravado.isAtivo()).isTrue();
        assertThat(gravado.getFalhasLogin()).isZero();
    }
}
