package br.com.chimaclub.admin.totp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Correção provada contra os vetores de teste do apêndice B do RFC 6238.
 *
 * A implementação é própria, e não de biblioteca, porque TOTP não é desenho
 * de criptografia: é HMAC-SHA1 do JDK mais um truncamento, tudo fechado na
 * especificação. Escrever as trinta linhas evita uma dependência a
 * acompanhar no §A06, e estes vetores são evidência bem mais forte do que a
 * confiança de que a biblioteca faz o que diz.
 *
 * O segredo dos vetores é o ASCII "12345678901234567890".
 */
class GeradorTotpTest {

    /** "12345678901234567890" em ASCII, codificado em base32. */
    private static final String SEGREDO_DO_RFC = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";

    @ParameterizedTest(name = "T={0} produz {1}")
    @CsvSource({
            "59,          94287082",
            "1111111109,  07081804",
            "1111111111,  14050471",
            "1234567890,  89005924",
            "2000000000,  69279037",
            "20000000000, 65353130"
    })
    @DisplayName("bate com os vetores do apêndice B do RFC 6238")
    void bateComOsVetoresDoRfc(long segundos, String esperado) {
        String codigo = GeradorTotp.codigo(SEGREDO_DO_RFC, Instant.ofEpochSecond(segundos), 8);

        assertThat(codigo).isEqualTo(esperado);
    }

    @Test
    @DisplayName("o código de seis dígitos é o sufixo do de oito")
    void seisDigitosSaoOSufixoDeOito() {
        Instant instante = Instant.ofEpochSecond(1111111109L);

        assertThat(GeradorTotp.codigo(SEGREDO_DO_RFC, instante, 6)).isEqualTo("081804");
    }

    @Test
    @DisplayName("o código muda a cada trinta segundos e se mantém dentro da janela")
    void mudaACadaTrintaSegundos() {
        String noSegundo0 = GeradorTotp.codigo(SEGREDO_DO_RFC, Instant.ofEpochSecond(60), 6);
        String noSegundo29 = GeradorTotp.codigo(SEGREDO_DO_RFC, Instant.ofEpochSecond(89), 6);
        String noSegundo30 = GeradorTotp.codigo(SEGREDO_DO_RFC, Instant.ofEpochSecond(90), 6);

        assertThat(noSegundo0).isEqualTo(noSegundo29);
        assertThat(noSegundo30).isNotEqualTo(noSegundo0);
    }

    @Test
    @DisplayName("o segredo gerado tem 160 bits, como manda o RFC 4226")
    void segredoTem160Bits() {
        String segredo = GeradorTotp.novoSegredo();

        // 20 bytes em base32 dão 32 caracteres.
        assertThat(segredo).hasSize(32).matches("[A-Z2-7]+");
        assertThat(GeradorTotp.novoSegredo())
                .as("dois segredos seguidos não podem coincidir")
                .isNotEqualTo(segredo);
    }

    @Test
    @DisplayName("base32 decodifica o que codifica, com e sem preenchimento")
    void base32VaiEVolta() {
        byte[] original = "12345678901234567890".getBytes();

        assertThat(Base32.decodificar(Base32.codificar(original))).isEqualTo(original);
        assertThat(Base32.decodificar(SEGREDO_DO_RFC)).isEqualTo(original);
    }
}
