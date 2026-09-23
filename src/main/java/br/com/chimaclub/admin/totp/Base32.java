package br.com.chimaclub.admin.totp;

/**
 * Base32 do RFC 4648, alfabeto padrão. É o que os aplicativos
 * autenticadores esperam no segredo, e o Java não traz no padrão.
 */
public final class Base32 {

    private static final String ALFABETO = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    private Base32() {
    }

    public static String codificar(byte[] dados) {
        StringBuilder saida = new StringBuilder();
        int acumulador = 0;
        int bitsNoAcumulador = 0;

        for (byte b : dados) {
            acumulador = (acumulador << 8) | (b & 0xFF);
            bitsNoAcumulador += 8;
            while (bitsNoAcumulador >= 5) {
                bitsNoAcumulador -= 5;
                saida.append(ALFABETO.charAt((acumulador >>> bitsNoAcumulador) & 0x1F));
            }
        }
        if (bitsNoAcumulador > 0) {
            saida.append(ALFABETO.charAt((acumulador << (5 - bitsNoAcumulador)) & 0x1F));
        }
        return saida.toString();
    }

    public static byte[] decodificar(String texto) {
        String limpo = texto.trim().replace("=", "").replace(" ", "").toUpperCase();
        byte[] saida = new byte[limpo.length() * 5 / 8];

        int acumulador = 0;
        int bitsNoAcumulador = 0;
        int posicao = 0;

        for (char caractere : limpo.toCharArray()) {
            int valor = ALFABETO.indexOf(caractere);
            if (valor < 0) {
                throw new IllegalArgumentException("caractere fora do alfabeto base32");
            }
            acumulador = (acumulador << 5) | valor;
            bitsNoAcumulador += 5;
            if (bitsNoAcumulador >= 8) {
                bitsNoAcumulador -= 8;
                saida[posicao++] = (byte) ((acumulador >>> bitsNoAcumulador) & 0xFF);
            }
        }
        return saida;
    }
}
