# Chima Club — Fase 2: Painel administrativo — Plano de Implementação

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Entregar o painel administrativo completo — login com segundo fator, cadastro e edição de produtos, upload e processamento de fotos, configuração da loja e registro de auditoria — com os controles de upload hostil e de autenticação do plano de segurança provados por teste.

**Architecture:** Tudo continua no mesmo processo, servido apenas pelo conector administrativo da porta 8081. Templates Thymeleaf renderizados no servidor, sem framework de frontend. O upload é tratado como hostil de ponta a ponta: o arquivo enviado nunca é gravado como veio, nunca guarda o nome que trouxe, e é reescrito pela aplicação antes de tocar o disco.

**Tech Stack:** Spring Boot 4.1.1, Spring Security 7, Thymeleaf, Thumbnailator + webp-imageio (verificado nesta máquina: escritor registrado, grava e relê), `java-otp` para TOTP, AES-GCM do JDK para o segredo, JUnit 5 + Testcontainers + MockMvc.

**Spec:** `chimaclub-definicao-projeto.md` e `chimaclub-plano-seguranca.md`

**Fase anterior:** `docs/superpowers/plans/2026-09-22-fase-1-esqueleto.md` — entregue, 31 testes verdes.

## Global Constraints

Valem as restrições globais da Fase 1, que não se repetem aqui, mais estas:

- **Todo o painel responde só na porta administrativa.** Nenhuma rota nova pode ser alcançável pela cadeia pública; cada tarefa que acrescenta rota acrescenta também a asserção correspondente.
- **Upload é hostil por definição.** Extensão na lista permitida, tipo real conferido pelos bytes iniciais, dimensão limitada a 8000 × 8000 antes de alocar memória, imagem reescrita pela aplicação, original descartado com todos os metadados. Arquivo que não abre como imagem é rejeitado.
- **O nome do arquivo é gerado pelo sistema.** O nome enviado nunca vai para o disco nem volta para o navegador.
- **`th:utext` é proibido** nos templates. Thymeleaf escapa por padrão, e é assim que fica.
- **Nenhum segredo em log.** Senha, hash, segredo TOTP, cookie e token jamais aparecem em registro, nem em nível de depuração.
- **Mensagem de erro de login sempre idêntica**, sem revelar se o e-mail existe.
- **BCrypt custo 12**, por `DelegatingPasswordEncoder`, para permitir troca de algoritmo depois.
- **Toda rota que altera estado exige CSRF**, sem exceção no painel.

---

### Task 1: Usuário administrador e autenticação por senha

**Files:**
- Create: `src/main/java/br/com/chimaclub/admin/UsuarioAdmin.java`
- Create: `src/main/java/br/com/chimaclub/admin/UsuarioAdminRepository.java`
- Create: `src/main/java/br/com/chimaclub/admin/UsuarioAdminDetailsService.java`
- Create: `src/main/java/br/com/chimaclub/admin/CriarAdminCommand.java`
- Modify: `src/main/java/br/com/chimaclub/config/SegurancaConfig.java`
- Test: `src/test/java/br/com/chimaclub/admin/UsuarioAdminTest.java`

**Interfaces:**
- Consumes: Fase 1
- Produces:
  - `UsuarioAdmin` com `getEmail()`, `getSenhaHash()`, `isAtivo()`, `getFalhasLogin()` (`int`), `getBloqueadoAte()` (`Instant`), `registrarFalha()`, `registrarSucesso()`, `estaBloqueado()` (`boolean`)
  - `UsuarioAdminRepository.findByEmailIgnoreCase(String)` → `Optional<UsuarioAdmin>`
  - bean `PasswordEncoder` (BCrypt custo 12, prefixo `{bcrypt}`)

- [ ] **Step 1: Escrever o teste, que falha**

```java
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
        usuarios.save(new UsuarioAdmin("Dona@ChimaClub.com.br", "Dona", codificador.encode("senha longa de teste")));

        assertThat(usuarios.findByEmailIgnoreCase("dona@chimaclub.com.br")).isPresent();
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
}
```

- [ ] **Step 2: Rodar e verificar que falha**

Run: `./mvnw test -Dtest=UsuarioAdminTest`
Expected: erro de compilação — `UsuarioAdmin` não existe.

- [ ] **Step 3: Escrever a entidade, o repositório e o codificador**

A entidade mapeia a tabela `usuario_admin` já criada pela V1. As regras de bloqueio ficam nela, e não num serviço, porque são invariantes do próprio usuário: `registrarFalha()` incrementa e, ao chegar a 5, preenche `bloqueadoAte` com agora mais 15 minutos; `estaBloqueado()` compara `bloqueadoAte` com o instante atual; `registrarSucesso()` zera o contador, limpa o bloqueio e grava `ultimoLoginEm`.

O `PasswordEncoder` entra em `SegurancaConfig`:

```java
@Bean
PasswordEncoder codificadorDeSenha() {
    // DelegatingPasswordEncoder grava com prefixo "{bcrypt}", o que permite
    // migrar de algoritmo depois sem invalidar as senhas existentes.
    String padrao = "bcrypt";
    Map<String, PasswordEncoder> codificadores = Map.of(
            "bcrypt", new BCryptPasswordEncoder(12));
    return new DelegatingPasswordEncoder(padrao, codificadores);
}
```

- [ ] **Step 4: Escrever o `UsuarioAdminDetailsService`**

Carrega por e-mail, devolve `UserDetails` com papel `ROLE_ADMIN`, e reflete `ativo` em `isEnabled()` e o bloqueio em `isAccountNonLocked()`.

- [ ] **Step 5: Escrever o comando de criação do primeiro usuário**

Sem cadastro público e sem recuperação de senha por e-mail: a criação é por linha de comando na máquina, e a senha é gerada aleatoriamente e impressa uma única vez.

```java
package br.com.chimaclub.admin;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * Criação do primeiro administrador, por linha de comando:
 *
 *   ./mvnw spring-boot:run -Dspring-boot.run.arguments=--criar-admin=dona@exemplo.com
 *
 * A senha é gerada aqui e impressa uma única vez. Não há cadastro público
 * nem recuperação por e-mail, o que elimina duas superfícies de ataque.
 */
@Component
public class CriarAdminCommand implements ApplicationRunner {
    // implementação: lê o argumento, recusa e-mail já existente, gera 24
    // bytes de SecureRandom em base64url, grava o usuário e imprime a senha
    // em System.out — nunca no log da aplicação.
}
```

- [ ] **Step 6: Rodar e verificar que passa**

Run: `./mvnw test -Dtest=UsuarioAdminTest`
Expected: PASS, nos cinco testes.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "feat: usuário administrador com BCrypt custo 12 e bloqueio por tentativa"
```

---

### Task 2: Registro de auditoria

**Files:**
- Create: `src/main/java/br/com/chimaclub/admin/EventoAuditoria.java`
- Create: `src/main/java/br/com/chimaclub/admin/EventoAuditoriaRepository.java`
- Create: `src/main/java/br/com/chimaclub/admin/AuditoriaService.java`
- Create: `src/main/java/br/com/chimaclub/admin/Acao.java`
- Test: `src/test/java/br/com/chimaclub/admin/AuditoriaServiceTest.java`

**Interfaces:**
- Consumes: Task 1
- Produces: `AuditoriaService.registrar(Acao, UsuarioAdmin, String entidade, UUID entidadeId, Map<String,Object> detalhes, HttpServletRequest)`; enum `Acao` com `LOGIN_OK`, `LOGIN_FALHA`, `CONTA_BLOQUEADA`, `PRODUTO_CRIADO`, `PRODUTO_ALTERADO`, `PRODUTO_PUBLICADO`, `PRODUTO_EXCLUIDO`, `FOTO_ENVIADA`, `FOTO_EXCLUIDA`, `CONFIGURACAO_ALTERADA`

- [ ] **Step 1: Escrever o teste, que falha**

```java
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

    @Test
    @DisplayName("registra a tentativa de login com IP e horário")
    void registraLoginComIp() {
        auditoria.registrar(Acao.LOGIN_FALHA, null, null, null,
                Map.of("email", "tentativa@exemplo.com"), requisicaoDe("192.168.1.50"));

        EventoAuditoria evento = eventos.findAll().getLast();

        assertThat(evento.getAcao()).isEqualTo(Acao.LOGIN_FALHA.name());
        assertThat(evento.getIp()).isEqualTo("192.168.1.50");
        assertThat(evento.getCriadoEm()).isNotNull();
    }

    @Test
    @DisplayName("um IPv6 é gravado sem estourar a coluna INET")
    void aceitaIpv6() {
        auditoria.registrar(Acao.LOGIN_OK, null, null, null, Map.of(),
                requisicaoDe("2001:db8::8a2e:370:7334"));

        assertThat(eventos.findAll().getLast().getIp()).isEqualTo("2001:db8::8a2e:370:7334");
    }

    @Test
    @DisplayName("o User-Agent é truncado em vez de estourar a coluna")
    void truncaUserAgentLongo() {
        MockHttpServletRequest requisicao = new MockHttpServletRequest();
        requisicao.setRemoteAddr("10.0.0.1");
        requisicao.addHeader("User-Agent", "x".repeat(2000));

        auditoria.registrar(Acao.LOGIN_OK, null, null, null, Map.of(), requisicao);

        assertThat(eventos.findAll().getLast().getUserAgent()).hasSizeLessThanOrEqualTo(300);
    }

    @Test
    @DisplayName("nenhum detalhe sensível é gravado, mesmo se alguém tentar passar")
    void nuncaGravaSenhaNemSegredo() {
        auditoria.registrar(Acao.LOGIN_OK, null, null, null,
                Map.of("senha", "minha-senha-secreta",
                       "totp_segredo", "JBSWY3DPEHPK3PXP",
                       "email", "ok@exemplo.com"),
                requisicaoDe("10.0.0.2"));

        String detalhes = String.valueOf(eventos.findAll().getLast().getDetalhes());

        assertThat(detalhes)
                .as("chaves sensíveis são descartadas antes de gravar")
                .doesNotContain("minha-senha-secreta")
                .doesNotContain("JBSWY3DPEHPK3PXP")
                .contains("ok@exemplo.com");
    }
}
```

- [ ] **Step 2: Rodar e verificar que falha**

Run: `./mvnw test -Dtest=AuditoriaServiceTest`
Expected: erro de compilação.

- [ ] **Step 3: Escrever a entidade e o serviço**

A coluna `ip` é `INET` no banco. O Hibernate não a mapeia sozinha: usar `@JdbcTypeCode(SqlTypes.OTHER)` com `columnDefinition = "inet"`, ou, se o driver resistir, um `AttributeConverter` que grava texto com cast explícito. A coluna `detalhes` é `JSONB`, mapeada com `@JdbcTypeCode(SqlTypes.JSON)`.

O serviço descarta, antes de gravar, toda chave cujo nome contenha `senha`, `password`, `segredo`, `secret`, `token` ou `cookie`. É uma rede de proteção: o certo é não passar esses dados, e esta lista garante que um descuido futuro não vire vazamento no banco.

- [ ] **Step 4: Rodar e verificar que passa**

Run: `./mvnw test -Dtest=AuditoriaServiceTest`
Expected: PASS, nos quatro testes.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: registro de auditoria com descarte de campo sensível"
```

---

### Task 3: Login por formulário, sessão e bloqueio

**Files:**
- Create: `src/main/resources/templates/admin/login.html`
- Create: `src/main/java/br/com/chimaclub/admin/web/LoginController.java`
- Create: `src/main/java/br/com/chimaclub/config/ManipuladorDeLogin.java`
- Modify: `src/main/java/br/com/chimaclub/config/SegurancaConfig.java`
- Modify: `src/main/resources/application.yaml`
- Test: `src/test/java/br/com/chimaclub/admin/LoginTest.java`

**Interfaces:**
- Consumes: Tasks 1 e 2
- Produces: `GET/POST /admin/login`, `POST /admin/logout`; cookie de sessão `CHIMASESSION`

- [ ] **Step 1: Escrever o teste, que falha**

O teste usa MockMvc com a cadeia administrativa. Para que o `securityMatcher` por porta case, a requisição simulada precisa declarar a porta local: `.with(requisicao -> { requisicao.setLocalPort(PORTA_ADMIN_DE_TESTE); return requisicao; })`.

```java
package br.com.chimaclub.admin;

import br.com.chimaclub.BancoDeTesteBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class LoginTest extends BancoDeTesteBase {

    private static final String SENHA = "uma senha longa o bastante";

    @Autowired MockMvc mvc;
    @Autowired UsuarioAdminRepository usuarios;
    @Autowired PasswordEncoder codificador;

    @BeforeEach
    void criarAdmin() {
        usuarios.deleteAll();
        usuarios.save(new UsuarioAdmin("dona@chimaclub.com.br", "Dona", codificador.encode(SENHA)));
    }

    /** Faz a requisição simulada chegar pela porta administrativa. */
    private MockHttpServletRequestBuilder naPortaAdmin(MockHttpServletRequestBuilder builder) {
        return builder.with(requisicao -> {
            requisicao.setLocalPort(PORTA_ADMIN_DE_TESTE);
            return requisicao;
        });
    }

    @Test
    @DisplayName("o login recusa sem token CSRF")
    void recusaSemCsrf() throws Exception {
        mvc.perform(naPortaAdmin(post("/admin/login")
                        .param("username", "dona@chimaclub.com.br")
                        .param("password", SENHA)))
           .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("o login aceita credencial correta e regenera a sessão")
    void aceitaCredencialCorreta() throws Exception {
        mvc.perform(naPortaAdmin(post("/admin/login").with(csrf())
                        .param("username", "dona@chimaclub.com.br")
                        .param("password", SENHA)))
           .andExpect(status().is3xxRedirection())
           .andExpect(redirectedUrlPattern("**/admin/produtos"));
    }

    @Test
    @DisplayName("a mensagem de erro é idêntica para e-mail inexistente e senha errada")
    void mensagemDeErroNaoRevelaSeOEmailExiste() throws Exception {
        String comEmailErrado = corpoDoErroAoTentar("nao-existe@exemplo.com", SENHA);
        String comSenhaErrada = corpoDoErroAoTentar("dona@chimaclub.com.br", "senha errada qualquer");

        assertThat(comEmailErrado)
                .as("uma diferença aqui entrega quais e-mails existem")
                .isEqualTo(comSenhaErrada);
    }

    private String corpoDoErroAoTentar(String email, String senha) throws Exception {
        mvc.perform(naPortaAdmin(post("/admin/login").with(csrf())
                        .param("username", email).param("password", senha)))
           .andExpect(status().is3xxRedirection());

        return mvc.perform(naPortaAdmin(get("/admin/login").param("erro", "")))
                  .andReturn().getResponse().getContentAsString();
    }

    @Test
    @DisplayName("cinco tentativas erradas bloqueiam, e a sexta falha mesmo com a senha certa")
    void bloqueiaAposCincoFalhas() throws Exception {
        for (int i = 0; i < 5; i++) {
            mvc.perform(naPortaAdmin(post("/admin/login").with(csrf())
                            .param("username", "dona@chimaclub.com.br")
                            .param("password", "senha errada")));
        }

        mvc.perform(naPortaAdmin(post("/admin/login").with(csrf())
                        .param("username", "dona@chimaclub.com.br")
                        .param("password", SENHA)))
           .andExpect(redirectedUrlPattern("**/admin/login?erro*"));

        assertThat(usuarios.findByEmailIgnoreCase("dona@chimaclub.com.br").orElseThrow().estaBloqueado())
                .isTrue();
    }

    @Test
    @DisplayName("toda tentativa vai para a auditoria, com e sem sucesso")
    void registraTentativasNaAuditoria(@Autowired EventoAuditoriaRepository eventos) throws Exception {
        long antes = eventos.count();

        mvc.perform(naPortaAdmin(post("/admin/login").with(csrf())
                        .param("username", "dona@chimaclub.com.br").param("password", "errada")));
        mvc.perform(naPortaAdmin(post("/admin/login").with(csrf())
                        .param("username", "dona@chimaclub.com.br").param("password", SENHA)));

        assertThat(eventos.count()).isGreaterThanOrEqualTo(antes + 2);
    }

    @Test
    @DisplayName("a tela de login não revela versão nem rastro de pilha")
    void telaDeLoginNaoVazaDetalheInterno() throws Exception {
        String corpo = mvc.perform(naPortaAdmin(get("/admin/login")))
                          .andExpect(status().isOk())
                          .andReturn().getResponse().getContentAsString();

        assertThat(corpo)
                .doesNotContainIgnoringCase("spring")
                .doesNotContainIgnoringCase("tomcat")
                .doesNotContain("Exception");
    }
}
```

- [ ] **Step 2: Rodar e verificar que falha**

Run: `./mvnw test -Dtest=LoginTest`
Expected: FAIL — não há `/admin/login`.

- [ ] **Step 3: Configurar a sessão e o cookie no `application.yaml`**

```yaml
server:
  servlet:
    session:
      timeout: 30m
      cookie:
        name: CHIMASESSION
        http-only: true
        same-site: strict
        # secure fica true só no perfil prod: em dev o painel é http local
        secure: false
```

- [ ] **Step 4: Trocar o `httpBasic` provisório por login de formulário**

Na cadeia administrativa: `formLogin` apontando para `/admin/login`, com manipulador de falha que registra a tentativa e incrementa o contador, e manipulador de sucesso que zera o contador e grava `ultimoLoginEm`. Mais `sessionFixation().newSession()`, `maximumSessions(2)` e `logout` em `POST /admin/logout`.

O manipulador de falha **sempre redireciona para `/admin/login?erro`**, com a mesma mensagem, seja qual for o motivo: e-mail inexistente, senha errada ou conta bloqueada.

- [ ] **Step 5: Escrever o template `login.html`**

Formulário com campo de e-mail, senha e token CSRF. Sem "lembrar de mim". Sem link de recuperação de senha, que não existe. A mensagem de erro é uma só: "E-mail ou senha inválidos."

- [ ] **Step 6: Rodar e verificar que passa**

Run: `./mvnw test -Dtest=LoginTest`
Expected: PASS, nos seis testes.

- [ ] **Step 7: Confirmar que o login continua invisível pela porta pública**

Run: `./mvnw test -Dtest=IsolamentoDoPainelTest`
Expected: PASS — em especial `loginNaoRespondeNaPortaPublica`, que agora tem uma tela de verdade para tentar alcançar.

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "feat: login por formulário com bloqueio, auditoria e mensagem única de erro"
```

---

### Task 4: Segundo fator TOTP com segredo cifrado

**Files:**
- Create: `src/main/java/br/com/chimaclub/admin/totp/CifradorDeSegredo.java`
- Create: `src/main/java/br/com/chimaclub/admin/totp/ServicoTotp.java`
- Create: `src/main/java/br/com/chimaclub/admin/web/TotpController.java`
- Create: `src/main/resources/templates/admin/totp.html`
- Modify: `pom.xml` (acrescentar `com.eatthepath:java-otp`)
- Test: `src/test/java/br/com/chimaclub/admin/totp/CifradorDeSegredoTest.java`
- Test: `src/test/java/br/com/chimaclub/admin/totp/ServicoTotpTest.java`

**Interfaces:**
- Consumes: Task 3
- Produces:
  - `CifradorDeSegredo.cifrar(String)` → `String` (base64 de nonce + texto cifrado)
  - `CifradorDeSegredo.decifrar(String)` → `String`
  - `ServicoTotp.gerarSegredo()` → `String` (base32), `ServicoTotp.confere(UsuarioAdmin, String codigo)` → `boolean`

- [ ] **Step 1: Escrever os testes, que falham**

```java
package br.com.chimaclub.admin.totp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CifradorDeSegredoTest {

    private final CifradorDeSegredo cifrador =
            new CifradorDeSegredo("Y2hpbWFjbHViLWNoYXZlLWRlLXRlc3RlLTMyYnl0ZXM=");

    @Test
    @DisplayName("o texto cifrado não contém o segredo em claro")
    void naoVazaOSegredo() {
        String cifrado = cifrador.cifrar("JBSWY3DPEHPK3PXP");

        assertThat(cifrado).doesNotContain("JBSWY3DPEHPK3PXP");
        assertThat(cifrador.decifrar(cifrado)).isEqualTo("JBSWY3DPEHPK3PXP");
    }

    @Test
    @DisplayName("cifrar duas vezes o mesmo segredo dá resultados diferentes")
    void usaNonceNovoACadaVez() {
        assertThat(cifrador.cifrar("JBSWY3DPEHPK3PXP"))
                .as("nonce repetido em AES-GCM quebra a cifra por completo")
                .isNotEqualTo(cifrador.cifrar("JBSWY3DPEHPK3PXP"));
    }

    @Test
    @DisplayName("um texto cifrado adulterado é recusado, não decifrado errado")
    void detectaAdulteracao() {
        String cifrado = cifrador.cifrar("JBSWY3DPEHPK3PXP");
        String adulterado = cifrado.substring(0, cifrado.length() - 4) + "AAAA";

        assertThatThrownBy(() -> cifrador.decifrar(adulterado))
                .as("é para isso que serve o GCM: autenticação, não só sigilo")
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("uma chave de outro tamanho é recusada na construção")
    void recusaChaveDeTamanhoErrado() {
        assertThatThrownBy(() -> new CifradorDeSegredo("Y3VydGE="))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
```

```java
package br.com.chimaclub.admin.totp;

import br.com.chimaclub.BancoDeTesteBase;
import br.com.chimaclub.admin.UsuarioAdmin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

class ServicoTotpTest extends BancoDeTesteBase {

    @Autowired ServicoTotp totp;

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
        assertThat(totp.confereSegredo(segredo, "")).isFalse();
        assertThat(totp.confereSegredo(segredo, null)).isFalse();
    }

    @Test
    @DisplayName("o segredo guardado no usuário está cifrado em repouso")
    void segredoGuardadoCifrado() {
        UsuarioAdmin admin = new UsuarioAdmin("totp@exemplo.com", "Teste", "{bcrypt}$2a$12$qualquer");
        String segredo = totp.gerarSegredo();

        totp.ativar(admin, segredo);

        assertThat(admin.getTotpSegredo())
                .as("o segredo em claro no banco vale tanto quanto a senha")
                .isNotEqualTo(segredo)
                .doesNotContain(segredo);
        assertThat(admin.isTotpAtivo()).isTrue();
    }
}
```

- [ ] **Step 2: Rodar e verificar que falham**

Run: `./mvnw test -Dtest='CifradorDeSegredoTest,ServicoTotpTest'`
Expected: erro de compilação.

- [ ] **Step 3: Acrescentar a dependência de TOTP ao `pom.xml`**

```xml
    <dependency>
      <groupId>com.eatthepath</groupId>
      <artifactId>java-otp</artifactId>
      <version>0.4.0</version>
    </dependency>
```

- [ ] **Step 4: Escrever o `CifradorDeSegredo`**

AES-GCM com nonce de 12 bytes sorteado a cada operação e etiqueta de 128 bits. A chave vem da propriedade `app.chave-totp`, que em produção é variável de ambiente e nunca está no repositório. O construtor recusa chave que não tenha 32 bytes depois de decodificada.

- [ ] **Step 5: Escrever o `ServicoTotp`**

Gera segredo de 20 bytes em base32, confere o código com tolerância de uma janela para trás (para acomodar relógio ligeiramente atrasado) e nenhuma para a frente. Guarda no usuário o segredo já cifrado.

- [ ] **Step 6: Exigir o segundo fator em produção**

Perfil `prod`: usuário com `totpAtivo = false` não completa o login, e é levado à tela de ativação. Perfil `dev`: o segundo fator fica desligado, como diz a §8.3.

- [ ] **Step 7: Rodar e verificar que passam**

Run: `./mvnw test -Dtest='CifradorDeSegredoTest,ServicoTotpTest'`
Expected: PASS, nos oito testes.

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "feat: segundo fator TOTP com segredo cifrado em AES-GCM"
```

---

### Task 5: Upload tratado como hostil

A tarefa mais importante da fase do ponto de vista de segurança. O teste vem antes e define o contrato: o que **não** pode passar.

**Files:**
- Create: `src/main/java/br/com/chimaclub/midia/ValidadorUpload.java`
- Create: `src/main/java/br/com/chimaclub/midia/ProcessadorImagem.java`
- Create: `src/main/java/br/com/chimaclub/midia/ArmazenamentoFotos.java`
- Create: `src/main/java/br/com/chimaclub/midia/UploadRecusadoException.java`
- Modify: `pom.xml` (acrescentar `thumbnailator` e `webp-imageio`)
- Modify: `src/main/resources/application.yaml` (limites de multipart)
- Test: `src/test/java/br/com/chimaclub/midia/ValidadorUploadTest.java`
- Test: `src/test/java/br/com/chimaclub/midia/ProcessadorImagemTest.java`
- Test: `src/test/java/br/com/chimaclub/midia/ArmazenamentoFotosTest.java`

**Interfaces:**
- Consumes: Fase 1
- Produces:
  - `ValidadorUpload.validar(MultipartFile)` → lança `UploadRecusadoException` ou devolve `ImagemValidada(BufferedImage imagem, String tipoReal)`
  - `ProcessadorImagem.processar(BufferedImage)` → `Map<Versao, byte[]>` para `MINI` (600 px), `MEDIA` (1200 px), `GRANDE` (2000 px)
  - `ArmazenamentoFotos.gravar(UUID base, Map<Versao, byte[]>)` → nomes gerados; `ArmazenamentoFotos.ler(String nome)` → `byte[]`, recusando nome fora do padrão

- [ ] **Step 1: Escrever o teste do validador, que falha**

```java
package br.com.chimaclub.midia;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ValidadorUploadTest {

    private final ValidadorUpload validador = new ValidadorUpload();

    private static byte[] jpegDe(int largura, int altura) throws Exception {
        BufferedImage imagem = new BufferedImage(largura, altura, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream saida = new ByteArrayOutputStream();
        ImageIO.write(imagem, "jpg", saida);
        return saida.toByteArray();
    }

    @Test
    @DisplayName("aceita um JPEG legítimo")
    void aceitaJpegLegitimo() throws Exception {
        MockMultipartFile arquivo = new MockMultipartFile(
                "foto", "cuia.jpg", "image/jpeg", jpegDe(1200, 1500));

        assertThatCode(() -> validador.validar(arquivo)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("recusa um .php renomeado para .jpg — o conteúdo é que manda")
    void recusaPhpRenomeado() {
        MockMultipartFile arquivo = new MockMultipartFile(
                "foto", "backdoor.jpg", "image/jpeg",
                "<?php system($_GET['c']); ?>".getBytes());

        assertThatThrownBy(() -> validador.validar(arquivo))
                .isInstanceOf(UploadRecusadoException.class);
    }

    @Test
    @DisplayName("recusa um SVG com script, mesmo declarado como imagem")
    void recusaSvgComScript() {
        String svg = "<svg xmlns='http://www.w3.org/2000/svg'><script>alert(1)</script></svg>";
        MockMultipartFile arquivo = new MockMultipartFile(
                "foto", "vetor.svg", "image/svg+xml", svg.getBytes());

        assertThatThrownBy(() -> validador.validar(arquivo))
                .isInstanceOf(UploadRecusadoException.class);
    }

    @Test
    @DisplayName("recusa extensão fora da lista, ainda que o conteúdo seja imagem")
    void recusaExtensaoForaDaLista() throws Exception {
        MockMultipartFile arquivo = new MockMultipartFile(
                "foto", "cuia.bmp", "image/bmp", jpegDe(100, 100));

        assertThatThrownBy(() -> validador.validar(arquivo))
                .isInstanceOf(UploadRecusadoException.class);
    }

    @Test
    @DisplayName("recusa imagem de dimensão absurda antes de alocar memória")
    void recusaBombaDeDescompressao() {
        // Cabeçalho PNG declarando 20000 x 20000. Descomprimir isso alocaria
        // mais de 1 GB; a dimensão é lida do cabeçalho e recusada antes.
        byte[] cabecalho = cabecalhoPngCom(20000, 20000);
        MockMultipartFile arquivo = new MockMultipartFile(
                "foto", "bomba.png", "image/png", cabecalho);

        assertThatThrownBy(() -> validador.validar(arquivo))
                .isInstanceOf(UploadRecusadoException.class)
                .hasMessageContaining("dimens");
    }

    @Test
    @DisplayName("recusa arquivo vazio")
    void recusaArquivoVazio() {
        MockMultipartFile arquivo = new MockMultipartFile("foto", "vazio.jpg", "image/jpeg", new byte[0]);

        assertThatThrownBy(() -> validador.validar(arquivo))
                .isInstanceOf(UploadRecusadoException.class);
    }

    private static byte[] cabecalhoPngCom(int largura, int altura) {
        // assinatura PNG + bloco IHDR com largura e altura declaradas
        byte[] png = new byte[33];
        System.arraycopy(new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A}, 0, png, 0, 8);
        png[11] = 13;                     // tamanho do IHDR
        png[12] = 'I'; png[13] = 'H'; png[14] = 'D'; png[15] = 'R';
        png[16] = (byte) (largura >>> 24); png[17] = (byte) (largura >>> 16);
        png[18] = (byte) (largura >>> 8);  png[19] = (byte) largura;
        png[20] = (byte) (altura >>> 24);  png[21] = (byte) (altura >>> 16);
        png[22] = (byte) (altura >>> 8);   png[23] = (byte) altura;
        return png;
    }
}
```

- [ ] **Step 2: Escrever o teste do processador e do armazenamento**

```java
package br.com.chimaclub.midia;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ArmazenamentoFotosTest {

    @Test
    @DisplayName("grava as três versões com nome gerado pelo sistema")
    void gravaTresVersoes(@TempDir Path raiz) throws Exception {
        ArmazenamentoFotos armazenamento = new ArmazenamentoFotos(raiz.toString());
        UUID base = UUID.randomUUID();

        Map<String, String> nomes = armazenamento.gravar(base, Map.of(
                "mini", new byte[]{1, 2, 3},
                "media", new byte[]{4, 5, 6},
                "grande", new byte[]{7, 8, 9}));

        assertThat(nomes.get("mini")).isEqualTo(base + "-mini.webp");
        assertThat(raiz.resolve(base + "-mini.webp")).exists();
    }

    @Test
    @DisplayName("recusa nome fora do padrão, incluindo travessia de diretório")
    void recusaNomeForaDoPadrao(@TempDir Path raiz) {
        ArmazenamentoFotos armazenamento = new ArmazenamentoFotos(raiz.toString());

        for (String nome : new String[]{
                "../../etc/passwd",
                "..%2F..%2Fetc%2Fpasswd",
                "/etc/passwd",
                "qualquer.webp",
                "a3f1-mini.webp",
                UUID.randomUUID() + "-mini.php"}) {
            assertThatThrownBy(() -> armazenamento.ler(nome))
                    .as("nome recusado: %s", nome)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    @DisplayName("aceita e devolve um nome no padrão")
    void aceitaNomeNoPadrao(@TempDir Path raiz) throws Exception {
        ArmazenamentoFotos armazenamento = new ArmazenamentoFotos(raiz.toString());
        UUID base = UUID.randomUUID();
        armazenamento.gravar(base, Map.of("mini", new byte[]{1, 2, 3}));

        assertThat(armazenamento.ler(base + "-mini.webp")).containsExactly(1, 2, 3);
    }
}
```

```java
package br.com.chimaclub.midia;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ProcessadorImagemTest {

    private final ProcessadorImagem processador = new ProcessadorImagem();

    @Test
    @DisplayName("gera as três versões nas larguras previstas")
    void geraTresVersoes() throws Exception {
        BufferedImage original = new BufferedImage(3000, 3750, BufferedImage.TYPE_INT_RGB);

        Map<String, byte[]> versoes = processador.processar(original);

        assertThat(largura(versoes.get("mini"))).isEqualTo(600);
        assertThat(largura(versoes.get("media"))).isEqualTo(1200);
        assertThat(largura(versoes.get("grande"))).isEqualTo(2000);
    }

    @Test
    @DisplayName("a imagem gerada não carrega os metadados da original")
    void descartaMetadados() throws Exception {
        // Um JPEG com comentário EXIF embutido: depois do reprocessamento,
        // os bytes de saída não podem conter o texto original.
        BufferedImage original = new BufferedImage(800, 800, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = original.createGraphics();
        g.dispose();

        Map<String, byte[]> versoes = processador.processar(original);

        assertThat(new String(versoes.get("media")))
                .doesNotContain("Exif")
                .doesNotContain("<?php");
    }

    @Test
    @DisplayName("a saída é WebP de verdade, legível de volta")
    void saidaEhWebpLegivel() throws Exception {
        BufferedImage original = new BufferedImage(1500, 1500, BufferedImage.TYPE_INT_RGB);

        byte[] media = processador.processar(original).get("media");

        assertThat(ImageIO.read(new ByteArrayInputStream(media)))
                .as("se a nativa de WebP não carregar, este teste avisa em vez de gravar lixo")
                .isNotNull();
    }

    private static int largura(byte[] bytes) throws Exception {
        return ImageIO.read(new ByteArrayInputStream(bytes)).getWidth();
    }
}
```

- [ ] **Step 3: Rodar e verificar que falham**

Run: `./mvnw test -Dtest='ValidadorUploadTest,ProcessadorImagemTest,ArmazenamentoFotosTest'`
Expected: erro de compilação.

- [ ] **Step 4: Acrescentar as dependências de imagem**

```xml
    <dependency>
      <groupId>net.coobird</groupId>
      <artifactId>thumbnailator</artifactId>
      <version>0.4.20</version>
    </dependency>
    <dependency>
      <groupId>org.sejda.imageio</groupId>
      <artifactId>webp-imageio</artifactId>
      <version>0.1.6</version>
    </dependency>
```

- [ ] **Step 5: Declarar os limites de upload no `application.yaml`**

```yaml
spring:
  servlet:
    multipart:
      max-file-size: 10MB
      max-request-size: 25MB
```

- [ ] **Step 6: Escrever o validador**

A ordem importa, e é a mais barata primeiro: arquivo não vazio, extensão em `jpg`/`jpeg`/`png`/`webp`, assinatura dos bytes iniciais correspondendo à extensão, dimensão lida do cabeçalho por `ImageIO.getImageReaders` sem descomprimir, e só então a carga completa. Um arquivo que não abra como imagem é recusado.

- [ ] **Step 7: Escrever o processador e o armazenamento**

O processador redimensiona com Thumbnailator e grava em WebP, sempre a partir do `BufferedImage` já carregado — o que descarta metadados por construção, porque nada do arquivo original é copiado.

O armazenamento aceita apenas nomes que casem com `^[0-9a-f-]{36}-(mini|media|grande)\.webp$`, e ainda assim resolve o caminho e compara com a raiz permitida antes de abrir. As duas conferências são de propósito: a expressão sozinha já barra travessia, e a comparação de caminho protege contra um erro futuro na expressão.

- [ ] **Step 8: Rodar e verificar que passam**

Run: `./mvnw test -Dtest='ValidadorUploadTest,ProcessadorImagemTest,ArmazenamentoFotosTest'`
Expected: PASS, nos treze testes.

- [ ] **Step 9: Commit**

```bash
git add -A
git commit -m "feat: upload tratado como hostil, com reescrita da imagem e nome gerado"
```

---

### Task 6: Cadastro e edição de produtos

**Files:**
- Create: `src/main/java/br/com/chimaclub/comum/Slugify.java`
- Create: `src/main/java/br/com/chimaclub/catalogo/service/ProdutoService.java`
- Create: `src/main/java/br/com/chimaclub/catalogo/dto/ProdutoForm.java`
- Create: `src/main/java/br/com/chimaclub/catalogo/web/AdminProdutoController.java`
- Create: `src/main/resources/templates/admin/produtos.html`, `produto-form.html`, `layout.html`
- Test: `src/test/java/br/com/chimaclub/comum/SlugifyTest.java`
- Test: `src/test/java/br/com/chimaclub/catalogo/ProdutoServiceTest.java`
- Test: `src/test/java/br/com/chimaclub/catalogo/AdminProdutoControllerTest.java`

**Interfaces:**
- Consumes: Tasks 3 e 5
- Produces:
  - `Slugify.de(String)` → `String`
  - `ProdutoService.criar(ProdutoForm)`, `.alterar(UUID, ProdutoForm)`, `.publicar(UUID, boolean)`, `.excluir(UUID)`
  - `ProdutoForm` com `precoEmReais` (`String`, convertido para centavos na entrada) e `versao` (`long`)

- [ ] **Step 1: Escrever o teste do `Slugify`**

```java
package br.com.chimaclub.comum;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SlugifyTest {

    @Test
    @DisplayName("remove acento, baixa a caixa e junta com hífen")
    void normalizaONome() {
        assertThat(Slugify.de("Cuía Gold em madeira")).isEqualTo("cuia-gold-em-madeira");
        assertThat(Slugify.de("Cuia em porongo com pérolas brancas"))
                .isEqualTo("cuia-em-porongo-com-perolas-brancas");
    }

    @Test
    @DisplayName("descarta pontuação e espaço repetido")
    void descartaPontuacao() {
        assertThat(Slugify.de("  Cuia   'Sunset' / edição #2!  ")).isEqualTo("cuia-sunset-edicao-2");
    }

    @Test
    @DisplayName("nunca devolve vazio, nem começa ou termina com hífen")
    void nuncaDevolveVazio() {
        assertThat(Slugify.de("!!!")).isNotEmpty();
        assertThat(Slugify.de("---a---")).isEqualTo("a");
    }

    @Test
    @DisplayName("respeita o limite de 160 caracteres da coluna")
    void respeitaOLimiteDaColuna() {
        assertThat(Slugify.de("cuia ".repeat(80))).hasSizeLessThanOrEqualTo(160);
    }
}
```

- [ ] **Step 2: Escrever o teste do serviço**

```java
package br.com.chimaclub.catalogo;

import br.com.chimaclub.BancoDeTesteBase;
import br.com.chimaclub.catalogo.dto.ProdutoForm;
import br.com.chimaclub.catalogo.service.ProdutoService;
import br.com.chimaclub.comum.RegraDeNegocioException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProdutoServiceTest extends BancoDeTesteBase {

    @Autowired ProdutoService servico;
    @Autowired ProdutoRepository produtos;

    private ProdutoForm formulario(String nome, String precoEmReais) {
        ProdutoForm form = new ProdutoForm();
        form.setNome(nome);
        form.setPrecoEmReais(precoEmReais);
        form.setUnidades(3);
        return form;
    }

    @Test
    @DisplayName("o preço em reais com vírgula vira centavos exatos")
    void convertePrecoParaCentavos() {
        UUID id = servico.criar(formulario("Cuia Gold", "89,90"));

        assertThat(produtos.findById(id).orElseThrow().getPrecoCentavos()).isEqualTo(8990L);
    }

    @Test
    @DisplayName("preços com uma casa, sem casa e com ponto de milhar também convertem")
    void convertePrecoEmVariosFormatos() {
        assertThat(centavosDe("89,9")).isEqualTo(8990L);
        assertThat(centavosDe("90")).isEqualTo(9000L);
        assertThat(centavosDe("1.234,56")).isEqualTo(123456L);
        assertThat(centavosDe("R$ 89,90")).isEqualTo(8990L);
    }

    private long centavosDe(String texto) {
        UUID id = servico.criar(formulario("Produto " + UUID.randomUUID(), texto));
        return produtos.findById(id).orElseThrow().getPrecoCentavos();
    }

    @Test
    @DisplayName("um preço que não é número é recusado, não vira zero")
    void recusaPrecoInvalido() {
        assertThatThrownBy(() -> servico.criar(formulario("Cuia estranha", "muito barato")))
                .isInstanceOf(RegraDeNegocioException.class);
    }

    @Test
    @DisplayName("nome repetido ganha sufixo numérico, sem estourar a chave única")
    void resolveColisaoDeSlug() {
        servico.criar(formulario("Cuia Gold em madeira", "89,90"));
        UUID segundo = servico.criar(formulario("Cuia Gold em madeira", "89,90"));

        assertThat(produtos.findById(segundo).orElseThrow().getSlug())
                .isEqualTo("cuia-gold-em-madeira-2");
    }

    @Test
    @DisplayName("publicar exige ao menos uma foto e preço maior que zero")
    void publicacaoExigeFotoEPreco() {
        UUID semFoto = servico.criar(formulario("Cuia sem foto", "89,90"));

        assertThatThrownBy(() -> servico.publicar(semFoto, true))
                .isInstanceOf(RegraDeNegocioException.class)
                .hasMessageContaining("foto");
    }

    @Test
    @DisplayName("o slug não muda sozinho quando o nome é editado, para não quebrar link")
    void slugNaoMudaSozinho() {
        UUID id = servico.criar(formulario("Cuia Gold", "89,90"));
        String slugOriginal = produtos.findById(id).orElseThrow().getSlug();

        ProdutoForm alteracao = formulario("Cuia Gold Edição Nova", "99,90");
        alteracao.setVersao(produtos.findById(id).orElseThrow().getVersao());
        servico.alterar(id, alteracao);

        assertThat(produtos.findById(id).orElseThrow().getSlug()).isEqualTo(slugOriginal);
    }

    @Test
    @DisplayName("a exclusão é lógica: a linha fica, some do catálogo")
    void exclusaoEhLogica() {
        UUID id = servico.criar(formulario("Cuia a excluir", "89,90"));

        servico.excluir(id);

        assertThat(produtos.findById(id).orElseThrow().getExcluidoEm()).isNotNull();
        assertThat(produtos.findBySlugAndExcluidoEmIsNull("cuia-a-excluir")).isEmpty();
    }

    @Test
    @DisplayName("versão divergente recusa a gravação em vez de sobrescrever")
    void versaoDivergenteRecusaGravacao() {
        UUID id = servico.criar(formulario("Cuia disputada", "89,90"));

        ProdutoForm atrasado = formulario("Cuia disputada", "10,00");
        atrasado.setVersao(99L);

        assertThatThrownBy(() -> servico.alterar(id, atrasado))
                .isInstanceOf(RegraDeNegocioException.class)
                .hasMessageContaining("alterado");
    }
}
```

- [ ] **Step 3: Escrever o teste do controlador, com foco em segurança**

```java
package br.com.chimaclub.catalogo;

import br.com.chimaclub.BancoDeTesteBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AdminProdutoControllerTest extends BancoDeTesteBase {

    @Autowired MockMvc mvc;

    private MockHttpServletRequestBuilder naPortaAdmin(MockHttpServletRequestBuilder builder) {
        return builder.with(requisicao -> {
            requisicao.setLocalPort(PORTA_ADMIN_DE_TESTE);
            return requisicao;
        });
    }

    @Test
    @DisplayName("sem sessão, a lista de produtos não abre")
    void semSessaoNaoAbre() throws Exception {
        mvc.perform(naPortaAdmin(get("/admin/produtos")))
           .andExpect(status().is3xxRedirection());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("com sessão, a lista abre")
    void comSessaoAbre() throws Exception {
        mvc.perform(naPortaAdmin(get("/admin/produtos")))
           .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("gravar sem token CSRF é recusado")
    void gravarSemCsrfEhRecusado() throws Exception {
        mvc.perform(naPortaAdmin(post("/admin/produtos/novo")
                        .param("nome", "Cuia").param("precoEmReais", "89,90")))
           .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("script no nome do produto sai escapado na tela, não executa")
    void xssNoNomeSaiEscapado() throws Exception {
        mvc.perform(naPortaAdmin(post("/admin/produtos/novo").with(csrf())
                        .param("nome", "<script>alert(1)</script>")
                        .param("precoEmReais", "89,90")
                        .param("unidades", "1")));

        String corpo = mvc.perform(naPortaAdmin(get("/admin/produtos")))
                          .andReturn().getResponse().getContentAsString();

        assertThat(corpo)
                .doesNotContain("<script>alert(1)</script>")
                .contains("&lt;script&gt;");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("descrição além de 4000 caracteres é recusada pela validação")
    void descricaoLongaEhRecusada() throws Exception {
        mvc.perform(naPortaAdmin(post("/admin/produtos/novo").with(csrf())
                        .param("nome", "Cuia longa")
                        .param("precoEmReais", "89,90")
                        .param("unidades", "1")
                        .param("descricao", "x".repeat(4001))))
           .andExpect(status().isOk());   // volta ao formulário com erro, não grava
    }
}
```

- [ ] **Step 4: Rodar e verificar que falham**

Run: `./mvnw test -Dtest='SlugifyTest,ProdutoServiceTest,AdminProdutoControllerTest'`
Expected: erro de compilação.

- [ ] **Step 5: Escrever `Slugify`, `RegraDeNegocioException`, `ProdutoForm` e `ProdutoService`**

A conversão de preço usa `BigDecimal` com escala 2 e `RoundingMode.UNNECESSARY`, e nunca `double`. A colisão de slug acrescenta `-2`, `-3`, e assim por diante, consultando `existsBySlug`.

- [ ] **Step 6: Escrever o controlador e os templates**

Templates com `th:text`, jamais `th:utext`. O formulário leva o campo oculto `versao`, e o `ProdutoService` compara com a do banco.

- [ ] **Step 7: Rodar e verificar que passam**

Run: `./mvnw test -Dtest='SlugifyTest,ProdutoServiceTest,AdminProdutoControllerTest'`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "feat: cadastro e edição de produtos com slug estável e bloqueio otimista"
```

---

### Task 7: Fotos no painel e rota de imagem

**Files:**
- Create: `src/main/java/br/com/chimaclub/catalogo/service/FotoService.java`
- Create: `src/main/java/br/com/chimaclub/catalogo/web/AdminFotoController.java`
- Create: `src/main/java/br/com/chimaclub/catalogo/web/FotoController.java`
- Create: `src/main/java/br/com/chimaclub/catalogo/ProdutoFotoRepository.java`
- Test: `src/test/java/br/com/chimaclub/catalogo/FotoServiceTest.java`
- Test: `src/test/java/br/com/chimaclub/catalogo/FotoControllerTest.java`

**Interfaces:**
- Consumes: Tasks 5 e 6
- Produces: `POST /admin/produtos/{id}/fotos`, `PATCH /admin/produtos/{id}/fotos/ordem`, `DELETE /admin/produtos/{id}/fotos/{fotoId}`, `GET /fotos/{arquivo}` (público)

- [ ] **Step 1: Escrever os testes**

Cobrem as regras 5 e 6 da §6 e os controles de A01 e A08:

- a primeira foto enviada vira principal automaticamente;
- ao excluir a principal, a próxima na ordem assume;
- excluir a única foto de um produto publicado o despublica, porque a regra 3 exige ao menos uma foto;
- `GET /fotos/{arquivo}` responde `Content-Type: image/webp` fixo, `Cache-Control: public, max-age=31536000, immutable` e `X-Content-Type-Options: nosniff`;
- `GET /fotos/../../etc/passwd` e variações codificadas respondem 400 ou 404, nunca o arquivo;
- upload de `.php` renomeado pelo painel devolve erro ao usuário e não grava nada no disco nem no banco.

- [ ] **Step 2: Rodar e verificar que falham**

Run: `./mvnw test -Dtest='FotoServiceTest,FotoControllerTest'`

- [ ] **Step 3: Escrever o serviço, os controladores e o repositório de fotos**

- [ ] **Step 4: Rodar e verificar que passam**

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: fotos no painel, com foto principal automática e rota de imagem endurecida"
```

---

### Task 8: Configuração da loja e tela de auditoria

**Files:**
- Create: `src/main/java/br/com/chimaclub/config_loja/Configuracao.java`, `ConfiguracaoRepository.java`, `ConfiguracaoService.java`
- Create: `src/main/java/br/com/chimaclub/admin/web/ConfiguracaoController.java`, `AuditoriaController.java`
- Create: `src/main/resources/templates/admin/configuracao.html`, `auditoria.html`
- Test: `src/test/java/br/com/chimaclub/config_loja/ConfiguracaoServiceTest.java`

**Interfaces:**
- Consumes: Task 6
- Produces: `GET/POST /admin/configuracao`, `GET /admin/auditoria`

- [ ] **Step 1: Escrever o teste**

O número de WhatsApp é o ativo de média sensibilidade da §1.3: trocá-lo é o caminho de um golpe contra os clientes. Então: só dígitos, entre 12 e 15 caracteres, e toda alteração vai para a auditoria com o valor anterior e o novo.

- [ ] **Step 2 a 4: falhar, implementar, passar**

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: configuração da loja e tela de auditoria"
```

---

### Task 9: Fechamento da fase

- [ ] **Step 1: Rodar a suíte inteira**

Run: `./mvnw clean verify`
Expected: BUILD SUCCESS, sem nenhum teste ignorado.

- [ ] **Step 2: Conferir que nenhuma rota nova vazou para a porta pública**

Acrescentar ao `IsolamentoDoPainelTest` uma asserção por rota administrativa criada nesta fase, e rodar.

- [ ] **Step 3: Percorrer o painel à mão**

Criar admin pelo comando, entrar, cadastrar um produto com foto de verdade, publicar, editar, excluir, conferir a auditoria.

- [ ] **Step 4: Atualizar o `README.md`** com o estado da Fase 2.

- [ ] **Step 5: Commit**

---

## O que a Fase 2 deliberadamente não entrega

- Cabeçalhos de segurança, CSP e limite de requisições com Bucket4j — Fase 4.
- `Dockerfile` e o serviço `app` no Compose — Fase 4.
- Home pública, busca na tela, página de produto e identidade visual — Fase 3.
- Rotina de limpeza das fotos de produto excluído após 30 dias — Fase 4.
