package br.com.chimaclub;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
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
     * Cada teste começa da mesma base: esquema migrado, dados da V2
     * semeados, e nada mais. O contêiner é um só para a suíte inteira, por
     * velocidade, e sem esta limpeza uma classe enxergaria as linhas que a
     * anterior deixou — e passaria ou falharia conforme a ordem de execução,
     * que ninguém controla.
     *
     * As tabelas semeadas pela V2 (categoria, configuracao) ficam de fora de
     * propósito: elas são parte do estado inicial, não resíduo de teste.
     */
    @BeforeEach
    void limparDadosDeTeste() {
        jdbcDaLimpeza.execute("""
                TRUNCATE TABLE clique_whatsapp, evento_auditoria, produto_foto, produto, usuario_admin
                RESTART IDENTITY CASCADE
                """);
    }

    @DynamicPropertySource
    static void propriedades(DynamicPropertyRegistry registro) {
        registro.add("spring.datasource.url", BANCO::getJdbcUrl);
        registro.add("spring.datasource.username", BANCO::getUsername);
        registro.add("spring.datasource.password", BANCO::getPassword);
        registro.add("app.porta-admin", () -> PORTA_ADMIN_DE_TESTE);
    }
}
