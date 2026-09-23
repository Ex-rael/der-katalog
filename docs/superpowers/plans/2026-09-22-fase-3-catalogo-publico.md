# Chima Club — Fase 3: Catálogo público — Plano de Implementação

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Entregar o catálogo que o cliente vê: home com a identidade Chima Club, grade de produtos agrupada por categoria, busca por nome que funciona com e sem JavaScript, página de produto com carrossel, e o botão que leva a conversa para o WhatsApp — tudo servido pela porta 8080, a única que atravessa o Funnel.

**Architecture:** Thymeleaf renderizando no servidor, sem framework de frontend. HTMX apenas para trocar o bloco da grade na busca; a mesma rota responde a página inteira quando o pedido não vem do HTMX, o que faz a busca funcionar com JavaScript desligado sem código duplicado. Fontes e HTMX hospedados localmente, para a CSP da Fase 4 poder ficar restrita a `'self'`.

**Tech Stack:** Spring MVC, Thymeleaf, HTMX auto-hospedado, CSS próprio a partir do catálogo original, PostgreSQL com `pg_trgm`.

**Spec:** `chimaclub-definicao-projeto.md` §4.1, §4.2, §5.1, §6, §7 e `chimaclub-plano-seguranca.md`

**Fases anteriores:** 1 e 2 entregues, 194 testes verdes.

## Global Constraints

Valem as restrições das fases anteriores, mais estas:

- **Tudo nesta fase responde pela porta pública**, e nada aqui pode depender de sessão. Uma rota nova que precise de login está no lugar errado.
- **O termo de busca é entrada hostil.** Vai para a consulta como parâmetro vinculado, e para a tela escapado. Nunca concatenado em SQL, nunca em `th:utext`.
- **Nenhuma requisição a domínio externo.** Fontes e HTMX ficam em `static/`; o §A05 exige a CSP restrita a `'self'`, e um `<link>` para o Google faria a política ter de abrir uma exceção permanente.
- **Funciona sem JavaScript.** A busca por `?q=` e a navegação do carrossel por link precisam responder com JS desligado. É requisito da §7, e também o que mantém o site utilizável para buscadores.
- **Responsivo a partir de 360 px**, foco visível no teclado, texto alternativo em toda imagem, contraste mínimo AA.
- **Nada de dado pessoal de cliente.** O clique no WhatsApp registra produto e horário, nunca identificador de visitante. É o que mantém o projeto fora do alcance da LGPD, e é decisão a preservar.

---

### Task 1: Identidade visual e recursos estáticos

**Files:**
- Create: `src/main/resources/static/css/chimaclub.css`
- Create: `src/main/resources/static/fontes/*.woff2` (já baixados)
- Create: `src/main/resources/static/img/logo-chimaclub.png`, `selo-chimaclub.png`, `sois.svg` (já extraídos)
- Create: `src/main/resources/static/js/htmx.min.js`
- Create: `src/main/resources/static/fontes/LICENCA.md`
- Test: `src/test/java/br/com/chimaclub/publico/RecursosEstaticosTest.java`

**Interfaces:**
- Consumes: Fase 2
- Produces: `/css/chimaclub.css`, `/fontes/*.woff2`, `/img/*`, `/js/htmx.min.js` — todos servidos pela porta pública

- [ ] **Step 1: Escrever o teste, que falha**

```java
package br.com.chimaclub.publico;

import br.com.chimaclub.BancoDeTesteBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

@AutoConfigureMockMvc
class RecursosEstaticosTest extends BancoDeTesteBase {

    @Autowired MockMvc mvc;

    @Test
    @DisplayName("o CSS, as fontes e o HTMX são servidos localmente")
    void recursosSaoServidosLocalmente() throws Exception {
        String[] recursos = {
                "/css/chimaclub.css",
                "/fontes/marcellus-latin.woff2",
                "/fontes/pinyon-script-latin.woff2",
                "/fontes/jost-300-latin.woff2",
                "/img/logo-chimaclub.png",
                "/img/selo-chimaclub.png",
                "/js/htmx.min.js"
        };
        for (String recurso : recursos) {
            assertThat(mvc.perform(get(recurso)).andReturn().getResponse().getStatus())
                    .as("recurso %s", recurso)
                    .isEqualTo(200);
        }
    }

    @Test
    @DisplayName("o CSS não pede nada a domínio externo")
    void cssNaoPedeNadaDeFora() throws Exception {
        String css = mvc.perform(get("/css/chimaclub.css"))
                        .andReturn().getResponse().getContentAsString();

        assertThat(css)
                .as("um @import externo faria a CSP precisar de exceção permanente")
                .doesNotContain("https://")
                .doesNotContain("fonts.googleapis")
                .doesNotContain("fonts.gstatic");
    }
}
```

- [ ] **Step 2: Rodar e verificar que falha**

Run: `./mvnw test -Dtest=RecursosEstaticosTest`
Expected: FAIL — os recursos não existem.

- [ ] **Step 3: Baixar o HTMX e registrar as licenças**

O HTMX vem de `https://unpkg.com/htmx.org@2/dist/htmx.min.js`, gravado em `static/js/`. As três fontes são SIL Open Font License 1.1; o arquivo `LICENCA.md` registra isso, com a origem de cada uma.

- [ ] **Step 4: Escrever o `chimaclub.css`**

Base: o bloco `<style>` de `styles/catalogo-chimaclub-previa.html`, que já traz a paleta, a tipografia e a estrutura de abertura, moldura de categoria e cartão de produto. As mudanças em relação ao original:

- o `<link>` para o Google Fonts vira `@font-face` apontando para `/fontes/`, com `unicode-range` separando `latin` de `latin-ext`;
- acrescenta o que o catálogo estático não tinha: campo de busca, marca de "Esgotado", página de produto e carrossel.

- [ ] **Step 5: Rodar e verificar que passa**

Run: `./mvnw test -Dtest=RecursosEstaticosTest`

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat: identidade visual com fontes e HTMX hospedados localmente"
```

---

### Task 2: Consulta do catálogo

**Files:**
- Modify: `src/main/java/br/com/chimaclub/catalogo/ProdutoRepository.java`
- Create: `src/main/java/br/com/chimaclub/catalogo/service/CatalogoService.java`
- Create: `src/main/java/br/com/chimaclub/catalogo/dto/ProdutoResumo.java`
- Test: `src/test/java/br/com/chimaclub/catalogo/CatalogoServiceTest.java`

**Interfaces:**
- Consumes: Fase 2
- Produces:
  - `CatalogoService.publicados()` → `List<ProdutoResumo>`
  - `CatalogoService.buscar(String termo)` → `List<ProdutoResumo>`
  - `CatalogoService.porCategoria()` → `Map<Categoria, List<ProdutoResumo>>`
  - `CatalogoService.detalhe(String slug)` → `Optional<ProdutoDetalhe>`
  - `ProdutoResumo` com `id`, `nome`, `slug`, `precoCentavos`, `unidades`, `arquivoMini`, `esgotado()`

- [ ] **Step 1: Escrever o teste, que falha**

A consulta é a da §3.4, com `imutavel_unaccent`. O teste cobre o critério de aceite: "gold", "Gold" e "cuia gold" chegam ao mesmo produto.

```java
@Test
@DisplayName("a busca encontra gold, Gold e cuia gold")
void buscaEncontraAsTresFormas() {
    // produto publicado com foto: "Cuía Gold em madeira"
    for (String termo : List.of("gold", "Gold", "GOLD", "cuia gold", "Cuía Gold", "goldd")) {
        assertThat(servico.buscar(termo))
                .as("busca por '%s'", termo)
                .extracting(ProdutoResumo::slug)
                .contains("cuia-gold-em-madeira");
    }
}

@Test
@DisplayName("a busca não devolve rascunho nem produto excluído")
void buscaSoDevolveOQueEstaNoCatalogo() { /* ... */ }

@Test
@DisplayName("um termo com aspas e ponto-e-vírgula não quebra nem injeta")
void termoHostilNaoQuebraAConsulta() {
    for (String termo : List.of("'; DROP TABLE produto; --", "%", "_", "100%", "a'b")) {
        assertThatCode(() -> servico.buscar(termo)).doesNotThrowAnyException();
    }
    assertThat(produtos.count()).as("a tabela continua lá").isPositive();
}

@Test
@DisplayName("termo vazio devolve o catálogo inteiro, não nada")
void termoVazioDevolveTudo() { /* ... */ }

@Test
@DisplayName("o limite de 60 resultados da §3.4 é respeitado")
void respeitaOLimite() { /* ... */ }
```

- [ ] **Step 2 a 4: falhar, implementar, passar**

A consulta usa `@Query(nativeQuery = true)` com a SQL da §3.4, parâmetro vinculado. O `%` e o `_` do `LIKE` vindos do termo são escapados, senão uma busca por `%` devolveria tudo — não é falha de segurança, mas é resultado errado.

- [ ] **Step 5: Commit**

---

### Task 3: Home pública

**Files:**
- Create: `src/main/java/br/com/chimaclub/catalogo/web/CatalogoController.java`
- Create: `src/main/resources/templates/publico/home.html`
- Create: `src/main/resources/templates/publico/fragmentos/grade.html`
- Test: `src/test/java/br/com/chimaclub/publico/HomeTest.java`

**Interfaces:**
- Consumes: Task 2
- Produces: `GET /` com `?q=` e `?categoria=`; `GET /busca` devolvendo o fragmento da grade

- [ ] **Step 1: Escrever o teste, que falha**

```java
@Test
@DisplayName("a home abre sem sessão e mostra os produtos publicados")
void homeAbreSemSessao() { /* 200, contém o nome do produto publicado */ }

@Test
@DisplayName("a home não mostra rascunho nem produto excluído")
void homeEscondeOQueNaoEstaPublicado() { /* ... */ }

@Test
@DisplayName("produto sem unidades aparece como Esgotado, e continua clicável")
void esgotadoApareceEContinuaClicavel() { /* ... */ }

@Test
@DisplayName("a busca por ?q= funciona sem JavaScript")
void buscaFuncionaSemJavaScript() {
    // GET /?q=gold devolve a página inteira, já filtrada
}

@Test
@DisplayName("o HTMX recebe só o fragmento da grade")
void htmxRecebeApenasOFragmento() {
    // GET /busca?q=gold com cabeçalho HX-Request: true
    // devolve o bloco da grade, sem <html> nem <head>
}

@Test
@DisplayName("o termo de busca é escapado ao voltar para a tela")
void termoDeBuscaEhEscapado() {
    // GET /?q=<script>alert(1)</script>
    // o corpo não contém o script cru; contém &lt;script&gt;
}
```

- [ ] **Step 2 a 4: falhar, implementar, passar**

O controlador decide entre página inteira e fragmento pelo cabeçalho `HX-Request`. A rota `/busca` existe para o HTMX; a home aceita `?q=` para quem não tem JavaScript. As duas usam o mesmo fragmento, então não há markup duplicado.

- [ ] **Step 5: Commit**

---

### Task 4: Página do produto

**Files:**
- Create: `src/main/resources/templates/publico/produto.html`
- Create: `src/main/java/br/com/chimaclub/catalogo/dto/ProdutoDetalhe.java`
- Test: `src/test/java/br/com/chimaclub/publico/PaginaDeProdutoTest.java`

**Interfaces:**
- Consumes: Task 3
- Produces: `GET /produto/{slug}`

- [ ] **Step 1: Escrever o teste, que falha**

```java
@Test
@DisplayName("a página abre pelo slug e mostra preço em reais")
void abrePeloSlug() { /* contém "R$ 89,90" */ }

@Test
@DisplayName("slug de produto despublicado ou excluído responde 404")
void naoAbreOQueNaoEstaPublicado() { /* ... */ }

@Test
@DisplayName("slug inexistente responde 404 sem revelar nada")
void slugInexistenteResponde404() { /* sem "Exception", sem nome de classe */ }

@Test
@DisplayName("a descrição com HTML sai escapada")
void descricaoComHtmlSaiEscapada() { /* ... */ }

@Test
@DisplayName("as unidades aparecem na forma certa")
void unidadesNaFormaCerta() {
    // 3 -> "3 unidades disponíveis"; 1 -> "Última unidade"; 0 -> "Esgotado"
}

@Test
@DisplayName("toda imagem tem texto alternativo")
void todaImagemTemTextoAlternativo() { /* nenhum <img sem alt= */ }
```

- [ ] **Step 2 a 4: falhar, implementar, passar**

Carrossel navegável por toque, teclado e clique, e que sem JavaScript vira uma lista de imagens rolável — degradação, não quebra.

- [ ] **Step 5: Commit**

---

### Task 5: Botão do WhatsApp e registro de clique

**Files:**
- Create: `src/main/java/br/com/chimaclub/catalogo/CliqueWhatsapp.java`, `CliqueWhatsappRepository.java`
- Create: `src/main/java/br/com/chimaclub/catalogo/service/WhatsappService.java`
- Modify: `src/main/java/br/com/chimaclub/catalogo/web/CatalogoController.java`
- Test: `src/test/java/br/com/chimaclub/publico/WhatsappTest.java`

**Interfaces:**
- Consumes: Task 4
- Produces: `POST /produto/{slug}/whatsapp` → redireciona para `https://wa.me/<numero>?text=<mensagem>`

- [ ] **Step 1: Escrever o teste, que falha**

Esta é a rota pública que altera estado, e a única isenta de CSRF. O teste precisa provar que a isenção não vira brecha:

```java
@Test
@DisplayName("o clique redireciona para o WhatsApp com o nome do produto na mensagem")
void redirecionaComAMensagem() {
    // Location começa com https://wa.me/5551989250481?text=
    // e a mensagem traz o nome do produto e o endereço da página
}

@Test
@DisplayName("o nome do produto é codificado na URL, não colado cru")
void nomeEhCodificadoNaUrl() {
    // produto chamado 'Cuia "Gold" & cia' não quebra a URL nem injeta parâmetro
}

@Test
@DisplayName("o destino é sempre wa.me: não há redirecionamento aberto")
void naoHaRedirecionamentoAberto() {
    // nenhum parâmetro da requisição influencia o destino
}

@Test
@DisplayName("o clique é registrado sem nada que identifique o visitante")
void registraSemIdentificarVisitante() {
    // a linha gravada tem produto e horário; não tem IP, cookie nem user-agent
}

@Test
@DisplayName("slug inexistente não registra clique nem redireciona")
void slugInexistenteNaoRegistra() { /* 404 */ }
```

- [ ] **Step 2 a 4: falhar, implementar, passar**

O número e a mensagem vêm da `configuracao`, não de constante no código. O `{produto}` da mensagem é substituído pelo nome, e o resultado inteiro é codificado para URL.

- [ ] **Step 5: Commit**

---

### Task 6: Indexação e fechamento

**Files:**
- Create: `src/main/java/br/com/chimaclub/catalogo/web/IndexacaoController.java`
- Create: `src/main/resources/templates/publico/sitemap.xml`
- Test: `src/test/java/br/com/chimaclub/publico/IndexacaoTest.java`
- Modify: `README.md`

**Interfaces:**
- Consumes: Task 5
- Produces: `GET /sitemap.xml`, `GET /robots.txt`

- [ ] **Step 1: Escrever o teste**

```java
@Test
@DisplayName("o sitemap lista só o que está publicado")
void sitemapListaSoOPublicado() { /* ... */ }

@Test
@DisplayName("o robots.txt pede intervalo entre visitas e barra o painel")
void robotsPedeIntervaloEBarraOPainel() {
    // Crawl-delay presente (§4.2: banda de subida é o ponto fraco)
    // Disallow: /admin — ainda que ele nem responda por aqui
}
```

- [ ] **Step 2 a 4: falhar, implementar, passar**

- [ ] **Step 5: Percorrer o catálogo à mão**, num navegador e num celular real: buscar, abrir produto, clicar no WhatsApp.

- [ ] **Step 6: Atualizar o `README.md` e commitar**

---

## O que a Fase 3 deliberadamente não entrega

- Cabeçalhos de segurança e CSP — Fase 4. Esta fase prepara o terreno hospedando tudo localmente, mas a política em si entra lá.
- Limite de requisições por IP — Fase 4.
- Carga dos 17 produtos do catálogo antigo — Fase 5.
