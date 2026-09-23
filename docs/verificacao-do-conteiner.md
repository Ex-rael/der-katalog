# Verificação do contêiner

Os comandos abaixo foram executados nesta máquina, com a saída real
registrada. Repita-os depois de qualquer mudança no `Dockerfile` ou no
`compose.yaml`: são a prova de que a contenção do §1.2 do plano de
segurança existe de fato, e não apenas no arquivo.

```bash
docker compose up -d --build
```

## Roda sem privilégio

```console
$ docker compose exec app id
uid=10001(chimaclub) gid=10001(chimaclub) groups=10001(chimaclub),1000(ubuntu)
```

O grupo suplementar 1000 existe por um motivo só: o Compose fora do modo
swarm monta o arquivo de segredo com a posse que ele tem no host, ignorando
`uid`, `gid` e `mode`. Sem o grupo, a aplicação não conseguiria ler o próprio
segredo, e a alternativa — tornar o arquivo legível por todo mundo —
contrariaria o §4.4.

## O sistema de arquivos não é gravável

```console
$ docker compose exec app sh -c 'touch /teste'
touch: cannot touch '/teste': Read-only file system

$ docker compose exec app sh -c 'touch /aplicacao/teste'
touch: cannot touch '/aplicacao/teste': Read-only file system
```

## Nenhuma capacidade do kernel

```console
$ docker compose exec app sh -c 'grep -E "^Cap(Eff|Prm|Bnd)" /proc/self/status'
CapPrm:	0000000000000000
CapEff:	0000000000000000
CapBnd:	0000000000000000
```

Zerado nos três: nem as capacidades efetivas, nem as permitidas, nem o limite
do que o processo poderia vir a adquirir.

## Grava só onde precisa, e o que grava não executa

```console
$ docker compose exec app sh -c 'touch /var/chimaclub/fotos/teste && echo ok'
ok

$ docker compose exec app sh -c 'printf "#!/bin/sh\necho executou\n" > /tmp/x.sh; chmod +x /tmp/x.sh; /tmp/x.sh'
sh: 1: /tmp/x.sh: Permission denied
```

O `/tmp` é onde o Tomcat deposita os temporários de upload. Montá-lo com
`noexec` significa que um arquivo enviado que aterrisse ali não roda, mesmo
que tudo o mais falhe.

## As portas continuam confinadas

```console
$ ss -ltn | grep -E ':8080|:8081'
LISTEN 0 4096 127.0.0.1:8080 0.0.0.0:*
LISTEN 0 4096 127.0.0.1:8081 0.0.0.0:*
```

## A nativa de WebP carrega dentro do contêiner

O processador de imagem recua para JPEG quando o escritor de WebP não está
disponível, e registra um aviso ao subir. A ausência do aviso é a
confirmação:

```console
$ docker compose logs app | grep -i webp
(nenhuma linha)
```

A imagem instala `libstdc++6` por causa disso: a imagem do JRE não a traz, e
sem ela a nativa não carrega.

## Os cabeçalhos chegam de fora do contêiner

```console
$ curl -sI -H 'X-Forwarded-Proto: https' http://127.0.0.1:8080/ | grep -i strict-transport
Strict-Transport-Security: max-age=31536000 ; includeSubDomains

$ curl -sI http://127.0.0.1:8080/ | grep -ci strict-transport
0
```

O HSTS aparece quando a requisição veio por HTTPS, e não aparece no acesso
local direto — que é o comportamento certo: anunciá-lo sobre http local
travaria o acesso ao painel na própria máquina.

## O endereço canônico é o público

```console
$ curl -s -H 'X-Forwarded-Proto: https' -H 'X-Forwarded-Host: chimaclub.exemplo.ts.net' \
    http://127.0.0.1:8080/sitemap.xml | grep -o '<loc>[^<]*</loc>' | head -1
<loc>https://chimaclub.exemplo.ts.net/</loc>
```

Sem o `forward-headers-strategy`, esta linha anunciava aos buscadores
`http://127.0.0.1:8080/`.
