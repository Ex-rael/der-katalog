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
