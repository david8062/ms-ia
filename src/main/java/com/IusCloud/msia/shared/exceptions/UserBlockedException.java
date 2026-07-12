package com.IusCloud.msia.shared.exceptions;

/**
 * Se lanza cuando un usuario superó el umbral de intentos de uso indebido y queda
 * temporalmente bloqueado de las funciones de IA (cooldown auto-liberable).
 */
public class UserBlockedException extends RuntimeException {
    public UserBlockedException(String message) {
        super(message);
    }
}
