# Backlog

O que está decidido mas ainda não implementado. Cada item diz o que é,
por que foi adiado, e o que já existe no código a favor dele.

---

## 1. Enviar fotos na própria tela de cadastro, e tirar "Publicado" dela

**Decidido em:** 2026-09-24
**Origem:** teste de uso do painel — não havia como pôr foto num produto
que estava sendo cadastrado.

### O que fazer

Duas mudanças que andam juntas:

**(a) Aceitar arquivos no formulário de cadastro.** Hoje o envio de fotos
só aparece na tela de edição (`th:unless="${novo}"` em
`admin/produto-form.html`), porque só há um `produto.id` para pendurar a
foto depois que o produto existe. A tela de cadastro passaria a ser
`multipart/form-data` e a aceitar arquivos junto: cria o produto e anexa
as fotos no mesmo envio, dando para publicar de uma vez.

**(b) Tirar a caixa "Publicado no catálogo" da tela de cadastro.** Um
produto que está sendo criado nunca tem foto, então essa caixa ali falha
100% das vezes — é uma promessa que a tela não pode cumprir. A
publicação continua sendo ação explícita, na listagem ou na edição.

### O caso novo que (a) traz

Hoje toda validação acontece antes de qualquer gravação: um arquivo
recusado não deixa rastro nem no disco nem no banco (`FotoService.enviar`
valida antes de gravar). Com o cadastro aceitando arquivos, passa a
existir o meio-termo — produto criado, foto recusada — que precisa de
decisão explícita: desfazer o produto junto, ou deixá-lo como rascunho e
avisar que a foto não entrou. É o motivo de o item ter sido adiado em vez
de feito junto com a correção das mensagens.

### O que já está pronto a favor

- `POST /admin/produtos/{id}/fotos` aceita até 8 arquivos por envio e já
  faz validação de extensão, número mágico e dimensão antes de
  descomprimir.
- `FotoService.enviar` marca a primeira foto como principal sozinho.
- A recusa de publicar sem foto agora **aparece na tela** (corrigido em
  2026-09-24); sem isso, qualquer mudança aqui seria feita às cegas.
- `CadastroPeloPainelTest.telaDeEdicaoOfereceEnvioDeFoto` fixa o contrato
  da tela de edição, que não deve regredir enquanto isto é feito.

### Como saber que ficou pronto

Um teste que renderiza `/admin/produtos/novo`, encontra `type="file"` e
`enctype="multipart/form-data"`, envia nome, preço e uma foto no mesmo
POST, e termina com o produto criado e uma foto principal associada.
