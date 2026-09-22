package br.com.chimaclub;

import org.springframework.boot.test.context.SpringBootTest;
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

    @DynamicPropertySource
    static void propriedades(DynamicPropertyRegistry registro) {
        registro.add("spring.datasource.url", BANCO::getJdbcUrl);
        registro.add("spring.datasource.username", BANCO::getUsername);
        registro.add("spring.datasource.password", BANCO::getPassword);
    }
}
