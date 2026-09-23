#!/usr/bin/env bash
#
# Publica o catálogo na internet, pelo Tailscale (§8.2).
#
# ATENÇÃO: o segundo comando torna esta máquina alcançável pela internet
# inteira, em segundos. Do ponto de vista de risco, publicar pelo Funnel
# equivale a publicar na internet aberta, com o agravante de o processo rodar
# dentro da rede doméstica. Leia a lista 5.1 do plano de segurança antes.
#
# A ordem importa: o painel primeiro, na tailnet, para que dê para conferir
# que ele responde ali antes de abrir qualquer coisa para fora.

set -Eeuo pipefail

RAIZ="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$RAIZ"

# ---------------------------------------------------------------
# Recusas — antes de expor qualquer coisa
# ---------------------------------------------------------------

command -v tailscale >/dev/null || {
  echo "ERRO: tailscale não encontrado." >&2
  exit 1
}

tailscale status >/dev/null 2>&1 || {
  echo "ERRO: o Tailscale não está conectado. Rode 'tailscale up' antes." >&2
  exit 1
}

if ! curl -sf --max-time 5 http://127.0.0.1:8080/saude >/dev/null; then
  echo "ERRO: o catálogo não responde em 127.0.0.1:8080." >&2
  echo "      Suba a aplicação com 'docker compose up -d' antes de publicar." >&2
  exit 1
fi

if ! curl -s --max-time 5 -o /dev/null http://127.0.0.1:8081/admin/login; then
  echo "ERRO: o painel não responde em 127.0.0.1:8081." >&2
  exit 1
fi

# Publicar com o perfil de desenvolvimento seria publicar sem segundo fator
# obrigatório, sem HSTS e com o cookie de sessão sem Secure.
PERFIL="$(docker compose exec -T app sh -c 'echo $SPRING_PROFILES_ACTIVE' 2>/dev/null | tr -d '[:space:]')"
if [ "$PERFIL" != "prod" ]; then
  echo "ERRO: o perfil ativo é '${PERFIL:-desconhecido}', e não 'prod'." >&2
  echo "      Em dev não há segundo fator obrigatório nem cookie Secure." >&2
  exit 1
fi

NOME="$(tailscale status --json | python3 -c \
  'import json,sys; print(json.load(sys.stdin)["Self"]["DNSName"].rstrip("."))')"

cat <<AVISO

  Isto vai publicar o catálogo em:

      https://$NOME/

  Alcançável pela internet inteira, incluindo quem varre a rede o dia todo.
  O painel NÃO será publicado: ele fica em https://$NOME:8443/, acessível
  apenas por dispositivos da sua tailnet.

  Antes de continuar, confirme que você já:

    - percorreu a lista 5.1 do plano de segurança;
    - ativou o segundo fator na conta da administradora;
    - trocou a senha do banco pela definitiva;
    - levou uma cópia do backup para fora desta máquina;
    - conferiu que não há redirecionamento de porta no roteador.

AVISO

printf 'Digite PUBLICAR para continuar: '
read -r CONFIRMACAO
[ "$CONFIRMACAO" = "PUBLICAR" ] || { echo "cancelado"; exit 1; }

# ---------------------------------------------------------------
# Publicação
# ---------------------------------------------------------------

echo
echo "1/2 painel na tailnet (porta 8443, NÃO publicado na internet)"
tailscale serve --bg --https=8443 http://127.0.0.1:8081

echo "2/2 catálogo na internet (porta 443, pelo Funnel)"
tailscale funnel --bg --https=443 http://127.0.0.1:8080

echo
tailscale serve status
echo
echo "Publicado. Agora rode a verificação:"
echo
echo "    ./scripts/verificar-exposicao.sh"
echo
echo "E faça o teste que nenhum script faz por você: abrir"
echo "https://$NOME/admin no celular, FORA de casa, com o Tailscale DESLIGADO."
echo "A resposta correta é 403 ou 404 — nunca a tela de login."
echo
echo "Para tirar do ar a qualquer momento:  tailscale funnel reset"
