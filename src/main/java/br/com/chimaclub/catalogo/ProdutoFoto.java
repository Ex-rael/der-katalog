package br.com.chimaclub.catalogo;

import br.com.chimaclub.comum.GeradorDeId;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "produto_foto")
public class ProdutoFoto {

    @Id
    private UUID id = GeradorDeId.novo();

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "produto_id", nullable = false)
    private Produto produto;

    /** Nome gerado pelo sistema. O nome enviado pelo usuário é descartado. */
    @Column(nullable = false, length = 255)
    private String arquivo;

    @Column(name = "arquivo_mini", nullable = false, length = 255)
    private String arquivoMini;

    @Column(name = "texto_alt", length = 180)
    private String textoAlt;

    @Column(nullable = false)
    private int largura;

    @Column(nullable = false)
    private int altura;

    @Column(nullable = false)
    private long bytes;

    @Column(name = "tipo_mime", nullable = false, length = 40)
    private String tipoMime;

    @Column(nullable = false)
    private boolean principal;

    @Column(nullable = false)
    private int ordem;

    @Column(name = "criado_em", nullable = false, updatable = false)
    private Instant criadoEm = Instant.now();

    protected ProdutoFoto() {
        // exigido pelo JPA
    }

    public ProdutoFoto(Produto produto, String arquivo, String arquivoMini,
                       int largura, int altura, long bytes, String tipoMime) {
        this.produto = produto;
        this.arquivo = arquivo;
        this.arquivoMini = arquivoMini;
        this.largura = largura;
        this.altura = altura;
        this.bytes = bytes;
        this.tipoMime = tipoMime;
    }

    public UUID getId() { return id; }
    public Produto getProduto() { return produto; }
    public String getArquivo() { return arquivo; }
    public String getArquivoMini() { return arquivoMini; }
    public String getTextoAlt() { return textoAlt; }
    public void setTextoAlt(String textoAlt) { this.textoAlt = textoAlt; }
    public int getLargura() { return largura; }
    public int getAltura() { return altura; }
    public long getBytes() { return bytes; }
    public String getTipoMime() { return tipoMime; }
    public boolean isPrincipal() { return principal; }
    public void setPrincipal(boolean principal) { this.principal = principal; }
    public int getOrdem() { return ordem; }
    public void setOrdem(int ordem) { this.ordem = ordem; }
    public Instant getCriadoEm() { return criadoEm; }
}
