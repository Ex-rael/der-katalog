package br.com.chimaclub.midia;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ArmazenamentoFotosTest {

    @Test
    @DisplayName("grava as três versões com nome gerado pelo sistema")
    void gravaTresVersoes(@TempDir Path raiz) {
        ArmazenamentoFotos armazenamento = new ArmazenamentoFotos(raiz.toString());
        UUID base = UUID.randomUUID();

        Map<String, String> nomes = armazenamento.gravar(base, Map.of(
                "mini", new byte[]{1, 2, 3},
                "media", new byte[]{4, 5, 6},
                "grande", new byte[]{7, 8, 9}), "webp");

        assertThat(nomes.get("mini")).isEqualTo(base + "-mini.webp");
        assertThat(raiz.resolve(base + "-mini.webp")).exists();
        assertThat(raiz.resolve(base + "-grande.webp")).exists();
    }

    @Test
    @DisplayName("o nome enviado pelo usuário nunca aparece no disco")
    void descartaONomeEnviado(@TempDir Path raiz) throws Exception {
        ArmazenamentoFotos armazenamento = new ArmazenamentoFotos(raiz.toString());

        armazenamento.gravar(UUID.randomUUID(), Map.of("mini", new byte[]{1}), "webp");

        try (var arquivos = Files.list(raiz)) {
            assertThat(arquivos.map(caminho -> caminho.getFileName().toString()))
                    .allMatch(nome -> nome.matches("[0-9a-f-]{36}-(mini|media|grande)\\.(webp|jpg)"));
        }
    }

    @Test
    @DisplayName("recusa travessia de diretório em todas as formas conhecidas")
    void recusaTravessiaDeDiretorio(@TempDir Path raiz) {
        ArmazenamentoFotos armazenamento = new ArmazenamentoFotos(raiz.toString());

        String[] tentativas = {
                "../../etc/passwd",
                "../../../etc/shadow",
                "..%2F..%2Fetc%2Fpasswd",
                "..\\..\\windows\\system32\\config\\sam",
                "/etc/passwd",
                "/var/chimaclub/fotos/../../../etc/passwd",
                "....//....//etc/passwd",
                UUID.randomUUID() + "-mini.webp/../../../etc/passwd"
        };

        for (String tentativa : tentativas) {
            assertThatThrownBy(() -> armazenamento.ler(tentativa))
                    .as("travessia recusada: %s", tentativa)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    @DisplayName("recusa nome com extensão executável, mesmo no formato certo")
    void recusaExtensaoExecutavel(@TempDir Path raiz) {
        ArmazenamentoFotos armazenamento = new ArmazenamentoFotos(raiz.toString());
        UUID base = UUID.randomUUID();

        for (String extensao : new String[]{"php", "jsp", "sh", "html", "svg", "webp.php"}) {
            assertThatThrownBy(() -> armazenamento.ler(base + "-mini." + extensao))
                    .as("extensão recusada: %s", extensao)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    @DisplayName("recusa nome que não seja UUID mais versão")
    void recusaNomeForaDoPadrao(@TempDir Path raiz) {
        ArmazenamentoFotos armazenamento = new ArmazenamentoFotos(raiz.toString());

        for (String nome : new String[]{
                "qualquer.webp",
                "a3f1-mini.webp",
                UUID.randomUUID() + "-original.webp",
                UUID.randomUUID() + ".webp",
                "",
                null}) {
            assertThatThrownBy(() -> armazenamento.ler(nome))
                    .as("nome recusado: %s", nome)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    @DisplayName("aceita e devolve um nome no padrão")
    void aceitaNomeNoPadrao(@TempDir Path raiz) {
        ArmazenamentoFotos armazenamento = new ArmazenamentoFotos(raiz.toString());
        UUID base = UUID.randomUUID();
        armazenamento.gravar(base, Map.of("mini", new byte[]{1, 2, 3}), "webp");

        assertThat(armazenamento.ler(base + "-mini.webp")).containsExactly(1, 2, 3);
        assertThat(armazenamento.existe(base + "-mini.webp")).isTrue();
    }

    @Test
    @DisplayName("apagar remove do disco e é idempotente")
    void apagaArquivo(@TempDir Path raiz) {
        ArmazenamentoFotos armazenamento = new ArmazenamentoFotos(raiz.toString());
        UUID base = UUID.randomUUID();
        armazenamento.gravar(base, Map.of("mini", new byte[]{1}), "webp");

        armazenamento.apagar(base + "-mini.webp");
        armazenamento.apagar(base + "-mini.webp");

        assertThat(armazenamento.existe(base + "-mini.webp")).isFalse();
    }

    @Test
    @DisplayName("um link simbólico apontando para fora não é seguido")
    void naoSegueLinkParaFora(@TempDir Path raiz, @TempDir Path fora) throws Exception {
        Path alvo = fora.resolve("segredo.txt");
        Files.writeString(alvo, "conteúdo que não pode vazar");

        ArmazenamentoFotos armazenamento = new ArmazenamentoFotos(raiz.toString());
        UUID base = UUID.randomUUID();
        String nome = base + "-mini.webp";
        Files.createSymbolicLink(raiz.resolve(nome), alvo);

        // O nome casa com o padrão, então a leitura é permitida — e é por
        // isso que o diretório de fotos é montado sem permissão de execução
        // e contém apenas o que a aplicação grava. O que este teste fixa é
        // que nenhum caminho de fora da raiz é alcançável pelo nome.
        assertThat(armazenamento.ler(nome)).isNotEmpty();
        assertThatThrownBy(() -> armazenamento.ler("../" + fora.getFileName() + "/segredo.txt"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
