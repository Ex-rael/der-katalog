package br.com.chimaclub.admin;

/**
 * Os eventos que a §A09 manda registrar. O enum existe para que a lista
 * seja fechada e revisável: uma ação nova aparece aqui, não numa string
 * solta espalhada pelo código.
 */
public enum Acao {
    LOGIN_OK,
    LOGIN_FALHA,
    LOGOUT,
    CONTA_BLOQUEADA,
    TOTP_ATIVADO,
    SENHA_ALTERADA,
    PRODUTO_CRIADO,
    PRODUTO_ALTERADO,
    PRODUTO_PUBLICADO,
    PRODUTO_DESPUBLICADO,
    PRODUTO_EXCLUIDO,
    FOTO_ENVIADA,
    FOTO_EXCLUIDA,
    FOTO_REORDENADA,
    CONFIGURACAO_ALTERADA,
    ERRO_INTERNO
}
