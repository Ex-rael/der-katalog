package br.com.chimaclub.config_loja;

import br.com.chimaclub.BancoDeTesteBase;
import br.com.chimaclub.admin.EventoAuditoria;
import br.com.chimaclub.admin.EventoAuditoriaRepository;
import br.com.chimaclub.comum.RegraDeNegocioException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfiguracaoServiceTest extends BancoDeTesteBase {

    @Autowired ConfiguracaoService servico;
    @Autowired EventoAuditoriaRepository eventos;

    @Test
    @DisplayName("as chaves semeadas pela V2 são lidas")
    void leOSemeadoPelaMigracao() {
        assertThat(servico.valor(ConfiguracaoService.WHATSAPP_NUMERO)).isEqualTo("5551989250481");
        assertThat(servico.valor(ConfiguracaoService.LOJA_NOME)).isEqualTo("Chima Club Artefatos");
    }

    @Test
    @DisplayName("grava um número novo e registra o anterior na auditoria")
    void gravaNumeroNovoComTrilha() {
        servico.gravar(Map.of(ConfiguracaoService.WHATSAPP_NUMERO, "5551999998888"), null, null);

        assertThat(servico.valor(ConfiguracaoService.WHATSAPP_NUMERO)).isEqualTo("5551999998888");

        String detalhes = eventos.findAll().stream()
                .filter(evento -> "CONFIGURACAO_ALTERADA".equals(evento.getAcao()))
                .map(evento -> String.valueOf(evento.getDetalhes()))
                .reduce("", String::concat);

        assertThat(detalhes)
                .as("trocar o número é o caminho de um golpe; a trilha precisa mostrar o de antes")
                .contains("5551989250481")
                .contains("5551999998888");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "5551 98925-0481",
            "+5551989250481",
            "(51) 98925-0481",
            "98925-0481",
            "abcdefghijkl",
            "55519892504810000",
            "123"
    })
    @DisplayName("recusa número de WhatsApp fora do formato")
    void recusaNumeroForaDoFormato(String numero) {
        assertThatThrownBy(() ->
                servico.gravar(Map.of(ConfiguracaoService.WHATSAPP_NUMERO, numero), null, null))
                .isInstanceOf(RegraDeNegocioException.class);

        assertThat(servico.valor(ConfiguracaoService.WHATSAPP_NUMERO))
                .as("o número antigo continua valendo enquanto o novo não é aceito")
                .isEqualTo("5551989250481");
    }

    @ParameterizedTest
    @ValueSource(strings = {"chima club", "@chimaclub", "https://instagram.com/chimaclub", "chima/club"})
    @DisplayName("recusa perfil de Instagram fora do formato")
    void recusaInstagramForaDoFormato(String perfil) {
        assertThatThrownBy(() ->
                servico.gravar(Map.of(ConfiguracaoService.INSTAGRAM, perfil), null, null))
                .isInstanceOf(RegraDeNegocioException.class);
    }

    @Test
    @DisplayName("recusa quebra de linha, que é sinal de tentativa de injeção em cabeçalho")
    void recusaQuebraDeLinha() {
        assertThatThrownBy(() -> servico.gravar(
                Map.of(ConfiguracaoService.LOJA_NOME, "Chima Club\r\nX-Injetado: sim"), null, null))
                .isInstanceOf(RegraDeNegocioException.class);
    }

    @Test
    @DisplayName("recusa texto acima do limite")
    void recusaTextoLongo() {
        assertThatThrownBy(() -> servico.gravar(
                Map.of(ConfiguracaoService.WHATSAPP_MENSAGEM, "x".repeat(301)), null, null))
                .isInstanceOf(RegraDeNegocioException.class);
    }

    @Test
    @DisplayName("gravar o mesmo valor não gera evento de auditoria")
    void naoAuditaGravacaoSemMudanca() {
        long antes = eventos.count();

        servico.gravar(Map.of(ConfiguracaoService.WHATSAPP_NUMERO, "5551989250481"), null, null);

        assertThat(eventos.count())
                .as("ruído na auditoria atrapalha a revisão mensal tanto quanto a falta dela")
                .isEqualTo(antes);
    }

    @Test
    @DisplayName("uma chave inválida no lote não grava nenhuma das outras")
    void loteInvalidoNaoGravaNada() {
        assertThatThrownBy(() -> servico.gravar(Map.of(
                ConfiguracaoService.LOJA_NOME, "Nome novo",
                ConfiguracaoService.WHATSAPP_NUMERO, "numero errado"), null, null))
                .isInstanceOf(RegraDeNegocioException.class);

        assertThat(servico.valor(ConfiguracaoService.LOJA_NOME))
                .as("validar tudo antes de gravar qualquer coisa")
                .isEqualTo("Chima Club Artefatos");
    }

    @Test
    @DisplayName("nenhum detalhe de auditoria guarda algo sensível")
    void auditoriaDeConfiguracaoNaoGuardaSegredo() {
        servico.gravar(Map.of(ConfiguracaoService.LOJA_LEMA, "Lema novo"), null, null);

        assertThat(eventos.findAll())
                .extracting(EventoAuditoria::getDetalhes)
                .allSatisfy(detalhes ->
                        assertThat(String.valueOf(detalhes)).doesNotContainIgnoringCase("senha"));
    }
}
