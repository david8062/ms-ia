package com.IusCloud.msia.shared.plans.dto;

/**
 * Envoltura genérica de las respuestas de ms-plans (ApiResponse&lt;T&gt;).
 * Solo nos interesa el campo {@code data}; los demás se ignoran al deserializar.
 */
public record PlansApiResponse<T>(T data) {}
