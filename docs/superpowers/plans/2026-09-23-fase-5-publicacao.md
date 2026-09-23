# Chima Club — Fase 5: Publicação — Plano de Implementação

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Pôr o catálogo no ar, com os 17 produtos reais carregados, o painel alcançável apenas pela tailnet, e a exposição verificada de fora da casa.

**Architecture:** Nada muda no código. Esta fase é carga de dados, configuração do Tailscale e verificação — e é a única em que um erro de configuração expõe a rede doméstica de imediato.

**Spec:** `chimaclub-definicao-projeto.md` §8.2, §10 e `chimaclub-plano-seguranca.md` §2, §5.1

**Fases anteriores:** 1 a 4 entregues, 290 testes verdes.

## Global Constraints

- **A publicação é a última coisa a acontecer.** A lista 5.1 do plano precisa estar cumprida antes, inclusive os itens que dependem da pessoa que administra a máquina. Publicar antes disso é o mesmo que instalar a fechadura depois de mudar para a casa.
- **A carga de produtos é idempotente.** Rodar o script duas vezes não pode gerar dezessete produtos duplicados nem apagar o que a dona da loja já tiver ajustado à mão.
- **A verificação de exposição é feita de fora**, com o Tailscale desligado. Verificar de dentro da tailnet não prova nada: de lá, o painel deve mesmo responder.
- **Nenhuma ação que exponha a máquina roda sem confirmação explícita.** Ligar o Funnel torna esta máquina alcançável pela internet inteira, em segundos, e desfazer depois não desfaz o que tiver sido varrido no meio-tempo.

---

### Task 1: Carga dos 17 produtos

**Files:**
- Create: `scripts/carregar-catalogo-inicial.sh`
- Create: `dados-iniciais/catalogo.json` e `dados-iniciais/fotos/*.jpg`

**Interfaces:**
- Consumes: Fase 4
- Produces: 17 produtos publicados, com foto, nas duas categorias

- [ ] **Step 1: Extrair os produtos do catálogo antigo**

Nome, preço, categoria e foto saem de `styles/catalogo-chimaclub-previa.html`, que é a fonte real: o catálogo estático que a loja usava antes deste projeto.

- [ ] **Step 2: Escrever o script de carga**

Ele conversa com o painel pela porta administrativa, como a dona da loja faria: assim exercita o mesmo caminho — validação de upload, reprocessamento da imagem, regra de publicação — em vez de escrever direto no banco e contornar tudo.

Idempotente: antes de criar, consulta se já existe produto com aquele nome.

- [ ] **Step 3: Executar e conferir**

17 produtos publicados, 15 em madeira e 2 em porongo, cada um com três arquivos WebP no volume.

- [ ] **Step 4: Commit**

---

### Task 2: Scripts de publicação e de verificação

**Files:**
- Create: `scripts/publicar.sh`
- Create: `scripts/verificar-exposicao.sh`

**Interfaces:**
- Consumes: Task 1
- Produces: os comandos do Tailscale, e a verificação que os acompanha

- [ ] **Step 1: Escrever `publicar.sh`**

```bash
tailscale serve  --bg --https=8443 http://127.0.0.1:8081   # painel, só na tailnet
tailscale funnel --bg --https=443  http://127.0.0.1:8080   # catálogo, na internet
```

A ordem importa: o painel primeiro, para que quem estiver configurando confirme que ele responde na tailnet antes de abrir qualquer coisa para fora.

O script recusa rodar se a aplicação não estiver no ar, e recusa rodar se o perfil ativo não for `prod`.

- [ ] **Step 2: Escrever `verificar-exposicao.sh`**

Confere, do lado de dentro, o que dá para conferir: que o Funnel aponta para 8080 e não para 8081, que o painel não está em `funnel`, e que a aplicação responde. E **imprime a lista do que só pode ser verificado de fora**, porque essa parte não tem como ser automatizada daqui.

- [ ] **Step 3: Commit**

---

### Task 3: Publicação — depende de confirmação

- [ ] **Step 1: Conferir a lista 5.1**, em especial os itens que dependem da pessoa
- [ ] **Step 2: Rodar `scripts/publicar.sh`**
- [ ] **Step 3: Verificar de fora**, com o celular fora de casa e o Tailscale desligado:
  - `https://<nome>.ts.net/` abre o catálogo
  - `https://<nome>.ts.net/admin` responde 403 ou 404, **nunca a tela de login**
  - `https://<nome>.ts.net/actuator/health` responde 403 ou 404
- [ ] **Step 4: Testar o fluxo no celular**: buscar, abrir produto, clicar no WhatsApp
- [ ] **Step 5: Ativar o segundo fator** na conta da administradora, pela tailnet
- [ ] **Step 6: Agendar o backup no cron** e levar a primeira cópia para fora da máquina

---

## O que esta fase NÃO faz

- Não liga o Funnel sem confirmação explícita.
- Não substitui a verificação de fora da casa, que é a única que prova o isolamento do painel.
