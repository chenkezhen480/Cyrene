package com.harness.agent.graph;

import java.sql.Connection;
import java.sql.SQLException;

@FunctionalInterface
public interface GraphSpaceAccessConnectionProvider {

    Connection getConnection() throws SQLException;
}
