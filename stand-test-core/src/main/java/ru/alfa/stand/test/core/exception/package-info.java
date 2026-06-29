/**
 * Failure-semantics exceptions for the stand-test SDK.
 *
 * <p>{@link ru.alfa.stand.test.core.exception.StandTestAssertionError} represents an SDK-level
 * assertion failure and extends {@link java.lang.AssertionError} so that JUnit and Allure treat it
 * natively as a failed test. {@link ru.alfa.stand.test.core.exception.StandTestException} represents
 * an infrastructure or configuration failure and is an unchecked exception.
 */
package ru.alfa.stand.test.core.exception;
