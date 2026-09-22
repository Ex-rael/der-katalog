package br.com.chimaclub;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * Deliberadamente magro: prova que o pom.xml resolve e que a classe principal
 * está no lugar. O que importa é verificado pelos testes de integração das
 * tarefas seguintes, contra um PostgreSQL real.
 */
class ChimaClubApplicationTest {

    @Test
    void classePrincipalTemMetodoMain() {
        assertDoesNotThrow(() -> ChimaClubApplication.class.getDeclaredMethod("main", String[].class));
    }
}
