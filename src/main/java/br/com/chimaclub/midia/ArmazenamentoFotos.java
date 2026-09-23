package br.com.chimaclub.midia;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Guarda as fotos em disco, fora do diretório da aplicação, com nome
 * gerado pelo sistema.
 *
 * O nome enviado pelo usuário é descartado por completo: ele nunca toca o
 * disco nem volta para o navegador. Nome de arquivo vindo do cliente é a
 * origem de travessia de diretório, de sobrescrita de arquivo alheio e de
 * extensão executável disfarçada, e a forma de eliminar os três de uma vez é
 * não usá-lo.
 */
@Component
public class ArmazenamentoFotos {

    /**
     * UUID, versão e extensão — nada mais. Barra, ponto-ponto e qualquer
     * outra extensão não casam.
     */
    private static final Pattern NOME_VALIDO =
            Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
                            + "-(mini|media|grande)\\.(webp|jpg)$");

    private final Path raiz;

    public ArmazenamentoFotos(@Value("${app.diretorio-fotos}") String diretorio) {
        this.raiz = Path.of(diretorio).toAbsolutePath().normalize();
        try {
            Files.createDirectories(raiz);
        } catch (IOException falha) {
            throw new UncheckedIOException("não foi possível criar " + raiz, falha);
        }
    }

    /** Grava cada versão e devolve o nome gerado de cada uma. */
    public Map<String, String> gravar(UUID base, Map<String, byte[]> versoes, String extensao) {
        Map<String, String> nomes = new LinkedHashMap<>();
        versoes.forEach((versao, bytes) -> {
            String nome = base + "-" + versao + "." + extensao;
            try {
                Files.write(caminhoConferido(nome), bytes);
            } catch (IOException falha) {
                throw new UncheckedIOException("não foi possível gravar " + nome, falha);
            }
            nomes.put(versao, nome);
        });
        return nomes;
    }

    public byte[] ler(String nome) {
        try {
            return Files.readAllBytes(caminhoConferido(nome));
        } catch (IOException falha) {
            throw new UncheckedIOException("não foi possível ler " + nome, falha);
        }
    }

    public boolean existe(String nome) {
        return Files.exists(caminhoConferido(nome));
    }

    public void apagar(String nome) {
        try {
            Files.deleteIfExists(caminhoConferido(nome));
        } catch (IOException falha) {
            throw new UncheckedIOException("não foi possível apagar " + nome, falha);
        }
    }

    /**
     * Duas conferências de propósito. A expressão regular já barra travessia
     * de diretório sozinha; a comparação de caminho é a rede embaixo, para o
     * caso de a expressão ganhar um buraco numa edição futura. Controle de
     * caminho é exatamente o lugar onde vale ter a segunda camada.
     */
    private Path caminhoConferido(String nome) {
        if (nome == null || !NOME_VALIDO.matcher(nome).matches()) {
            throw new IllegalArgumentException("nome de arquivo fora do padrão");
        }

        Path caminho = raiz.resolve(nome).normalize();
        if (!caminho.startsWith(raiz)) {
            throw new IllegalArgumentException("caminho resolvido fora do diretório de fotos");
        }
        return caminho;
    }
}
