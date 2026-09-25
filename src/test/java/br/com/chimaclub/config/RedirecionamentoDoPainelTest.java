package br.com.chimaclub.config;

import br.com.chimaclub.BancoDeTesteBase;
import br.com.chimaclub.admin.UsuarioAdmin;
import br.com.chimaclub.admin.UsuarioAdminRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * O cabeçalho Location de um redirecionamento do painel, medido num Tomcat
 * de verdade.
 *
 * Este teste existe porque o MockMvc não consegue vê-lo. Ele guarda o
 * destino tal como o código o escreveu, e por isso os testes do painel
 * afirmam "/admin/produtos" e passam. O Tomcat, por padrão, faz outra coisa:
 * transforma o caminho num endereço ABSOLUTO, remontado a partir do esquema,
 * do host e da porta que ele julga que a requisição teve. Atrás de um
 * intermediário, esse julgamento vem dos cabeçalhos X-Forwarded-*, e basta
 * um deles faltar ou vir incompleto para o destino apontar para uma origem
 * diferente da que o navegador está usando.
 *
 * <p>O estrago é silencioso, e é o mesmo que já mordeu o botão do WhatsApp
 * duas vezes: a CSP declara <code>form-action 'self'</code>, e essa diretiva
 * vale para CADA salto do redirecionamento que nasce de um formulário. Um
 * destino de outra origem é descartado pelo navegador sem mensagem na tela —
 * só um aviso no console. Quem entra a senha certa fica olhando a tela de
 * login parada; quem entra a senha errada nunca chega ao "?erro" e não vê
 * aviso nenhum. Foi exatamente o que aconteceu em produção.
 *
 * <p>O que este teste fixa, portanto, não é o texto do destino: é que ele
 * seja RELATIVO. Um caminho relativo é resolvido pelo próprio navegador
 * contra a página em que ele está, e por construção nunca troca de origem —
 * não importa o que o intermediário mande, ou deixe de mandar.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RedirecionamentoDoPainelTest extends BancoDeTesteBase {

    private static final String EMAIL = "dona@chimaclub.com.br";
    private static final String SENHA = "uma senha longa o bastante";

    private static final Pattern TOKEN_NO_FORMULARIO =
            Pattern.compile("name=\"_csrf\"\\s+value=\"([^\"]+)\"");

    @Autowired UsuarioAdminRepository usuarios;
    @Autowired PasswordEncoder codificador;

    private final HttpClient cliente = HttpClient.newBuilder()
            // Seguir o redirecionamento aqui esconderia justamente o que se
            // quer medir, que é o cabeçalho Location.
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private String cookie;

    @BeforeEach
    void preparar() {
        usuarios.save(new UsuarioAdmin(EMAIL, "Dona", codificador.encode(SENHA)));
        cookie = null;
    }

    @Test
    @DisplayName("o destino do login bem-sucedido é relativo, e não um endereço absoluto")
    void destinoDoSucessoEhRelativo() throws Exception {
        String destino = destinoDoLogin(EMAIL, SENHA);

        assertThat(destino)
                .as("um destino absoluto é remontado pelo Tomcat a partir de esquema, host e "
                    + "porta; atrás do intermediário isso erra a origem, e a CSP descarta o "
                    + "salto sem mensagem na tela")
                .doesNotStartWith("http://")
                .doesNotStartWith("https://")
                .isEqualTo("/admin/produtos");
    }

    @Test
    @DisplayName("o destino da falha de senha também é relativo, senão o aviso nunca aparece")
    void destinoDaFalhaEhRelativo() throws Exception {
        String destino = destinoDoLogin(EMAIL, "senha errada qualquer");

        assertThat(destino)
                .as("é este salto que carrega o \"?erro\"; bloqueado, quem erra a senha não "
                    + "recebe aviso nenhum")
                .doesNotStartWith("http://")
                .doesNotStartWith("https://")
                .isEqualTo("/admin/login?erro");
    }

    /**
     * O caso que reproduz produção: a aplicação recebe em http, na porta do
     * painel, e é informada por cabeçalho de que a origem pública é outra.
     *
     * <p>Note que o X-Forwarded-Host traz porta, e que o Tomcat a perde ao
     * remontar o endereço — o destino sairia na 443. É o suficiente para o
     * navegador considerar outra origem.
     */
    @Test
    @DisplayName("com cabeçalhos de intermediário, o destino continua relativo")
    void destinoNaoDependeDoQueOIntermediarioInforma() throws Exception {
        String destino = destinoDoLogin(EMAIL, SENHA,
                "X-Forwarded-Proto", "https",
                "X-Forwarded-Host", "painel.exemplo.ts.net:8443");

        assertThat(destino)
                .as("o destino não pode depender de o intermediário reproduzir esquema, host "
                    + "E porta — um caminho relativo dispensa os três")
                .doesNotStartWith("http://")
                .doesNotStartWith("https://")
                .isEqualTo("/admin/produtos");
    }

    // ----------------------------------------------------------------

    private String destinoDoLogin(String email, String senha, String... cabecalhosExtras)
            throws Exception {

        String token = tokenDaTelaDeLogin();
        String corpo = campo("username", email)
                       + "&" + campo("password", senha)
                       + "&" + campo("_csrf", token);

        HttpRequest.Builder envio = HttpRequest.newBuilder(uri("/admin/login"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Cookie", cookie)
                .POST(HttpRequest.BodyPublishers.ofString(corpo));

        for (int i = 0; i < cabecalhosExtras.length; i += 2) {
            envio.header(cabecalhosExtras[i], cabecalhosExtras[i + 1]);
        }

        HttpResponse<String> resposta = enviar(envio.build());

        assertThat(resposta.statusCode())
                .as("sem o 302 não há Location para medir; corpo: %s", resposta.body())
                .isEqualTo(302);

        return resposta.headers().firstValue("Location").orElseThrow(
                () -> new AssertionError("o 302 veio sem Location"));
    }

    private String tokenDaTelaDeLogin() throws Exception {
        HttpResponse<String> resposta = enviar(
                HttpRequest.newBuilder(uri("/admin/login")).GET().build());

        Matcher encontrado = TOKEN_NO_FORMULARIO.matcher(resposta.body());
        if (!encontrado.find()) {
            throw new IllegalStateException("a tela de login não trouxe campo _csrf");
        }
        return encontrado.group(1);
    }

    /** Faz a requisição e adota o cookie de sessão que voltar, como um navegador. */
    private HttpResponse<String> enviar(HttpRequest requisicao) throws IOException, InterruptedException {
        HttpResponse<String> resposta = cliente.send(requisicao, HttpResponse.BodyHandlers.ofString());

        resposta.headers().firstValue("Set-Cookie")
                .map(valor -> valor.split(";", 2)[0])
                .ifPresent(novo -> this.cookie = novo);

        return resposta;
    }

    private static URI uri(String caminho) {
        return URI.create("http://127.0.0.1:" + PORTA_ADMIN_DE_TESTE + caminho);
    }

    private static String campo(String nome, String valor) {
        return nome + "=" + URLEncoder.encode(valor, StandardCharsets.UTF_8);
    }
}
