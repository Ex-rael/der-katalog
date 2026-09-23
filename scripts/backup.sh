#!/usr/bin/env bash
#
# Backup diário do banco (§4.5).
#
# Formato -Fc (comprimido, do PostgreSQL) porque ele permite restaurar
# tabelas soltas e é bem menor que SQL puro. A retenção é de 30 dias.
#
# Uso:   ./scripts/backup.sh
# Cron:  0 3 * * *  cd /caminho/do/projeto && ./scripts/backup.sh >> backups/backup.log 2>&1

set -Eeuo pipefail

RAIZ="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$RAIZ"

DESTINO="$RAIZ/backups"
DIAS_DE_RETENCAO=30
AGORA="$(date +%Y-%m-%d_%H%M%S)"
ARQUIVO="chimaclub_${AGORA}.dump"

mkdir -p "$DESTINO"

echo "[$(date -Is)] backup iniciado"

# O pg_dump roda dentro do contêiner do banco: assim não é preciso ter o
# cliente do PostgreSQL instalado no host, nem passar a senha por fora.
docker compose exec -T banco sh -c \
  'PGPASSWORD="$(cat /run/secrets/senha_banco)" pg_dump -Fc -U chimaclub -d chimaclub' \
  > "$DESTINO/$ARQUIVO"

TAMANHO="$(stat -c %s "$DESTINO/$ARQUIVO")"

# Um dump de zero byte é o pior tipo de backup: existe, aparenta sucesso, e
# não restaura nada. Falhar aqui é melhor que descobrir no dia do incidente.
if [ "$TAMANHO" -lt 1024 ]; then
  echo "[$(date -Is)] ERRO: o dump saiu com $TAMANHO bytes — backup descartado" >&2
  rm -f "$DESTINO/$ARQUIVO"
  exit 1
fi

chmod 600 "$DESTINO/$ARQUIVO"
echo "[$(date -Is)] backup concluído: $ARQUIVO ($TAMANHO bytes)"

# As fotos vão à parte, num tar do volume.
ARQUIVO_FOTOS="fotos_${AGORA}.tar.gz"
docker compose exec -T app tar -czf - -C /var/chimaclub fotos > "$DESTINO/$ARQUIVO_FOTOS" 2>/dev/null || true
chmod 600 "$DESTINO/$ARQUIVO_FOTOS" 2>/dev/null || true
echo "[$(date -Is)] fotos: $ARQUIVO_FOTOS ($(stat -c %s "$DESTINO/$ARQUIVO_FOTOS") bytes)"

# Um backup de fotos vazio, com o banco cheio de fotos, é o mesmo tipo de
# armadilha do dump de zero byte: o arquivo existe, o script diz "concluído",
# e o dia de restaurar é o dia da descoberta. Comparar as duas contagens é
# barato e pega a divergência no dia em que ela aparece.
NO_BANCO="$(docker compose exec -T banco psql -U chimaclub -d chimaclub -tAc \
  'SELECT count(*) FROM produto_foto' 2>/dev/null | tr -d '[:space:]' || echo 0)"
NO_DISCO="$(docker compose exec -T app sh -c 'ls -1 /var/chimaclub/fotos 2>/dev/null | wc -l' \
  | tr -d '[:space:]' || echo 0)"

# Cada foto gera três arquivos: mini, media e grande.
ESPERADOS=$(( NO_BANCO * 3 ))
if [ "$NO_BANCO" -gt 0 ] && [ "$NO_DISCO" -lt "$ESPERADOS" ]; then
  echo "[$(date -Is)] AVISO: o banco referencia $NO_BANCO foto(s), o que dá $ESPERADOS arquivo(s)," >&2
  echo "[$(date -Is)]        e o volume tem $NO_DISCO. O backup das fotos está incompleto." >&2
fi

REMOVIDOS="$(find "$DESTINO" -maxdepth 1 -name 'chimaclub_*.dump' -o -name 'fotos_*.tar.gz' \
             | xargs -r -I{} sh -c 'find "{}" -mtime +'"$DIAS_DE_RETENCAO"' -print -delete' | wc -l)"
echo "[$(date -Is)] retenção: $REMOVIDOS arquivo(s) além de $DIAS_DE_RETENCAO dias removido(s)"

echo "[$(date -Is)] LEMBRETE: copie $DESTINO para fora desta máquina, cifrado."
