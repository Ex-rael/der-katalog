# Cloudflare Tunnel e chimaclub.com — brainstorming em andamento

**Estado: INCOMPLETO.** Este não é uma spec aprovada. É o registro de um
brainstorming arquitetural interrompido no meio, para ser retomado em outra
sessão.

- Seções 1 e 2 do desenho: **aprovadas** por quem administra o projeto.
- Seções 3 e 4: **escritas e aguardando aprovação.**
- Spec definitiva: **não escrita.** Só depois da aprovação das seções 3 e 4.
- Plano de implementação: **não escrito.** Só depois da spec aprovada.

Nada de código foi tocado. O repositório está como estava.

## O objetivo

Trocar o Tailscale Funnel pelo Cloudflare Tunnel, para que o catálogo
responda em `chimaclub.com` — domínio já comprado —, com a infraestrutura
Cloudflare descrita em Terraform em vez de comandos à mão.

Ganhos esperados: nome próprio em vez de `<host>.ts.net`; nenhuma porta de
entrada em lugar nenhum, porque o `cloudflared` abre a conexão de dentro para
fora; e cache de borda para as fotos WebP, que é exatamente onde a banda de
subida doméstica dói (§4.2 do plano de segurança).

## Decisões já tomadas

| Questão | Decisão |
|---|---|
| O painel administrativo (8081) | **Fica no Tailscale.** Só o catálogo público atravessa o túnel. A tela de login continua inalcançável pela internet por construção, não por configuração — a invariante que o README, os testes de mutação e o teste trimestral afirmam. |
| Estado do domínio | Comprado na **HostGator**. A zona ainda não existe na Cloudflare; os nameservers apontam para a HostGator. Trocá-los é passo manual, no painel da HostGator. |
| O que mais vive no domínio | **Nada.** Domínio novo e vazio: sem e-mail, sem site, sem registro a preservar. A zona começa limpa e não há risco de derrubar serviço existente. |
| Modelo de configuração do túnel | **Local** (`config_src = "local"`): credencial em arquivo sob `secrets/`, regras de ingresso em `cloudflared/config.yml` versionado. Recusada a alternativa de token por variável de ambiente, que é o vazamento por `docker inspect` e `/proc/<pid>/environ` que o `compose.yaml` já se dá o trabalho de evitar para a senha do banco. |
| A porta 8080 no host | **Continua publicada em `127.0.0.1:8080`.** Ver a correção registrada abaixo. |

### Correção registrada

Na primeira versão da seção 2 eu afirmei que tirar a 8080 do host "faz
desaparecer uma superfície inteira". Está errado, e a correção mudou a
decisão: a 8080 está hoje em `127.0.0.1:8080` e **nunca foi alcançável pela
internet**. Tirá-la removeria uma superfície *local* — qualquer usuário desta
máquina alcança o catálogo —, não uma externa.

O custo seria real: é por aquela porta que passam as conferências mais
valiosas do `verificar-exposicao.sh` (os cinco cabeçalhos de segurança e a
negação das cinco rotas administrativas, que são as invariantes provadas por
mutação). A postura deste projeto é que controle precisa ser *conferível*;
trocar um controle conferível por redução marginal de superfície local é o
negócio errado. O modelo de ameaça do plano é a internet, não usuários
co-residentes.

Decisão: manter a publicação, e **acrescentar** uma conferência — que o
catálogo responde em `app:8080` pela rede do Compose, que é o caminho real do
`cloudflared`.

## Seção 1 — A infraestrutura Cloudflare em Terraform (APROVADA)

Novo diretório `infra/`, com o provider `cloudflare/cloudflare` v5 fixado por
versão exata — mesma disciplina do `.mise.toml` e do digest da imagem do
Postgres.

| Recurso | Papel |
|---|---|
| `cloudflare_zone` | cria a zona `chimaclub.com` na conta |
| `cloudflare_zero_trust_tunnel_cloudflared` | o túnel, com `config_src = "local"` e `tunnel_secret` sorteado |
| `local_file` | escreve `secrets/tunel_credenciais.json` com permissão `0640` |
| `cloudflare_dns_record` (raiz e `www`) | `CNAME` proxied para `<id>.cfargotunnel.com` |
| `cloudflare_ruleset` (dynamic redirect) | `www.chimaclub.com` -> `https://chimaclub.com`, 301 |
| `cloudflare_ruleset` (cache settings) | `/fotos/*` e os estáticos com TTL de borda longo |
| `cloudflare_zone_setting` | `always_use_https=on`, `min_tls_version=1.2`, `ssl=strict` |
| `cloudflare_zone_setting` | `email_obfuscation=off`, `rocket_loader=off`, `mirage=off` |

A última linha não é enfeite. A ofuscação de e-mail e o Rocket Loader
**injetam JavaScript inline na resposta**, e a CSP deste projeto não tem
`unsafe-inline` de propósito. Ligados, quebrariam o catálogo em silêncio — o
mesmo tipo de falha que já custou tempo no `form-action`. Declarar que estão
desligados põe isso no código, e não na memória de quem configurou o painel.

O cache de `/fotos/*` é o ganho prático mais direto: a foto WebP sai desta
máquina **uma vez** e a Cloudflare a entrega ao resto do mundo. A banda de
subida deixa de ser proporcional ao número de visitantes.

**O estado do Terraform guarda o `tunnel_secret` em claro.** Ele passa a ser
segredo de primeira classe: `infra/*.tfstate*` no `.gitignore`, permissão
`600`, e incluído no `scripts/backup.sh` — perder o estado é perder o
controle do túnel. O token da API nunca entra em arquivo versionado nem em
variável dentro do repositório: fica em `secrets/cloudflare_api_token.txt`, e
o comando de aplicar é precedido de
`export CLOUDFLARE_API_TOKEN=$(cat secrets/cloudflare_api_token.txt)`.

## Seção 2 — O `cloudflared` no Compose (APROVADA)

Serviço novo no `compose.yaml`, com a mesma contenção dos outros:
`read_only`, `cap_drop: [ALL]`, `no-new-privileges`, usuário não-root, sem
volume de escrita.

```yaml
cloudflared:
  image: cloudflare/cloudflared:<digest fixado>
  command: ["tunnel", "--config", "/etc/cloudflared/config.yml", "run", "${ID_DO_TUNEL}"]
  depends_on: { app: { condition: service_healthy } }
```

O `config.yml` é **versionado** e estático:

```yaml
credentials-file: /run/secrets/tunel_credenciais.json
no-autoupdate: true
ingress:
  - hostname: chimaclub.com
    service: http://app:8080
  - service: http_status:404     # qualquer outro nome: 404
```

O catch-all é o controle: nome que não esteja listado ali não alcança nada,
mesmo que alguém crie um registro DNS apontando para o túnel.

O `ID_DO_TUNEL` vai no `.env` e **não é segredo** — ele é público no CNAME.
Ele vem por argumento de linha de comando porque o `cloudflared` não
interpola variável de ambiente dentro do `config.yml`, e é assim que o
arquivo de ingresso continua estático e versionado.

O `cloudflared` fala com `app:8080` pela rede interna do Compose. A 8080
continua publicada em `127.0.0.1` para diagnóstico e conferência (ver a
correção registrada acima); a 8081 continua publicada no loopback, porque é
por ali que o `tailscale serve` entrega o painel.

O interruptor de emergência muda de `tailscale funnel reset` para
`docker compose stop cloudflared`, e some do ar em segundos do mesmo jeito.

## Seção 3 — O que muda dentro da aplicação (AGUARDANDO APROVAÇÃO)

Menos do que parece. O `forward-headers-strategy: NATIVE`, o
`use-relative-redirects`, a CSP e o `internal-proxies` **continuam valendo
como estão** — o `cloudflared` também termina o TLS fora, também manda
`X-Forwarded-Proto: https`, e também é um contêiner numa faixa privada. Três
mudanças reais:

**1. `IpDeOrigem` passa a preferir `CF-Connecting-IP`.** A borda da
Cloudflare *sobrescreve* esse cabeçalho com o IP real do visitante — o
cliente não consegue forjá-lo, porque o que ele mandar é substituído. É um
valor único, sem lista para percorrer. O XFF fica como recuo, que é o que
atende o caminho da tailnet e o desenvolvimento. A condição de confiança não
muda: só vale quando a conexão vem de um intermediário conhecido.

Testes novos: cabeçalho forjado em conexão direta é ignorado; honrado quando
vem do intermediário; e o XFF da direita para a esquerda continua funcionando
na ausência do cabeçalho da Cloudflare.

**2. A base canônica do `sitemap.xml` e do `robots.txt` deixa de vir do
cabeçalho.** Hoje `IndexacaoController.enderecoBase()` monta a partir do
`requestURL`, que depende de `Host` / `X-Forwarded-Host`. Atrás da Cloudflare
o `Host` chega certo, então *funcionaria* — mas um `Host` forjado põe o
domínio de outra pessoa dentro do `sitemap.xml`, que os buscadores leem.
Passa a `app.endereco-publico: https://chimaclub.com` no perfil `prod`, com
recuo para a requisição quando ausente (desenvolvimento). Fecha uma classe de
injeção e põe o nome canônico por escrito na configuração.

**3. Os comentários que nomeiam o Tailscale.** `application-prod.yaml`,
`IpDeOrigem`, `PortasConfig`, `CatalogoController` e o bloco do
`use-relative-redirects` explicam *por que* cada decisão foi tomada citando o
Funnel e o `tailscaled`. O raciocínio segue válido; o sujeito não. Reescrever
é parte do trabalho, não acabamento: comentário que mente sobre o motivo é
pior que comentário ausente.

Uma consequência a registrar: com `/fotos/*` em cache na borda, essas
requisições **param de chegar na aplicação**. A faixa de limite por IP que
cobre imagens vai ver uma fração do tráfego de hoje. Não é defeito, é efeito
— e precisa estar escrito para ninguém concluir que o limite parou de
funcionar.

## Seção 4 — Scripts, documentos e a ordem da virada (AGUARDANDO APROVAÇÃO)

`scripts/publicar.sh` reescrito com as recusas equivalentes às de hoje:
perfil `prod`; aplicação saudável; credencial do túnel presente com `640`;
`terraform plan` sem diferença pendente; DNS já resolvendo pela Cloudflare. A
mesma confirmação por escrito antes de qualquer coisa ir ao ar.

`scripts/verificar-exposicao.sh` troca `tailscale serve status` por
`cloudflared tunnel info` e pelo conteúdo do `config.yml`, mantendo intactas
as conferências de cabeçalho, de loopback e de negação das cinco rotas.

`scripts/backup.sh` passa a levar `infra/terraform.tfstate` e
`secrets/tunel_credenciais.json`.

No `.gitignore` entram `infra/.terraform/` e `infra/*.tfstate*`. O
`.terraform.lock.hcl` **é versionado**, porque fixa os hashes do provider
exatamente como o digest fixa a imagem do Postgres.

`docs/operacao.md`: o trimestral passa a incluir girar a credencial do túnel
e revisar o log de auditoria da conta Cloudflare; o mensal inclui
`terraform plan` para detectar mudança feita à mão no painel. A §8.2 do
`chimaclub-plano-seguranca.md` e o README são reescritos.

### A ordem da virada, e ela importa

1. `terraform apply` — cria zona, túnel e credencial. **Nada no ar ainda:**
   os nameservers da HostGator continuam mandando no domínio.
2. Trocar os NS no painel da HostGator pelos dois que a Cloudflare atribuir.
   Propagação: minutos a horas.
3. Zona vira `Active`; `dig NS chimaclub.com` confirma.
4. `docker compose up -d cloudflared` — catálogo no ar em
   `https://chimaclub.com`.
5. `verificar-exposicao.sh`, e o teste que nenhum script faz: celular no 4G,
   Tailscale desligado, `https://chimaclub.com/admin` respondendo 403 ou 404.
6. **Só então** `tailscale funnel reset`. O Funnel sai por último de
   propósito — enquanto o caminho novo não estiver provado, existe caminho de
   volta.

## O que falta de quem administra o projeto

Nenhuma dessas quatro coisas foi fornecida ainda. A primeira sessão de
implementação começa por elas.

**1. Account ID da Cloudflare** — não é segredo, pode vir no chat. Painel ->
Workers & Pages -> Overview, na barra lateral direita.

**2. API Token** — Meu Perfil -> API Tokens -> Create Token -> Custom token:

| Escopo | Permissão |
|---|---|
| Account -> Cloudflare Tunnel | Edit |
| Account -> Account Settings | Read |
| Zone -> Zone | Edit |
| Zone -> DNS | Edit |
| Zone -> Zone Settings | Edit |
| Zone -> Cache Rules | Edit |
| Zone -> Config Rules | Edit |

Em *Zone Resources*, **All zones from account** — a zona ainda não existe,
então não há como restringir a ela. (Se algum `apply` levar 403, é aqui que se
olha primeiro: a lista acima pode precisar de um escopo a mais.)

**O token não deve ser colado no chat.** Ele ficaria no histórico da conversa
e em qualquer log dela. Gravar à mão, com a disciplina dos outros segredos:

```bash
install -m 600 /dev/null secrets/cloudflare_api_token.txt
$EDITOR secrets/cloudflare_api_token.txt    # colar, salvar
```

**3. Confirmar que a conta Cloudflare existe** (ou criá-la).

**4. Confirmar acesso ao painel da HostGator** para trocar os nameservers.
Sem esse acesso o passo 2 da virada trava tudo o que vem depois.

## Fatos do código já levantados

Para a próxima sessão não redescobrir:

- `scripts/publicar.sh` e `scripts/verificar-exposicao.sh` são os dois únicos
  scripts que falam com o Tailscale. `scripts/carregar-catalogo-inicial.sh`
  usa a 8081 (painel) e não é afetado.
- `127.0.0.1:8080` do host é usada por `publicar.sh:32`,
  `verificar-exposicao.sh:72`, `:87`, `:108` e `:120`.
- `./mvnw verify` (Testcontainers, porta aleatória) e
  `./mvnw spring-boot:run` em desenvolvimento (JVM liga direto no host, sem
  contêiner) **não** dependem da porta publicada.
- As fotos são servidas em `/fotos/{arquivo}`
  (`FotoController.java:40`) — é o alvo da regra de cache.
- A imagem da aplicação (`eclipse-temurin:21-jre-noble`) tem **`wget`** e não
  tem `curl`. É o que o healthcheck do `compose.yaml` já usa.
- `terraform` 1.15.9 está instalado na máquina. `cloudflared` não está, e não
  precisa: roda como contêiner.
- Arquivos que citam Tailscale/Funnel em comentário ou texto, e por isso
  precisam de revisão: `compose.yaml`, `README.md`,
  `chimaclub-definicao-projeto.md`, `chimaclub-plano-seguranca.md`,
  `docs/operacao.md`, `docs/verificacao-do-conteiner.md`,
  `src/main/resources/application-prod.yaml`,
  `src/main/java/br/com/chimaclub/config/IpDeOrigem.java`,
  `.../config/PortasConfig.java`, `.../catalogo/web/CatalogoController.java`,
  `.../catalogo/web/IndexacaoController.java`, e os testes
  `IsolamentoDoPainelTest`, `RedirecionamentoDoPainelTest`,
  `LimiteDeRequisicoesTest`.
- O sandbox desta sessão não tem rede: `dig` para `1.1.1.1` falha por
  timeout. Conferências de DNS precisam ser rodadas por quem administra a
  máquina.

## Onde retomar

1. Ler as seções 3 e 4 acima e aprovar, corrigir ou recusar.
2. Com a aprovação, escrever a spec em
   `docs/superpowers/specs/<data>-cloudflare-tunnel-design.md` e submetê-la à
   revisão.
3. Com a spec aprovada, invocar a skill `superpowers:writing-plans` para o
   plano de implementação. Nenhuma outra skill entra antes dessa.
