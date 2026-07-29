/**
 * Healthcare Treatment Journey service.
 *
 * <p>Layering follows the base framework's convention:
 * <ul>
 *   <li>{@code domain/} — clinical model and rules; no Spring, no Camunda, no JPA.</li>
 *   <li>{@code application/} — use cases orchestrating the domain, plus outbound ports.</li>
 *   <li>{@code infrastructure/} — adapters: Camunda job workers, JPA persistence, wiring.</li>
 * </ul>
 *
 * <p>Dependencies point inwards only: infrastructure depends on application, application
 * depends on domain, and domain depends on nothing.
 */
package com.aaseya.healthcare;
