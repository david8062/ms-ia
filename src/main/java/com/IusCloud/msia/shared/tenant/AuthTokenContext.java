package com.IusCloud.msia.shared.tenant;

/**
 * Guarda el token JWT crudo de la petición actual (ThreadLocal), para poder
 * reenviarlo en llamadas internas a otros microservicios (p. ej. legalCore para
 * obtener una presigned URL). Lo puebla {@code TenantFilter} y lo limpia en finally.
 */
public final class AuthTokenContext {

    private static final ThreadLocal<String> CURRENT_TOKEN = new ThreadLocal<>();

    private AuthTokenContext() {}

    public static void setToken(String token) {
        CURRENT_TOKEN.set(token);
    }

    public static String getToken() {
        return CURRENT_TOKEN.get();
    }

    public static void clear() {
        CURRENT_TOKEN.remove();
    }
}
