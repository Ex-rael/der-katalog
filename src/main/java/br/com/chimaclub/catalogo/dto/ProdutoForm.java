package br.com.chimaclub.catalogo.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * O que o formulário do painel envia. Os limites daqui espelham os da
 * migração: quando a validação e o banco discordam, é o banco que recusa, e
 * a dona da loja vê um erro interno em vez de uma mensagem útil.
 */
public class ProdutoForm {

    @NotBlank(message = "Informe o nome do produto.")
    @Size(max = 140, message = "O nome pode ter no máximo 140 caracteres.")
    private String nome;

    /**
     * Endereço do produto no catálogo (§6.2).
     *
     * Vazio na criação: sai do nome. Na edição vem preenchido, e trocá-lo é
     * ação explícita de quem está na tela — o slug não acompanha a edição do
     * nome justamente para não quebrar links já compartilhados no WhatsApp.
     */
    @Size(max = 160, message = "O endereço pode ter no máximo 160 caracteres.")
    private String slug;

    private UUID categoriaId;

    @Size(max = 4000, message = "A descrição pode ter no máximo 4000 caracteres.")
    private String descricao;

    @NotBlank(message = "Informe o preço.")
    private String precoEmReais;

    @PositiveOrZero(message = "As unidades não podem ser negativas.")
    private int unidades;

    private boolean publicado;
    private boolean destaque;
    private int ordem;

    /** Bloqueio otimista: vem do campo oculto do formulário. */
    private long versao;

    public String getNome() { return nome; }
    public void setNome(String nome) { this.nome = nome; }
    public String getSlug() { return slug; }
    public void setSlug(String slug) { this.slug = slug; }
    public UUID getCategoriaId() { return categoriaId; }
    public void setCategoriaId(UUID categoriaId) { this.categoriaId = categoriaId; }
    public String getDescricao() { return descricao; }
    public void setDescricao(String descricao) { this.descricao = descricao; }
    public String getPrecoEmReais() { return precoEmReais; }
    public void setPrecoEmReais(String precoEmReais) { this.precoEmReais = precoEmReais; }
    public int getUnidades() { return unidades; }
    public void setUnidades(int unidades) { this.unidades = unidades; }
    public boolean isPublicado() { return publicado; }
    public void setPublicado(boolean publicado) { this.publicado = publicado; }
    public boolean isDestaque() { return destaque; }
    public void setDestaque(boolean destaque) { this.destaque = destaque; }
    public int getOrdem() { return ordem; }
    public void setOrdem(int ordem) { this.ordem = ordem; }
    public long getVersao() { return versao; }
    public void setVersao(long versao) { this.versao = versao; }
}
