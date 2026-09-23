# Operação do Chima Club

O que fazer no dia a dia, no mês e no trimestre. As listas vêm da §5 do
plano de segurança; aqui estão os comandos.

---

## Todo dia (automático)

| Hora | O quê | Como |
|---|---|---|
| 03:00 | Backup do banco e das fotos | `scripts/backup.sh`, pelo cron |
| 04:00 | Limpeza das fotos de produto excluído há mais de 30 dias | rotina da própria aplicação |

Para agendar o backup:

```bash
crontab -e
```

```cron
0 3 * * * cd /caminho/do/chimaclub && ./scripts/backup.sh >> backups/backup.log 2>&1
```

**O que o cron não faz:** levar a cópia para fora da máquina. Um backup que
mora no mesmo disco do banco não protege contra o que mais provavelmente vai
acontecer — o disco falhar. Leve `backups/` para um disco externo cifrado, ou
configure o `restic` para isso.

---

## Comandos do dia a dia

### Subir e derrubar

```bash
docker compose up -d          # sobe banco e aplicação
docker compose ps             # estado
docker compose logs -f app    # acompanhar
docker compose down           # derruba (os dados ficam nos volumes)
```

### Criar a primeira administradora

```bash
docker compose run --rm --no-deps app \
  java -jar /aplicacao/aplicacao.jar --criar-admin=voce@exemplo.com --nome="Seu Nome"
```

A senha aparece uma única vez. Guarde no gerenciador antes de fechar o
terminal — ela não é gravada em lugar nenhum e não há recuperação por e-mail.

No primeiro acesso, o painel exige ativar o segundo fator. Guarde o segredo
junto da senha: se você perder o celular e não tiver o segredo, a única saída
é limpar `totp_ativo` pelo banco, na própria máquina.

### Atualizar a aplicação

```bash
git pull
./mvnw clean verify            # os testes precisam passar ANTES
docker compose up -d --build
```

---

## Todo mês (§5.2)

### 1. Atualizar o que roda por baixo

```bash
sudo pacman -Syu                      # ou apt upgrade, conforme a máquina
docker compose pull
./mvnw versions:display-dependency-updates
```

### 2. Varrer as dependências

```bash
./mvnw verify -Pseguranca
```

**Peça uma chave de API do NVD antes da primeira execução.** Sem ela, o
download da base de vulnerabilidades é fortemente limitado e a primeira
varredura leva de trinta minutos a algumas horas; com ela, poucos minutos. A
chave é gratuita e sai em minutos:

<https://nvd.nist.gov/developers/request-an-api-key>

**Não passe a chave pela linha de comando.** O próprio plugin avisa
(GHSA-qqhq-8r2c-c3f5) que `-DnvdApiKey=` a expõe no log de depuração do
Maven, e log acaba em terminal, em histórico de shell e em saída de
integração contínua.

A chave mora em `~/.m2/settings.xml`, com permissão `600`, e o `pom.xml` a
procura pelo id `nvd`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0">
  <servers>
    <server>
      <id>nvd</id>
      <username>chimaclub</username>
      <password>A-SUA-CHAVE-AQUI</password>
    </server>
  </servers>
</settings>
```

O `<username>` é ignorado pelo plugin; o que vale é o `<password>`.

Para cifrar a chave em vez de deixá-la em texto, dois comandos do Maven
resolvem — `mvn` com a opção `encrypt-master-password`, uma vez, e depois
com `encrypt-password`, que devolve o valor entre chaves para colar no lugar
da chave. (Escritos assim, e não como opções longas, porque comentário de
XML não aceita dois hifens seguidos — foi o que quebrou a primeira versão
deste arquivo de configuração.)

Depois é só:

```bash
./mvnw verify -Pseguranca
```

A base fica em `~/.m2/repository/org/owasp/dependency-check-data/` e é
atualizada de forma incremental nas execuções seguintes, então só a primeira
é lenta.

Falha se houver vulnerabilidade de severidade 7 ou mais. O relatório sai em
`target/seguranca/`, e o inventário de dependências em
`target/classes/META-INF/sbom/application.cdx.json` — é ele que responde, no
dia em que sair uma falha grave numa biblioteca, se ela está aqui dentro.

Para suprimir um falso positivo, edite `docs/supressoes-dependency-check.xml`
**com justificativa escrita e data**. Supressão sem motivo registrado vira
permissão permanente para ignorar um aviso.

### 3. Ler a auditoria

Entre no painel, vá em Auditoria, e procure por:

- rajadas de `LOGIN_FALHA`, em especial vindas do mesmo endereço;
- `CONTA_BLOQUEADA`, que você não tenha provocado;
- `CONFIGURACAO_ALTERADA` — sobretudo se tocar o número de WhatsApp, que é o
  caminho mais curto de um golpe contra seus clientes;
- `PRODUTO_EXCLUIDO` que você não reconheça.

### 4. Testar a restauração

```bash
./scripts/testar-restauracao.sh
```

Restaura o backup mais recente numa base temporária, confere a contagem de
produtos, a existência do índice de busca e se a busca responde. Não toca na
base de produção.

**Este é o item mais importante da lista.** A §1.2 do plano diz que
recuperação vale mais que perfeição, e um backup que nunca foi restaurado é
uma suposição. A primeira vez que rodamos este teste, ele encontrou um
defeito que deixava a base restaurada sem o índice de busca — e o backup
parecia perfeito.

### 5. Conferir o espaço

```bash
df -h .
docker system df
docker compose exec app sh -c 'du -sh /var/chimaclub/fotos'
```

---

## Todo trimestre (§5.3)

### 1. Repetir o teste de acesso externo ao painel

Com um celular **fora de casa** e **com o Tailscale desligado**, abrir:

```
https://<seu-nome>.ts.net/admin
```

A resposta correta é 403 ou 404. Se aparecer a tela de login, **desligue o
Funnel imediatamente** e refaça a configuração:

```bash
tailscale funnel reset
```

### 2. Trocar a senha do banco e girar os segredos

```bash
head -c 32 /dev/urandom | base64 | tr -d '\n=/+' > secrets/senha_banco.txt
chmod 640 secrets/senha_banco.txt
docker compose down && docker compose up -d
```

Girar a chave do TOTP (`secrets/chave_totp.txt`) invalida os segundos
fatores já cadastrados: todo mundo precisa cadastrar de novo. Faça com o
autenticador à mão.

### 3. Revisar quem tem acesso à tailnet

```bash
tailscale status
```

Remova dispositivos que não existem mais.

### 4. Reler o plano de segurança

E ajustar o que mudou no sistema. Um plano que descreve um sistema que não
existe mais é pior que nenhum, porque dá confiança sem base.

---

## Em caso de incidente (§4.6)

A ordem importa.

### 1. Conter — primeiro, sempre

```bash
tailscale funnel reset
```

Tira o site do ar em segundos. Faça isto **antes** de investigar: investigar
com o atacante ainda dentro é dar tempo a ele.

### 2. Preservar

```bash
docker compose logs app > /tmp/incidente-app.log
./scripts/backup.sh
cp -a backups/ /caminho/seguro/fora/da/maquina/
```

Copie antes de mexer em qualquer coisa. Reiniciar o contêiner apaga o que
estava em memória, e derrubar o Compose pode levar registros junto.

### 3. Avaliar

Na auditoria, procure o que foi acessado e quando. Compare com o backup do
dia anterior: o que mudou no catálogo, na configuração, nos usuários.

### 4. Erradicar

Trocar a senha do administrador, a senha do banco e a chave do TOTP.
Atualizar todas as dependências. Se houver qualquer suspeita de execução de
código, reinstalar a máquina a partir de imagem limpa — o contêiner é sem
privilégio e somente leitura, o que torna isso improvável, mas "improvável"
não é "impossível".

### 5. Restaurar

```bash
./scripts/restaurar.sh backups/chimaclub_<data anterior ao incidente>.dump
```

Suba, confira o catálogo e a auditoria, e só então reabra o Funnel.

### 6. Aprender

Escreva o que aconteceu, o que falhou e qual controle foi acrescentado.
Numa página só. Um incidente sem registro acontece de novo.

---

## O que este projeto NÃO tem, e você precisa saber

- **Alerta automático.** O §A09 prevê aviso por e-mail ou Telegram em três
  casos — dez falhas de login em dez minutos, aplicação fora do ar por mais
  de cinco minutos, backup que não concluiu. Isso não está implementado, e
  depende de escolher um canal. Enquanto não houver, a revisão mensal da
  auditoria é o que cobre o primeiro caso, e nada cobre os outros dois.

- **Redundância.** Uma queda de energia ou de internet tira o site do ar, e a
  §11 do documento de projeto aceita isso na versão 1.

- **Proteção contra ataque volumétrico.** O limite por IP trata do abuso de
  origem única. Um ataque distribuído não é absorvível nesta infraestrutura,
  e a resposta aceita é desligar o Funnel e esperar.
