# Chima Club — Definição de Projeto

Catálogo online de cuias e acessórios para chimarrão, com painel administrativo próprio e venda finalizada via WhatsApp.

- **Versão:** 1.0
- **Data:** setembro de 2026
- **Hospedagem:** máquina local (segunda máquina na residência), exposta pela internet via Tailscale Funnel

---

## 1. Objetivo e escopo

### 1.1 Problema

O catálogo hoje é um PDF e uma tentativa de incorporar HTML no Wix, que não funcionou. Não há como atualizar preço, foto ou disponibilidade sem refazer o arquivo inteiro.

### 1.2 Objetivo

Uma aplicação web própria em que a dona da loja cadastra produtos, fotos, descrição, preço e unidades disponíveis por um painel, e o cliente navega por um catálogo público que leva a conversa para o WhatsApp.

### 1.3 Está no escopo

- Painel administrativo com login, para cadastrar, editar, despublicar e excluir produtos.
- Upload de várias fotos por produto, com definição da foto principal e da ordem das demais.
- Home pública com listagem de todos os produtos, mostrando nome e preço, no estilo do catálogo atual.
- Busca por nome do produto.
- Página de produto com foto em destaque, carrossel das demais fotos, preço, descrição, unidades disponíveis e botão que abre o WhatsApp.
- Identidade visual Chima Club (verde-escuro, creme, sálvia, terracota; logo e selo já recortados).

### 1.4 Está fora do escopo (versão 1)

- Carrinho, checkout, pagamento e cálculo de frete. A compra é fechada no WhatsApp.
- Cadastro e login de clientes.
- Controle fiscal, emissão de nota, integração com ERP.
- Aplicativo móvel. O site é responsivo, e isso basta.
- Múltiplos administradores com papéis diferentes. Na versão 1 existe um único papel, `ADMIN`.

### 1.5 Premissas

- Volume baixo: dezenas de produtos, centenas de visitas por dia. Isso permite uma arquitetura simples, monolítica, sem cache distribuído nem fila.
- A máquina fica ligada em casa, em rede doméstica, e o acesso externo é intermediado pelo Tailscale Funnel.
- O estoque é controlado manualmente. O sistema mostra unidades disponíveis, mas não reserva nem baixa automaticamente.

---

## 2. Arquitetura

### 2.1 Visão geral

```
                    Internet
                       │
                       ▼
        Tailscale Funnel (TLS, nome público)
                       │
        ┌──────────────┴──────────────┐
        ▼                             ▼
  porta 8080 (público)          porta 8081 (admin)
  exposta pelo Funnel           exposta só na tailnet
        │                             │
        └──────────────┬──────────────┘
                       ▼
        Spring Boot (um único processo, dois conectores)
             ├── catálogo público (Thymeleaf + HTML)
             ├── painel admin (Thymeleaf + Spring Security)
             └── camada de serviços + JPA
                       │
        ┌──────────────┴──────────────┐
        ▼                             ▼
   PostgreSQL 16                 Disco local
   (Docker, só localhost)        /var/chimaclub/fotos
```

A decisão central de arquitetura: **o painel administrativo não é publicado pelo Funnel**. Ele responde em outra porta, acessível apenas por dispositivos da tailnet. Quem chega pela internet não alcança a tela de login. Isso está detalhado no plano de segurança.

Dentro do contêiner, os dois conectores escutam em `0.0.0.0`. Quem restringe o alcance é a publicação do Docker (`127.0.0.1:8080:8080` e `127.0.0.1:8081:8081`), o firewall do sistema e o Tailscale — não o endereço de bind da JVM, que dentro do contêiner se refere ao loopback do próprio contêiner e tornaria a porta inalcançável de fora dele. No perfil `dev`, que roda direto no host, o bind é `127.0.0.1`. A cadeia do Spring Security por porta permanece como segunda barreira, independente de qualquer uma dessas camadas.

### 2.2 Stack

| Camada | Escolha | Motivo |
|---|---|---|
| Linguagem | Java 21 (LTS) | Exigência do projeto; LTS com suporte longo |
| Framework | Spring Boot 3.5.x | Padrão de mercado, Spring Security integrado; linha *minor* com suporte corrente, como exige o §A06 do plano de segurança |
| Web | Spring MVC + Thymeleaf | Renderização no servidor; dispensa build de frontend e uma segunda aplicação |
| Interatividade | HTMX + JavaScript mínimo | Busca e carrossel sem framework de SPA |
| Persistência | Spring Data JPA (Hibernate 6) | Mapeamento direto do modelo |
| Migrações | Flyway | Versionamento do banco em SQL, revisável |
| Banco | PostgreSQL 16 | Exigência do projeto |
| Segurança | Spring Security 6 | Sessão, CSRF, cabeçalhos, BCrypt |
| Imagens | Thumbnailator + webp-imageio | Reprocessamento e miniaturas. O `ImageIO` do Java lê WebP mas não grava; o escritor vem de biblioteca nativa |
| Identificadores | uuid-creator | UUID v7 gerado na aplicação; o PostgreSQL oferece apenas v4 |
| Build | Maven | Ecossistema Spring |
| Testes | JUnit 5, Testcontainers, MockMvc | Testes de integração com Postgres real |
| Execução | Docker Compose | App + banco isolados, reinício automático |
| Observabilidade | Spring Actuator + logs em arquivo | Suficiente na escala prevista |

### 2.3 Por que Thymeleaf e não React

Um SPA exigiria segundo processo de build, autenticação por token, CORS e uma camada extra de deploy. Para um catálogo de dezenas de produtos, renderizar no servidor deixa o HTML pronto para buscadores, reaproveita o CSS da identidade que já existe, e reduz a superfície de ataque a uma aplicação só. HTMX cobre a busca com filtro em tempo real e o carrossel, que são as únicas partes interativas.

### 2.4 Organização de pacotes

```
br.com.chimaclub
├── ChimaClubApplication.java
├── config/          SecurityConfig, WebConfig, PortsConfig, FlywayConfig
├── catalogo/        Produto, Categoria, ProdutoFoto (entidades + repositórios)
│   ├── web/         CatalogoController (público), AdminProdutoController
│   ├── service/     ProdutoService, BuscaService
│   └── dto/         ProdutoResumo, ProdutoDetalhe, ProdutoForm
├── midia/           ArmazenamentoFotos, ProcessadorImagem, ValidadorUpload
├── admin/           UsuarioAdmin, UsuarioRepository, AuditoriaService
├── config_loja/     Configuracao (WhatsApp, Instagram, textos)
└── comum/           exceções, Slugify, ManipuladorDeErros
```

---

## 3. Modelo de dados (PostgreSQL)

### 3.1 Diagrama

```
  categoria 1 ──── N produto 1 ──── N produto_foto
                       │
                       └── N clique_whatsapp (métrica)

  usuario_admin 1 ──── N evento_auditoria
  configuracao (chave/valor, sem relacionamento)
```

### 3.2 Decisões

- **Preço em centavos (`BIGINT`), nunca em ponto flutuante.** `preco_centavos = 8990` representa R$ 89,90. Evita erro de arredondamento e mantém aritmética exata.
- **Chaves primárias UUID v7** (ordenadas no tempo). Não revelam quantidade de produtos cadastrados, como um `id` sequencial revelaria, e mantêm boa localidade em índice.
- **Exclusão lógica.** Produto tem `excluido_em`; nada some do banco, o que permite desfazer erros e manter o histórico de auditoria coerente.
- **Busca com `pg_trgm` + `unaccent`.** "cuia gold", "Cuía Gold" e "gold" chegam ao mesmo produto.
- **`versao` para bloqueio otimista.** Impede que duas abas do painel sobrescrevam uma à outra em silêncio.

### 3.3 DDL

```sql
-- V1__esquema_inicial.sql
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE EXTENSION IF NOT EXISTS unaccent;

-- ---------------------------------------------------------------
-- unaccent(text) é declarada STABLE, porque depende do dicionário
-- em uso, e o PostgreSQL recusa expressão não imutável em índice.
-- A forma de dois argumentos fixa o dicionário, o que torna o
-- resultado determinístico e permite marcar a função IMMUTABLE.
-- O índice e a consulta de busca precisam usar esta mesma função:
-- qualquer outra forma faz o índice ser ignorado em silêncio.
-- ---------------------------------------------------------------
CREATE OR REPLACE FUNCTION imutavel_unaccent(texto text)
RETURNS text
LANGUAGE sql
IMMUTABLE
PARALLEL SAFE
STRICT
AS $$ SELECT unaccent('unaccent', texto) $$;

-- ---------------------------------------------------------------
-- Categorias: "Cuias em madeira", "Cuias em porongo", "Bombas"...
-- ---------------------------------------------------------------
CREATE TABLE categoria (
    id           UUID        PRIMARY KEY,
    nome         VARCHAR(80) NOT NULL,
    slug         VARCHAR(80) NOT NULL UNIQUE,
    ordem        INTEGER     NOT NULL DEFAULT 0,
    criado_em    TIMESTAMPTZ NOT NULL DEFAULT now(),
    atualizado_em TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT categoria_nome_nao_vazio CHECK (length(btrim(nome)) > 0)
);

-- ---------------------------------------------------------------
-- Produtos
-- ---------------------------------------------------------------
CREATE TABLE produto (
    id              UUID         PRIMARY KEY,
    categoria_id    UUID         REFERENCES categoria(id) ON DELETE SET NULL,
    nome            VARCHAR(140) NOT NULL,
    slug            VARCHAR(160) NOT NULL UNIQUE,
    descricao       TEXT,
    preco_centavos  BIGINT       NOT NULL,
    unidades        INTEGER      NOT NULL DEFAULT 0,
    publicado       BOOLEAN      NOT NULL DEFAULT false,
    destaque        BOOLEAN      NOT NULL DEFAULT false,
    ordem           INTEGER      NOT NULL DEFAULT 0,
    versao          BIGINT       NOT NULL DEFAULT 0,
    criado_em       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    atualizado_em   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    excluido_em     TIMESTAMPTZ,
    CONSTRAINT produto_preco_positivo   CHECK (preco_centavos >= 0),
    CONSTRAINT produto_unidades_positiva CHECK (unidades >= 0),
    CONSTRAINT produto_nome_nao_vazio   CHECK (length(btrim(nome)) > 0),
    CONSTRAINT produto_descricao_limite CHECK (descricao IS NULL OR length(descricao) <= 4000)
);

CREATE INDEX idx_produto_listagem
    ON produto (publicado, ordem, criado_em DESC)
    WHERE excluido_em IS NULL;

CREATE INDEX idx_produto_categoria
    ON produto (categoria_id)
    WHERE excluido_em IS NULL;

-- Busca por nome, tolerante a acento e a erro de digitação
CREATE INDEX idx_produto_busca_nome
    ON produto USING gin (imutavel_unaccent(lower(nome)) gin_trgm_ops);

-- ---------------------------------------------------------------
-- Fotos: 1 principal + N no carrossel
-- ---------------------------------------------------------------
CREATE TABLE produto_foto (
    id            UUID         PRIMARY KEY,
    produto_id    UUID         NOT NULL REFERENCES produto(id) ON DELETE CASCADE,
    arquivo       VARCHAR(255) NOT NULL,   -- nome gerado, nunca o nome enviado
    arquivo_mini  VARCHAR(255) NOT NULL,   -- miniatura para a grade da home
    texto_alt     VARCHAR(180),
    largura       INTEGER      NOT NULL,
    altura        INTEGER      NOT NULL,
    bytes         BIGINT       NOT NULL,
    tipo_mime     VARCHAR(40)  NOT NULL,
    principal     BOOLEAN      NOT NULL DEFAULT false,
    ordem         INTEGER      NOT NULL DEFAULT 0,
    criado_em     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT foto_bytes_limite CHECK (bytes > 0 AND bytes <= 10485760)
);

-- Uma única foto principal por produto
CREATE UNIQUE INDEX idx_foto_principal_unica
    ON produto_foto (produto_id)
    WHERE principal;

CREATE INDEX idx_foto_produto ON produto_foto (produto_id, ordem);

-- ---------------------------------------------------------------
-- Administradores
-- ---------------------------------------------------------------
CREATE TABLE usuario_admin (
    id               UUID         PRIMARY KEY,
    email            VARCHAR(160) NOT NULL UNIQUE,
    nome             VARCHAR(120) NOT NULL,
    senha_hash       VARCHAR(100) NOT NULL,   -- BCrypt custo 12
    ativo            BOOLEAN      NOT NULL DEFAULT true,
    totp_segredo     VARCHAR(120),            -- 2FA, cifrado na aplicação
    totp_ativo       BOOLEAN      NOT NULL DEFAULT false,
    falhas_login     INTEGER      NOT NULL DEFAULT 0,
    bloqueado_ate    TIMESTAMPTZ,
    senha_alterada_em TIMESTAMPTZ NOT NULL DEFAULT now(),
    ultimo_login_em  TIMESTAMPTZ,
    criado_em        TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------
-- Auditoria: quem mudou o quê e quando
-- ---------------------------------------------------------------
CREATE TABLE evento_auditoria (
    id          BIGSERIAL    PRIMARY KEY,
    usuario_id  UUID         REFERENCES usuario_admin(id) ON DELETE SET NULL,
    acao        VARCHAR(60)  NOT NULL,   -- LOGIN_OK, LOGIN_FALHA, PRODUTO_CRIADO...
    entidade    VARCHAR(60),
    entidade_id UUID,
    detalhes    JSONB,
    ip          INET,                    -- mapeado no Hibernate por conversor com cast explícito
    user_agent  VARCHAR(300),
    criado_em   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_auditoria_data ON evento_auditoria (criado_em DESC);
CREATE INDEX idx_auditoria_acao ON evento_auditoria (acao, criado_em DESC);

-- ---------------------------------------------------------------
-- Configuração da loja (WhatsApp, Instagram, textos)
-- ---------------------------------------------------------------
CREATE TABLE configuracao (
    chave         VARCHAR(60) PRIMARY KEY,
    valor         TEXT        NOT NULL,
    atualizado_em TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------
-- Métrica simples: cliques no botão de compra
-- ---------------------------------------------------------------
CREATE TABLE clique_whatsapp (
    id         BIGSERIAL   PRIMARY KEY,
    produto_id UUID        REFERENCES produto(id) ON DELETE SET NULL,
    criado_em  TIMESTAMPTZ NOT NULL DEFAULT now(),
    referer    VARCHAR(300)
);

CREATE INDEX idx_clique_produto ON clique_whatsapp (produto_id, criado_em DESC);
```

```sql
-- V2__dados_iniciais.sql
INSERT INTO configuracao (chave, valor) VALUES
  ('whatsapp_numero',   '5551989250481'),
  ('whatsapp_exibido',  '(51) 98925-0481'),
  ('whatsapp_mensagem', 'Olá! Gostaria de saber a disponibilidade da {produto}.'),
  ('instagram',         'chimaclub'),
  ('loja_nome',         'Chima Club Artefatos'),
  ('loja_lema',         'Seu tempo de qualidade merece artefatos à altura');

-- UUID v7 fixos. A migração precisa ser determinística, e gen_random_uuid()
-- produziria um v4 diferente em cada base, contrariando a decisão da §3.2.
INSERT INTO categoria (id, nome, slug, ordem) VALUES
  ('01920000-0000-7000-8000-000000000001', 'Cuias em madeira', 'cuias-em-madeira', 1),
  ('01920000-0000-7000-8000-000000000002', 'Cuias em porongo', 'cuias-em-porongo', 2);
```

### 3.4 Consulta de busca

```sql
-- Busca por nome: prefixo, trecho ou aproximação
SELECT p.*, f.arquivo_mini
  FROM produto p
  LEFT JOIN produto_foto f ON f.produto_id = p.id AND f.principal
 WHERE p.excluido_em IS NULL
   AND p.publicado
   AND (
         imutavel_unaccent(lower(p.nome)) LIKE '%' || imutavel_unaccent(lower(:termo)) || '%'
      OR similarity(imutavel_unaccent(lower(p.nome)), imutavel_unaccent(lower(:termo))) > 0.25
   )
 ORDER BY similarity(imutavel_unaccent(lower(p.nome)), imutavel_unaccent(lower(:termo))) DESC,
          p.ordem, p.criado_em DESC
 LIMIT 60;
```

O parâmetro `:termo` é sempre vinculado pelo JPA/JDBC, nunca concatenado na string da consulta.

A consulta usa `imutavel_unaccent`, a mesma função do índice `idx_produto_busca_nome`. Escrever `unaccent(...)` aqui devolveria o mesmo resultado, mas com varredura sequencial. Um teste de integração confere o plano de execução para que essa divergência não passe despercebida.

### 3.5 Armazenamento das fotos

Os arquivos ficam no disco, em `/var/chimaclub/fotos`, e o banco guarda apenas o nome gerado. Guardar binário em `BYTEA` engordaria o backup e as consultas sem nenhum ganho nessa escala.

Cada upload gera três arquivos, todos reescritos em WebP pela aplicação:

| Versão | Largura | Uso |
|---|---|---|
| `mini` | 600 px | grade da home |
| `media` | 1200 px | carrossel |
| `grande` | 2000 px | foto em destaque, zoom |

O nome do arquivo é um UUID novo mais a versão, por exemplo `a3f1...-media.webp`. O nome enviado pelo usuário é descartado.

O `ImageIO` do Java não traz escritor de WebP: a gravação depende da biblioteca nativa `webp-imageio`, e o carregamento dessa nativa dentro da imagem do contêiner é verificado por teste já na Fase 1. Se ela não carregar no ambiente de execução, o recuo é JPEG progressivo nos mesmos três tamanhos. A garantia de segurança vem de reescrever a imagem e descartar o original com todos os metadados, não do formato de saída.

---

## 4. Funcionalidades

### 4.1 Home pública (`GET /`)

- Abertura com o logo Chima Club sobre o verde-escuro, o lema e a divisória com o sol da marca.
- Campo de busca por nome, no topo da listagem.
- Grade de produtos publicados: 3 colunas no computador, 2 no celular. Cada cartão mostra a foto principal em moldura fina, o nome e o preço.
- Produtos sem unidades aparecem com a marca "Esgotado" e continuam clicáveis.
- Agrupamento por categoria, com a moldura em verde separando cada bloco, como no catálogo atual.
- A busca filtra a grade sem recarregar a página (HTMX troca só o bloco da listagem) e também funciona com JavaScript desligado, pela URL `/?q=gold`.

### 4.2 Página do produto (`GET /produto/{slug}`)

- Foto principal em destaque.
- Carrossel com as demais fotos, navegável por toque, teclado e clique.
- Nome, preço, descrição e unidades disponíveis ("3 unidades disponíveis", "Última unidade", "Esgotado").
- Botão "Comprar pelo WhatsApp", que registra o clique e redireciona para `https://wa.me/<numero>?text=<mensagem>` com o nome do produto e o link da página na mensagem.
- Bloco com outros produtos da mesma categoria.

### 4.3 Painel administrativo (`/admin`, só pela tailnet)

- Login com e-mail e senha, e segundo fator TOTP.
- Lista de produtos com filtro por nome, situação e categoria.
- Formulário de produto: nome, categoria, descrição, preço, unidades, publicado ou rascunho, ordem de exibição.
- Fotos: envio de várias de uma vez, reordenação por arrastar, escolha da principal, exclusão.
- Ações rápidas na lista: publicar, despublicar, duplicar, excluir (lógico).
- Tela de configuração: número de WhatsApp, texto padrão da mensagem, Instagram.
- Tela de auditoria: últimos acessos e alterações.

---

## 5. API e rotas

### 5.1 Público (porta 8080, exposta pelo Funnel)

| Método | Rota | Descrição |
|---|---|---|
| GET | `/` | Home com listagem; aceita `?q=` e `?categoria=` |
| GET | `/busca` | Fragmento HTML da grade filtrada (HTMX) |
| GET | `/produto/{slug}` | Página do produto |
| GET | `/fotos/{arquivo}` | Imagem, servida com cache longo |
| POST | `/produto/{slug}/whatsapp` | Registra o clique e responde com o redirecionamento |
| GET | `/sitemap.xml`, `/robots.txt` | Indexação |
| GET | `/saude` | Verificação simples, sem detalhes internos |

### 5.2 Administrativo (porta 8081, só na tailnet)

| Método | Rota | Descrição |
|---|---|---|
| GET/POST | `/admin/login`, `/admin/logout` | Autenticação |
| GET | `/admin/produtos` | Lista com filtros |
| GET/POST | `/admin/produtos/novo` | Cadastro |
| GET/POST | `/admin/produtos/{id}` | Edição (com `versao` para bloqueio otimista) |
| POST | `/admin/produtos/{id}/fotos` | Upload |
| PATCH | `/admin/produtos/{id}/fotos/ordem` | Reordenação |
| DELETE | `/admin/produtos/{id}/fotos/{fotoId}` | Exclusão de foto |
| POST | `/admin/produtos/{id}/publicar` | Publicar ou despublicar |
| DELETE | `/admin/produtos/{id}` | Exclusão lógica |
| GET/POST | `/admin/configuracao` | Dados da loja |
| GET | `/admin/auditoria` | Registro de eventos |
| GET | `/admin/actuator/**` | Métricas e saúde detalhada |

Todas as rotas administrativas que alteram dados exigem token CSRF e sessão válida.

---

## 6. Regras de negócio

1. **Preço.** Informado em reais com vírgula no formulário e convertido para centavos na entrada. A exibição usa `pt-BR`: `R$ 89,90`.
2. **Slug.** Gerado a partir do nome, sem acento, em minúsculas, com hífen. Em caso de colisão, acrescenta sufixo numérico. Uma vez publicado, o slug não muda sozinho, para não quebrar links já compartilhados; a troca é ação explícita do painel.
3. **Publicação.** Um produto só aparece no catálogo se `publicado = true`, tiver ao menos uma foto e preço maior que zero.
4. **Unidades.** Zero unidades não esconde o produto; mostra "Esgotado" e mantém o botão de WhatsApp com mensagem de encomenda.
5. **Foto principal.** A primeira foto enviada vira principal automaticamente. Ao excluir a principal, a próxima na ordem assume.
6. **Exclusão.** Sempre lógica. As fotos do produto excluído permanecem em disco por 30 dias e depois são removidas por rotina de limpeza.
7. **Edição concorrente.** Se a `versao` enviada no formulário for diferente da do banco, a gravação é recusada com aviso de que o produto foi alterado em outro lugar.

---

## 7. Interface

A identidade já definida no brand board é a base:

| Elemento | Valor |
|---|---|
| Verde-escuro | `#0D3316` — aberturas, rodapé, molduras |
| Creme | `#E9E0D0` — fundo do catálogo |
| Sálvia | `#6D9571` — apoios e estados |
| Terracota | `#84431D` — preços |
| Névoa | `#A7B3A2` — textos secundários sobre verde |
| Cursiva | Pinyon Script (substituta da Symphony) |
| Serifada | Marcellus (substituta da Awesome Lathusca) |
| Sem serifa | Jost (substituta da Nourd) |

O CSS do catálogo HTML já produzido vira `static/css/chimaclub.css`, e os templates Thymeleaf reaproveitam a estrutura de abertura, moldura de categoria e cartão de produto. Se as fontes originais forem licenciadas, entram em `static/fonts` com `@font-face` e as substitutas saem.

Requisitos não negociáveis de interface: responsivo a partir de 360 px, foco visível no teclado, texto alternativo em toda imagem, contraste mínimo AA e funcionamento básico sem JavaScript.

---

## 8. Ambiente e execução

### 8.0 Ferramentas de desenvolvimento

A máquina de desenvolvimento fixa as versões com `mise`, num `.mise.toml` versionado junto do código:

```toml
[tools]
java  = "temurin-21"
maven = "3.9"
```

O Maven Wrapper (`./mvnw`) também é versionado, para que um clone sem `mise` construa o projeto do mesmo jeito. A versão do Java é a mesma da imagem de produção, e não a que estiver instalada no sistema.

Os testes de integração usam Testcontainers e falam com o Docker do host, subindo um PostgreSQL 16 real. Não há banco em memória em lugar nenhum: `pg_trgm`, `unaccent`, índice parcial e `INET` não existem no H2, e testar contra um substituto esconderia justamente os erros que importam.

### 8.1 Docker Compose

```yaml
services:
  banco:
    # Fixar por digest antes de publicar:
    #   docker buildx imagetools inspect postgres:16-alpine
    # e trocar para postgres:16-alpine@sha256:<digest>, conforme §A06.
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
      test: ["CMD-SHELL", "pg_isready -U chimaclub"]
      interval: 10s

  app:
    build: .
    restart: unless-stopped
    depends_on:
      banco: { condition: service_healthy }
    environment:
      SPRING_PROFILES_ACTIVE: prod
      SPRING_DATASOURCE_URL: jdbc:postgresql://banco:5432/chimaclub
      SPRING_DATASOURCE_USERNAME: chimaclub
      # A senha nunca aparece em variável de ambiente, onde vazaria por
      # "docker inspect" e pelo /proc. O "config tree" do Spring Boot lê
      # /run/secrets/spring.datasource.password como se fosse a propriedade
      # de mesmo nome, e o valor só existe no arquivo montado pelo secret.
      SPRING_CONFIG_IMPORT: "optional:configtree:/run/secrets/"
    secrets:
      - source: senha_banco
        target: spring.datasource.password
    volumes:
      - fotos:/var/chimaclub/fotos
    ports:
      - "127.0.0.1:8080:8080"   # público, entregue pelo Funnel
      - "127.0.0.1:8081:8081"   # admin, entregue só na tailnet
    user: "10001:10001"               # nunca root, §A05
    read_only: true
    cap_drop: [ALL]
    security_opt: ["no-new-privileges:true"]
    tmpfs: [/tmp]

volumes:
  dados_pg:
  fotos:
secrets:
  senha_banco:
    file: ./secrets/senha_banco.txt
```

O `read_only: true` vale para o sistema de arquivos da imagem; volumes montados seguem graváveis, e é por isso que `/var/chimaclub/fotos` continua funcionando. O `/tmp` em `tmpfs` existe porque o Tomcat precisa gravar os arquivos temporários de upload.

### 8.2 Tailscale

```bash
# Catálogo público na internet
tailscale funnel --bg --https=443 http://127.0.0.1:8080

# Painel apenas para dispositivos da tailnet
tailscale serve --bg --https=8443 http://127.0.0.1:8081
```

### 8.3 Perfis

- `dev`: Postgres em contêiner local, dados de exemplo, logs em nível `DEBUG`, TOTP desligado.
- `prod`: segredos por variável de ambiente, `DEBUG` desligado, páginas de erro genéricas, HSTS ligado.

### 8.4 Rotina de operação

| Tarefa | Frequência | Como |
|---|---|---|
| Backup do banco | diário, 3h | `pg_dump -Fc` em `./backups`, mantendo 30 dias |
| Backup das fotos | diário | `restic` ou `rsync` para disco externo |
| Teste de restauração | mensal | restaurar em banco temporário e conferir contagem de produtos |
| Atualização de dependências | mensal | `mvn versions:display-dependency-updates`, revisão e implantação |
| Revisão de auditoria | mensal | conferir tentativas de login e alterações |

---

## 9. Testes

| Tipo | Cobertura esperada |
|---|---|
| Unitário | Conversão de preço, geração de slug, regras de publicação, validação de upload |
| Integração (Testcontainers) | Repositórios, busca com acento, bloqueio otimista, migrações do Flyway |
| Web (MockMvc) | Rotas públicas sem sessão, bloqueio das rotas admin sem sessão, rejeição sem CSRF |
| Segurança | Upload de arquivo disfarçado, XSS em descrição, limite de tamanho, limite de tentativas de login |
| Manual | Fluxo completo em celular real: buscar, abrir produto, clicar no WhatsApp |

A lista 5.1 do plano de segurança não é conferência manual. Cada item verificável dentro da aplicação vira teste automatizado que roda a cada construção: `/admin` negado na porta pública, upload de `.php` renomeado e de `.svg` com script rejeitados, imagem de dimensão absurda recusada antes de alocar memória, `<script>alert(1)</script>` escapado na home e na página do produto, limite de requisições disparando, erro 500 sem rastro de pilha. Só permanece manual o que existe fora do processo: o teste a partir de rede externa, o disco cifrado, o roteador e o `gitleaks`.

Critério de aceite de cada entrega: testes verdes, migração aplicada sem erro em base limpa e em base com dados, e revisão da lista do plano de segurança correspondente à fase.

---

## 10. Fases

| Fase | Entrega | Estimativa |
|---|---|---|
| 1 | Esqueleto: `mise` e Maven Wrapper, Spring Boot, Docker Compose, Flyway, entidades, migração inicial, dois conectores, suíte de testes com Testcontainers | 1 semana |
| 2 | Painel: login, CRUD de produtos, upload e processamento de fotos, auditoria | 2 semanas |
| 3 | Catálogo público: home, busca, página de produto, botão de WhatsApp, identidade visual | 2 semanas |
| 4 | Endurecimento: cabeçalhos, limites de requisição, 2FA, backups, testes de segurança | 1 semana |
| 5 | Publicação: Tailscale Funnel, carga dos 17 produtos atuais, testes em celular | 3 dias |

### Definição de pronto

- [ ] Os 17 produtos do catálogo antigo cadastrados com fotos e preço.
- [ ] Busca encontra "gold", "Gold" e "cuia gold".
- [ ] Botão de WhatsApp abre a conversa com a mensagem correta no celular.
- [ ] Painel inacessível pela internet, comprovado por teste a partir de rede externa.
- [ ] Backup restaurado com sucesso em teste.
- [ ] Lista do plano de segurança revisada por inteiro.

---

## 11. Riscos

| Risco | Impacto | Como reduzir |
|---|---|---|
| Queda de energia ou de internet em casa | Site fora do ar | Aceito na versão 1; se incomodar, migrar para VPS pequena depois |
| Disco único sem redundância | Perda de fotos | Backup diário fora da máquina |
| Exposição do painel por engano | Comprometimento total | Painel em porta separada, nunca em `funnel`; conferência após cada mudança de configuração |
| Upload malicioso | Execução de código | Reescrita da imagem, diretório sem execução, validação por conteúdo |
| Sobrecarga por robôs | Indisponibilidade | Limite de requisições por IP e cache de imagens |
| Dependência do WhatsApp | Perda de venda | Registrar cliques para medir; e-mail alternativo no rodapé |
