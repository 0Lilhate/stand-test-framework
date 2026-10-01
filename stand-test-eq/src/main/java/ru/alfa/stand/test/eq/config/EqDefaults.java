package ru.alfa.stand.test.eq.config;

import java.math.BigDecimal;

/**
 * Optional account and organisation defaults supplied by the selected backend registry entry.
 *
 * <p>{@code organisationType} is the <strong>organisation</strong> type sent as {@code GZCTP} on
 * {@code ONU} — deliberately distinct from the account type. The reference library filled {@code GZCTP}
 * with the account type (defect Г-1); the SDK refuses an organisation seed that declares no organisation
 * type rather than repeat that defect.
 *
 * <p>{@code packageRegistration} and {@code packageDuration} are the {@code GZREG} and {@code GZSROK}
 * values of a {@code KP1} package connection. They are registry values, not per-test constants.
 *
 * <p>{@code individual} carries the physical-client inputs the {@code ONF}/{@code VAD}/{@code SPU} chain
 * needs: the three name parts, the document type ({@code GZDUL}) and the client service package
 * ({@code GZP3R}). They are non-secret registry values so a test never hardcodes personal data.
 */
public record EqDefaults(String organisationNamePrefix, String organisationType, String organisationAccountType,
                         String individualAccountType, String currency, BigDecimal topUp,
                         String packageRegistration, String packageDuration, IndividualDefaults individual) {

    /** Preserves the seven-argument shape used before the package defaults were added. */
    public EqDefaults(String organisationNamePrefix, String organisationType, String organisationAccountType,
                      String individualAccountType, String currency, BigDecimal topUp) {
        this(organisationNamePrefix, organisationType, organisationAccountType, individualAccountType, currency, topUp,
                null, null, null);
    }

    /** Preserves the eight-argument shape used before the individual defaults were added. */
    public EqDefaults(String organisationNamePrefix, String organisationType, String organisationAccountType,
                      String individualAccountType, String currency, BigDecimal topUp,
                      String packageRegistration, String packageDuration) {
        this(organisationNamePrefix, organisationType, organisationAccountType, individualAccountType, currency, topUp,
                packageRegistration, packageDuration, null);
    }

    /** Physical-client inputs for the {@code ONF}/{@code VAD}/{@code SPU} chain. */
    public record IndividualDefaults(String surName, String givenName, String middleName, String documentType,
                                     String servicePackage) {
    }
}