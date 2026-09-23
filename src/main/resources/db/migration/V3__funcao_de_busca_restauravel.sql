-- V3__funcao_de_busca_restauravel.sql
--
-- Conserta um defeito que só apareceu no primeiro teste de restauração, e
-- que é do tipo que o §4.5 do plano de segurança existe para pegar: o
-- backup estava íntegro, o pg_restore dizia "warning: errors ignored", e o
-- banco restaurado saía SEM o índice de busca.
--
-- A causa: imutavel_unaccent chamava unaccent() sem qualificar o esquema.
-- Durante a restauração, o pg_restore executa cada comando com um
-- search_path restrito — proteção dele contra funções plantadas num esquema
-- intermediário — e a chamada não resolvia. O CREATE INDEX falhava junto.
--
-- O efeito seria silencioso e caro: o catálogo funcionaria, e toda busca
-- viraria varredura sequencial, sem nada no log dizendo por quê.
--
-- A correção tem duas partes, e as duas importam:
--   qualificar a chamada com o esquema, para não depender do search_path;
--   fixar o search_path da própria função, para que ela não possa ser
--   sequestrada por um esquema que venha antes na busca.

CREATE OR REPLACE FUNCTION imutavel_unaccent(texto text)
RETURNS text
LANGUAGE sql
IMMUTABLE
PARALLEL SAFE
STRICT
SET search_path = pg_catalog, public, pg_temp
AS $$ SELECT public.unaccent('public.unaccent'::regdictionary, texto) $$;

-- O índice guarda valores calculados pela versão anterior da função. Ela
-- devolve o mesmo resultado, mas reconstruir é barato nesta escala e
-- elimina a dúvida.
REINDEX INDEX idx_produto_busca_nome;
