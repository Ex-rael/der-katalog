#!/usr/bin/env bash
#
# Restauração de verdade, para uso em incidente (§4.6).
#
# SOBRESCREVE a base de produção. Pede confirmação por escrito, porque é o
# tipo de comando que não deve rodar por engano de histórico do shell.

set -Eeuo pipefail

RAIZ="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$RAIZ"

ARQUIVO="${1:-}"
if [ -z "$ARQUIVO" ] || [ ! -f "$ARQUIVO" ]; then
  echo "uso: $0 backups/chimaclub_AAAA-MM-DD_HHMMSS.dump" >&2
  echo >&2
  echo "backups disponíveis:" >&2
  ls -1t backups/chimaclub_*.dump 2>/dev/null | head -10 >&2 || echo "  nenhum" >&2
  exit 1
fi

echo "Isto vai SOBRESCREVER a base de produção com $ARQUIVO."
echo "O catálogo atual será perdido, e não há como desfazer."
printf 'Digite RESTAURAR para continuar: '
read -r CONFIRMACAO
[ "$CONFIRMACAO" = "RESTAURAR" ] || { echo "cancelado"; exit 1; }

# Antes de sobrescrever, um backup do estado atual: se a restauração for a
# escolha errada, ainda há para onde voltar.
echo "guardando o estado atual antes de sobrescrever..."
./scripts/backup.sh

docker compose stop app
docker compose exec -T banco pg_restore -U chimaclub -d chimaclub --clean --if-exists --no-owner < "$ARQUIVO"
docker compose start app

echo "restaurado. Confira o catálogo e a auditoria antes de reabrir o Funnel."
