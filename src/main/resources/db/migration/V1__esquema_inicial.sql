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
