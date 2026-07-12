package com.IusCloud.msia.shared.exceptions;

/**
 * Se lanza cuando se detecta un intento de manipulación del asistente (prompt
 * injection) en el texto del usuario. La solicitud se rechaza sin invocar al modelo.
 */
public class PromptInjectionException extends RuntimeException {
    public PromptInjectionException(String message) {
        super(message);
    }
}
