package com.harness.core.persistence;

import java.sql.Connection;
import java.sql.SQLException;

@FunctionalInterface
public interface SqlConnectionProvider {

    Connection getConnection() throws SQLException;
}
