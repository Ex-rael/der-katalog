#!/usr/bin/env bash
#
# Carga inicial do catálogo: os 17 produtos que a loja já tinha.
#
# O script conversa com o painel pela porta administrativa, como a dona da
# loja faria. Não escreve no banco direto, de propósito: assim a carga passa
# pela validação de upload, pelo reprocessamento da imagem e pela regra de
# publicação — os mesmos caminhos que serão usados todo dia. Uma carga que
# contorna tudo isso deixa o catálogo num estado que o sistema não sabe
# produzir sozinho.
#
# Idempotente: consulta antes de criar, e não duplica o que já existe.
#
# Uso:  ./scripts/carregar-catalogo-inicial.sh dona@exemplo.com

set -Eeuo pipefail

RAIZ="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$RAIZ"

PAINEL="${PAINEL:-http://127.0.0.1:8081}"
DADOS="$RAIZ/dados-iniciais"
BISCOITOS="$(mktemp)"
CORPO="$(mktemp)"
CABECALHOS="$(mktemp)"
trap 'rm -f "$BISCOITOS" "$CORPO" "$CABECALHOS"' EXIT

EMAIL="${1:-}"
if [ -z "$EMAIL" ]; then
  echo "uso: $0 <e-mail da administradora>" >&2
  exit 1
fi

[ -f "$DADOS/catalogo.json" ] || {
  echo "ERRO: $DADOS/catalogo.json não encontrado." >&2
  exit 1
}

printf 'Senha de %s: ' "$EMAIL" >&2
read -rs SENHA
echo >&2

# Em produção o painel exige segundo fator, e é assim que deve ser. O script
# pede um código em vez de contornar a exigência: ative o segundo fator pelo
# navegador antes de rodar esta carga.
printf 'Código do segundo fator (vazio se ainda não ativou): ' >&2
read -r CODIGO_TOTP
echo >&2

# ---------------------------------------------------------------
# Conversa com o painel
# ---------------------------------------------------------------

# O painel limita requisições por IP (§A04), e uma carga em lote é
# exatamente o tipo de cliente que esbarra nesse limite — como aconteceu na
# primeira versão deste script, que interpretou o 429 como "categoria não
# encontrada". Um script de carga precisa se comportar: respeitar o
# Retry-After e tentar de novo, em vez de insistir ou falhar em silêncio.
esperar_se_preciso() {
  local status="$1" tentativa="$2"
  case "$status" in
    429*)
      [ "$tentativa" -le 3 ] || return 1
      local espera
      espera="$(grep -i '^retry-after:' "$CABECALHOS" | tr -dc '0-9')"
      printf '  (limite de requisições atingido; aguardando %ss)\n' "${espera:-60}" >&2
      sleep "${espera:-60}"
      return 0 ;;
    *) return 1 ;;
  esac
}

pegar() {
  local caminho="$1" tentativa=1 status
  while :; do
    status="$(curl -s -b "$BISCOITOS" -c "$BISCOITOS" -o "$CORPO" -D "$CABECALHOS" \
                -w '%{http_code}' "$PAINEL$caminho")"
    esperar_se_preciso "$status" "$tentativa" || { echo "$status"; return 0; }
    tentativa=$((tentativa + 1))
  done
}

enviar() {
  local caminho="$1"; shift
  local tentativa=1 status
  while :; do
    status="$(curl -s -b "$BISCOITOS" -c "$BISCOITOS" -o "$CORPO" -D "$CABECALHOS" \
                -w '%{http_code} %{redirect_url}' "$@" "$PAINEL$caminho")"
    esperar_se_preciso "$status" "$tentativa" || { echo "$status"; return 0; }
    tentativa=$((tentativa + 1))
  done
}

token_do_corpo() {
  grep -oP 'name="_csrf" value="\K[^"]+' "$CORPO" | head -1
}

nome_da_categoria() {
  case "$1" in
    cuias-em-madeira) echo "Cuias em madeira" ;;
    cuias-em-porongo) echo "Cuias em porongo" ;;
    *) echo "$1" ;;
  esac
}

categoria_do_corpo() {
  tr -d '\n' < "$CORPO" \
    | grep -oP 'value="\K[0-9a-f-]{36}(?="[^>]*>\s*'"$(nome_da_categoria "$1")"')' \
    | head -1 || true
}

# Procurar o nome no corpo da página não funciona: o próprio termo de busca é
# renderizado de volta no campo do formulário, então a conferência sempre
# diria que sim — foi o que a primeira versão deste script fez. O sinal
# confiável é a linha da tabela, que só existe quando a busca casou.
ja_existe() {
  curl -s -b "$BISCOITOS" -c "$BISCOITOS" --get --data-urlencode "q=$1" \
    "$PAINEL/admin/produtos" \
    | grep -qP '/admin/produtos/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}'
}

entrar() {
  pegar /admin/login >/dev/null
  local token; token="$(token_do_corpo)"
  [ -n "$token" ] || { echo "ERRO: o painel não respondeu em $PAINEL." >&2; exit 1; }

  local resultado
  resultado="$(enviar /admin/login \
    --data-urlencode "_csrf=$token" \
    --data-urlencode "username=$EMAIL" \
    --data-urlencode "password=$SENHA")"

  case "${resultado#* }" in
    *"/admin/login?erro"*) echo "ERRO: e-mail ou senha inválidos." >&2; exit 1 ;;
    "") echo "ERRO: o login não respondeu como esperado." >&2; exit 1 ;;
  esac

  # A senha abre a sessão; o segundo fator a completa. Enquanto não for
  # conferido, nenhuma rota do painel responde — que é exatamente o ponto.
  local apos
  apos="$(curl -s -b "$BISCOITOS" -c "$BISCOITOS" -o /dev/null -w '%{redirect_url}' \
    "$PAINEL/admin/produtos")"

  case "$apos" in
    *"/admin/totp/ativar"*)
      echo "ERRO: esta conta ainda não tem segundo fator ativo." >&2
      echo "      Abra $PAINEL/admin/totp/ativar no navegador, cadastre no seu" >&2
      echo "      autenticador, e rode este script de novo." >&2
      exit 1 ;;
    *"/admin/totp/conferir"*)
      [ -n "$CODIGO_TOTP" ] || {
        echo "ERRO: esta conta exige segundo fator; rode de novo informando o código." >&2
        exit 1
      }
      pegar /admin/totp/conferir >/dev/null
      local conferencia
      conferencia="$(enviar /admin/totp/conferir \
        --data-urlencode "_csrf=$(token_do_corpo)" \
        --data-urlencode "codigo=$CODIGO_TOTP")"
      case "${conferencia#* }" in
        *"?erro"*) echo "ERRO: código do segundo fator inválido." >&2; exit 1 ;;
      esac ;;
  esac
}

# ---------------------------------------------------------------
# Carga
# ---------------------------------------------------------------

entrar
echo "autenticado em $PAINEL"

CRIADOS=0
PULADOS=0
FALHAS=0

while IFS=$'\t' read -r NOME PRECO CATEGORIA FOTO UNIDADES; do
  if ja_existe "$NOME"; then
    printf '  já existe: %s\n' "$NOME"
    PULADOS=$((PULADOS + 1))
    continue
  fi

  # Uma única busca pela tela de cadastro rende as duas coisas de que
  # precisamos: o token e o identificador da categoria. Pedir duas vezes
  # dobrava o consumo do limite sem nenhum ganho.
  pegar /admin/produtos/novo >/dev/null
  TOKEN="$(token_do_corpo)"
  CATEGORIA_ID="$(categoria_do_corpo "$CATEGORIA")"

  if [ -z "$CATEGORIA_ID" ] || [ -z "$TOKEN" ]; then
    echo "  ERRO: o formulário de cadastro não respondeu como esperado para $NOME." >&2
    FALHAS=$((FALHAS + 1))
    continue
  fi

  RESULTADO="$(enviar /admin/produtos/novo \
    --data-urlencode "_csrf=$TOKEN" \
    --data-urlencode "nome=$NOME" \
    --data-urlencode "precoEmReais=$PRECO" \
    --data-urlencode "unidades=$UNIDADES" \
    --data-urlencode "categoriaId=$CATEGORIA_ID" \
    --data-urlencode "versao=0" \
    --data-urlencode "descricao=Peça selecionada do catálogo Chima Club.")"

  DESTINO="${RESULTADO#* }"
  ID="${DESTINO##*/}"
  if [ -z "$ID" ] || [ "$ID" = "$DESTINO" ]; then
    echo "  ERRO ao cadastrar: $NOME" >&2
    FALHAS=$((FALHAS + 1))
    continue
  fi

  pegar "/admin/produtos/$ID" >/dev/null
  enviar "/admin/produtos/$ID/fotos" \
    -F "_csrf=$(token_do_corpo)" \
    -F "arquivos=@$DADOS/fotos/$FOTO;type=image/jpeg" >/dev/null

  pegar "/admin/produtos/$ID" >/dev/null
  enviar "/admin/produtos/$ID/publicar" \
    --data-urlencode "_csrf=$(token_do_corpo)" \
    --data-urlencode "publicar=true" >/dev/null

  printf '  criado:    %s\n' "$NOME"
  CRIADOS=$((CRIADOS + 1))

done < <(python3 -c "
import json
dados = json.load(open('$DADOS/catalogo.json'))
for p in dados['produtos']:
    print('\t'.join([p['nome'], p['preco'], p['categoria'], p['foto'], str(p['unidades'])]))
")

echo
echo "$CRIADOS criado(s), $PULADOS já existia(m), $FALHAS falha(s)."
[ "$FALHAS" -eq 0 ] || exit 1

echo
echo "Confira em $PAINEL/admin/produtos e no catálogo público antes de publicar."
