/**
 * Healthcare Treatment Journey service.
 *
 * <p>Layering follows the base framework's convention:
 * <ul>
 *   <li>{@code domain/} — clinical model, rules and the persisted case aggregate.</li>
 *   <li>{@code application/} — use cases orchestrating the domain, plus outbound ports.</li>
 *   <li>{@code repository/} — Spring Data interfaces over the domain aggregate.</li>
 *   <li>{@code web/} — REST controllers and their DTOs; no Camunda types cross this boundary.</li>
 *   <li>{@code infrastructure/} — adapters. Everything touching {@code io.camunda.client} lives
 *       under {@code infrastructure/camunda/}, job workers included; the architecture test
 *       enforces it.</li>
 *   <li>{@code config/} — cross-cutting Spring configuration.</li>
 * </ul>
 *
 * <p>Dependencies point inwards only: infrastructure depends on application, application
 * depends on domain, and domain depends on nothing.
 */
package com.aaseya.healthcare;
