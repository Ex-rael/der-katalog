# Chima Club — Plano de Segurança da Informação

Aplicação Java 21 + Spring Boot + PostgreSQL, hospedada em máquina doméstica e exposta à internet por Tailscale Funnel.

- **Versão:** 1.0
- **Data:** setembro de 2026
- **Referências:** OWASP Top 10 (2021), OWASP ASVS 4.0 nível 1, OWASP Cheat Sheet Series

---

## 1. Contexto e princípios

### 1.1 O que muda por hospedar em casa

O Tailscale Funnel entrega um nome público e um certificado TLS válido, e encaminha o tráfego da internet até uma porta local. **Ele não é firewall de aplicação, não filtra conteúdo e não limita requisições.** Do ponto de vista de risco, publicar pelo Funnel equivale a publicar na internet aberta, com dois agravantes: o processo roda dentro da rede doméstica, junto de televisores, celulares e do computador pessoal; e não há equipe de plantão.

Daí decorrem os três princípios do plano.

### 1.2 Princípios

1. **Superfície mínima na internet.** Só o catálogo público atravessa o Funnel. O painel, o banco, as métricas e as ferramentas ficam restritos à tailnet ou ao `localhost`.
2. **Contenção.** Se a aplicação for comprometida, o estrago deve parar nela: contêiner sem privilégio, sistema de arquivos somente leitura, banco isolado, rede doméstica segmentada.
3. **Recuperação acima de perfeição.** Backup testado vale mais que qualquer controle preventivo isolado. O critério é conseguir reconstruir tudo em poucas horas.

### 1.3 O que estamos protegendo

| Ativo | Sensibilidade | Impacto se perdido ou vazado |
|---|---|---|
| Catálogo (produtos, preços, fotos) | Baixa (é público) | Retrabalho de recadastro; fotos podem não ter cópia |
| Credenciais do administrador | Alta | Controle total do site; alteração de preços e do número de WhatsApp |
| Número de WhatsApp da loja | Média | Golpe contra clientes com número trocado |
| Banco de dados e backups | Alta | Perda do catálogo; backup com senha vazada abre o sistema |
| Rede doméstica | Alta | Acesso a dispositivos pessoais |
| Reputação da marca | Alta | Site depredado ou usado em golpe afasta cliente |

Não há dado pessoal de cliente armazenado: a conversa acontece no WhatsApp e nada é gravado além de contagem anônima de cliques. Isso reduz muito a exposição à LGPD, e é uma decisão a manter.

---

## 2. Arquitetura de exposição

```
Internet ──► Tailscale Funnel (TLS 443) ──► 127.0.0.1:8080  catálogo público
                                              └─ só GET e o POST de clique

Tailnet  ──► tailscale serve (8443)     ──► 127.0.0.1:8081  painel admin
                                              └─ login, CRUD, upload, actuator

localhost ─────────────────────────────► 127.0.0.1:5432    PostgreSQL
```

### 2.1 Dois conectores no mesmo processo

```java
@Configuration
public class PortasConfig {

    /**
     * O endereço de bind vem de configuração: 127.0.0.1 no perfil dev, que roda
     * direto no host, e 0.0.0.0 no perfil prod, que roda em contêiner. Dentro do
     * contêiner, 127.0.0.1 é o loopback do próprio contêiner: ligar nele deixaria
     * a porta inalcançável pela publicação do Docker, e o painel simplesmente não
     * responderia. Em produção o confinamento é feito pelo "127.0.0.1:8081:8081"
     * do Compose, pelo UFW e pelo Tailscale — nunca por este endereço.
     */
    @Bean
    public WebServerFactoryCustomizer<TomcatServletWebServerFactory> conectorAdmin(
            @Value("${app.porta-admin:8081}") int portaAdmin,
            @Value("${app.endereco-bind:127.0.0.1}") String enderecoBind) {
        return factory -> {
            Connector admin = new Connector("org.apache.coyote.http11.Http11NioProtocol");
            admin.setPort(portaAdmin);
            admin.setProperty("address", enderecoBind);
            factory.addAdditionalTomcatConnectors(admin);
        };
    }
}
```

E, no Spring Security, uma cadeia por porta, de forma que uma requisição que chegue a `/admin` pela porta pública seja negada mesmo que alguma configuração de roteamento mude:

```java
@Bean
@Order(1)
SecurityFilterChain cadeiaAdmin(HttpSecurity http) throws Exception {
    http.securityMatcher(req -> req.getLocalPort() == 8081)
        .authorizeHttpRequests(a -> a
            .requestMatchers("/admin/login", "/css/**", "/js/**").permitAll()
            .anyRequest().hasRole("ADMIN"))
        .formLogin(f -> f.loginPage("/admin/login").failureHandler(registraFalha))
        .sessionManagement(s -> s
            .sessionFixation().newSession()
            .maximumSessions(2))
        .csrf(Customizer.withDefaults());
    return http.build();
}

@Bean
@Order(2)
SecurityFilterChain cadeiaPublica(HttpSecurity http) throws Exception {
    http.securityMatcher(req -> req.getLocalPort() == 8080)
        .authorizeHttpRequests(a -> a
            .requestMatchers("/admin/**", "/actuator/**").denyAll()
            .requestMatchers(HttpMethod.GET, "/", "/busca", "/produto/**", "/fotos/**",
                             "/css/**", "/js/**", "/fontes/**", "/sitemap.xml", "/robots.txt").permitAll()
            .requestMatchers(HttpMethod.POST, "/produto/*/whatsapp").permitAll()
            .anyRequest().denyAll())
        .csrf(c -> c.ignoringRequestMatchers("/produto/*/whatsapp"))
        .anonymous(Customizer.withDefaults());
    return http.build();
}
```

**Verificação obrigatória após cada mudança de configuração do Tailscale:** a partir de um celular fora de casa, com Tailscale desligado, acessar `https://<nome>.ts.net/admin`. A resposta correta é 404 ou 403, nunca a tela de login.

---

## 3. OWASP Top 10 (2021) — controles

### A01 — Quebra de controle de acesso

| Risco | Controle |
|---|---|
| Painel alcançável pela internet | Conectores separados; cadeia pública nega `/admin/**` e `/actuator/**`; teste externo documentado |
| Rota administrativa esquecida sem proteção | Política padrão `denyAll`; toda liberação é explícita |
| Acesso direto a recurso por ID (IDOR) | Existe um único papel `ADMIN`, mas toda consulta de edição filtra por `excluido_em IS NULL` e valida existência antes de renderizar |
| Navegação em diretório de fotos | Servidas por controlador que só aceita nome no padrão `^[0-9a-f-]{36}-(mini\|media\|grande)\.webp$`, com caminho resolvido e comparado à raiz permitida |
| Método HTTP inesperado | `TRACE` e `OPTIONS` desabilitados; rotas públicas restritas a `GET` mais um `POST` |

### A02 — Falhas criptográficas

- TLS 1.2+ com certificado gerenciado pelo Tailscale, renovado automaticamente; HTTP não é servido.
- HSTS com `max-age=31536000; includeSubDomains`, ligado só depois de confirmar que o domínio funciona em HTTPS.
- Senhas com BCrypt custo 12 (`DelegatingPasswordEncoder`, com prefixo que permite migrar de algoritmo depois).
- Segredo TOTP cifrado em repouso com AES-GCM, e a chave vem de variável de ambiente, nunca do repositório.
- Cookie de sessão com `Secure`, `HttpOnly` e `SameSite=Strict`; nome trocado para `CHIMASESSION`.
- Disco da máquina cifrado (LUKS no Linux, BitLocker no Windows). Sem isso, um furto do equipamento entrega banco e backups.
- Backups cifrados antes de sair da máquina.

### A03 — Injeção

- Consultas por JPA e por parâmetro vinculado. Concatenação de string em JPQL ou SQL é proibida em revisão de código, inclusive na busca com `LIKE`. O termo entra como parâmetro e é normalizado dentro do banco por `imutavel_unaccent`, nunca montado em Java e colado na consulta.
- Thymeleaf escapa por padrão; `th:utext` é proibido. Se a descrição precisar de negrito e lista no futuro, o caminho é Markdown limitado com sanitização por OWASP Java HTML Sanitizer, nunca HTML livre.
- Nenhuma chamada a `Runtime.exec` ou processo externo com entrada do usuário. Processamento de imagem é feito em biblioteca Java, não invocando ImageMagick por linha de comando.
- Validação de entrada por Bean Validation em todos os formulários: tamanho máximo, faixa numérica, padrão de caracteres.
- Cabeçalhos de resposta nunca recebem valor vindo do usuário sem remoção de `\r` e `\n`.

### A04 — Design inseguro

- O painel fora da internet é decisão de arquitetura, não de configuração pontual.
- Limite de requisições por IP com Bucket4j: 60 por minuto nas rotas públicas, 5 por minuto na rota de login, 10 por minuto no upload.
- Bloqueio progressivo de conta: após 5 falhas, bloqueio de 15 minutos, registrado em auditoria.
- Limites de tamanho declarados: 10 MB por arquivo, 8 arquivos por requisição, 25 MB por requisição, 4000 caracteres na descrição.
- Tempo limite de sessão: 30 minutos de inatividade, 8 horas de duração máxima.
- Ausência de funcionalidade perigosa: sem recuperação de senha por e-mail (a troca é feita por linha de comando na máquina), sem cadastro público, sem upload por URL remota, o que elimina SSRF por completo.

### A05 — Configuração insegura

| Item | Estado desejado |
|---|---|
| Senha padrão | Nenhuma. Primeiro usuário criado por comando, com senha gerada aleatoriamente |
| Páginas de erro | Genéricas; `server.error.include-stacktrace=never`, `include-message=never` |
| Banner e cabeçalho do servidor | `server.server-header=` vazio, `server.error.whitelabel.enabled=false` |
| Actuator | Apenas `health` e `info` na porta admin; `env`, `heapdump`, `threaddump` desativados |
| Postgres | Porta ligada a `127.0.0.1`; sem usuário `postgres` com senha fraca; usuário da aplicação sem `SUPERUSER` nem `CREATEDB` |
| Senha do banco na aplicação | Entregue por arquivo em `/run/secrets`, lido pelo *config tree* do Spring Boot. Nunca em variável de ambiente, onde apareceria em `docker inspect` e em `/proc/<pid>/environ` |
| Contêiner | Usuário não root, `read_only: true`, `cap_drop: [ALL]`, `no-new-privileges` |
| Firewall do sistema | UFW com política de negar entrada, exceto SSH pela tailnet |
| Autenticação SSH | Somente chave, senha desabilitada, acesso restrito à tailnet |
| Wi-Fi e roteador | Sem redirecionamento de porta; UPnP desligado; senha do roteador trocada |

Cabeçalhos de segurança aplicados a todas as respostas:

```
Content-Security-Policy: default-src 'none'; img-src 'self' data:; style-src 'self';
  font-src 'self' https://fonts.gstatic.com; script-src 'self';
  connect-src 'self'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'
Referrer-Policy: strict-origin-when-cross-origin
X-Content-Type-Options: nosniff
X-Frame-Options: DENY
Permissions-Policy: geolocation=(), camera=(), microphone=(), payment=()
Cross-Origin-Opener-Policy: same-origin
Cross-Origin-Resource-Policy: same-origin
```

Se as fontes do Google forem usadas, `style-src` precisa incluir `https://fonts.googleapis.com`. Melhor ainda é hospedar as fontes localmente e manter a política restrita a `'self'`.

### A06 — Componentes vulneráveis e desatualizados

- `mvn org.owasp:dependency-check-maven:check` na construção; falha se houver vulnerabilidade de severidade 7 ou superior.
- Dependabot ou Renovate no repositório, com revisão mensal.
- Imagens base fixadas por digest e reconstruídas mensalmente; varredura com Trivy antes de publicar.
- Inventário simples de dependências (SBOM via CycloneDX) gerado a cada versão.
- A aplicação acompanha uma linha *minor* do Spring Boot com suporte corrente — hoje a 3.5.x. Permanecer numa minor fora de suporte contraria este item mesmo que nenhuma vulnerabilidade tenha sido anunciada, porque a correção, quando vier, não virá para ela.
- Regra prática: qualquer correção crítica de Spring, Postgres ou do sistema operacional é aplicada em até 7 dias; as demais, no ciclo mensal.

### A07 — Falhas de identificação e autenticação

- Usuário único, com e-mail e senha longa gerada por gerenciador de senhas; mínimo de 12 caracteres, com verificação contra a lista de senhas mais comuns.
- Segundo fator TOTP obrigatório em produção.
- Mensagem de erro de login sempre idêntica, sem revelar se o e-mail existe.
- Sessão regenerada no login; encerrada no logout; no máximo duas sessões simultâneas.
- Toda tentativa, bem ou malsucedida, registrada com IP e horário.
- Sem "lembrar de mim" e sem token de longa duração.

### A08 — Falhas de integridade de software e de dados

- Upload tratado como hostil. A sequência é: verificar extensão na lista permitida (`jpg`, `jpeg`, `png`, `webp`), verificar o tipo real pelos bytes iniciais, carregar com `ImageIO` dentro de limite de dimensão (máximo 8000 × 8000, para evitar bomba de descompressão), **reescrever a imagem** — em WebP, ou em JPEG progressivo caso a biblioteca nativa de WebP não carregue no ambiente de execução — e descartar o arquivo original com todos os metadados. Arquivo que não abre como imagem é rejeitado.
- Nome do arquivo gerado pelo sistema; o nome enviado nunca é usado no disco nem devolvido ao navegador.
- Diretório de fotos montado sem permissão de execução (`noexec`) e fora do diretório da aplicação.
- Imagens servidas com `Content-Type` fixo, derivado da extensão gerada pelo sistema e nunca do arquivo enviado, mais `Content-Disposition: inline` e `X-Content-Type-Options: nosniff`.
- Migrações do Flyway versionadas e revisadas; `clean` desativado em produção.
- Artefatos de construção gerados só na máquina de desenvolvimento, a partir de repositório Git com histórico conhecido.

### A09 — Falhas de registro e monitoramento

Eventos registrados em `evento_auditoria` e em arquivo:

- login bem-sucedido e falho, com IP;
- bloqueio de conta;
- criação, alteração, publicação e exclusão de produto, com identificação do usuário;
- upload e exclusão de foto;
- alteração de configuração da loja;
- erro 500 com identificador de correlação.

Regras: nunca registrar senha, segredo TOTP, cookie ou token; rotação diária com retenção de 90 dias; auditoria retida por 1 ano. Revisão mensal de 10 minutos procurando rajadas de falha de login e alterações inesperadas. Alerta por e-mail ou Telegram em três casos: 10 falhas de login em 10 minutos, aplicação fora do ar por mais de 5 minutos, backup que não concluiu.

### A10 — Falsificação de requisição no servidor (SSRF)

A aplicação não faz requisição de saída a partir de entrada do usuário: não há upload por URL, nem visualização de link, nem webhook. Isso elimina o risco na origem, e a decisão deve ser mantida. Se um dia for preciso buscar algo externo, a regra é lista de destinos permitidos, bloqueio de IP privado e de redirecionamento.

---

## 4. Controles adicionais

### 4.1 Proteção específica de CSRF e clickjacking

CSRF do Spring Security ativo em todo o painel, com token por sessão. A única exceção é o `POST` de clique no WhatsApp, que não altera estado relevante e é protegido por limite de requisições. `frame-ancestors 'none'` e `X-Frame-Options: DENY` impedem que o site seja embutido em outro.

### 4.2 Negação de serviço

O ponto fraco de uma hospedagem doméstica é a banda de subida. Medidas: limite de requisições por IP; cache de imagem com `Cache-Control: public, max-age=31536000, immutable`; miniaturas pequenas na home (600 px, WebP, tipicamente abaixo de 80 KB); `robots.txt` com `Crawl-delay`; e, em caso de abuso persistente, desligar o Funnel por um período. Um ataque volumétrico não tem como ser absorvido nessa infraestrutura, e a resposta aceita é indisponibilidade temporária.

### 4.3 Segmentação da rede doméstica

A máquina do site fica em VLAN ou rede de convidados separada, sem alcance aos demais dispositivos da casa. Nenhum redirecionamento de porta no roteador: todo o tráfego externo entra pelo Tailscale. Compartilhamento de arquivos (SMB, NFS) desligado nessa máquina.

### 4.4 Segredos

Nada de credencial em `application.yml` versionado. Em produção, os valores vêm de variáveis de ambiente e de arquivos em `./secrets` com permissão `600`, fora do Git. O `.gitignore` inclui `secrets/`, `*.env`, `backups/`. Antes do primeiro `push`, rodar `gitleaks` no repositório.

### 4.5 Backup e recuperação

| Item | Frequência | Destino | Retenção |
|---|---|---|---|
| `pg_dump -Fc` | diário, 3h | disco local + disco externo cifrado | 30 dias |
| Fotos | diário | `restic` para disco externo | 30 dias |
| Configuração (compose, secrets cifrados) | a cada mudança | cópia cifrada fora da máquina | última versão |

Teste de restauração mensal, em banco temporário, conferindo a contagem de produtos e a abertura de três fotos. Meta de recuperação: até 4 horas para voltar ao ar e até 24 horas de dados perdidos no pior caso.

### 4.6 Resposta a incidente

1. **Conter:** `tailscale funnel reset` tira o site do ar em segundos. É a primeira ação em qualquer suspeita.
2. **Preservar:** copiar logs e o estado do banco antes de mexer em qualquer coisa.
3. **Avaliar:** identificar o que foi acessado, conferindo auditoria e registros de acesso.
4. **Erradicar:** trocar senha do admin e do banco, girar a chave de cifra do TOTP, atualizar dependências, reinstalar a partir de imagem limpa se houver suspeita de execução de código.
5. **Restaurar:** subir a partir de backup íntegro anterior ao incidente, republicar e observar por 48 horas.
6. **Aprender:** registrar em uma página o que aconteceu, o que falhou e qual controle foi acrescentado.

Se dado pessoal de cliente algum dia for armazenado e houver vazamento, a LGPD exige comunicação à ANPD e aos titulares em prazo razoável. Hoje esse cenário não existe, e é mais um motivo para não começar a guardar contato de cliente no sistema.

---

## 5. Listas de verificação

### 5.1 Antes de publicar (uma vez)

Os itens marcados **[auto]** são cobertos por teste automatizado da suíte do projeto e rodam a cada construção; uma regressão quebra o build em vez de esperar a próxima revisão. Os demais existem fora do processo e continuam sendo conferência manual.

- [ ] **[auto]** Rota `/admin/**` e `/actuator/**` negadas na porta pública, mesmo com sessão válida
- [ ] **[auto]** Actuator só na porta admin, com `env`, `heapdump` e `threaddump` desativados
- [ ] **[auto]** Upload de `.php` renomeado para `.jpg`, de `.svg` com script e de imagem além de 8000 × 8000 rejeitados; imagem legítima reescrita e original descartado
- [ ] **[auto]** Nome de arquivo fora do padrão e travessia de diretório (`../`) recusados pela rota de fotos
- [ ] **[auto]** `<script>alert(1)</script>` no nome e na descrição, escapado na home e na página do produto
- [ ] **[auto]** Limite de requisições disparando no login, no upload e nas rotas públicas
- [ ] **[auto]** Bloqueio da conta após 5 falhas, e mensagem de erro idêntica para e-mail existente e inexistente
- [ ] **[auto]** Alteração sem token CSRF recusada em toda rota administrativa
- [ ] **[auto]** Cabeçalhos de segurança presentes em toda resposta, e CSP sem `unsafe-inline`
- [ ] **[auto]** Erro 500 sem rastro de pilha, sem mensagem interna e sem versão do framework
- [ ] **[auto]** Cookie de sessão com `Secure`, `HttpOnly` e `SameSite=Strict`, e sessão regenerada no login
- [ ] `/admin` inacessível a partir de rede externa, testado com Tailscale desligado no celular
- [ ] Postgres ligado a `127.0.0.1`, senha forte, usuário sem privilégio administrativo
- [ ] Senha do admin gerada por gerenciador e TOTP ativado
- [ ] Cabeçalhos conferidos também de fora, pelo nome público (`curl -I` e Mozilla Observatory)
- [ ] Fontes hospedadas localmente, sem requisição ao Google
- [ ] Backup executado e restaurado com sucesso ao menos uma vez
- [ ] Disco da máquina cifrado
- [ ] Sem redirecionamento de porta no roteador; UPnP desligado
- [ ] `gitleaks` sem achados no repositório

### 5.2 Mensal

- [ ] Atualizar sistema operacional, imagens Docker e dependências Maven
- [ ] Rodar `dependency-check` e Trivy, e tratar o que for severidade alta
- [ ] Ler a auditoria de logins e de alterações
- [ ] Restaurar um backup em base temporária
- [ ] Conferir espaço em disco e crescimento da pasta de fotos

### 5.3 Trimestral

- [ ] Repetir o teste de acesso externo ao `/admin`
- [ ] Trocar a senha do banco e girar segredos
- [ ] Revisar quem tem acesso à tailnet e remover dispositivos antigos
- [ ] Reler este plano e ajustar o que mudou no sistema

---

## 6. Matriz de risco residual

| Risco | Probabilidade | Impacto | Controle principal | Residual |
|---|---|---|---|---|
| Varredura automatizada na porta pública | Alta | Baixo | Superfície mínima, limite de requisições, cabeçalhos | Baixo |
| Vulnerabilidade nova em dependência | Média | Alto | Atualização mensal, varredura na construção | Médio |
| Upload malicioso | Média | Alto | Reescrita da imagem, `noexec`, validação por conteúdo | Baixo |
| Credencial do admin comprometida | Baixa | Alto | Painel fora da internet, TOTP, bloqueio por tentativa | Baixo |
| Indisponibilidade por queda de energia ou internet | Alta | Médio | Aceito; avaliar VPS se o prejuízo crescer | Médio |
| Perda de disco | Baixa | Alto | Backup diário externo e teste de restauração | Baixo |
| Erro humano no painel (preço ou exclusão) | Média | Médio | Exclusão lógica, auditoria, backup diário | Baixo |
| Comprometimento se espalhando pela casa | Baixa | Alto | Segmentação de rede, contêiner sem privilégio | Baixo |

---

## 7. Onde este plano se ancora

- OWASP Top 10 (2021) — A01 a A10, cobertos na seção 3.
- OWASP ASVS 4.0 nível 1 — autenticação (V2), sessão (V3), controle de acesso (V4), validação (V5), armazenamento (V6), registro (V7), arquivos (V12), configuração (V14).
- OWASP Cheat Sheets — Upload de Arquivos, CSRF, Cabeçalhos de Segurança, Armazenamento de Senha, Registro.
- CIS Benchmarks para Docker e PostgreSQL, nos pontos aplicáveis a uma instalação pequena.

O nível 1 do ASVS é o alvo adequado aqui: é o patamar verificável sem equipe dedicada, coerente com uma loja que não guarda dado pessoal nem processa pagamento. Se um dia entrar checkout no site, o alvo passa a ser o nível 2 e este plano precisa ser refeito.
