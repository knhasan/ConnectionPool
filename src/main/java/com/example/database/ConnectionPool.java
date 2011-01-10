package com.example.database;

/**
 * ConnectionPool interface - provides a connection and releases it.
 *
 * @author Khandker Hasan
 */
public interface ConnectionPool {

    /**
     * Provides a database connection.
     *
     * @return java.sql.Connection
     * @throws java.sql.SQLException
     */
    public java.sql.Connection getConnection() throws java.sql.SQLException;

    /**
     * Releases a connection provided by this pool.
     *
     * @param con java.sql.Connection
     * @throws java.sql.SQLException
     */
    public void releaseConnection(java.sql.Connection con) throws java.sql.SQLException;
}
