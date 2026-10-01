package ru.alfa.stand.test.eq.backend.gateway;

/**
 * Reads the current AS/400 unit phase through a read-only call sequence.
 *
 * <p>It is an interface so the gateway's phase precondition can be tested without a live AS/400. The
 * production implementation is {@link Jt400UnitPhaseReader}, which reproduces the reference library's
 * sequence ({@code LIBL <unit>} + {@code CALL PGM(UAA37R)} then a {@code ProgramCall} of
 * {@code ALFAINSTAL/MONUNTSTS} reading the phase from the program's output parameter). No command that
 * changes state is issued, and a credential is never interpolated into a CL command string (SEC-05, Г-9).
 */
public interface UnitPhaseReader {

    /**
     * Reads the unit phase.
     *
     * @param system the AS/400 system reference (resolved host name, never a secret)
     * @param unit the unit whose phase is read (part of the library selection and the program input)
     * @param username the resolved user name (never secret, but validated before any CL interpolation)
     * @param password the resolved password (never logged, never interpolated, used only to connect)
     * @return the raw phase value as the stand reports it
     */
    String readPhase(String system, String unit, String username, String password);
}