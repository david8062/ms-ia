package com.IusCloud.msia.core.common.ai;

/**
 * Un turno de la conversación que se reenvía a Claude como contexto.
 *
 * @param assistant {@code true} si el turno lo produjo el modelo, {@code false} si fue el usuario.
 * @param content   texto del turno.
 */
public record ChatTurn(boolean assistant, String content) {
}
