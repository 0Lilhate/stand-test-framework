package ru.alfa.stand.test.db;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A {@link Connection} test double built on a {@link Proxy}: implementing the whole JDBC surface by hand is
 * impractical and the project ships no mocking framework. It optionally fails {@code setAutoCommit} and/or
 * {@code close}, and counts {@code close()} calls, so a test can prove the executor registers the connection
 * for the run before configuring it (and closes it exactly once, with no leak) and that a driver close
 * failure is wrapped/aggregated. Every other method returns a type-appropriate default; the executor only
 * invokes {@code setAutoCommit} and {@code close} in the scenarios under test.
 */
final class FakeConnection {

    private final boolean failOnSetAutoCommit;
    private final boolean failOnClose;
    private final AtomicInteger closeCount = new AtomicInteger();
    private final Connection connection;

    FakeConnection(boolean failOnSetAutoCommit) {
        this(failOnSetAutoCommit, false);
    }

    FakeConnection(boolean failOnSetAutoCommit, boolean failOnClose) {
        this.failOnSetAutoCommit = failOnSetAutoCommit;
        this.failOnClose = failOnClose;
        this.connection = (Connection) Proxy.newProxyInstance(
                FakeConnection.class.getClassLoader(),
                new Class<?>[] {Connection.class},
                (proxy, method, args) -> invoke(proxy, method.getName(), method.getReturnType(), args));
    }

    Connection connection() {
        return this.connection;
    }

    int closeCount() {
        return this.closeCount.get();
    }

    private Object invoke(Object proxy, String method, Class<?> returnType, Object[] args) throws SQLException {
        return switch (method) {
            case "setAutoCommit" -> setAutoCommit();
            case "close" -> close();
            case "isClosed" -> this.closeCount.get() > 0;
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            case "toString" -> "FakeConnection";
            default -> defaultValue(returnType);
        };
    }

    private Object setAutoCommit() throws SQLException {
        if (this.failOnSetAutoCommit) {
            throw new SQLException("setAutoCommit is not supported by this connection");
        }
        return null;
    }

    private Object close() throws SQLException {
        this.closeCount.incrementAndGet();
        if (this.failOnClose) {
            throw new SQLException("close failed");
        }
        return null;
    }

    private static Object defaultValue(Class<?> returnType) {
        if (returnType == boolean.class) {
            return false;
        }
        if (returnType == int.class) {
            return 0;
        }
        if (returnType == long.class) {
            return 0L;
        }
        return null;
    }
}
