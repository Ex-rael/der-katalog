# Chima Club

Catálogo online de cuias e acessórios para chimarrão, com painel
administrativo próprio e venda finalizada via WhatsApp.

Documentos do projeto:

- [`chimaclub-definicao-projeto.md`](chimaclub-definicao-projeto.md) — escopo, arquitetura, modelo de dados
- [`chimaclub-plano-seguranca.md`](chimaclub-plano-seguranca.md) — controles de segurança e listas de verificação
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
administrativo em <http://127.0.0.1:8081/admin>. São portas separadas de
propósito: só a pública atravessa o Tailscale Funnel, e quem chega pela
internet não alcança nem a tela de login.

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
diretório `secrets/` inteiro está no `.gitignore` desde o primeiro commit.

## Estado atual — Fases 1 e 2 concluídas

### Fase 2 — painel administrativo

- Login por formulário, com bloqueio de 15 minutos após 5 falhas e mensagem
  de erro idêntica para todos os motivos
- Segundo fator TOTP, implementado sobre o RFC 6238 e provado contra os
  vetores do apêndice B do próprio RFC; segredo cifrado em AES-GCM
- Cadastro, edição, publicação e exclusão lógica de produtos, com slug
  estável e bloqueio otimista
- Upload de fotos tratado como hostil: extensão, assinatura dos bytes,
  limite de dimensão antes de descomprimir, reescrita em WebP e descarte do
  original com todos os metadados
- Configuração da loja com validação do número de WhatsApp
- Auditoria de tudo que altera estado, com o valor anterior

Para criar a primeira administradora:

```bash
./mvnw spring-boot:run \
  -Dspring-boot.run.arguments="--criar-admin=voce@exemplo.com --nome=Seu Nome"
```

A senha é sorteada e impressa uma única vez. Não há recuperação por e-mail,
e isso é deliberado.

### Fase 1 — esqueleto

Funciona, com teste automatizado cobrindo cada item:

- Aplicação subindo com dois conectores: 8080 público, 8081 administrativo,
  ambos ligados ao loopback
- PostgreSQL 16 em Compose, publicado apenas em `127.0.0.1:5432`
- Esquema completo criado por Flyway (V1) e dados iniciais semeados (V2),
  em base limpa e em base já povoada
- Entidades `Categoria`, `Produto` e `ProdutoFoto` com UUID v7 e bloqueio
  otimista; preço sempre em centavos
- Busca tolerante a acento e a caixa, com o índice `gin` comprovadamente
  aplicável à consulta escrita
- `/admin` e `/actuator` negados na porta pública — verificado por mutação,
  ou seja, o teste falha se a proteção for removida
- `clean` do Flyway desativado, sem cabeçalho `Server`, `TRACE` recusado

### Ainda não existe

- Catálogo público, busca na tela e identidade visual (Fase 3)
- Cabeçalhos de segurança, CSP, limite de requisições e `Dockerfile` (Fase 4)
- Rotina de limpeza das fotos de produto excluído após 30 dias (Fase 4)
- Tela de ativação do TOTP e exigência do segundo fator em produção (Fase 4)
- Publicação pelo Tailscale Funnel e carga dos 17 produtos (Fase 5)

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
