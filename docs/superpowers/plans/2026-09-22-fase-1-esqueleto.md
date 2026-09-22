# Chima Club — Fase 1: Esqueleto — Plano de Implementação

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Entregar a aplicação Spring Boot subindo com dois conectores HTTP separados, PostgreSQL 16 em Docker, esquema criado por Flyway, entidades JPA mapeadas, e uma suíte de testes de integração que já prova o isolamento do painel administrativo e o funcionamento da busca com acento.

**Architecture:** Monólito Spring Boot único, com dois conectores Tomcat (8080 público, 8081 administrativo) no mesmo processo, separados por cadeias distintas do Spring Security casadas por `getLocalPort()`. Persistência em PostgreSQL 16 real — em desenvolvimento pelo Docker Compose, nos testes pelo Testcontainers. Nenhum banco em memória em lugar nenhum: `pg_trgm`, `unaccent`, índice parcial e `INET` não existem no H2, e testar contra um substituto esconderia justamente os erros que importam.

**Tech Stack:** Java 21 (Temurin 21.0.12 LTS), Spring Boot 4.1.1, Spring Data JPA/Hibernate, Flyway 11, PostgreSQL 16, Maven 3.9.16 fixado por `mise`, JUnit 5, Testcontainers, MockMvc.

**Spec:** `chimaclub-definicao-projeto.md` e `chimaclub-plano-seguranca.md` (ambos na raiz do repositório)

## Global Constraints

Estas regras valem para **toda** tarefa deste plano. Os requisitos de cada tarefa incluem esta seção implicitamente.

- **Java 21**, exatamente `temurin-21.0.12+101.0.LTS`, fixado em `.mise.toml`. Nunca o JDK do sistema (que é 26).
- **Maven 3.9.16**, fixado em `.mise.toml`. Todo comando Maven roda por `mise exec -- mvn ...` ou `./mvnw`.
- **Spring Boot 4.1.1.** O suporte OSS da 3.3.x terminou em 2025-06-30 e o da 3.5.x em 2026-06-30; a 4.1.x é a linha corrente, com suporte OSS até 2027-07-31. Manter linha fora de suporte contraria o §A06 do plano de segurança.
- **Idioma do código:** nomes de pacote, classe, método, coluna e tabela em português, como nos documentos. Mensagens de log e comentários em português.
- **Preço sempre em centavos**, `long` em Java e `BIGINT` no banco. Ponto flutuante para dinheiro é proibido.
- **Chaves primárias UUID v7**, geradas na aplicação pela `uuid-creator`. `gen_random_uuid()` do PostgreSQL produz v4 e não deve ser usada.
- **Exclusão sempre lógica**, pela coluna `excluido_em`. Nenhum `DELETE` de produto.
- **Nenhum segredo versionado.** `secrets/`, `*.env` e `backups/` entram no `.gitignore` no primeiro commit, antes de qualquer arquivo de configuração existir.
- **Concatenação de string em JPQL ou SQL é proibida**, inclusive na busca com `LIKE`. Todo valor vindo do usuário entra como parâmetro vinculado.
- **Teste de integração usa PostgreSQL real** por Testcontainers. H2 e qualquer outro banco em memória são proibidos.
- **Cada tarefa termina em commit próprio**, com testes verdes.

---

### Task 1: Fundação do repositório e primeiro commit

Antes de qualquer arquivo de configuração existir, o `.gitignore` precisa estar no lugar. Um `secrets/senha_banco.txt` criado antes disso entra no histórico e não sai mais.

**Files:**
- Create: `.gitignore`
- Create: `.mise.toml` (já existe — conferir conteúdo)
- Create: `README.md`

**Interfaces:**
- Consumes: nada
- Produces: repositório inicializado com o primeiro commit; `mise exec -- java -version` respondendo 21.0.12

- [ ] **Step 1: Conferir que o toolchain responde**

```bash
mise exec -- java -version
mise exec -- mvn -v
```

Expected: `openjdk version "21.0.12.1"` e `Apache Maven 3.9.16`. Se não, rodar `mise trust . && mise install`.

- [ ] **Step 2: Escrever o `.gitignore`**

```gitignore
# Segredos — nunca versionados (§4.4 do plano de segurança)
secrets/
*.env
.env.*
!.env.example

# Backups
backups/

# Build
target/
!.mvn/wrapper/maven-wrapper.jar

# Fotos enviadas em desenvolvimento
fotos/
/var/

# IDE
.idea/
*.iml
.vscode/
.settings/
.classpath
.project

# Sistema
.DS_Store
*.log
```

- [ ] **Step 3: Escrever o `README.md`**

```markdown
# Chima Club

Catálogo online de cuias e acessórios para chimarrão, com painel
administrativo próprio e venda finalizada via WhatsApp.

Documentos do projeto:

- `chimaclub-definicao-projeto.md` — escopo, arquitetura, modelo de dados
- `chimaclub-plano-seguranca.md` — controles de segurança e listas de verificação

## Desenvolvimento

As versões de Java e Maven são fixadas por [mise](https://mise.jdx.dev):

```bash
mise trust .
mise install
```

Subir o banco e rodar a aplicação:

```bash
docker compose up -d banco
./mvnw spring-boot:run
```

O catálogo público responde em <http://127.0.0.1:8080> e o painel
administrativo em <http://127.0.0.1:8081/admin>. São portas separadas de
propósito: só a pública atravessa o Tailscale Funnel.

## Testes

```bash
./mvnw verify
```

Os testes de integração sobem um PostgreSQL 16 real por Testcontainers, e
exigem o Docker em funcionamento.
```

- [ ] **Step 4: Verificar que nenhum segredo entraria no commit**

```bash
git add -A && git status --short
```

Expected: nenhuma linha contendo `secrets/`, `.env` ou `backups/`.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "chore: fundação do repositório com toolchain fixado e gitignore de segredos"
```

---

### Task 2: Projeto Maven e aplicação que sobe

**Files:**
- Create: `pom.xml`
- Create: `mvnw`, `mvnw.cmd`, `.mvn/wrapper/maven-wrapper.properties`
- Create: `src/main/java/br/com/chimaclub/ChimaClubApplication.java`
- Create: `src/main/resources/application.yaml`
- Test: `src/test/java/br/com/chimaclub/ChimaClubApplicationTest.java`

**Interfaces:**
- Consumes: Task 1
- Produces: classe `br.com.chimaclub.ChimaClubApplication`; propriedades `app.porta-admin` (int, padrão 8081) e `app.endereco-bind` (String, padrão `127.0.0.1`); `./mvnw` funcional

- [ ] **Step 1: Escrever o `pom.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>

  <parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>4.1.1</version>
    <relativePath/>
  </parent>

  <groupId>br.com.chimaclub</groupId>
  <artifactId>chimaclub</artifactId>
  <version>1.0.0-SNAPSHOT</version>
  <name>Chima Club</name>
  <description>Catálogo de cuias e acessórios para chimarrão</description>

  <properties>
    <java.version>21</java.version>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    <uuid-creator.version>6.1.1</uuid-creator.version>
  </properties>

  <dependencies>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-web</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-data-jpa</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-validation</artifactId>
    </dependency>
    <dependency>
      <groupId>org.flywaydb</groupId>
      <artifactId>flyway-core</artifactId>
    </dependency>
    <dependency>
      <groupId>org.flywaydb</groupId>
      <artifactId>flyway-database-postgresql</artifactId>
    </dependency>
    <dependency>
      <groupId>org.postgresql</groupId>
      <artifactId>postgresql</artifactId>
      <scope>runtime</scope>
    </dependency>
    <dependency>
      <groupId>com.github.f4b6a3</groupId>
      <artifactId>uuid-creator</artifactId>
      <version>${uuid-creator.version}</version>
    </dependency>

    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-test</artifactId>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-testcontainers</artifactId>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>org.testcontainers</groupId>
      <artifactId>postgresql</artifactId>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>org.testcontainers</groupId>
      <artifactId>junit-jupiter</artifactId>
      <scope>test</scope>
    </dependency>
  </dependencies>

  <build>
    <plugins>
      <plugin>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-maven-plugin</artifactId>
      </plugin>
    </plugins>
  </build>
</project>
```

- [ ] **Step 2: Gerar o Maven Wrapper**

```bash
mise exec -- mvn -N wrapper:wrapper -Dmaven=3.9.16
```

Expected: cria `mvnw`, `mvnw.cmd` e `.mvn/wrapper/maven-wrapper.properties`.

- [ ] **Step 3: Escrever a classe principal**

```java
package br.com.chimaclub;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class ChimaClubApplication {

    public static void main(String[] args) {
        SpringApplication.run(ChimaClubApplication.class, args);
    }
}
```

- [ ] **Step 4: Escrever o `application.yaml`**

```yaml
spring:
  application:
    name: chimaclub
  datasource:
    url: jdbc:postgresql://127.0.0.1:5432/chimaclub
    username: chimaclub
  jpa:
    open-in-view: false
    hibernate:
      ddl-auto: validate
    properties:
      hibernate.jdbc.time_zone: America/Sao_Paulo
  flyway:
    enabled: true
    clean-disabled: true

server:
  port: 8080
  server-header: ""
  error:
    include-stacktrace: never
    include-message: never
    whitelabel:
      enabled: false

app:
  porta-admin: 8081
  # Dentro do contêiner precisa ser 0.0.0.0: 127.0.0.1 ali é o loopback do
  # próprio contêiner, e a porta ficaria inalcançável pela publicação do
  # Docker. O perfil prod sobrescreve este valor.
  endereco-bind: 127.0.0.1
```

- [ ] **Step 5: Escrever o teste de contexto**

Ele é deliberadamente magro: o que ele prova é que o `pom.xml` resolve, que a classe principal está anotada e que nada na configuração impede a aplicação de subir. As tarefas seguintes acrescentam o que importa.

```java
package br.com.chimaclub;

import org.junit.jupiter.api.Test;

class ChimaClubApplicationTest {

    @Test
    void classePrincipalTemMetodoMain() throws Exception {
        assertDoesNotThrow(() -> ChimaClubApplication.class.getDeclaredMethod("main", String[].class));
    }
}
```

Acrescentar o import estático:

```java
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
```

- [ ] **Step 6: Rodar e verificar que passa**

```bash
./mvnw -q test
```

Expected: BUILD SUCCESS. Se o Spring Boot 4.1.1 não resolver, conferir a conectividade com o Maven Central antes de trocar a versão.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "feat: projeto Maven com Spring Boot 4.1.1 e wrapper fixado"
```

---

### Task 3: Docker Compose com PostgreSQL 16

**Files:**
- Create: `compose.yaml`
- Create: `secrets/.gitkeep`
- Create: `secrets/senha_banco.txt` (**não versionado**)

**Interfaces:**
- Consumes: Task 2
- Produces: serviço `banco` respondendo em `127.0.0.1:5432`, base `chimaclub`, usuário `chimaclub`

- [ ] **Step 1: Escrever o `compose.yaml`**

Nesta fase entra só o serviço do banco. O serviço `app`, com `read_only`, `cap_drop` e usuário não-root, entra na Fase 4, junto do `Dockerfile` — adiantá-lo aqui criaria um arquivo que ninguém consegue executar e que ninguém revisa.

```yaml
services:
  banco:
    # Fixar por digest antes de publicar (§A06):
    #   docker buildx imagetools inspect postgres:16-alpine
    image: postgres:16-alpine
    restart: unless-stopped
    environment:
      POSTGRES_DB: chimaclub
      POSTGRES_USER: chimaclub
      POSTGRES_PASSWORD_FILE: /run/secrets/senha_banco
    volumes:
      - dados_pg:/var/lib/postgresql/data
      - ./backups:/backups
    ports:
      - "127.0.0.1:5432:5432"   # nunca 0.0.0.0
    secrets: [senha_banco]
    security_opt: ["no-new-privileges:true"]
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U chimaclub -d chimaclub"]
      interval: 10s
      timeout: 5s
      retries: 5

volumes:
  dados_pg:

secrets:
  senha_banco:
    file: ./secrets/senha_banco.txt
```

- [ ] **Step 2: Gerar a senha do banco**

```bash
mkdir -p secrets backups
umask 077
head -c 32 /dev/urandom | base64 | tr -d '\n=/+' > secrets/senha_banco.txt
chmod 600 secrets/senha_banco.txt
```

- [ ] **Step 3: Confirmar que o segredo está fora do Git**

```bash
git check-ignore -v secrets/senha_banco.txt
```

Expected: uma linha apontando para a regra `secrets/` do `.gitignore`. Se não imprimir nada, **parar** e corrigir o `.gitignore` antes de seguir.

- [ ] **Step 4: Subir o banco e verificar**

```bash
docker compose up -d banco
docker compose exec banco pg_isready -U chimaclub -d chimaclub
```

Expected: `accepting connections`.

- [ ] **Step 5: Confirmar que a porta não está exposta na rede**

```bash
ss -ltnp 2>/dev/null | grep 5432
```

Expected: `127.0.0.1:5432`, nunca `0.0.0.0:5432` nem `*:5432`.

- [ ] **Step 6: Commit**

```bash
git add compose.yaml .gitignore
git commit -m "feat: PostgreSQL 16 em Compose, ligado apenas ao loopback"
```

---

### Task 4: Migração V1 — esquema inicial

A migração é escrita em SQL e revisada como código. O teste que a acompanha não confere apenas que ela roda: confere as decisões de modelagem que o spec toma, porque é nelas que um erro passa despercebido por meses.

**Files:**
- Create: `src/main/resources/db/migration/V1__esquema_inicial.sql`
- Test: `src/test/java/br/com/chimaclub/BancoDeTesteBase.java`
- Test: `src/test/java/br/com/chimaclub/migracao/MigracaoV1Test.java`

**Interfaces:**
- Consumes: Task 3
- Produces: classe base `BancoDeTesteBase` com `@Container PostgreSQLContainer` e `@DynamicPropertySource`, herdada por todo teste de integração; função SQL `imutavel_unaccent(text)`; tabelas `categoria`, `produto`, `produto_foto`, `usuario_admin`, `evento_auditoria`, `configuracao`, `clique_whatsapp`

- [ ] **Step 1: Escrever o teste, que falha porque não há migração**

```java
package br.com.chimaclub;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Base de todo teste de integração. Sobe um PostgreSQL 16 real, porque o
 * esquema depende de pg_trgm, unaccent, índice parcial e INET — nada disso
 * existe em banco em memória, e testar contra um substituto esconderia
 * justamente os erros que importam.
 *
 * O contêiner é estático: sobe uma vez por execução da suíte, não por classe.
 */
@Testcontainers
@SpringBootTest
public abstract class BancoDeTesteBase {

    @Container
    static final PostgreSQLContainer<?> BANCO =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("chimaclub")
                    .withUsername("chimaclub")
                    .withPassword("senha-de-teste");

    @DynamicPropertySource
    static void propriedades(DynamicPropertyRegistry registro) {
        registro.add("spring.datasource.url", BANCO::getJdbcUrl);
        registro.add("spring.datasource.username", BANCO::getUsername);
        registro.add("spring.datasource.password", BANCO::getPassword);
    }
}
```

```java
package br.com.chimaclub.migracao;

import br.com.chimaclub.BancoDeTesteBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MigracaoV1Test extends BancoDeTesteBase {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    @DisplayName("cria todas as tabelas do modelo")
    void criaTodasAsTabelas() {
        List<String> tabelas = jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'",
                String.class);

        assertThat(tabelas).contains(
                "categoria", "produto", "produto_foto",
                "usuario_admin", "evento_auditoria", "configuracao", "clique_whatsapp");
    }

    @Test
    @DisplayName("imutavel_unaccent é IMMUTABLE, senão o índice de busca não pode existir")
    void funcaoDeBuscaEhImutavel() {
        String volatilidade = jdbc.queryForObject(
                "SELECT provolatile FROM pg_proc WHERE proname = 'imutavel_unaccent'",
                String.class);

        // 'i' = immutable, 's' = stable, 'v' = volatile
        assertThat(volatilidade).isEqualTo("i");
    }

    @Test
    @DisplayName("remove acento e caixa, para que 'Cuía' e 'cuia' se encontrem")
    void normalizaAcentoECaixa() {
        String normalizado = jdbc.queryForObject(
                "SELECT imutavel_unaccent(lower(?))", String.class, "Cuía Gold");

        assertThat(normalizado).isEqualTo("cuia gold");
    }

    @Test
    @DisplayName("preço negativo é recusado pelo banco, não só pela aplicação")
    void recusaPrecoNegativo() {
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO produto (id, nome, slug, preco_centavos)
                VALUES (gen_random_uuid(), 'Teste', 'teste-preco', -1)
                """))
                .hasMessageContaining("produto_preco_positivo");
    }

    @Test
    @DisplayName("só existe uma foto principal por produto")
    void impedeDuasFotosPrincipais() {
        jdbc.update("""
                INSERT INTO produto (id, nome, slug, preco_centavos)
                VALUES ('01920000-0000-7000-8000-0000000000aa', 'Teste foto', 'teste-foto', 8990)
                """);

        String inserirFoto = """
                INSERT INTO produto_foto
                  (id, produto_id, arquivo, arquivo_mini, largura, altura, bytes, tipo_mime, principal)
                VALUES (gen_random_uuid(), '01920000-0000-7000-8000-0000000000aa',
                        ?, ?, 1200, 1500, 1024, 'image/webp', true)
                """;

        jdbc.update(inserirFoto, "a-media.webp", "a-mini.webp");

        assertThatThrownBy(() -> jdbc.update(inserirFoto, "b-media.webp", "b-mini.webp"))
                .hasMessageContaining("idx_foto_principal_unica");
    }
}
```

Acrescentar o import estático:

```java
import static org.assertj.core.api.Assertions.assertThatThrownBy;
```

- [ ] **Step 2: Rodar e verificar que falha**

```bash
./mvnw -q test -Dtest=MigracaoV1Test
```

Expected: FAIL — as tabelas não existem, porque não há migração nenhuma.

- [ ] **Step 3: Escrever a migração V1**

Copiar a DDL da §3.3 de `chimaclub-definicao-projeto.md` para `src/main/resources/db/migration/V1__esquema_inicial.sql`, na íntegra e sem alteração. A DDL do documento já inclui a função `imutavel_unaccent` e o índice de busca construído sobre ela.

- [ ] **Step 4: Rodar e verificar que passa**

```bash
./mvnw -q test -Dtest=MigracaoV1Test
```

Expected: PASS, nos cinco testes.

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/db/migration src/test/java
git commit -m "feat: migração V1 com esquema inicial e função imutável de busca"
```

---

### Task 5: Migração V2 — dados iniciais, e prova de que o índice é usado

Um índice que existe mas nunca é escolhido pelo planejador é pior que nenhum: dá a impressão de que a busca escala. Este teste lê o plano de execução.

**Files:**
- Create: `src/main/resources/db/migration/V2__dados_iniciais.sql`
- Test: `src/test/java/br/com/chimaclub/migracao/MigracaoV2Test.java`
- Test: `src/test/java/br/com/chimaclub/migracao/IndiceDeBuscaTest.java`

**Interfaces:**
- Consumes: Task 4
- Produces: seis chaves em `configuracao`; duas categorias com UUID v7 fixo `01920000-0000-7000-8000-000000000001` (madeira) e `...002` (porongo)

- [ ] **Step 1: Escrever os testes, que falham**

```java
package br.com.chimaclub.migracao;

import br.com.chimaclub.BancoDeTesteBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

class MigracaoV2Test extends BancoDeTesteBase {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    @DisplayName("semeia a configuração da loja")
    void semeiaConfiguracao() {
        String numero = jdbc.queryForObject(
                "SELECT valor FROM configuracao WHERE chave = 'whatsapp_numero'", String.class);

        assertThat(numero).isEqualTo("5551989250481");
    }

    @Test
    @DisplayName("as categorias têm UUID fixo, para a migração ser determinística")
    void categoriasTemIdFixo() {
        String id = jdbc.queryForObject(
                "SELECT id::text FROM categoria WHERE slug = 'cuias-em-madeira'", String.class);

        assertThat(id).isEqualTo("01920000-0000-7000-8000-000000000001");
    }

    @Test
    @DisplayName("os identificadores semeados são versão 7, não versão 4")
    void identificadoresSaoVersao7() {
        // O 13º dígito hexadecimal do UUID carrega o número da versão.
        List<String> versoes = jdbc.queryForList(
                "SELECT substring(id::text, 15, 1) FROM categoria", String.class);

        assertThat(versoes).isNotEmpty().allMatch("7"::equals);
    }
}
```

Acrescentar `import java.util.List;`.

```java
package br.com.chimaclub.migracao;

import br.com.chimaclub.BancoDeTesteBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A consulta de busca precisa usar imutavel_unaccent, a mesma função do
 * índice. Escrever unaccent(...) devolveria o mesmo resultado, mas com
 * varredura sequencial — e ninguém perceberia até o catálogo crescer.
 */
class IndiceDeBuscaTest extends BancoDeTesteBase {

    @Autowired
    JdbcTemplate jdbc;

    private static final String CONSULTA = """
            SELECT p.id FROM produto p
             WHERE imutavel_unaccent(lower(p.nome)) LIKE '%' || imutavel_unaccent(lower(?)) || '%'
            """;

    @Test
    @DisplayName("o planejador escolhe o índice gin em vez de varredura sequencial")
    void usaOIndiceGin() {
        // Com poucas linhas o planejador prefere varredura sequencial por ser
        // mais barata, o que é correto. Desligá-la revela se o índice é sequer
        // aplicável à consulta — que é o que este teste verifica.
        jdbc.execute("SET enable_seqscan = off");
        for (int i = 0; i < 50; i++) {
            jdbc.update("""
                    INSERT INTO produto (id, nome, slug, preco_centavos, publicado)
                    VALUES (?, ?, ?, 8990, true)
                    """, UUID.randomUUID(), "Cuia número " + i, "cuia-numero-" + i);
        }

        List<String> plano = jdbc.queryForList("EXPLAIN " + CONSULTA, String.class, "cuia");

        assertThat(String.join("\n", plano)).contains("idx_produto_busca_nome");
    }

    @Test
    @DisplayName("encontra 'gold', 'Gold' e 'cuia gold' no mesmo produto")
    void encontraIndependenteDeCaixaEAcento() {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO produto (id, nome, slug, preco_centavos, publicado)
                VALUES (?, 'Cuía Gold em madeira', 'cuia-gold-em-madeira', 8990, true)
                """, id);

        for (String termo : List.of("gold", "Gold", "cuia gold", "CUIA GOLD")) {
            List<UUID> achados = jdbc.queryForList(CONSULTA, UUID.class, termo);
            assertThat(achados).as("busca por '%s'", termo).contains(id);
        }
    }
}
```

- [ ] **Step 2: Rodar e verificar que falham**

```bash
./mvnw -q test -Dtest='MigracaoV2Test,IndiceDeBuscaTest'
```

Expected: `MigracaoV2Test` FAIL porque não há dados semeados. `IndiceDeBuscaTest` pode falhar na busca por `cuia gold`, já que `LIKE` sozinho não casa termo com palavras intercaladas — ver o passo 4.

- [ ] **Step 3: Escrever a migração V2**

Copiar a §3.3 de `chimaclub-definicao-projeto.md`, bloco `V2__dados_iniciais.sql`, na íntegra.

- [ ] **Step 4: Se a busca por termo fora de ordem falhar, usar a consulta completa da §3.4**

`LIKE '%cuia gold%'` encontra `Cuía Gold em madeira`, porque o trecho é contíguo. Já `'gold cuia'` não encontraria, e é para isso que a §3.4 soma `similarity(...) > 0.25` ao `LIKE`. Se algum termo do teste falhar, trocar a constante `CONSULTA` do `IndiceDeBuscaTest` por esta, que é a da §3.4:

```sql
SELECT p.id FROM produto p
 WHERE imutavel_unaccent(lower(p.nome)) LIKE '%' || imutavel_unaccent(lower(?)) || '%'
    OR similarity(imutavel_unaccent(lower(p.nome)), imutavel_unaccent(lower(?))) > 0.25
```

Ela leva o termo duas vezes, então as chamadas do `jdbc` passam o argumento duplicado: `jdbc.queryForList(CONSULTA, UUID.class, termo, termo)`.

- [ ] **Step 5: Rodar e verificar que passam**

```bash
./mvnw -q test -Dtest='MigracaoV2Test,IndiceDeBuscaTest'
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/resources/db/migration src/test/java
git commit -m "feat: migração V2 com dados iniciais e prova de uso do índice de busca"
```

---

### Task 6: Entidades JPA

**Files:**
- Create: `src/main/java/br/com/chimaclub/comum/GeradorDeId.java`
- Create: `src/main/java/br/com/chimaclub/catalogo/Categoria.java`
- Create: `src/main/java/br/com/chimaclub/catalogo/Produto.java`
- Create: `src/main/java/br/com/chimaclub/catalogo/ProdutoFoto.java`
- Create: `src/main/java/br/com/chimaclub/catalogo/CategoriaRepository.java`
- Create: `src/main/java/br/com/chimaclub/catalogo/ProdutoRepository.java`
- Test: `src/test/java/br/com/chimaclub/catalogo/ProdutoRepositoryTest.java`

**Interfaces:**
- Consumes: Task 5
- Produces:
  - `GeradorDeId.novo()` → `UUID` (versão 7)
  - `Produto` com `getId()`, `getNome()`, `getSlug()`, `getPrecoCentavos()` (`long`), `getUnidades()` (`int`), `isPublicado()`, `getVersao()` (`long`), `getExcluidoEm()` (`Instant`)
  - `ProdutoRepository extends JpaRepository<Produto, UUID>`

- [ ] **Step 1: Escrever o teste, que falha**

```java
package br.com.chimaclub.catalogo;

import br.com.chimaclub.BancoDeTesteBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProdutoRepositoryTest extends BancoDeTesteBase {

    @Autowired ProdutoRepository produtos;
    @Autowired TransactionTemplate transacao;

    @Test
    @DisplayName("grava e relê um produto com preço em centavos")
    void gravaERele() {
        Produto produto = new Produto("Cuia Gold em madeira", "cuia-gold-em-madeira", 8990L);
        UUID id = produtos.save(produto).getId();

        Produto lido = produtos.findById(id).orElseThrow();

        assertThat(lido.getPrecoCentavos()).isEqualTo(8990L);
        assertThat(lido.isPublicado()).isFalse();
        assertThat(lido.getVersao()).isZero();
    }

    @Test
    @DisplayName("duas abas do painel não se sobrescrevem em silêncio")
    void bloqueioOtimistaRecusaSegundaGravacao() {
        UUID id = produtos.save(new Produto("Cuia Snow", "cuia-snow", 8990L)).getId();

        Produto abaA = transacao.execute(s -> produtos.findById(id).orElseThrow());
        Produto abaB = transacao.execute(s -> produtos.findById(id).orElseThrow());

        abaA.setPrecoCentavos(9990L);
        transacao.execute(s -> produtos.saveAndFlush(abaA));

        abaB.setPrecoCentavos(7990L);
        assertThatThrownBy(() -> transacao.execute(s -> produtos.saveAndFlush(abaB)))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);

        assertThat(produtos.findById(id).orElseThrow().getPrecoCentavos()).isEqualTo(9990L);
    }

    @Test
    @DisplayName("o identificador gerado é versão 7")
    void identificadorEhVersao7() {
        UUID id = produtos.save(new Produto("Cuia Pink", "cuia-pink", 8990L)).getId();

        assertThat(id.version()).isEqualTo(7);
    }
}
```

- [ ] **Step 2: Rodar e verificar que falha**

```bash
./mvnw -q test -Dtest=ProdutoRepositoryTest
```

Expected: erro de compilação — `Produto` não existe.

- [ ] **Step 3: Escrever o gerador de identificadores**

```java
package br.com.chimaclub.comum;

import com.github.f4b6a3.uuid.UuidCreator;

import java.util.UUID;

/**
 * UUID v7: ordenado no tempo, o que dá boa localidade em índice, e sem
 * revelar quantos produtos existem, como um id sequencial revelaria.
 * O PostgreSQL só oferece v4, então a geração fica na aplicação.
 */
public final class GeradorDeId {

    private GeradorDeId() {
    }

    public static UUID novo() {
        return UuidCreator.getTimeOrderedEpoch();
    }
}
```

- [ ] **Step 4: Escrever a entidade `Produto`**

```java
package br.com.chimaclub.catalogo;

import br.com.chimaclub.comum.GeradorDeId;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "produto")
public class Produto {

    @Id
    private UUID id = GeradorDeId.novo();

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "categoria_id")
    private Categoria categoria;

    @Column(nullable = false, length = 140)
    private String nome;

    @Column(nullable = false, length = 160, unique = true)
    private String slug;

    @Column(columnDefinition = "text")
    private String descricao;

    /** Sempre em centavos. Ponto flutuante para dinheiro é proibido. */
    @Column(name = "preco_centavos", nullable = false)
    private long precoCentavos;

    @Column(nullable = false)
    private int unidades;

    @Column(nullable = false)
    private boolean publicado;

    @Column(nullable = false)
    private boolean destaque;

    @Column(nullable = false)
    private int ordem;

    @Version
    @Column(nullable = false)
    private long versao;

    @Column(name = "criado_em", nullable = false, updatable = false)
    private Instant criadoEm = Instant.now();

    @Column(name = "atualizado_em", nullable = false)
    private Instant atualizadoEm = Instant.now();

    /** Exclusão é sempre lógica: nada some do banco. */
    @Column(name = "excluido_em")
    private Instant excluidoEm;

    protected Produto() {
        // exigido pelo JPA
    }

    public Produto(String nome, String slug, long precoCentavos) {
        this.nome = nome;
        this.slug = slug;
        this.precoCentavos = precoCentavos;
    }

    @PreUpdate
    void aoAtualizar() {
        this.atualizadoEm = Instant.now();
    }

    public UUID getId() { return id; }
    public Categoria getCategoria() { return categoria; }
    public void setCategoria(Categoria categoria) { this.categoria = categoria; }
    public String getNome() { return nome; }
    public void setNome(String nome) { this.nome = nome; }
    public String getSlug() { return slug; }
    public void setSlug(String slug) { this.slug = slug; }
    public String getDescricao() { return descricao; }
    public void setDescricao(String descricao) { this.descricao = descricao; }
    public long getPrecoCentavos() { return precoCentavos; }
    public void setPrecoCentavos(long precoCentavos) { this.precoCentavos = precoCentavos; }
    public int getUnidades() { return unidades; }
    public void setUnidades(int unidades) { this.unidades = unidades; }
    public boolean isPublicado() { return publicado; }
    public void setPublicado(boolean publicado) { this.publicado = publicado; }
    public boolean isDestaque() { return destaque; }
    public void setDestaque(boolean destaque) { this.destaque = destaque; }
    public int getOrdem() { return ordem; }
    public void setOrdem(int ordem) { this.ordem = ordem; }
    public long getVersao() { return versao; }
    public Instant getCriadoEm() { return criadoEm; }
    public Instant getAtualizadoEm() { return atualizadoEm; }
    public Instant getExcluidoEm() { return excluidoEm; }
    public void setExcluidoEm(Instant excluidoEm) { this.excluidoEm = excluidoEm; }
}
```

- [ ] **Step 5: Escrever `Categoria`, `ProdutoFoto` e os repositórios**

```java
package br.com.chimaclub.catalogo;

import br.com.chimaclub.comum.GeradorDeId;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "categoria")
public class Categoria {

    @Id
    private UUID id = GeradorDeId.novo();

    @Column(nullable = false, length = 80)
    private String nome;

    @Column(nullable = false, length = 80, unique = true)
    private String slug;

    @Column(nullable = false)
    private int ordem;

    @Column(name = "criado_em", nullable = false, updatable = false)
    private Instant criadoEm = Instant.now();

    @Column(name = "atualizado_em", nullable = false)
    private Instant atualizadoEm = Instant.now();

    protected Categoria() {
        // exigido pelo JPA
    }

    public Categoria(String nome, String slug, int ordem) {
        this.nome = nome;
        this.slug = slug;
        this.ordem = ordem;
    }

    @PreUpdate
    void aoAtualizar() {
        this.atualizadoEm = Instant.now();
    }

    public UUID getId() { return id; }
    public String getNome() { return nome; }
    public void setNome(String nome) { this.nome = nome; }
    public String getSlug() { return slug; }
    public void setSlug(String slug) { this.slug = slug; }
    public int getOrdem() { return ordem; }
    public void setOrdem(int ordem) { this.ordem = ordem; }
    public Instant getCriadoEm() { return criadoEm; }
    public Instant getAtualizadoEm() { return atualizadoEm; }
}
```

```java
package br.com.chimaclub.catalogo;

import br.com.chimaclub.comum.GeradorDeId;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "produto_foto")
public class ProdutoFoto {

    @Id
    private UUID id = GeradorDeId.novo();

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "produto_id", nullable = false)
    private Produto produto;

    /** Nome gerado pelo sistema. O nome enviado pelo usuário é descartado. */
    @Column(nullable = false, length = 255)
    private String arquivo;

    @Column(name = "arquivo_mini", nullable = false, length = 255)
    private String arquivoMini;

    @Column(name = "texto_alt", length = 180)
    private String textoAlt;

    @Column(nullable = false)
    private int largura;

    @Column(nullable = false)
    private int altura;

    @Column(nullable = false)
    private long bytes;

    @Column(name = "tipo_mime", nullable = false, length = 40)
    private String tipoMime;

    @Column(nullable = false)
    private boolean principal;

    @Column(nullable = false)
    private int ordem;

    @Column(name = "criado_em", nullable = false, updatable = false)
    private Instant criadoEm = Instant.now();

    protected ProdutoFoto() {
        // exigido pelo JPA
    }

    public ProdutoFoto(Produto produto, String arquivo, String arquivoMini,
                       int largura, int altura, long bytes, String tipoMime) {
        this.produto = produto;
        this.arquivo = arquivo;
        this.arquivoMini = arquivoMini;
        this.largura = largura;
        this.altura = altura;
        this.bytes = bytes;
        this.tipoMime = tipoMime;
    }

    public UUID getId() { return id; }
    public Produto getProduto() { return produto; }
    public String getArquivo() { return arquivo; }
    public String getArquivoMini() { return arquivoMini; }
    public String getTextoAlt() { return textoAlt; }
    public void setTextoAlt(String textoAlt) { this.textoAlt = textoAlt; }
    public int getLargura() { return largura; }
    public int getAltura() { return altura; }
    public long getBytes() { return bytes; }
    public String getTipoMime() { return tipoMime; }
    public boolean isPrincipal() { return principal; }
    public void setPrincipal(boolean principal) { this.principal = principal; }
    public int getOrdem() { return ordem; }
    public void setOrdem(int ordem) { this.ordem = ordem; }
    public Instant getCriadoEm() { return criadoEm; }
}
```

```java
package br.com.chimaclub.catalogo;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ProdutoRepository extends JpaRepository<Produto, UUID> {

    Optional<Produto> findBySlugAndExcluidoEmIsNull(String slug);

    boolean existsBySlug(String slug);
}
```

```java
package br.com.chimaclub.catalogo;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface CategoriaRepository extends JpaRepository<Categoria, UUID> {
}
```

- [ ] **Step 6: Rodar e verificar que passa**

```bash
./mvnw -q test -Dtest=ProdutoRepositoryTest
```

Expected: PASS, nos três testes. O `ddl-auto: validate` do `application.yaml` também confere que cada campo mapeado corresponde a uma coluna real da migração — um nome trocado quebra a subida do contexto.

- [ ] **Step 7: Commit**

```bash
git add src/main/java src/test/java
git commit -m "feat: entidades JPA do catálogo com UUID v7 e bloqueio otimista"
```

---

### Task 7: Dois conectores e o isolamento do painel

Esta é a tarefa central da fase, e a razão de o painel existir em porta separada. O teste tem de provar o que o §A01 promete: **quem chega pela porta pública não alcança `/admin`, mesmo autenticado.**

**Files:**
- Create: `src/main/java/br/com/chimaclub/config/PortasConfig.java`
- Create: `src/main/java/br/com/chimaclub/config/SegurancaConfig.java`
- Create: `src/main/java/br/com/chimaclub/comum/SaudeController.java`
- Create: `src/main/java/br/com/chimaclub/admin/PainelController.java`
- Modify: `pom.xml` (acrescentar `spring-boot-starter-security`)
- Test: `src/test/java/br/com/chimaclub/config/IsolamentoDoPainelTest.java`

**Interfaces:**
- Consumes: Task 6
- Produces: `GET /saude` na porta pública devolvendo 200 e o corpo `ok`; `GET /admin/painel` respondendo apenas na porta administrativa

- [ ] **Step 1: Acrescentar a dependência de segurança ao `pom.xml`**

```xml
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-security</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.security</groupId>
      <artifactId>spring-security-test</artifactId>
      <scope>test</scope>
    </dependency>
```

- [ ] **Step 2: Escrever o teste, que falha**

O teste sobe a aplicação numa porta real (`webEnvironment = RANDOM_PORT`), porque `MockMvc` não tem porta e não conseguiria distinguir as duas cadeias. Ele bate nas duas portas com um cliente HTTP de verdade.

```java
package br.com.chimaclub.config;

import br.com.chimaclub.BancoDeTesteBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class IsolamentoDoPainelTest extends BancoDeTesteBase {

    @LocalServerPort int portaPublica;

    @Value("${app.porta-admin}") int portaAdmin;

    @Autowired TestRestTemplate cliente;

    @Test
    @DisplayName("o catálogo público responde na porta pública")
    void catalogoRespondeNaPortaPublica() {
        ResponseEntity<String> resposta =
                cliente.getForEntity("http://127.0.0.1:" + portaPublica + "/saude", String.class);

        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resposta.getBody()).isEqualTo("ok");
    }

    @Test
    @DisplayName("o painel NÃO é alcançável pela porta pública — item central do §A01")
    void painelNaoRespondeNaPortaPublica() {
        ResponseEntity<String> resposta =
                cliente.getForEntity("http://127.0.0.1:" + portaPublica + "/admin/painel", String.class);

        assertThat(resposta.getStatusCode())
                .as("quem chega pela internet não pode nem ver a tela de login")
                .isIn(HttpStatus.NOT_FOUND, HttpStatus.FORBIDDEN);
        assertThat(resposta.getBody()).doesNotContain("senha");
    }

    @Test
    @DisplayName("o actuator NÃO é alcançável pela porta pública")
    void actuatorNaoRespondeNaPortaPublica() {
        ResponseEntity<String> resposta =
                cliente.getForEntity("http://127.0.0.1:" + portaPublica + "/actuator/health", String.class);

        assertThat(resposta.getStatusCode()).isIn(HttpStatus.NOT_FOUND, HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("o painel exige autenticação na porta administrativa, mas existe ali")
    void painelExigeAutenticacaoNaPortaAdmin() {
        ResponseEntity<String> resposta =
                cliente.getForEntity("http://127.0.0.1:" + portaAdmin + "/admin/painel", String.class);

        assertThat(resposta.getStatusCode())
                .as("na tailnet a resposta é o desvio para o login, não 404")
                .isIn(HttpStatus.UNAUTHORIZED, HttpStatus.FOUND, HttpStatus.OK);
    }

    @Test
    @DisplayName("nenhuma resposta revela a versão do servidor")
    void naoRevelaVersaoDoServidor() {
        ResponseEntity<String> resposta =
                cliente.getForEntity("http://127.0.0.1:" + portaPublica + "/saude", String.class);

        assertThat(resposta.getHeaders().getFirst("Server")).isNull();
        assertThat(resposta.getHeaders().getFirst("X-Powered-By")).isNull();
    }
}
```

**Atenção:** o `app.porta-admin` precisa ser fixo e livre durante o teste. Acrescentar `src/test/resources/application.yaml` com `app: { porta-admin: 18081, endereco-bind: 127.0.0.1 }`. Se a suíte for rodar em paralelo no futuro, essa porta fixa vira conflito — resolver então, não agora.

- [ ] **Step 3: Rodar e verificar que falha**

```bash
./mvnw -q test -Dtest=IsolamentoDoPainelTest
```

Expected: FAIL — não há conector administrativo nem rota `/saude`.

- [ ] **Step 4: Escrever o `PortasConfig`**

```java
package br.com.chimaclub.config;

import org.apache.catalina.connector.Connector;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PortasConfig {

    /**
     * O endereço de bind vem de configuração: 127.0.0.1 no perfil dev, que roda
     * direto no host, e 0.0.0.0 no perfil prod, que roda em contêiner. Dentro do
     * contêiner, 127.0.0.1 é o loopback do próprio contêiner: ligar nele deixaria
     * a porta inalcançável pela publicação do Docker, e o painel simplesmente não
     * responderia. Em produção o confinamento é feito pelo "127.0.0.1:8081:8081"
     * do Compose, pelo UFW e pelo Tailscale — nunca por este endereço.
     */
    @Bean
    public WebServerFactoryCustomizer<TomcatServletWebServerFactory> conectorAdmin(
            @Value("${app.porta-admin:8081}") int portaAdmin,
            @Value("${app.endereco-bind:127.0.0.1}") String enderecoBind) {
        return factory -> {
            Connector admin = new Connector("org.apache.coyote.http11.Http11NioProtocol");
            admin.setPort(portaAdmin);
            admin.setProperty("address", enderecoBind);
            admin.setAllowTrace(false);
            factory.addAdditionalTomcatConnectors(admin);
        };
    }
}
```

- [ ] **Step 5: Escrever o `SegurancaConfig`**

As duas cadeias são casadas por `getLocalPort()`, de forma que a porta de chegada — e não o caminho da URL — decide qual política se aplica. A cadeia pública nega `/admin/**` explicitamente, o que torna o isolamento independente de qualquer configuração de roteamento.

```java
package br.com.chimaclub.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SegurancaConfig {

    @Bean
    @Order(1)
    SecurityFilterChain cadeiaAdmin(HttpSecurity http,
                                    @Value("${app.porta-admin:8081}") int portaAdmin) throws Exception {
        http.securityMatcher(req -> req.getLocalPort() == portaAdmin)
            .authorizeHttpRequests(a -> a
                .requestMatchers("/admin/login", "/css/**", "/js/**").permitAll()
                .anyRequest().hasRole("ADMIN"))
            .httpBasic(Customizer.withDefaults())   // o login por formulário entra na Fase 2
            .csrf(Customizer.withDefaults());
        return http.build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain cadeiaPublica(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(a -> a
                .requestMatchers("/admin/**", "/actuator/**").denyAll()
                .requestMatchers(HttpMethod.GET, "/", "/saude", "/busca", "/produto/**",
                                 "/fotos/**", "/css/**", "/js/**", "/fontes/**",
                                 "/sitemap.xml", "/robots.txt").permitAll()
                .requestMatchers(HttpMethod.POST, "/produto/*/whatsapp").permitAll()
                .anyRequest().denyAll())
            .csrf(c -> c.ignoringRequestMatchers("/produto/*/whatsapp"))
            .anonymous(Customizer.withDefaults());
        return http.build();
    }
}
```

- [ ] **Step 6: Escrever os dois controladores**

```java
package br.com.chimaclub.comum;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SaudeController {

    /** Verificação simples, sem detalhe interno: o Actuator fica na porta admin. */
    @GetMapping("/saude")
    public String saude() {
        return "ok";
    }
}
```

```java
package br.com.chimaclub.admin;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PainelController {

    @GetMapping("/admin/painel")
    public String painel() {
        return "painel";
    }
}
```

- [ ] **Step 7: Rodar e verificar que passa**

```bash
./mvnw -q test -Dtest=IsolamentoDoPainelTest
```

Expected: PASS, nos cinco testes. Se `painelNaoRespondeNaPortaPublica` falhar com 401 em vez de 403 ou 404, a cadeia pública não está casando — conferir a ordem dos `@Order` e o `securityMatcher`.

- [ ] **Step 8: Commit**

```bash
git add pom.xml src/main/java src/test/java src/test/resources
git commit -m "feat: conectores separados e isolamento do painel provado por teste"
```

---

### Task 8: Fechar a fase — suíte completa e base limpa

O critério de aceite da §9 exige que a migração rode em base limpa **e** em base com dados. Até aqui só provamos a base limpa, porque cada execução dos testes começa do zero.

**Files:**
- Test: `src/test/java/br/com/chimaclub/migracao/MigracaoIncrementalTest.java`
- Modify: `README.md`

**Interfaces:**
- Consumes: Tasks 1–7
- Produces: fase 1 completa, `./mvnw verify` verde

- [ ] **Step 1: Escrever o teste de migração sobre base com dados**

```java
package br.com.chimaclub.migracao;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import br.com.chimaclub.BancoDeTesteBase;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MigracaoIncrementalTest extends BancoDeTesteBase {

    @Autowired JdbcTemplate jdbc;
    @Autowired Flyway flyway;

    @Test
    @DisplayName("revalidar as migrações sobre base já povoada não perde dado nem falha")
    void migraSobreBaseComDados() {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO produto (id, nome, slug, preco_centavos, publicado)
                VALUES (?, 'Produto anterior', 'produto-anterior', 8990, true)
                """, id);

        // validate() confere que nenhum arquivo de migração já aplicado foi
        // editado depois do fato — o erro mais comum e mais silencioso do Flyway.
        flyway.validate();
        flyway.migrate();

        Integer restantes = jdbc.queryForObject(
                "SELECT count(*) FROM produto WHERE id = ?", Integer.class, id);

        assertThat(restantes).isEqualTo(1);
    }
}
```

- [ ] **Step 2: Rodar a suíte inteira**

```bash
./mvnw verify
```

Expected: BUILD SUCCESS, com todos os testes das tarefas 4 a 8 verdes.

- [ ] **Step 3: Subir a aplicação de verdade e conferir as duas portas**

```bash
docker compose up -d banco
./mvnw spring-boot:run &
sleep 25
curl -si http://127.0.0.1:8080/saude       | head -1   # espera 200
curl -so /dev/null -w '%{http_code}\n' http://127.0.0.1:8080/admin/painel   # espera 403
curl -so /dev/null -w '%{http_code}\n' http://127.0.0.1:8081/admin/painel   # espera 401
curl -sI http://127.0.0.1:8080/saude | grep -i '^server:' || echo "sem cabeçalho Server — correto"
kill %1
```

- [ ] **Step 4: Confirmar que nenhum segredo entrou no histórico**

```bash
git log --all --numstat --format= | awk '{print $3}' | sort -u | grep -E 'secrets/|\.env$' && echo "PARAR: segredo no histórico" || echo "nenhum segredo versionado"
```

- [ ] **Step 5: Acrescentar a seção "Estado atual" ao `README.md`**

```markdown
## Estado atual — Fase 1 concluída

Funciona:

- Aplicação subindo com dois conectores: 8080 público, 8081 administrativo
- PostgreSQL 16 em Compose, ligado apenas a `127.0.0.1`
- Esquema completo criado por Flyway (V1) e dados iniciais semeados (V2)
- Entidades `Categoria`, `Produto` e `ProdutoFoto` com UUID v7 e bloqueio otimista
- Busca tolerante a acento e a caixa, com índice `gin` comprovadamente em uso
- `/admin` e `/actuator` negados na porta pública, provado por teste

Ainda não existe:

- Login por formulário, TOTP e bloqueio por tentativa (Fase 2)
- CRUD de produtos, upload e processamento de fotos (Fase 2)
- Catálogo público, busca na tela e identidade visual (Fase 3)
- Cabeçalhos de segurança, CSP, limite de requisições e `Dockerfile` (Fase 4)
```

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "test: migração sobre base povoada e fechamento da Fase 1"
```

---

## O que a Fase 1 deliberadamente não entrega

Registrado aqui para que a ausência não seja lida como esquecimento, e para alimentar o plano da Fase 2:

- Login por formulário, TOTP e bloqueio por tentativa. A cadeia administrativa usa `httpBasic` provisório, que serve só para o teste de isolamento distinguir "não existe" de "exige autenticação".
- `Dockerfile` e o serviço `app` no Compose, com `read_only`, `cap_drop` e usuário não-root. Entram na Fase 4, junto do endurecimento.
- Cabeçalhos de segurança, CSP e limite de requisições — Fase 4.
- Upload, processamento de imagem e a decisão sobre a nativa de WebP — Fase 2.
- Qualquer template Thymeleaf, CSS ou identidade visual — Fase 3.
