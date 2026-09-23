# Fontes deste projeto

As três famílias são hospedadas aqui, e não carregadas do Google, para que a
política de segurança de conteúdo (§A05 do plano de segurança) possa ficar
restrita a `'self'`. Um endereço externo obrigaria a política a abrir uma
exceção permanente para um terceiro, e daria a esse terceiro o registro de
cada visita ao catálogo.

Todas estão sob a **SIL Open Font License 1.1**, que permite uso,
redistribuição e hospedagem própria.

| Família | Papel no projeto | Origem |
|---|---|---|
| Pinyon Script | cursiva das aberturas e do lema (substitui a Symphony) | Google Fonts |
| Marcellus | serifada dos títulos e nomes de produto (substitui a Awesome Lathusca) | Google Fonts |
| Jost | sem serifa do texto corrido (substitui a Nourd) | Google Fonts |

Cada família tem dois arquivos:

- `*-latin.woff2` — U+0000–00FF, que cobre todos os acentos do português
- `*-latin-ext.woff2` — o restante do latim estendido, baixado só se a página precisar

Se as fontes originais do brand board forem licenciadas um dia, elas entram
aqui e as substitutas saem, trocando apenas os `@font-face` do
`chimaclub.css`.

O `htmx.min.js`, em `../js/`, está sob licença BSD de duas cláusulas.
