# der katalog

Página de catálogo genérica, feita para rodar **selfhosted** numa máquina
sua, com o [Tailscale](https://tailscale.com) e a [Cloudflare](https://www.cloudflare.com)
cuidando de levar o tráfego até ela. Cadastre produtos num painel
administrativo próprio, publique uma vitrine pública e deixe a venda ser
fechada por conversa, via WhatsApp — sem carrinho, sem pagamento embutido.

Nada é pedido a serviço externo em tempo de execução: fontes, HTMX e imagens
são servidos localmente, e o banco de dados fica na mesma máquina.

> O projeto nasceu como o catálogo de uma loja de cuias para chimarrão, e os
> documentos de definição e de segurança ainda carregam esse nome.

## Como se encaixa

```
 visitante ──► Cloudflare ──► túnel ──► :8080  catálogo público ─┐
                                                                 ├─► PostgreSQL
 administradora ──► Tailscale (rede privada) ──► :8081  painel ──┘
```

- **Cloudflare** é a porta pública: DNS, TLS e o túnel que leva a vitrine até
  a máquina, sem abrir porta no roteador. A infraestrutura fica descrita em
  Terraform, em [`infra/`](infra/).
- **Tailscale** é a rede privada: o painel administrativo só é alcançável por
  quem está na tailnet. Quem chega pela internet não alcança nem a tela de
  login.
- São duas portas separadas de propósito — **8080** pública e **8081**
  administrativa, ambas ligadas ao loopback. Só a pública atravessa para a
  internet.

## O que tem

**Vitrine pública**

- Home com logo, lema, molduras de categoria e grade de produtos
- Busca por nome tolerante a acento e a caixa; com HTMX só a grade é trocada
  e, sem JavaScript, o formulário faz um GET comum e a página volta filtrada
- Página de produto com foto em destaque, carrossel, disponibilidade e botão
  de WhatsApp
- `sitemap.xml` gerado e `robots.txt` com `Crawl-delay`

**Painel administrativo**

- Login com bloqueio de 15 minutos após 5 falhas e mensagem de erro idêntica
  para todos os motivos
- Segundo fator TOTP (RFC 6238), com o segredo cifrado em AES-GCM
- Cadastro, edição, publicação e exclusão lógica de produtos, com slug
  estável e bloqueio otimista
- Upload de fotos tratado como hostil: extensão, assinatura dos bytes, limite
  de dimensão antes de descomprimir, reescrita em WebP e descarte do original
  com todos os metadados
- Configuração da loja (nome, número de WhatsApp validado)
- Auditoria de tudo que altera estado, com o valor anterior

**Endurecimento**

- Cabeçalhos de segurança e CSP em toda resposta, sem `unsafe-inline` e sem
  domínio externo
- Limite de requisições por IP em quatro faixas
- Actuator restrito a `health` e `info`, só na porta administrativa
- Contêiner sem privilégio: uid 10001, sistema de arquivos somente leitura,
  zero capacidades do kernel, `/tmp` com `noexec`
- Backup, restauração testada e limpeza das fotos de produto excluído
- Varredura de dependências e inventário CycloneDX num perfil próprio

## Documentos

- [`chimaclub-definicao-projeto.md`](chimaclub-definicao-projeto.md) — escopo, arquitetura, modelo de dados
- [`chimaclub-plano-seguranca.md`](chimaclub-plano-seguranca.md) — controles de segurança e listas de verificação
- [`docs/operacao.md`](docs/operacao.md) — comandos do dia a dia
- [`docs/verificacao-do-conteiner.md`](docs/verificacao-do-conteiner.md) — verificações à mão do contêiner
- [`docs/superpowers/plans/`](docs/superpowers/plans/) — planos de implementação, um por fase

## Desenvolvimento

As versões de Java e Maven são fixadas por [mise](https://mise.jdx.dev), para
que a máquina de desenvolvimento compile com o mesmo Java da imagem de
produção, e não com o que estiver instalado no sistema:

```bash
mise trust .
mise install
```

Subir o banco e rodar a aplicação:

```bash
docker compose up -d banco
./mvnw spring-boot:run
```

O catálogo público responde em <http://127.0.0.1:8080> e o painel
administrativo em <http://127.0.0.1:8081/admin>.

Para criar a primeira administradora:

```bash
./mvnw spring-boot:run \
  -Dspring-boot.run.arguments="--criar-admin=voce@exemplo.com --nome=Seu Nome"
```

A senha é sorteada e impressa uma única vez. Não há recuperação por e-mail,
e isso é deliberado.

## Testes

```bash
./mvnw verify
```

Os testes de integração sobem um PostgreSQL 16 real por Testcontainers, e
exigem o Docker em funcionamento. Não há banco em memória em lugar nenhum:
`pg_trgm`, `unaccent`, índice parcial e `INET` não existem no H2, e testar
contra um substituto esconderia justamente os erros que importam.

## Segredos

Nada de credencial versionada. A senha do banco fica em
`secrets/senha_banco.txt`, com permissão `600`, ignorada pelo Git. O
diretório `secrets/` inteiro está no `.gitignore` desde o primeiro commit. O
mesmo vale para o state e as variáveis do Terraform em `infra/`.

## Subir em produção

```bash
cp .env.example .env          # ajuste GRUPO_DOS_SEGREDOS com  id -g
head -c 32 /dev/urandom | base64 | tr -d '\n=/+' > secrets/senha_banco.txt
head -c 32 /dev/urandom | base64 > secrets/chave_totp.txt
chmod 640 secrets/*.txt

docker compose up -d --build

docker compose run --rm --no-deps app \
  java -jar /aplicacao/aplicacao.jar --criar-admin=voce@exemplo.com --nome="Seu Nome"
```

Antes de expor a vitrine à internet, percorra a lista 5.1 do plano de
segurança — em especial os itens que dependem de quem administra a máquina.

## Estado atual

As fases 1 a 5 estão implementadas e a vitrine está publicada pela
Cloudflare, com a infraestrutura descrita em [`infra/`](infra/).

- Os scripts `scripts/publicar.sh` e `scripts/verificar-exposicao.sh` foram
  escritos para o Tailscale Funnel e não descrevem o desenho atual.
- Ainda não existe alerta automático por e-mail ou Telegram (§A09) — depende
  de escolher um canal; a revisão mensal da auditoria cobre isso por enquanto.

## Notas para quem for mexer no código

O projeto usa Spring Boot 4, que difere da linha 3.x em pontos que custam
tempo a descobrir:

- a autoconfiguração do Flyway vive em `spring-boot-starter-flyway`; com
  `flyway-core` sozinho as migrações não rodam, e nada avisa
- os artefatos do Testcontainers foram renomeados na versão 2
  (`postgresql` virou `testcontainers-postgresql`)
- `TestRestTemplate` foi removido
- o registro de conector adicional chama-se agora `addAdditionalConnectors`

E uma armadilha que não é do Spring: um `application.yaml` em
`src/test/resources` encobre por inteiro o de `src/main/resources`, e a
suíte passa a exercitar uma configuração que não é a que vai ao ar. As
diferenças de teste vêm por `@DynamicPropertySource`, em
`BancoDeTesteBase`.
