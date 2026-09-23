# Chima Club — Fase 4: Endurecimento — Plano de Implementação

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deixar a aplicação pronta para receber tráfego da internet: cabeçalhos de segurança e CSP em toda resposta, limite de requisições por IP, segundo fator exigido em produção, contêiner sem privilégio com sistema de arquivos somente leitura, e as rotinas de backup e limpeza que a operação exige.

**Architecture:** Nada de novo no desenho. Esta fase acrescenta camadas ao que já existe: um filtro de limite de requisições antes da cadeia de segurança, cabeçalhos escritos pelo Spring Security, um perfil `prod` que aperta o que o `dev` deixa frouxo, e um `Dockerfile` que constrói a imagem em duas etapas e roda sem privilégio.

**Tech Stack:** Spring Security 7 (cabeçalhos), Bucket4j 8 (limite por IP), Docker multi-stage, `pg_dump`, `dependency-check`, CycloneDX.

**Spec:** `chimaclub-plano-seguranca.md` §A04, §A05, §A06, §A07, §4.2, §4.5 e `chimaclub-definicao-projeto.md` §8

**Fases anteriores:** 1, 2 e 3 entregues, 244 testes verdes.

## Global Constraints

Valem as restrições das fases anteriores, mais estas:

- **Todo controle desta fase é verificado por teste, e o teste é conferido por mutação.** Um controle de segurança que passa por acaso é pior que a sua ausência, porque cria confiança falsa. Onde a mutação não for possível, isso fica dito.
- **A CSP não leva `unsafe-inline` nem `unsafe-eval`.** A Fase 3 preparou o terreno hospedando fontes, HTMX e o script do carrossel localmente; se algo não funcionar sob a política, o que muda é o código, não a política.
- **O perfil `dev` continua utilizável.** Endurecer não pode significar que a dona da loja não consiga mais trabalhar na máquina local: cookie `Secure` e TOTP obrigatório valem em `prod`, e o `dev` diz claramente que está frouxo.
- **Nada de segredo novo no repositório.** A chave do TOTP e a senha do banco vêm de variável de ambiente ou de arquivo em `secrets/`.
- **O contêiner roda sem privilégio**, com sistema de arquivos somente leitura e todas as capacidades removidas. Se algo precisar gravar, ganha um volume, não permissão de escrita na imagem.

---

### Task 1: Cabeçalhos de segurança e CSP

**Files:**
- Modify: `src/main/java/br/com/chimaclub/config/SegurancaConfig.java`
- Create: `src/main/java/br/com/chimaclub/config/CabecalhosConfig.java`
- Test: `src/test/java/br/com/chimaclub/config/CabecalhosDeSegurancaTest.java`

**Interfaces:**
- Consumes: Fase 3
- Produces: `Content-Security-Policy`, `Referrer-Policy`, `X-Content-Type-Options`, `X-Frame-Options`, `Permissions-Policy`, `Cross-Origin-Opener-Policy`, `Cross-Origin-Resource-Policy` em toda resposta das duas portas; `Strict-Transport-Security` apenas no perfil `prod`

- [ ] **Step 1: Escrever o teste, que falha**

O teste roda sobre porta real, porque cabeçalho é coisa de resposta HTTP e MockMvc pode não exercitar o mesmo caminho — foi assim que o problema do `Cache-Control` das fotos apareceu e desapareceu na Fase 2.

```java
@Test
@DisplayName("toda resposta pública traz os cabeçalhos do §A05")
void respostaPublicaTrazOsCabecalhos() {
    // para "/", "/produto/{slug}", "/fotos/..." e uma resposta de erro
}

@Test
@DisplayName("a CSP não permite script nem estilo em linha")
void cspNaoPermiteInline() {
    // nem 'unsafe-inline', nem 'unsafe-eval', nem '*'
}

@Test
@DisplayName("a CSP não libera domínio externo")
void cspNaoLiberaDominioExterno() {
    // sem "https://" na política: tudo é 'self'
}

@Test
@DisplayName("o catálogo funciona sob a própria CSP")
void catalogoFuncionaSobAPropriaCsp() {
    // a home não pode ter <style> nem <script> inline, nem atributo on*
}

@Test
@DisplayName("HSTS só no perfil prod")
void hstsSoEmProducao() { /* ... */ }
```

- [ ] **Step 2 a 4: falhar, implementar, passar**

- [ ] **Step 5: Verificar por mutação** que remover a CSP faz o teste falhar.

- [ ] **Step 6: Commit**

---

### Task 2: Limite de requisições por IP

**Files:**
- Create: `src/main/java/br/com/chimaclub/config/LimiteDeRequisicoes.java`
- Create: `src/main/java/br/com/chimaclub/config/FiltroDeLimite.java`
- Modify: `pom.xml`
- Test: `src/test/java/br/com/chimaclub/config/LimiteDeRequisicoesTest.java`

**Interfaces:**
- Consumes: Task 1
- Produces: 60 requisições por minuto nas rotas públicas, 5 no login, 10 no upload; resposta 429 com `Retry-After`

- [ ] **Step 1: Escrever o teste, que falha**

```java
@Test
@DisplayName("passado o limite, a rota pública responde 429 com Retry-After")
void passadoOLimiteResponde429() { /* ... */ }

@Test
@DisplayName("o login tem limite próprio, bem mais apertado")
void loginTemLimiteMaisApertado() { /* 5 por minuto */ }

@Test
@DisplayName("IPs diferentes têm baldes diferentes")
void ipsDiferentesTemBaldesDiferentes() {
    // senão um visitante derrubaria o site para todos
}

@Test
@DisplayName("as fotos não entram no limite das páginas")
void fotosNaoEntramNoLimiteDasPaginas() {
    // uma página com 17 miniaturas dispararia o limite na primeira visita
}

@Test
@DisplayName("o 429 não vaza detalhe interno")
void respostaDeLimiteNaoVazaNada() { /* ... */ }
```

- [ ] **Step 2 a 4: falhar, implementar, passar**

Ponto de atenção: atrás do Tailscale Funnel, o endereço de origem chega em `X-Forwarded-For`. Confiar nesse cabeçalho sem saber de quem ele vem permite a qualquer visitante forjar o IP e escapar do limite; confiar apenas no socket, atrás do Funnel, junta todo mundo num balde só. A decisão precisa ser explícita, testada, e anotada no plano de segurança.

- [ ] **Step 5: Commit**

---

### Task 3: Segundo fator exigido em produção

**Files:**
- Create: `src/main/java/br/com/chimaclub/admin/web/TotpController.java`
- Create: `src/main/resources/templates/admin/totp-ativar.html`, `totp-conferir.html`
- Create: `src/main/java/br/com/chimaclub/config/FiltroDeSegundoFator.java`
- Test: `src/test/java/br/com/chimaclub/admin/SegundoFatorTest.java`

**Interfaces:**
- Consumes: Task 2
- Produces: `GET/POST /admin/totp/ativar`, `GET/POST /admin/totp/conferir`

- [ ] **Step 1: Escrever o teste, que falha**

```java
@Test
@DisplayName("em produção, sem segundo fator ativo o painel não abre")
void semSegundoFatorOPainelNaoAbre() { /* leva à tela de ativação */ }

@Test
@DisplayName("com segundo fator ativo, o painel exige o código depois da senha")
void exigeOCodigoDepoisDaSenha() { /* ... */ }

@Test
@DisplayName("código errado não abre o painel e vai para a auditoria")
void codigoErradoNaoAbre() { /* ... */ }

@Test
@DisplayName("a sessão pela metade não alcança rota nenhuma do painel")
void sessaoPelaMetadeNaoAlcancaNada() {
    // autenticou a senha mas não o código: /admin/produtos continua fechado
}

@Test
@DisplayName("em dev o segundo fator não é exigido, como diz a §8.3")
void emDesenvolvimentoNaoEhExigido() { /* ... */ }
```

- [ ] **Step 2 a 4: falhar, implementar, passar**

- [ ] **Step 5: Commit**

---

### Task 4: Actuator, perfil de produção e página de erro

**Files:**
- Create: `src/main/resources/application-prod.yaml`
- Create: `src/main/resources/application-dev.yaml`
- Create: `src/main/resources/templates/error.html`
- Modify: `src/main/resources/application.yaml`, `SegurancaConfig.java`
- Modify: `pom.xml` (actuator)
- Test: `src/test/java/br/com/chimaclub/config/ActuatorTest.java`

**Interfaces:**
- Consumes: Task 3
- Produces: `/actuator/health` e `/actuator/info` apenas na porta administrativa e autenticados; página de erro genérica

- [ ] **Step 1: Escrever o teste, que falha**

```java
@Test
@DisplayName("env, heapdump e threaddump não existem, em porta nenhuma")
void pontosPerigososNaoExistem() { /* ... */ }

@Test
@DisplayName("health não detalha banco nem disco para quem não está autenticado")
void healthNaoDetalhaParaAnonimo() { /* ... */ }

@Test
@DisplayName("a página de erro não traz rastro de pilha nem versão")
void paginaDeErroEhGenerica() { /* ... */ }
```

- [ ] **Step 2 a 4: falhar, implementar, passar**

- [ ] **Step 5: Commit**

---

### Task 5: Imagem e Compose endurecidos

**Files:**
- Create: `Dockerfile`, `.dockerignore`
- Modify: `compose.yaml`
- Test: verificação por execução, documentada em `docs/verificacao-do-conteiner.md`

**Interfaces:**
- Consumes: Task 4
- Produces: imagem que roda como usuário 10001, sem capacidades, com o sistema de arquivos somente leitura

- [ ] **Step 1: Escrever o `Dockerfile` em duas etapas**

A etapa de construção usa a imagem do Maven; a de execução, apenas o JRE. O que vai para a máquina de produção não leva compilador, nem o código-fonte, nem o cache do Maven — menos coisa na imagem é menos coisa para um invasor usar.

- [ ] **Step 2: Subir e verificar, na prática**

```bash
docker compose up -d --build
docker compose exec app id                 # espera uid=10001, não root
docker compose exec app touch /teste       # espera falha: sistema somente leitura
docker compose exec app sh -c 'cat /proc/self/status | grep CapEff'   # espera 0000000000000000
```

E, o mais importante desta tarefa: **conferir que a nativa de WebP carrega dentro do contêiner.** Se não carregar, o processador recua para JPEG, e é melhor descobrir agora que na primeira foto que a dona da loja enviar.

- [ ] **Step 3: Confirmar que as portas continuam confinadas**

```bash
ss -ltn | grep -E ':8080|:8081'   # espera 127.0.0.1 nas duas
```

- [ ] **Step 4: Commit**

---

### Task 6: Backup, restauração e limpeza

**Files:**
- Create: `scripts/backup.sh`, `scripts/restaurar.sh`, `scripts/testar-restauracao.sh`
- Create: `src/main/java/br/com/chimaclub/midia/LimpezaDeFotos.java`
- Test: `src/test/java/br/com/chimaclub/midia/LimpezaDeFotosTest.java`

**Interfaces:**
- Consumes: Task 5
- Produces: `pg_dump -Fc` diário com retenção de 30 dias; rotina que apaga fotos de produto excluído há mais de 30 dias

- [ ] **Step 1: Escrever o teste da limpeza, que falha**

```java
@Test
@DisplayName("foto de produto excluído há mais de 30 dias é apagada do disco")
void apagaFotoDeProdutoExcluidoHaMuito() { /* ... */ }

@Test
@DisplayName("foto de produto excluído ontem não é tocada")
void naoApagaExclusaoRecente() {
    // §6.6: os 30 dias existem para dar tempo de desfazer um engano
}

@Test
@DisplayName("foto de produto ativo nunca é apagada, nem por engano")
void nuncaApagaFotoDeProdutoAtivo() { /* ... */ }

@Test
@DisplayName("um arquivo ausente no disco não derruba a rotina")
void arquivoAusenteNaoDerrubaARotina() { /* ... */ }
```

- [ ] **Step 2 a 4: falhar, implementar, passar**

- [ ] **Step 5: Escrever e EXECUTAR o backup e a restauração**

A §4.5 é explícita: backup testado vale mais que controle preventivo. Então esta tarefa não termina com o script escrito — termina com um `pg_dump` feito, restaurado em base temporária, e a contagem de produtos conferida.

- [ ] **Step 6: Commit**

---

### Task 7: Varredura de dependências e inventário

**Files:**
- Modify: `pom.xml`
- Create: `docs/operacao.md`
- Test: execução de `./mvnw verify -Pseguranca`

**Interfaces:**
- Consumes: Task 6
- Produces: `dependency-check` e SBOM CycloneDX num perfil Maven próprio

- [ ] **Step 1: Acrescentar os plugins num perfil separado**

Em perfil, e não no build padrão: o `dependency-check` baixa a base de vulnerabilidades e leva minutos, e amarrá-lo a todo `verify` tornaria o ciclo de desenvolvimento penoso a ponto de alguém desligá-lo. O ciclo mensal do §A06 o executa; o `verify` do dia a dia, não.

- [ ] **Step 2: Executar e tratar o que aparecer**

- [ ] **Step 3: Escrever o `docs/operacao.md`** com os comandos do dia a dia e as listas mensal e trimestral do §5.

- [ ] **Step 4: Commit**

---

### Task 8: Fechamento — a lista 5.1 percorrida por inteiro

- [ ] **Step 1: Rodar `./mvnw clean verify`**
- [ ] **Step 2: Subir pelo Compose e repetir os ataques da Fase 2** contra o contêiner endurecido
- [ ] **Step 3: Conferir os cabeçalhos com `curl -I` a partir de fora do contêiner**
- [ ] **Step 4: Marcar, item a item, a lista 5.1 do plano de segurança**, separando o que ficou provado por teste do que depende de você
- [ ] **Step 5: Atualizar `README.md` e commitar**

---

## O que a Fase 4 deliberadamente não entrega

- Publicação pelo Tailscale Funnel e o teste de acesso externo — Fase 5, e depende de você.
- Alerta por e-mail ou Telegram (§A09) — precisa de um canal que ainda não existe; fica registrado como pendência.
- Segmentação da rede doméstica e cifra do disco — são da máquina, não da aplicação.
