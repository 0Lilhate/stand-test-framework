/**
 * Secret masking at the reporting sink.
 *
 * <p>{@link ru.alfa.stand.test.allure.masking.SecretMasker} replaces the value of any entry whose key
 * names a secret (password/secret/token/authorization/apiKey/cookie, case-insensitive) before key/value
 * blocks are published to Allure — a defence-in-depth net complementing source-side redaction by the
 * producing adapters.
 */
package ru.alfa.stand.test.allure.masking;
