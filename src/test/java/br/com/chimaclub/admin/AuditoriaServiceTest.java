package br.com.chimaclub.admin;

import br.com.chimaclub.BancoDeTesteBase;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AuditoriaServiceTest extends BancoDeTesteBase {

    @Autowired AuditoriaService auditoria;
    @Autowired EventoAuditoriaRepository eventos;

    private HttpServletRequest requisicaoDe(String ip) {
        MockHttpServletRequest requisicao = new MockHttpServletRequest();
        requisicao.setRemoteAddr(ip);
        requisicao.addHeader("User-Agent", "Mozilla/5.0 (teste)");
        return requisicao;
    }

    private EventoAuditoria ultimoEvento() {
        return eventos.findAll().stream()
                .max(java.util.Comparator.comparing(EventoAuditoria::getId))
                .orElseThrow();
    }

    @Test
    @DisplayName("registra a tentativa de login com IP e horário")
    void registraLoginComIp() {
        auditoria.registrar(Acao.LOGIN_FALHA, null, null, null,
                Map.of("email", "tentativa@exemplo.com"), requisicaoDe("192.168.1.50"));

        EventoAuditoria evento = ultimoEvento();

        assertThat(evento.getAcao()).isEqualTo(Acao.LOGIN_FALHA.name());
        assertThat(evento.getIp()).isEqualTo("192.168.1.50");
        assertThat(evento.getCriadoEm()).isNotNull();
    }

    @Test
    @DisplayName("um IPv6 é gravado sem estourar a coluna INET")
    void aceitaIpv6() {
        auditoria.registrar(Acao.LOGIN_OK, null, null, null, Map.of(),
                requisicaoDe("2001:db8::8a2e:370:7334"));

        assertThat(ultimoEvento().getIp()).isEqualTo("2001:db8::8a2e:370:7334");
    }

    @Test
    @DisplayName("um IP malformado não impede o registro do evento")
    void ipMalformadoNaoDerrubaORegistro() {
        // O IP vem de cabeçalho ou de socket; se vier torto, o importante é
        // que o evento seja gravado mesmo assim — perder a trilha de
        // auditoria é pior que perder o endereço.
        auditoria.registrar(Acao.LOGIN_FALHA, null, null, null, Map.of(),
                requisicaoDe("nao-e-um-ip"));

        assertThat(ultimoEvento().getAcao()).isEqualTo(Acao.LOGIN_FALHA.name());
        assertThat(ultimoEvento().getIp()).isNull();
    }

    @Test
    @DisplayName("o User-Agent é truncado em vez de estourar a coluna")
    void truncaUserAgentLongo() {
        MockHttpServletRequest requisicao = new MockHttpServletRequest();
        requisicao.setRemoteAddr("10.0.0.1");
        requisicao.addHeader("User-Agent", "x".repeat(2000));

        auditoria.registrar(Acao.LOGIN_OK, null, null, null, Map.of(), requisicao);

        assertThat(ultimoEvento().getUserAgent()).hasSizeLessThanOrEqualTo(300);
    }

    @Test
    @DisplayName("nenhum detalhe sensível é gravado, mesmo se alguém tentar passar")
    void nuncaGravaSenhaNemSegredo() {
        auditoria.registrar(Acao.LOGIN_OK, null, null, null,
                Map.of("senha", "minha-senha-secreta",
                       "totp_segredo", "JBSWY3DPEHPK3PXP",
                       "token", "abc123",
                       "email", "ok@exemplo.com"),
                requisicaoDe("10.0.0.2"));

        String detalhes = String.valueOf(ultimoEvento().getDetalhes());

        assertThat(detalhes)
                .as("chaves sensíveis são descartadas antes de gravar")
                .doesNotContain("minha-senha-secreta")
                .doesNotContain("JBSWY3DPEHPK3PXP")
                .doesNotContain("abc123")
                .contains("ok@exemplo.com");
    }

    @Test
    @DisplayName("o evento guarda quem fez, quando o usuário é conhecido")
    void guardaAutorQuandoConhecido(@Autowired UsuarioAdminRepository usuarios) {
        UsuarioAdmin admin = usuarios.save(
                new UsuarioAdmin("autor@chimaclub.com.br", "Autora", "{bcrypt}$2a$12$qualquercoisa"));

        auditoria.registrar(Acao.PRODUTO_CRIADO, admin, "produto", null,
                Map.of("nome", "Cuia Gold"), requisicaoDe("10.0.0.3"));

        assertThat(ultimoEvento().getUsuario().getId()).isEqualTo(admin.getId());
    }
}
