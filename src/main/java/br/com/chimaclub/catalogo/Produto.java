package br.com.chimaclub.catalogo;

import br.com.chimaclub.comum.GeradorDeId;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "produto")
public class Produto {

    @Id
    private UUID id = GeradorDeId.novo();

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "categoria_id")
    private Categoria categoria;

    @Column(nullable = false, length = 140)
    private String nome;

    @Column(nullable = false, length = 160, unique = true)
    private String slug;

    @Column(columnDefinition = "text")
    private String descricao;

    /** Sempre em centavos. Ponto flutuante para dinheiro é proibido. */
    @Column(name = "preco_centavos", nullable = false)
    private long precoCentavos;

    @Column(nullable = false)
    private int unidades;

    @Column(nullable = false)
    private boolean publicado;

    @Column(nullable = false)
    private boolean destaque;

    @Column(nullable = false)
    private int ordem;

    /**
     * Bloqueio otimista: impede que duas abas do painel sobrescrevam uma à
     * outra em silêncio. A segunda gravação é recusada, não perdida.
     */
    @Version
    @Column(nullable = false)
    private long versao;

    @Column(name = "criado_em", nullable = false, updatable = false)
    private Instant criadoEm = Instant.now();

    @Column(name = "atualizado_em", nullable = false)
    private Instant atualizadoEm = Instant.now();

    /** Exclusão é sempre lógica: nada some do banco. */
    @Column(name = "excluido_em")
    private Instant excluidoEm;

    protected Produto() {
        // exigido pelo JPA
    }

    public Produto(String nome, String slug, long precoCentavos) {
        this.nome = nome;
        this.slug = slug;
        this.precoCentavos = precoCentavos;
    }

    @PreUpdate
    void aoAtualizar() {
        this.atualizadoEm = Instant.now();
    }

    public UUID getId() { return id; }
    public Categoria getCategoria() { return categoria; }
    public void setCategoria(Categoria categoria) { this.categoria = categoria; }
    public String getNome() { return nome; }
    public void setNome(String nome) { this.nome = nome; }
    public String getSlug() { return slug; }
    public void setSlug(String slug) { this.slug = slug; }
    public String getDescricao() { return descricao; }
    public void setDescricao(String descricao) { this.descricao = descricao; }
    public long getPrecoCentavos() { return precoCentavos; }
    public void setPrecoCentavos(long precoCentavos) { this.precoCentavos = precoCentavos; }
    public int getUnidades() { return unidades; }
    public void setUnidades(int unidades) { this.unidades = unidades; }
    public boolean isPublicado() { return publicado; }
    public void setPublicado(boolean publicado) { this.publicado = publicado; }
    public boolean isDestaque() { return destaque; }
    public void setDestaque(boolean destaque) { this.destaque = destaque; }
    public int getOrdem() { return ordem; }
    public void setOrdem(int ordem) { this.ordem = ordem; }
    public long getVersao() { return versao; }
    public Instant getCriadoEm() { return criadoEm; }
    public Instant getAtualizadoEm() { return atualizadoEm; }
    public Instant getExcluidoEm() { return excluidoEm; }
    public void setExcluidoEm(Instant excluidoEm) { this.excluidoEm = excluidoEm; }
}
