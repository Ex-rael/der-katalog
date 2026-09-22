package br.com.chimaclub.comum;

import com.github.f4b6a3.uuid.UuidCreator;

import java.util.UUID;

/**
 * UUID v7: ordenado no tempo, o que dá boa localidade em índice, e sem
 * revelar quantos produtos existem, como um identificador sequencial
 * revelaria. O PostgreSQL só oferece v4, então a geração fica aqui.
 */
public final class GeradorDeId {

    private GeradorDeId() {
    }

    public static UUID novo() {
        return UuidCreator.getTimeOrderedEpoch();
    }
}
