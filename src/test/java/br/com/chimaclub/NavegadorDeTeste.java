package br.com.chimaclub;

import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Um navegador de mentira para os testes do painel: guarda a sessão entre
 * requisições e envia o token CSRF que a página realmente renderizou.
 *
 * Não usa o auxiliar csrf() do MockMvc de propósito. O Spring Security
 * protege o token contra o ataque BREACH mascarando-o a cada resposta
 * (XorCsrfTokenRequestAttributeHandler), e o csrf() envia o token cru, que é
 * recusado. Desligar o mascaramento faria os testes passarem à custa de
 * enfraquecer a proteção de verdade; ler o token da página, além de não
 * enfraquecer nada, prova que o formulário entregue ao navegador funciona.
 */
public final class NavegadorDeTeste {

    private static final Pattern TOKEN_NO_FORMULARIO =
            Pattern.compile("name=\"_csrf\"\\s+value=\"([^\"]+)\"");

    private final MockMvc mvc;
    private final int porta;
    /**
     * Não é final: o login troca a sessão de propósito, para impedir fixação
     * de sessão, e um navegador de verdade passa a usar o cookie novo. Sem
     * acompanhar a troca, este auxiliar continuaria apresentando a sessão
     * anônima e o painel nunca abriria.
     */
    private MockHttpSession sessao = new MockHttpSession();

    public NavegadorDeTeste(MockMvc mvc, int porta) {
        this.mvc = mvc;
        this.porta = porta;
    }

    public MvcResult abrir(String caminho) throws Exception {
        return executar(MockMvcRequestBuilders.get(caminho));
    }

    public String corpoDe(String caminho) throws Exception {
        return abrir(caminho).getResponse().getContentAsString();
    }

    /** Envia um formulário com o token CSRF colhido da página indicada. */
    public MvcResult enviar(String paginaDoFormulario, String destino, String... paresDeCampos)
            throws Exception {

        String token = tokenDe(paginaDoFormulario);
        MockHttpServletRequestBuilder envio = MockMvcRequestBuilders.post(destino)
                .param("_csrf", token);

        for (int i = 0; i < paresDeCampos.length; i += 2) {
            envio = envio.param(paresDeCampos[i], paresDeCampos[i + 1]);
        }
        return executar(envio);
    }

    /** Envia sem token, para provar que a proteção de CSRF está mesmo ligada. */
    public MvcResult enviarSemToken(String destino, String... paresDeCampos) throws Exception {
        MockHttpServletRequestBuilder envio = MockMvcRequestBuilders.post(destino);
        for (int i = 0; i < paresDeCampos.length; i += 2) {
            envio = envio.param(paresDeCampos[i], paresDeCampos[i + 1]);
        }
        return executar(envio);
    }

    /** Faz a requisição na sessão corrente e adota a sessão que voltar. */
    private MvcResult executar(MockHttpServletRequestBuilder builder) throws Exception {
        MvcResult resultado = mvc.perform(naPorta(builder.session(sessao))).andReturn();

        if (resultado.getRequest().getSession(false) instanceof MockHttpSession nova) {
            this.sessao = nova;
        }
        return resultado;
    }

    public String tokenDe(String caminho) throws Exception {
        Matcher encontrado = TOKEN_NO_FORMULARIO.matcher(corpoDe(caminho));
        if (!encontrado.find()) {
            throw new IllegalStateException(
                    "a página " + caminho + " não trouxe campo _csrf; sem ele nenhum formulário funciona");
        }
        return encontrado.group(1);
    }

    public MockHttpSession getSessao() {
        return sessao;
    }

    /**
     * Sem declarar a porta local, o securityMatcher escolheria a cadeia
     * pública e todo teste do painel passaria a medir a coisa errada.
     */
    private MockHttpServletRequestBuilder naPorta(MockHttpServletRequestBuilder builder) {
        return builder.with(requisicao -> {
            requisicao.setLocalPort(porta);
            return requisicao;
        });
    }
}
