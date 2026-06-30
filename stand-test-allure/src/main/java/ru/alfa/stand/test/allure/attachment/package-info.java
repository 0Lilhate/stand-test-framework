/**
 * Generic, transport-agnostic attachment publishing.
 *
 * <p>{@link ru.alfa.stand.test.allure.attachment.AttachmentType} enumerates the generic kinds
 * (TEXT/JSON/XML/SQL/BINARY/KEY_VALUE) and maps media types to file extensions;
 * {@link ru.alfa.stand.test.allure.attachment.AllureAttachmentPublisher} publishes core
 * {@link ru.alfa.stand.test.core.event.Attachment}s verbatim and renders key/value diagnostics as masked
 * text. There are deliberately no REST/Kafka/DB-specific attachment types (plan §8.9).
 */
package ru.alfa.stand.test.allure.attachment;
