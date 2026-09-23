#!/usr/bin/env bash
#
# Teste mensal de restauração (§4.5).
#
# Restaura o backup mais recente numa base temporária, confere a contagem de
# produtos e descarta a base. Não toca na base de produção em momento algum.
#
# A §1.2 do plano diz que recuperação vale mais que perfeição, e um backup
# que nunca foi restaurado é uma suposição, não um backup.

set -Eeuo pipefail

RAIZ="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$RAIZ"

BASE_TEMPORARIA="chimaclub_teste_de_restauracao"
ULTIMO="$(ls -1t backups/chimaclub_*.dump 2>/dev/null | head -1 || true)"

if [ -z "$ULTIMO" ]; then
  echo "ERRO: nenhum backup em backups/. Rode ./scripts/backup.sh antes." >&2
  exit 1
fi

echo "restaurando $ULTIMO em $BASE_TEMPORARIA"

limpar() {
  docker compose exec -T banco psql -U chimaclub -d postgres \
    -c "DROP DATABASE IF EXISTS $BASE_TEMPORARIA" >/dev/null 2>&1 || true
}
trap limpar EXIT

limpar
docker compose exec -T banco psql -U chimaclub -d postgres -q \
  -c "CREATE DATABASE $BASE_TEMPORARIA"

# --exit-on-error é o ponto deste script.
#
# Sem ele, o pg_restore termina com "warning: errors ignored on restore" e
# código de saída zero. Foi assim que a primeira execução deste teste quase
# passou: o índice de busca não tinha sido criado, o banco restaurado
# funcionaria, e toda busca viraria varredura sequencial — sem nada avisando.
#
# Um teste de restauração que tolera erro não é um teste de restauração.
docker compose exec -T banco pg_restore -U chimaclub -d "$BASE_TEMPORARIA" \
  --no-owner --exit-on-error < "$ULTIMO"

PRODUTOS="$(docker compose exec -T banco psql -U chimaclub -d "$BASE_TEMPORARIA" -tAc \
  'SELECT count(*) FROM produto' | tr -d '[:space:]')"
FOTOS="$(docker compose exec -T banco psql -U chimaclub -d "$BASE_TEMPORARIA" -tAc \
  'SELECT count(*) FROM produto_foto' | tr -d '[:space:]')"
PRODUTOS_AGORA="$(docker compose exec -T banco psql -U chimaclub -d chimaclub -tAc \
  'SELECT count(*) FROM produto' | tr -d '[:space:]')"

echo "  produtos no backup:    $PRODUTOS"
echo "  produtos em produção:  $PRODUTOS_AGORA"
echo "  fotos no backup:       $FOTOS"

if [ "$PRODUTOS" -eq 0 ]; then
  echo "ERRO: o backup restaurou sem produto nenhum." >&2
  exit 1
fi

# O índice de busca precisa existir na base restaurada. Foi a sua ausência,
# silenciosa, que motivou este bloco.
INDICE="$(docker compose exec -T banco psql -U chimaclub -d "$BASE_TEMPORARIA" -tAc \
  "SELECT count(*) FROM pg_indexes WHERE indexname = 'idx_produto_busca_nome'" | tr -d '[:space:]')"
if [ "$INDICE" != "1" ]; then
  echo "ERRO: a base restaurada está sem o índice de busca." >&2
  exit 1
fi

# E a busca precisa funcionar de fato, não só o índice existir.
ACHOU="$(docker compose exec -T banco psql -U chimaclub -d "$BASE_TEMPORARIA" -tAc \
  "SELECT count(*) FROM produto WHERE imutavel_unaccent(lower(nome)) LIKE '%cuia%'" \
  | tr -d '[:space:]')"
echo "  busca na base restaurada: $ACHOU produto(s) para 'cuia'"

echo "OK: restauração conferida em $(date -Is)"
