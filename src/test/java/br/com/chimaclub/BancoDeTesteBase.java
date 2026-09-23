package br.com.chimaclub;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Base de todo teste de integração. Sobe um PostgreSQL 16 real, porque o
 * esquema depende de pg_trgm, unaccent, índice parcial e INET — nada disso
 * existe em banco em memória, e testar contra um substituto esconderia
 * justamente os erros que importam.
 *
 * O contêiner é iniciado uma única vez, no carregamento da classe, e nunca
 * parado explicitamente: quem o remove ao fim da execução é o Ryuk do
 * próprio Testcontainers. A anotação @Container faria o oposto — pararia o
 * contêiner ao fim de cada classe de teste, enquanto o Spring reaproveita o
 * contexto em cache apontando para a porta que acabou de morrer.
 */
@SpringBootTest
public abstract class BancoDeTesteBase {

    static final PostgreSQLContainer<?> BANCO =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("chimaclub")
                    .withUsername("chimaclub")
                    .withPassword("senha-de-teste");

    /** Diretório descartável para as fotos gravadas durante a suíte. */
    protected static final String DIRETORIO_DE_FOTOS_DE_TESTE =
            System.getProperty("java.io.tmpdir") + "/chimaclub-fotos-de-teste";

    static {
        BANCO.start();
    }

    /**
     * Porta administrativa fixa e fora da faixa usual, para o teste de
     * isolamento conseguir bater nela.
     *
     * Estes valores vêm por @DynamicPropertySource, e não por um
     * application.yaml em src/test/resources: um arquivo de mesmo nome no
     * classpath de teste encobre o de produção por inteiro, e a suíte
     * passaria a exercitar uma configuração que não é a que vai ao ar.
     */
    protected static final int PORTA_ADMIN_DE_TESTE = 18081;

    @Autowired
    private JdbcTemplate jdbcDaLimpeza;

    /**
     * Opcional porque nem todo contexto de teste carrega a configuração de
     * limite; quando carrega, os baldes são zerados junto com o banco.
     */
    @Autowired(required = false)
    private br.com.chimaclub.config.LimiteDeRequisicoes limiteDeRequisicoes;

    /**
     * Guarda o conteúdo semeado pela V2, colhido antes de qualquer teste
     * mexer nele. É o que permite restaurar em vez de truncar: a semeadura
     * é parte do estado inicial, e apagá-la quebraria os testes que contam
     * com ela.
     */
    private static Map<String, String> configuracaoSemeada;

    /**
     * Cada teste começa da mesma base: esquema migrado, dados da V2
     * semeados, e nada mais. O contêiner é um só para a suíte inteira, por
     * velocidade, e sem esta limpeza uma classe enxergaria as linhas que a
     * anterior deixou — e passaria ou falharia conforme a ordem de
     * execução, que ninguém controla.
     *
     * A tabela configuracao é restaurada, e não truncada: ela é semeada
     * pela migração, e vários testes a alteram. Deixá-la de fora da limpeza
     * era um furo — o resultado passava a depender de qual teste rodou
     * antes.
     */
    @BeforeEach
    void limparDadosDeTeste() {
        // O limite por IP guarda estado em memória, compartilhado pela suíte
        // inteira. Sem zerar aqui, uma classe consumia o balde e a seguinte
        // recebia 429 em vez do que estava medindo — e o teste falhava por
        // um motivo que nada tem a ver com o que ele afirma.
        if (limiteDeRequisicoes != null) {
            limiteDeRequisicoes.limpar();
        }

        jdbcDaLimpeza.execute("""
                TRUNCATE TABLE clique_whatsapp, evento_auditoria, produto_foto, produto, usuario_admin
                RESTART IDENTITY CASCADE
                """);

        if (configuracaoSemeada == null) {
            configuracaoSemeada = new LinkedHashMap<>();
            jdbcDaLimpeza.query("SELECT chave, valor FROM configuracao", linha -> {
                configuracaoSemeada.put(linha.getString("chave"), linha.getString("valor"));
            });
        } else {
            jdbcDaLimpeza.update("DELETE FROM configuracao");
            configuracaoSemeada.forEach((chave, valor) -> jdbcDaLimpeza.update(
                    "INSERT INTO configuracao (chave, valor) VALUES (?, ?)", chave, valor));
        }
    }

    @DynamicPropertySource
    static void propriedades(DynamicPropertyRegistry registro) {
        registro.add("spring.datasource.url", BANCO::getJdbcUrl);
        registro.add("spring.datasource.username", BANCO::getUsername);
        registro.add("spring.datasource.password", BANCO::getPassword);
        registro.add("app.porta-admin", () -> PORTA_ADMIN_DE_TESTE);
        // Fora do projeto: sem isto a suíte deixa arquivos em ./fotos, que é
        // o diretório de desenvolvimento, e eles se acumulam a cada execução.
        registro.add("app.diretorio-fotos", () -> DIRETORIO_DE_FOTOS_DE_TESTE);
    }
}
