package br.com.chimaclub.config;

import br.com.chimaclub.BancoDeTesteBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.Enumeration;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Sem server.address, o Tomcat liga o conector público em todas as
 * interfaces. Em produção a publicação do Docker ainda confinaria a porta,
 * mas seria uma camada só — e em desenvolvimento o catálogo ficaria visível
 * para a rede doméstica inteira.
 *
 * Este teste procura um endereço IPv4 desta máquina que não seja o loopback
 * e tenta alcançar a aplicação por ele. Se conseguir, o bind está errado.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class EnderecoDeBindTest extends BancoDeTesteBase {

    @LocalServerPort
    int portaPublica;

    @Value("${app.endereco-bind}")
    String enderecoConfigurado;

    @Test
    @DisplayName("a configuração de teste manda ligar no loopback")
    void configuracaoPedeLoopback() {
        assertThat(enderecoConfigurado).isEqualTo("127.0.0.1");
    }

    @Test
    @DisplayName("o conector público não atende por endereço de rede, só pelo loopback")
    void naoAtendeForaDoLoopback() throws IOException {
        Optional<InetAddress> enderecoDeRede = primeiroEnderecoNaoLoopback();
        assumeTrue(enderecoDeRede.isPresent(), "máquina sem interface de rede além do loopback");

        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(enderecoDeRede.get(), portaPublica), 2000);
            throw new AssertionError(
                    "a aplicação atendeu em " + enderecoDeRede.get().getHostAddress()
                    + ": o conector está ligado em todas as interfaces, não no loopback");
        } catch (SocketTimeoutException | java.net.ConnectException esperado) {
            // É exatamente isto que se espera: nada escutando fora do loopback.
        }

        // E, pelo loopback, continua atendendo.
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", portaPublica), 2000);
            assertThat(socket.isConnected()).isTrue();
        }
    }

    private static Optional<InetAddress> primeiroEnderecoNaoLoopback() throws IOException {
        Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
        while (interfaces.hasMoreElements()) {
            NetworkInterface rede = interfaces.nextElement();
            if (!rede.isUp() || rede.isLoopback()) {
                continue;
            }
            Enumeration<InetAddress> enderecos = rede.getInetAddresses();
            while (enderecos.hasMoreElements()) {
                InetAddress endereco = enderecos.nextElement();
                if (endereco instanceof java.net.Inet4Address && !endereco.isLoopbackAddress()) {
                    return Optional.of(endereco);
                }
            }
        }
        return Optional.empty();
    }
}
