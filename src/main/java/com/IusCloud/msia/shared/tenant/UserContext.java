package com.IusCloud.msia.shared.tenant;

import java.util.UUID;

public final class UserContext {

    private static final ThreadLocal<UUID> CURRENT_USER = new ThreadLocal<>();

    private UserContext() {}

    public static void setUserId(UUID userId) {
        CURRENT_USER.set(userId);
    }

    public static UUID getUserId() {
        return CURRENT_USER.get();
    }

    public static boolean isAuthenticated() {
        return CURRENT_USER.get() != null;
    }

    public static void clear() {
        CURRENT_USER.remove();
    }
}
