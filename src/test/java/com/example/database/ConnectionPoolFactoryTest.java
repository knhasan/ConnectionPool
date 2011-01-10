package com.example.database;

import com.example.database.ConnectionPoolFactory.Errors;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Random;
import junit.framework.Assert;
import org.apache.log4j.NDC;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.rules.ExpectedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.support.ClassPathXmlApplicationContext;

/**
 * Unit test cases for ConnectionPoolFactory
 * 
 * @author Khandker Hasan
 */
public class ConnectionPoolFactoryTest {

    private static ApplicationContext ctx;
    private static ConnectionPool cp = null;
    private static ConnectionPoolFactory cpf = null;
    private static ConnectionPool cp_l = null;
    private static ConnectionPoolFactory cpf_l = null;
    private static ConnectionPool cp_m = null;
    private static ConnectionPoolFactory cpf_m = null;
    private static ConnectionPool cp_ml = null;
    private static ConnectionPoolFactory cpf_ml = null;

    @BeforeClass
    public static void setup() {
        ctx = new ClassPathXmlApplicationContext("applicationContext-test.xml");
        cp = ctx.getBean("testConnectionPool", ConnectionPoolFactory.class);
        cpf = (ConnectionPoolFactory) cp;
        cp_l = ctx.getBean("testConnectionPoolLL", ConnectionPoolFactory.class);
        cpf_l = (ConnectionPoolFactory) cp_l;
        cp_m = ctx.getBean("testConnectionPoolM", ConnectionPoolFactory.class);
        cpf_m = (ConnectionPoolFactory) cp_m;
        cp_ml = ctx.getBean("testConnectionPoolMLL", ConnectionPoolFactory.class);
        cpf_ml = (ConnectionPoolFactory) cp_ml;
    }
    @Rule
    public ExpectedException ee = ExpectedException.none();

    @org.junit.Test
    public void testGetConnection() throws SQLException {
        int free = cpf.free();
        int inUse = cpf.inUse();
        Connection c = cp.getConnection();
        assertFreeUse(cpf, (free == 0 ? 0 : free - 1), inUse + 1);
        cpf.releaseAll();
    }

    @org.junit.Test
    public void testReleaseConnection() throws SQLException {
        int free = cpf.free();
        int inUse = cpf.inUse();
        Connection c = cp.getConnection();
        assertFreeUse(cpf, (free == 0 ? 0 : free - 1), inUse + 1);
        cp.releaseConnection(c);
        assertFreeUse(cpf, free, inUse);
        cpf.releaseAll();
    }

    @org.junit.Test
    public void testConnectionPoolLazyLoad() throws SQLException {
        int free = cpf_l.free();
        int inUse = cpf_l.inUse();
        Connection c = cp_l.getConnection();
        assertFreeUse(cpf_l, free, inUse + 1);
        cp_l.releaseConnection(c);
        assertFreeUse(cpf_l, free + 1, (inUse == 0 ? 0 : inUse - 1));
        cpf_l.releaseAll();
    }

    @org.junit.Test
    public void testConnectionPoolMaxConnection() throws SQLException {
        ee.expect(SQLException.class);
        ee.expectMessage(Errors.MAX_CONNECTION_REACHED);
        //int free = cpf_m.free();
        int inUse = cpf_m.inUse();
        Connection c = cp_m.getConnection();
        Connection d = cp_m.getConnection();
        assertFreeUse(cpf_m, 0, inUse + 2);//Ensure that max connection is reached
        Connection e = cp_m.getConnection();
    }

    @org.junit.Test
    public void testConnectionPoolMaxConnectionLazyLoad() throws SQLException {
        ee.expect(SQLException.class);
        ee.expectMessage(Errors.MAX_CONNECTION_REACHED);
        assertFreeUse(cpf_ml, 0, 0);
        Connection c = cp_ml.getConnection();
        assertFreeUse(cpf_ml, 0, 1);
        cp_ml.releaseConnection(c);
        assertFreeUse(cpf_ml, 1, 0);
        c = cp_ml.getConnection();
        Connection d = cp_ml.getConnection();
        assertFreeUse(cpf_ml, 0, 2);
        Connection e = cp_ml.getConnection();
    }

    @org.junit.Test
    public void testCloseConnection() throws SQLException {
        int free = cpf.free();
        int inUse = cpf.inUse();
        Connection c = cp.getConnection();
        assertFreeUse(cpf, free - 1, inUse + 1);
        c.close();
        assertFreeUse(cpf, free, inUse);
        cpf.releaseAll();
    }

    @org.junit.Test
    public void testCloseJavaSqlConnection() throws SQLException {
        Connection c =
                DriverManager.getConnection("jdbc:hsqldb:mem:connectionpool", "sa", "");
        cp.releaseConnection(c);
    }

    @org.junit.Test
    public void testConnectionCloseException() throws SQLException {
        ee.expect(SQLException.class);
        ee.expectMessage(Errors.CONNECTION_CLOSED);
        Connection c = cp.getConnection();
        testStatements(c);
        cp.releaseConnection(c);
        Assert.assertTrue(c.isClosed());
        testStatements(c);
    }

    @org.junit.Test
    public void testDriverException() throws SQLException {
        ee.expect(SQLException.class);
        ee.expectMessage(Errors.FAIL_REGISTER_DRIVER);
        new ConnectionPoolFactory("", "", "", "");
    }

    @org.junit.Test
    public void testConnectionException() throws SQLException {
        ee.expect(SQLException.class);
        ee.expectMessage(Errors.FAIL_CONNECTION);
        new ConnectionPoolFactory("org.hsqldb.jdbcDriver", "jdbc:hsqldb:mem:connectionpool", "", "");
    }

    @org.junit.Test
    public void testThreadConnectionPool() throws InterruptedException, SQLException {
        Thread[] poolThreads = new Thread[10];
        cpf.releaseAll();
        assertFreeUse(cpf, 10, 0);
        for (int i = 0; i < 10; i++) {
            Thread t = new Thread(new PoolThread(i, cp, 5));
            poolThreads[i] = t;
            t.start();
        }
        for (int i = 0; i < 10; i++) {
            poolThreads[i].join();
        }
        assertFreeUse(cpf, 10, 0);
    }

    private void assertFreeUse(ConnectionPoolFactory cpf, int free, int inUse) {
        Assert.assertEquals(cpf.free(), free);
        Assert.assertEquals(cpf.inUse(), inUse);
    }

    private void testStatements(Connection c) throws SQLException {
        PreparedStatement ps = c.prepareStatement("CREATE TABLE dual (dummy VARCHAR(10))");
        ps.execute();
        ps = c.prepareStatement("DROP TABLE dual");
        ps.execute();
        c.commit();
        ps.close();
    }

    //A utility class to simulate multi thread testing of the connection pool
    class PoolThread implements Runnable {

        private int threadId;
        private ConnectionPool cp;
        private int maxLookup;
        private static final int DEFAULT_LOOKUP = 10;
        private Logger logger = LoggerFactory.getLogger(PoolThread.class);

        PoolThread(int threadId, ConnectionPool cp) {
            this(threadId, cp, DEFAULT_LOOKUP);
        }

        PoolThread(int threadId, ConnectionPool cp, int maxLookup) {
            this.threadId = threadId;
            this.cp = cp;
            this.maxLookup = maxLookup;
        }

        @Override
        public void run() {
            NDC.push("[Thread ID: " + threadId + "]");
            logger.debug("Starting execution");
            ConnectionPoolFactory cpf = (ConnectionPoolFactory) cp;
            Random r = new Random(System.currentTimeMillis());
            for (int i = 0; i < maxLookup; i++) {
                try {
                    Connection c = cp.getConnection();
                    //logger.debug("Got connection, pool status - free: " + cpf.free()
                    //        + " in use: " + cpf.inUse());
                    Thread.sleep((long) (Math.random() * 1000));
                    cp.releaseConnection(c);
                    //logger.debug("Released connection, pool status - free: "
                    //        + cpf.free() + " in use: " + cpf.inUse());
                    Thread.sleep(10);
                } catch (SQLException e) {
                    logger.error(e.getMessage(), e);
                } catch (InterruptedException e) {
                    logger.error(e.getMessage(), e);
                }
            }
            NDC.pop();
        }
    }
}
