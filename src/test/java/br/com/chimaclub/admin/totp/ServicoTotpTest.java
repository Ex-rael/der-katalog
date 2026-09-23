package br.com.chimaclub.admin.totp;

import br.com.chimaclub.BancoDeTesteBase;
import br.com.chimaclub.admin.UsuarioAdmin;
import br.com.chimaclub.admin.UsuarioAdminRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

class ServicoTotpTest extends BancoDeTesteBase {

    @Autowired ServicoTotp totp;
    @Autowired UsuarioAdminRepository usuarios;

    private UsuarioAdmin novoUsuario() {
        return usuarios.save(new UsuarioAdmin(
                "totp-" + System.nanoTime() + "@exemplo.com", "Teste", "{bcrypt}$2a$12$qualquercoisa"));
    }

    @Test
    @DisplayName("o código gerado agora é aceito")
    void aceitaCodigoValido() {
        String segredo = totp.gerarSegredo();

        assertThat(totp.confereSegredo(segredo, totp.codigoAgora(segredo))).isTrue();
    }

    @Test
    @DisplayName("um código de outro segredo é recusado")
    void recusaCodigoDeOutroSegredo() {
        String segredo = totp.gerarSegredo();
        String outro = totp.gerarSegredo();

        assertThat(totp.confereSegredo(segredo, totp.codigoAgora(outro))).isFalse();
    }

    @Test
    @DisplayName("um código malformado é recusado sem lançar exceção")
    void recusaCodigoMalformado() {
        String segredo = totp.gerarSegredo();

        assertThat(totp.confereSegredo(segredo, "abcdef")).isFalse();
        assertThat(totp.confereSegredo(segredo, "12345")).isFalse();
        assertThat(totp.confereSegredo(segredo, "1234567")).isFalse();
        assertThat(totp.confereSegredo(segredo, "")).isFalse();
        assertThat(totp.confereSegredo(segredo, null)).isFalse();
    }

    @Test
    @DisplayName("o segredo guardado no usuário está cifrado em repouso")
    void segredoGuardadoCifrado() {
        UsuarioAdmin admin = novoUsuario();
        String segredo = totp.gerarSegredo();

        totp.ativar(admin, segredo);

        assertThat(admin.getTotpSegredo())
                .as("o segredo em claro no banco vale tanto quanto a senha")
                .isNotEqualTo(segredo)
                .doesNotContain(segredo);
        assertThat(admin.isTotpAtivo()).isTrue();
    }

    @Test
    @DisplayName("o segredo cifrado é decifrado corretamente na conferência")
    void confereContraSegredoCifrado() {
        UsuarioAdmin admin = novoUsuario();
        String segredo = totp.gerarSegredo();
        totp.ativar(admin, segredo);

        assertThat(totp.confere(admin, totp.codigoAgora(segredo))).isTrue();
    }

    @Test
    @DisplayName("o mesmo código não serve duas vezes")
    void recusaReusoDoMesmoCodigo() {
        UsuarioAdmin admin = novoUsuario();
        String segredo = totp.gerarSegredo();
        totp.ativar(admin, segredo);
        String codigo = totp.codigoAgora(segredo);

        assertThat(totp.confere(admin, codigo)).isTrue();
        assertThat(totp.confere(admin, codigo))
                .as("um código visto por cima do ombro valeria pelo resto da janela")
                .isFalse();
    }

    @Test
    @DisplayName("usuário sem segundo fator ativo nunca confere")
    void usuarioSemTotpNaoConfere() {
        UsuarioAdmin admin = novoUsuario();

        assertThat(totp.confere(admin, "123456")).isFalse();
        assertThat(totp.confere(null, "123456")).isFalse();
    }

    @Test
    @DisplayName("a URI de cadastro traz emissor, conta e parâmetros")
    void uriDeCadastroEhCompleta() {
        String segredo = totp.gerarSegredo();

        String uri = totp.uriDeCadastro("dona@chimaclub.com.br", segredo);

        assertThat(uri)
                .startsWith("otpauth://totp/")
                .contains("secret=" + segredo)
                .contains("issuer=Chima+Club")
                .contains("digits=6")
                .contains("period=30");
    }
}
