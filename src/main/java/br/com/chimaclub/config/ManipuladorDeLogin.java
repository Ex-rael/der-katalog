package br.com.chimaclub.config;

import br.com.chimaclub.admin.Acao;
import br.com.chimaclub.admin.AuditoriaService;
import br.com.chimaclub.admin.UsuarioAdmin;
import br.com.chimaclub.admin.UsuarioAdminRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;

/**
 * Trata o desfecho de toda tentativa de login: conta a falha, bloqueia a
 * conta quando for o caso, registra na auditoria e responde.
 *
 * A resposta de falha é sempre exatamente a mesma — o mesmo destino, a mesma
 * mensagem — não importa se o e-mail não existe, se a senha está errada, se
 * a conta está bloqueada ou se está inativa. Qualquer diferença entre esses
 * casos entrega ao atacante quais e-mails existem, e o bloqueio por
 * tentativa deixa de valer para o resto.
 */
@Component
public class ManipuladorDeLogin implements AuthenticationFailureHandler, AuthenticationSuccessHandler {

    private static final String DESTINO_DA_FALHA = "/admin/login?erro";
    private static final String DESTINO_DO_SUCESSO = "/admin/produtos";

    private final UsuarioAdminRepository usuarios;
    private final AuditoriaService auditoria;

    public ManipuladorDeLogin(UsuarioAdminRepository usuarios, AuditoriaService auditoria) {
        this.usuarios = usuarios;
        this.auditoria = auditoria;
    }

    @Override
    @Transactional
    public void onAuthenticationFailure(HttpServletRequest requisicao, HttpServletResponse resposta,
                                        AuthenticationException excecao) throws IOException {

        String email = Optional.ofNullable(requisicao.getParameter("username")).orElse("");

        // A senha tentada nunca entra na auditoria: um usuário que erra o
        // campo digita a senha no lugar do e-mail com frequência suficiente
        // para que isso vire vazamento.
        Optional<UsuarioAdmin> usuario = usuarios.findByEmailIgnoreCase(email);
        usuario.ifPresent(admin -> {
            admin.registrarFalha();
            usuarios.save(admin);
            if (admin.estaBloqueado()) {
                auditoria.registrar(Acao.CONTA_BLOQUEADA, admin,
                        Map.of("falhas", admin.getFalhasLogin()), requisicao);
            }
        });

        auditoria.registrar(Acao.LOGIN_FALHA, usuario.orElse(null),
                Map.of("email_tentado", email), requisicao);

        resposta.sendRedirect(requisicao.getContextPath() + DESTINO_DA_FALHA);
    }

    @Override
    @Transactional
    public void onAuthenticationSuccess(HttpServletRequest requisicao, HttpServletResponse resposta,
                                        Authentication autenticacao) throws IOException {

        Optional<UsuarioAdmin> usuario = usuarios.findByEmailIgnoreCase(autenticacao.getName());
        usuario.ifPresent(admin -> {
            admin.registrarSucesso();
            usuarios.save(admin);
        });

        auditoria.registrar(Acao.LOGIN_OK, usuario.orElse(null), Map.of(), requisicao);

        resposta.sendRedirect(requisicao.getContextPath() + DESTINO_DO_SUCESSO);
    }
}
