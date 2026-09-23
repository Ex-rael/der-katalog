#!/usr/bin/env bash
#
# Confere o que está exposto, e o que não está.
#
# Roda de dentro da máquina, e por isso só prova metade: daqui, o painel
# DEVE responder. A outra metade — a que importa — só pode ser feita de
# fora, com o Tailscale desligado, e este script termina dizendo isso.
#
# Rode depois de publicar, e depois de qualquer mudança na configuração do
# Tailscale. O §2 do plano de segurança pede exatamente isso.

set -Euo pipefail

RAIZ="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$RAIZ"

FALHAS=0

ok()    { printf '  \033[32m✓\033[0m %s\n' "$1"; }
falha() { printf '  \033[31m✗\033[0m %s\n' "$1"; FALHAS=$((FALHAS + 1)); }
nota()  { printf '    %s\n' "$1"; }

echo
echo "── O que o Tailscale está servindo ──────────────────────────"
echo

CONFIG="$(tailscale serve status 2>/dev/null || echo '')"

if [ -z "$CONFIG" ] || echo "$CONFIG" | grep -qi "no serve config"; then
  echo "  Nada publicado ainda."
  echo
  echo "  Para publicar:  ./scripts/publicar.sh"
  exit 0
fi

echo "$CONFIG" | sed 's/^/  /'
echo
echo "── Conferências ─────────────────────────────────────────────"
echo

# 1. O Funnel precisa apontar para o catálogo, jamais para o painel.
if echo "$CONFIG" | grep -qi "funnel"; then
  if echo "$CONFIG" | grep -i "funnel" -A3 | grep -q "127.0.0.1:8081"; then
    falha "O FUNNEL ESTÁ APONTANDO PARA O PAINEL (8081)."
    nota "Desligue agora:  tailscale funnel reset"
  else
    ok "O Funnel não aponta para a porta do painel"
  fi

  if echo "$CONFIG" | grep -q "127.0.0.1:8080"; then
    ok "O catálogo (8080) está publicado"
  else
    falha "O Funnel está ligado mas não aponta para o catálogo (8080)"
  fi
else
  echo "  Funnel desligado — o catálogo não está na internet."
fi

# 2. O painel pode estar em serve, nunca em funnel.
if echo "$CONFIG" | grep -q "127.0.0.1:8081"; then
  if echo "$CONFIG" | grep -B3 "127.0.0.1:8081" | grep -qi "funnel"; then
    falha "O painel está em FUNNEL, e não em serve."
    nota "Isto expõe a tela de login à internet. Desligue agora."
  else
    ok "O painel está em serve (tailnet), não em funnel"
  fi
fi

echo

# 3. A aplicação responde, e com o perfil certo.
if curl -sf --max-time 5 http://127.0.0.1:8080/saude >/dev/null; then
  ok "O catálogo responde em 127.0.0.1:8080"
else
  falha "O catálogo não responde em 127.0.0.1:8080"
fi

PERFIL="$(docker compose exec -T app sh -c 'echo $SPRING_PROFILES_ACTIVE' 2>/dev/null | tr -d '[:space:]')"
if [ "$PERFIL" = "prod" ]; then
  ok "Perfil ativo: prod"
else
  falha "Perfil ativo é '${PERFIL:-desconhecido}', e não prod"
  nota "Em dev não há segundo fator obrigatório nem cookie Secure."
fi

# 4. As portas continuam confinadas ao loopback.
if ss -ltn 2>/dev/null | grep -qE '(127\.0\.0\.1|\[::ffff:127\.0\.0\.1\]):8080'; then
  ok "A porta 8080 está ligada apenas ao loopback"
else
  falha "A porta 8080 não está confinada ao loopback"
  nota "$(ss -ltn 2>/dev/null | grep ':8080' || echo 'não está escutando')"
fi

if ss -ltn 2>/dev/null | grep -qE '(127\.0\.0\.1|\[::ffff:127\.0\.0\.1\]):8081'; then
  ok "A porta 8081 está ligada apenas ao loopback"
else
  falha "A porta 8081 não está confinada ao loopback"
fi

if ss -ltn 2>/dev/null | grep -qE '(127\.0\.0\.1|\[::ffff:127\.0\.0\.1\]):5432'; then
  ok "O PostgreSQL está ligado apenas ao loopback"
else
  falha "O PostgreSQL não está confinado ao loopback"
fi

# 5. Os cabeçalhos chegam, e o painel é negado pela porta pública.
CABECALHOS="$(curl -s -D - -o /dev/null --max-time 5 \
  -H 'X-Forwarded-Proto: https' http://127.0.0.1:8080/ 2>/dev/null || echo '')"

for esperado in "content-security-policy" "x-frame-options" "x-content-type-options" \
                "referrer-policy" "strict-transport-security"; do
  if echo "$CABECALHOS" | grep -qi "^$esperado:"; then
    ok "Cabeçalho presente: $esperado"
  else
    falha "Cabeçalho ausente: $esperado"
  fi
done

for rota in /admin /admin/login /admin/produtos /actuator/env /actuator/health; do
  STATUS="$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 "http://127.0.0.1:8080$rota" || echo 000)"
  case "$STATUS" in
    403|404) ok "Negado pela porta pública: $rota ($STATUS)" ;;
    *)       falha "ALCANÇÁVEL pela porta pública: $rota ($STATUS)" ;;
  esac
done

echo
echo "── O que este script NÃO consegue verificar ─────────────────"
echo

NOME="$(tailscale status --json 2>/dev/null | python3 -c \
  'import json,sys; print(json.load(sys.stdin)["Self"]["DNSName"].rstrip("."))' 2>/dev/null || echo '<seu-nome>.ts.net')"

cat <<PENDENTE
  De dentro da máquina, o painel DEVE responder — então nada aqui prova que
  ele está protegido de fora. A verificação que vale precisa de outro lugar
  e de outra rede:

  Com o celular FORA de casa, no 4G, e o Tailscale DESLIGADO:

    https://$NOME/          deve abrir o catálogo
    https://$NOME/admin     deve dar 403 ou 404, NUNCA a tela de login
    https://$NOME/actuator/health   deve dar 403 ou 404

  Se a tela de login aparecer, desligue tudo imediatamente:

      tailscale funnel reset

  O §5.3 do plano pede que este teste seja repetido a cada trimestre, e
  depois de qualquer mudança na configuração do Tailscale.

PENDENTE

echo "─────────────────────────────────────────────────────────────"
if [ "$FALHAS" -eq 0 ]; then
  echo "  Nenhuma falha nas conferências automáticas."
  exit 0
fi
echo "  $FALHAS falha(s). Resolva antes de deixar publicado."
exit 1
