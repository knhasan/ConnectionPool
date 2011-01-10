package com.example.database;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;

import org.slf4j.LoggerFactory;

/**
 * A connection pool factory class that provides connections from the underlying
 * database.
 *
 * This factory should be used with spring to configure the pool settings and
 * injected using the ConnectionPool interface. An example configuration:
 * <pre>
 * {@code
 * <bean id="pool" class="com.example.database.ConnectionPoolFactory">
 *     <constructor-arg name="driver" value="some.driver.class"/>
 *     <constructor-arg name="url" value="jdbc:example.url"/>
 *     <constructor-arg name="username" value="db.user"/>
 *     <constructor-arg name="password" value="db.password"/>
 * </bean>
 * }
 * </pre>
 * @author Khandker Hasan
 */
public class ConnectionPoolFactory implements ConnectionPool {

    //Connection parameters
    private String driver;
    private String url;
    private String username;
    private String password;
    private int maxConnections;
    private boolean lazyLoad = false;
    //Connection pools
    private List<Connection> freeConnections = null;
    private List<Connection> inUseConnections = null;
    //Defaults
    private static final boolean DEFAULT_LAZY_LOAD = false;
    private static final int DEFAULT_MAX_CONNECTION = 10;
    //Logger
    private static final Logger logger =
            LoggerFactory.getLogger(ConnectionPoolFactory.class);

    /**
     * Default constructor.
     */
    private ConnectionPoolFactory() {
    }

    /**
     * Constructor.
     *
     * @param driver String. The database driver.
     * @param url String. The database url.
     * @param username String. The database user.
     * @param password String. The database password.
     * @throws SQLException
     */
    protected ConnectionPoolFactory(String driver, String url, String username,
            String password)
            throws SQLException {
        this(driver, url, username, password, DEFAULT_MAX_CONNECTION,
                DEFAULT_LAZY_LOAD);
    }

    /**
     * Constructor.
     *
     * @param driver String. The database driver.
     * @param url String. The database url.
     * @param username String. The database user.
     * @param password String. The database password.
     * @param maxConnection int. Maximum number of connections.
     * @throws SQLException
     */
    protected ConnectionPoolFactory(String driver, String url, String username,
            String password, int maxConnection)
            throws SQLException {
        this(driver, url, username, password, maxConnection, DEFAULT_LAZY_LOAD);
    }

    /**
     * Constructor.
     *
     * @param driver String. The database driver.
     * @param url String. The database url.
     * @param username String. The database user.
     * @param password String. The database password.
     * @param lazyLoad boolean. The flag for lazy loading of connections.
     * @throws SQLException
     */
    protected ConnectionPoolFactory(String driver, String url, String username,
            String password, boolean lazyLoad)
            throws SQLException {
        this(driver, url, username, password, DEFAULT_MAX_CONNECTION, lazyLoad);
    }

    /**
     *  Constructor.
     *
     * @param driver String. The database driver.
     * @param url String. The database url.
     * @param username String. The database user.
     * @param password String. The database password.
     * @param maxConnection int. Maximum number of connections.
     * @param lazyLoad boolean. The flag for lazy loading of connections.
     * @throws SQLException
     */
    protected ConnectionPoolFactory(String driver, String url, String username,
            String password, int maxConnection, boolean lazyLoad)
            throws SQLException {
        this.driver = driver;
        this.maxConnections = maxConnection;
        this.lazyLoad = lazyLoad;
        this.url = url;
        this.username = username;
        this.password = password;
        logger.info("Initializing connection pool with - |Driver:" + driver
                + "|Url:" + url
                + "|Username:" + username
                + "|Password:" + password
                + "|MaxConnection:" + maxConnection
                + "|LazyLoad:" + lazyLoad + "|");
        init();
    }

    @Override
    public synchronized Connection getConnection() throws SQLException {
        logger.trace("In Connection getConnection()");
        Connection c = null;
        if (freeConnections.size() > 0) {
            c = freeConnections.remove(0);
        } else if (inUseConnections.size() < maxConnections) {
            c = getProxyConnection();
        } else {
            throw new SQLException(Errors.MAX_CONNECTION_REACHED);
        }
        if (c != null && Proxy.isProxyClass(c.getClass())) {
            ProxyConnection pc = (ProxyConnection) Proxy.getInvocationHandler(c);
            if (pc.c == null || pc.c.isClosed()) {
                pc.c = getDbConnection();
            }
            pc.closed = false;
        }
        //If there are no exceptions, add the connection to in use pool
        inUseConnections.add(c);
        debug("After getConnection() - free: " + freeConnections.size()
                + " in use: " + inUseConnections.size());
        return c;
    }

    @Override
    public synchronized void releaseConnection(Connection c)
            throws SQLException {
        logger.trace("In void releaseConnection(Connection c)");
        if (c != null) {
            if (Proxy.isProxyClass(c.getClass())
                    && Proxy.getInvocationHandler(c) instanceof ProxyConnection) {
                ProxyConnection pc =
                        (ProxyConnection) Proxy.getInvocationHandler(c);
                pc.closed = true;
                inUseConnections.remove(c);
                freeConnections.add(c);
            } else {
                logger.warn("Attempting to close a connection which was"
                        + " not provided by this pool!");
                try {
                    c.close();
                } catch (Throwable t) {
                    throw new SQLException(t);
                }
            }
        }
        debug("After releaseConnection() - free: " + freeConnections.size()
                + " in use: " + inUseConnections.size());
    }

    /**
     * Initializes this pool. Registers the driver, initializes the pool arrays
     * and caches the connections if not lazy loaded.
     *
     * @throws SQLException
     */
    private void init() throws SQLException {
        //Register the driver
        try {
            DriverManager.registerDriver(
                    (Driver) Class.forName(driver).newInstance());
        } catch (Throwable t) {
            logger.error(Errors.FAIL_REGISTER_DRIVER, t);
            throw new SQLException(Errors.FAIL_REGISTER_DRIVER, t);
        }
        //Create the arrays
        freeConnections = new ArrayList<Connection>(maxConnections);
        inUseConnections = new ArrayList<Connection>(maxConnections);
        //Load the connections if not lazy loaded
        if (!lazyLoad) {
            for (int i = 0; i < maxConnections; i++) {
                Connection p = getProxyConnection();
                freeConnections.add(p);
            }
        }
    }

    /**
     * Returns a proxy connection maintained by this pool.
     *
     * @return c java.sql.Connection
     * @throws SQLException
     */
    private Connection getProxyConnection() throws SQLException {
        try {
            Connection c = getDbConnection();
            Object p = Proxy.newProxyInstance(c.getClass().getClassLoader(),
                    new Class[]{java.sql.Connection.class},
                    new ProxyConnection(c, this));
            return (Connection) p;
        } catch (Throwable t) {
            if (t instanceof SQLException) {
                throw (SQLException) t;
            } else {
                logger.error(Errors.FAIL_CONNECTION, t);
                throw new SQLException(Errors.FAIL_CONNECTION, t);
            }
        }
    }

    /**
     * Returns an actual database connection from the provided database
     * properties.
     *
     * @return c java.sql.Connection
     * @throws SQLException
     */
    private Connection getDbConnection() throws SQLException {
        try {
            Connection c = DriverManager.getConnection(url, username, password);
            return c;
        } catch (Throwable t) {
            logger.error(Errors.FAIL_CONNECTION, t);
            throw new SQLException(Errors.FAIL_CONNECTION, t);
        }
    }

    /**
     * A non-threadsafe method to return the number of free connections. Used
     * for unit testing.
     * 
     * @return int Free connections.
     */
    int free() {
        return freeConnections.size();
    }

    /**
     * A non-threadsafe method to return the number of connections in use. Used
     * for unit testing.
     *
     * @return int Connections in use.
     */
    int inUse() {
        return inUseConnections.size();
    }

    /**
     * A utility method for unit testing.
     *
     * @throws SQLException
     */
    void releaseAll() throws SQLException {
        for (Connection c : new ArrayList<Connection>(inUseConnections)) {
            releaseConnection(c);
        }
    }

    //Utility method for debug
    private void debug(String msg) {
        if (logger.isDebugEnabled()) {
            logger.debug(msg);
        }
    }

    //Error definitions
    final class Errors {

        public static final String MAX_CONNECTION_REACHED =
                "Maximum number of connections reached!";
        public static final String CONNECTION_CLOSED =
                "Connection is closed!";
        public static final String FAIL_REGISTER_DRIVER =
                "Failed to register driver!";
        public static final String FAIL_CONNECTION =
                "Failed to get connection!";
    }

    /**
     * A proxy class that has java.sql.Connection interface as one of the proxy
     * interfaces. This class is returned when a request for a connection is
     * made to this pool.
     */
    final class ProxyConnection implements InvocationHandler {

        Connection c = null;
        ConnectionPoolFactory cpf = null;
        boolean closed = true;
        //Constants
        private final String CLOSE_METHOD = "close";
        private final String EQUALS_METHOD = "equals";
        private final String HASHCODE_METHOD = "hashCode";
        private final String IS_CLOSED_METHOD = "isClosed";

        /**
         * Constructor
         *
         * @param c java.sql.Connection
         * @param cpf com.example.database.ConnectionPoolFactory
         */
        public ProxyConnection(Connection c, ConnectionPoolFactory cpf) {
            this.c = c;
            this.cpf = cpf;
        }

        @Override
        public boolean equals(Object o) {
            return o == null ? false : this.hashCode() == o.hashCode();
        }

        @Override
        public int hashCode() {
            int hash = 7;
            hash = 41 * hash + (this.c != null ? this.c.hashCode() : 0);
            hash = 41 * hash + (this.cpf != null ? this.cpf.hashCode() : 0);
            return hash;
        }

        @Override
        public Object invoke(Object o, Method method, Object[] args)
                throws Throwable {
            //Return the compare result of this proxy connection
            if (method.getName().equals(EQUALS_METHOD)) {
                return this.equals(o);
            }
            //Return generated hash code of this proxy connection
            if (method.getName().equals(HASHCODE_METHOD)) {
                return this.hashCode();
            }
            //Return the closed status of the proxy connection instead of the
            //database connection
            if (method.getName().equals(IS_CLOSED_METHOD)) {
                return closed;
            }
            logger.trace("In invoke(Object o, Method method, Object[] args) "
                    + "for method " + method.getName());
            try {
                if (!closed) {
                    if (method.getName().equals(CLOSE_METHOD)) {
                        logger.warn("Attempting to close a connection without "
                                + "using the corresponding pool, using the pool"
                                + " to close!");
                        cpf.releaseConnection((Connection) o);
                        return null;
                    } else {
                        return method.invoke(c, args);
                    }
                } else {
                    throw new Throwable(new SQLException(Errors.CONNECTION_CLOSED));
                }
            } catch (Throwable t) {
                throw t.getCause();
            }
        }
    }
}
