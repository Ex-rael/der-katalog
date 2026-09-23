package br.com.chimaclub.catalogo.web;

import br.com.chimaclub.midia.ArmazenamentoFotos;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.UncheckedIOException;
import java.time.Duration;

import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * Serve as fotos do catálogo. É a única rota pública que devolve arquivo do
 * disco, e por isso concentra três cuidados (§A01 e §A08):
 *
 * o nome é conferido contra o padrão e o caminho resolvido é comparado com a
 * raiz permitida, pelo ArmazenamentoFotos;
 *
 * o Content-Type é fixo, derivado da extensão que o sistema gerou e nunca do
 * arquivo enviado, com nosniff para o navegador não adivinhar outro;
 *
 * a resposta é sempre inline, jamais anexo, para que abrir o endereço não
 * vire download de algo executável.
 */
@RestController
public class FotoController {

    private final ArmazenamentoFotos armazenamento;

    public FotoController(ArmazenamentoFotos armazenamento) {
        this.armazenamento = armazenamento;
    }

    @GetMapping("/fotos/{arquivo}")
    public ResponseEntity<byte[]> servir(@PathVariable String arquivo) {
        byte[] bytes;
        try {
            bytes = armazenamento.ler(arquivo);
        } catch (IllegalArgumentException | UncheckedIOException naoExiste) {
            // Nome fora do padrão e arquivo inexistente respondem igual, de
            // propósito: distinguir os dois diria ao sondador quando um nome
            // é "válido mas ausente", o que é um oráculo desnecessário.
            throw new ResponseStatusException(NOT_FOUND);
        }

        MediaType tipo = arquivo.endsWith(".webp")
                ? MediaType.parseMediaType("image/webp")
                : MediaType.IMAGE_JPEG;

        return ResponseEntity.ok()
                .contentType(tipo)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                .header("X-Content-Type-Options", "nosniff")
                // O nome carrega um UUID novo a cada upload, então o conteúdo
                // de um nome nunca muda e o cache pode ser eterno (§4.2).
                .cacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable())
                .body(bytes);
    }
}
