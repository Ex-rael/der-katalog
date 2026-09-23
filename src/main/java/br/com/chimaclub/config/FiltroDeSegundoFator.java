package br.com.chimaclub.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Guarda a segunda metade do login.
 *
 * Autenticar a senha abre a sessão, mas não abre o painel: enquanto o código
 * do segundo fator não for conferido, a sessão está pela metade e não
 * alcança rota nenhuma. É a diferença entre ter segundo fator e ter uma tela
 * de segundo fator que dá para pular trocando a URL.
 *
 * Em produção o segundo fator é obrigatório; em desenvolvimento fica
 * desligado, como a §8.3 do documento de projeto prevê — senão trabalhar na
 * máquina local exigiria um autenticador a cada reinício.
 */
@Component
public class FiltroDeSegundoFator extends OncePerRequestFilter {

    public static final String SESSAO_SEGUNDO_FATOR_OK = "chimaclub.segundo-fator-conferido";

    /** Rotas que a sessão pela metade ainda precisa alcançar. */
    private static final List<String> LIBERADAS = List.of(
            "/admin/login", "/admin/logout", "/admin/totp/",
            "/css/", "/js/", "/fontes/", "/img/");

    private final boolean exigido;

    public FiltroDeSegundoFator(@Value("${app.segundo-fator-obrigatorio:false}") boolean exigido) {
        this.exigido = exigido;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest requisicao, HttpServletResponse resposta,
                                    FilterChain corrente) throws ServletException, IOException {

        if (!exigido || !precisaDeConferencia(requisicao)) {
            corrente.doFilter(requisicao, resposta);
            return;
        }

        Authentication autenticacao = SecurityContextHolder.getContext().getAuthentication();
        boolean autenticadoPelaSenha = autenticacao != null && autenticacao.isAuthenticated()
                && !"anonymousUser".equals(autenticacao.getPrincipal());

        if (!autenticadoPelaSenha) {
            // Sem sessão nenhuma: quem responde é o Spring Security, que
            // manda para o login. Não é assunto deste filtro.
            corrente.doFilter(requisicao, resposta);
            return;
        }

        HttpSession sessao = requisicao.getSession(false);
        boolean conferido = sessao != null && Boolean.TRUE.equals(sessao.getAttribute(SESSAO_SEGUNDO_FATOR_OK));

        if (conferido) {
            corrente.doFilter(requisicao, resposta);
            return;
        }

        resposta.sendRedirect(requisicao.getContextPath() + "/admin/totp/conferir");
    }

    private static boolean precisaDeConferencia(HttpServletRequest requisicao) {
        String caminho = requisicao.getRequestURI();
        if (!caminho.startsWith("/admin")) {
            return false;
        }
        return LIBERADAS.stream().noneMatch(caminho::startsWith);
    }
}
