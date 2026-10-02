package ru.alfa.stand.test.eq.backend.gateway;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import ru.alfa.stand.test.core.exception.StandTestException;

public final class Jt400UnitPhaseReader implements UnitPhaseReader {

    private static final String AS400 = "com.ibm.as400.access.AS400";
    private static final String COMMAND_CALL = "com.ibm.as400.access.CommandCall";
    private static final String PROGRAM_CALL = "com.ibm.as400.access.ProgramCall";
    private static final String PROGRAM_PARAMETER = "com.ibm.as400.access.ProgramParameter";
    private static final String AS400_TEXT = "com.ibm.as400.access.AS400Text";
    private static final String PROGRAM = "ALFAINSTAL/MONUNTSTS";
    private static final int UNIT_LENGTH = 3;
    private static final int PHASE_LENGTH = 4;

    @Override
    public String readPhase(String system, String unit, String username, String password) {
        if (system == null || system.isBlank()) {
            throw new StandTestException("EQ unit-phase system reference is not set");
        }
        if (unit == null || !unit.matches("[A-Za-z0-9]{1," + UNIT_LENGTH + "}")) {
            throw new StandTestException("EQ unit-phase unit must be 1-" + UNIT_LENGTH
                    + " alphanumeric characters, never a command fragment");
        }
        if (username == null || !username.matches("[A-Za-z0-9._-]{1,23}")) {
            throw new StandTestException("EQ unit-phase username has characters unsafe for a CL command"
                    + " (SEC-05, Г-9); it is never interpolated unvalidated");
        }
        Class<?> as400Class = load(AS400);
        Class<?> commandCallClass = load(COMMAND_CALL);
        Class<?> programCallClass = load(PROGRAM_CALL);
        Class<?> parameterClass = load(PROGRAM_PARAMETER);
        Class<?> textClass = load(AS400_TEXT);
        Object as400 = null;
        try {
            as400 = as400Class.getConstructor(String.class, String.class, String.class)
                    .newInstance(system, username, password);
            int ccsid = (Integer) as400Class.getMethod("getCcsid").invoke(as400);
            prepareLibraryList(commandCallClass, as400, unit, username);
            return readPhaseParameter(programCallClass, parameterClass, textClass, as400, ccsid, unit);
        } catch (NoSuchMethodException | InstantiationException | IllegalAccessException failure) {
            throw new StandTestException("EQ unit-phase read failed: jt400 API mismatch", failure);
        } catch (InvocationTargetException failure) {
            throw failure(failure);
        } finally {
            disconnect(as400Class, as400);
        }
    }

    private static void prepareLibraryList(Class<?> commandCallClass, Object as400, String unit, String username)
            throws NoSuchMethodException, InstantiationException, IllegalAccessException, InvocationTargetException {
        Object command = commandCallClass.getConstructor(load(AS400)).newInstance(as400);
        Method run = commandCallClass.getMethod("run", String.class);
        run.invoke(command, "LIBL " + unit);
        run.invoke(command, "CALL PGM(UAA37R) PARM('" + pad(username) + "')");
    }

    private static String readPhaseParameter(Class<?> programCallClass, Class<?> parameterClass, Class<?> textClass,
                                             Object as400, int ccsid, String unit)
            throws NoSuchMethodException, InstantiationException, IllegalAccessException, InvocationTargetException {
        Object program = programCallClass.getConstructor(load(AS400)).newInstance(as400);
        programCallClass.getMethod("setThreadSafe", boolean.class).invoke(program, false);
        Constructor<?> inputCtor = parameterClass.getConstructor(byte[].class);
        Constructor<?> outputCtor = parameterClass.getConstructor(byte[].class, int.class);
        Method toBytes = textClass.getMethod("toBytes", String.class);
        Object inputText = textClass.getConstructor(int.class, int.class).newInstance(UNIT_LENGTH, ccsid);
        Object outputText = textClass.getConstructor(int.class, int.class).newInstance(PHASE_LENGTH, ccsid);
        Object parameters = Array.newInstance(parameterClass, 2);
        Array.set(parameters, 0, inputCtor.newInstance(toBytes.invoke(inputText, unit)));
        Array.set(parameters, 1, outputCtor.newInstance(toBytes.invoke(outputText, ""), PHASE_LENGTH));
        programCallClass.getMethod("setProgram", String.class, parameters.getClass()).invoke(program, PROGRAM, parameters);
        boolean accepted = (Boolean) programCallClass.getMethod("run").invoke(program);
        if (!accepted) {
            throw new StandTestException("EQ unit-phase read of " + PROGRAM + " was rejected by the AS/400 system");
        }
        Object[] parameterList = (Object[]) programCallClass.getMethod("getParameterList").invoke(program);
        byte[] outputData = (byte[]) parameterClass.getMethod("getOutputData").invoke(parameterList[1]);
        Object phase = textClass.getMethod("toObject", byte[].class).invoke(outputText, outputData);
        return String.valueOf(phase).trim();
    }

    private static String pad(String username) {
        return String.format("%-23s", username);
    }

    private static StandTestException failure(InvocationTargetException wrapper) {
        Throwable cause = wrapper.getCause() == null ? wrapper : wrapper.getCause();
        if (cause instanceof StandTestException known) {
            return known;
        }
        return new StandTestException("EQ unit-phase read failed: " + cause.getClass().getSimpleName(), cause);
    }

    private static void disconnect(Class<?> as400Class, Object as400) {
        if (as400 == null) {
            return;
        }
        try {
            as400Class.getMethod("disconnectAllServices").invoke(as400);
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException ignored) {
            // The connection is being torn down; a failure here must not mask the read outcome.
        }
    }

    private static Class<?> load(String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException missing) {
            throw new StandTestException("EQ unit-phase is configured but jt400 is not on the classpath; add net.sf.jt400:jt400",
                    missing);
        }
    }
}