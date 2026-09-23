package br.com.chimaclub.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Aplica o limite de requisições antes de qualquer outra coisa.
 *
 * Antes da cadeia de segurança, e antes do roteamento: se a intenção é
 * poupar o servidor de um visitante abusivo, não adianta recusá-lo depois de
 * já ter consultado o banco e renderizado a página.
 */
@Component
@Order(Integer.MIN_VALUE + 100)
public class FiltroDeLimite extends OncePerRequestFilter {

    private static final Logger LOG = LoggerFactory.getLogger(FiltroDeLimite.class);

    private final LimiteDeRequisicoes limite;
    private final IpDeOrigem ipDeOrigem;

    public FiltroDeLimite(LimiteDeRequisicoes limite, IpDeOrigem ipDeOrigem) {
        this.limite = limite;
        this.ipDeOrigem = ipDeOrigem;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest requisicao, HttpServletResponse resposta,
                                    FilterChain corrente) throws ServletException, IOException {

        LimiteDeRequisicoes.Faixa faixa = limite.faixaDe(requisicao);
        String ip = ipDeOrigem.de(requisicao);

        if (limite.permitir(ip, faixa)) {
            corrente.doFilter(requisicao, resposta);
            return;
        }

        LOG.warn("limite de requisições atingido: faixa={} caminho={}", faixa, requisicao.getRequestURI());

        // 429 com Retry-After: é o que um cliente bem comportado precisa para
        // esperar em vez de insistir. O corpo é uma frase, sem detalhe do
        // limite nem do que resta — informar isso ajudaria a calibrar o abuso.
        resposta.setStatus(429);
        resposta.setHeader("Retry-After", String.valueOf(limite.segundosParaTentarDeNovo(faixa)));
        resposta.setContentType("text/plain;charset=UTF-8");
        resposta.getWriter().write("Muitas requisições. Tente de novo em instantes.");
    }

    /**
     * O despacho interno de erro não conta: senão uma requisição barrada
     * consumiria duas fichas, e a página de erro deixaria de ser servida
     * justamente para quem mais precisa dela.
     */
    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return true;
    }

    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return true;
    }
}
