/*
   Copyright 2008-2009 Christian Vest Hansen

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
 */
package net.nanopool.benchmark;

import biz.source_code.miniConnectionPoolManager.MiniConnectionPoolManager;
import com.mchange.v2.c3p0.ComboPooledDataSource;
import com.mchange.v2.c3p0.DataSources;
import com.mchange.v2.c3p0.WrapperConnectionPoolDataSource;
import com.mysql.jdbc.jdbc2.optional.MysqlConnectionPoolDataSource;
import org.apache.derby.jdbc.EmbeddedConnectionPoolDataSource;
import java.beans.PropertyVetoException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.ConnectionPoolDataSource;
import javax.sql.DataSource;
import net.nanopool.AbstractDataSource;
import net.nanopool.Settings;
import net.nanopool.NanoPoolDataSource;
import net.nanopool.contention.DefaultContentionHandler;
import org.apache.commons.dbcp.datasources.SharedPoolDataSource;
import org.junit.Test;

public class Benchmark {
    private static final int CORES =
            Runtime.getRuntime().availableProcessors();
    private static final boolean PRE_WARM_POOLS = Boolean.parseBoolean(
            System.getProperty("pre-warm", "true"));
    private static final int RUN_TIME = Integer.parseInt(
            System.getProperty("run-time", "650"));
    private static final int WARMUP_TIME = Integer.parseInt(
            System.getProperty("warmup-time", "8000"));
    private static final int TTL = Integer.parseInt(
            System.getProperty("ttl", "900000")); // 15 minutes
    private static final int THR_SCALE = Integer.parseInt(
            System.getProperty("thr-scale", "2"));
    private static final int CON_SCALE = Integer.parseInt(
            System.getProperty("con-scale", "2"));
    private static PoolFactory poolFactory;

    @Test
    public void executeBenchmark() throws InterruptedException {
        main(null);
    }
    
    public static void main(String[] args) throws InterruptedException {
        String pools =
                "," + System.getProperty("pools", "np,c3p0,dbcp,mcpm") + ",";

        System.out.println("--------------------------------");
        System.out.println("  thr = thread");
        System.out.println("  con = connection");
        System.out.println("  cyc = connect-close cycle");
        System.out.println("  tot = total");
        System.out.println("  cor = cpu core");
        System.out.println("  s   = a second");
        System.out.println("--------------------------------");

        poolFactory = new PoolFactory() {
            public DataSource buildPool(ConnectionPoolDataSource cpds, int size, long ttl) {
                return new NanoPoolDataSource(cpds, buildSettings(size, ttl));
            }

            public void closePool(DataSource pool) {
                if (pool instanceof NanoPoolDataSource) {
                    List<SQLException> sqles = ((NanoPoolDataSource)pool).close();
                    for (SQLException sqle : sqles) {
                        sqle.printStackTrace();
                    }
                }
            }
        };
        if (pools.contains(",np,")) {
            System.out.println("### Testing NanoPool");
            runTestSet();
        }

        poolFactory = new PoolFactory() {
            public DataSource buildPool(ConnectionPoolDataSource cpds, int size, long ttl) {
                ComboPooledDataSource cmds = new ComboPooledDataSource();
                try {
                    WrapperConnectionPoolDataSource wcpds = new WrapperConnectionPoolDataSource();
                    cmds.setConnectionPoolDataSource(cpds);
                    cmds.setMaxPoolSize(size);
                    return cmds;
                } catch (PropertyVetoException ex) {
                    throw new RuntimeException(ex);
                }
            }

            public void closePool(DataSource pool) {
                try {
                    DataSources.destroy(pool);
                } catch (SQLException ex) {
                    ex.printStackTrace();
                }
            }
        };
        if (pools.contains(",c3p0,")) {
            System.out.println("### Testing C3P0");
            //runTestSet();
        }

        poolFactory = new PoolFactory() {
            public DataSource buildPool(ConnectionPoolDataSource cpds, int size, long ttl) {
                SharedPoolDataSource spds = new SharedPoolDataSource();
                spds.setConnectionPoolDataSource(cpds);
                spds.setMaxActive(size);
                return spds;
            }

            public void closePool(DataSource pool) {
                SharedPoolDataSource spds = (SharedPoolDataSource)pool;
                try {
                    spds.close();
                } catch (Exception ex) {
                    ex.printStackTrace();
                }
            }
        };
        if (pools.contains(",dbcp,")) {
            System.out.println("### Testing Commons DBCP");
            runTestSet();
        }

        poolFactory = new PoolFactory() {
            public DataSource buildPool(ConnectionPoolDataSource cpds, int size, long ttl) {
                final MiniConnectionPoolManager mcpm = new MiniConnectionPoolManager(cpds, size);
                return new AbstractDataSource() {
                    public Connection getConnection() throws SQLException {
                        return mcpm.getConnection();
                    }

                    public List<SQLException> close() {
                        List<SQLException> sqles = new ArrayList<SQLException>();
                        try {
                            mcpm.dispose();
                        } catch (SQLException ex) {
                            sqles.add(ex);
                        }
                        return sqles;
                    }
                };
            }

            public void closePool(DataSource pool) {
                AbstractDataSource ads = (AbstractDataSource)pool;
                List<SQLException> sqles = ads.close();
                for (SQLException sqle : sqles) {
                    sqle.printStackTrace();
                }
            }
        };
        if (pools.contains(",mcpm,")) {
            System.out.println("### Testing MiniConnectionPoolManager");
            runTestSet();
        }
    }
    
    private static void runTestSet() throws InterruptedException {
        System.out.println("[thrs] [cons] : " +
        		"  [total] [cyc/tot/s] [syc/cor/s] [cyc/thr/s] [cyc/con/s]");
        try {
            benchmark(50, 20, WARMUP_TIME);
            benchmark(2, 1, WARMUP_TIME);
        System.out.println("------Warmup's over-------------");
            for (int connections = 1; connections <= 10; connections++) {
                benchmark(connections, connections, RUN_TIME);
                if (THR_SCALE > 1)
                    benchmark(connections * THR_SCALE, connections, RUN_TIME);
                if (CON_SCALE > 1)
                    benchmark(connections, connections * CON_SCALE, RUN_TIME);
            }
        } catch (Exception ex) {
            ex.printStackTrace();
            System.err.println("This benchmark test run died.");
        }
        System.out.println("--------------------------------");
    }

    private static ConnectionPoolDataSource newCpds() {
        String db = System.getProperty("db", "derby");
        if ("mysql".equals(db)) {
            MysqlConnectionPoolDataSource cpds =
                    new MysqlConnectionPoolDataSource();
            cpds.setUser("root");
            cpds.setPassword("");
            cpds.setDatabaseName("test");
            cpds.setPort(3306);
            cpds.setServerName("localhost");
            return cpds;
        }
        if ("derby".equals(db)) {
            EmbeddedConnectionPoolDataSource cpds =
                    new EmbeddedConnectionPoolDataSource();
            cpds.setCreateDatabase("create");
            cpds.setDatabaseName("test");
            return cpds;
        }
        throw new RuntimeException("Unknown database: " + db);
    }

    private static Settings buildSettings(int poolSize, long ttl) {
        Settings settings = new Settings();
        settings.setPoolSize(poolSize)
            .setTimeToLive(ttl)
            .setContentionHandler(new DefaultContentionHandler(false, 0));
        return settings;
    }

    private static DataSource buildPool(ConnectionPoolDataSource cpds, int size) {
        return poolFactory.buildPool(cpds, size, TTL);
    }

    private static void shutdown(DataSource pool) {
        poolFactory.closePool(pool);
    }

    private static void benchmark(int threads, int poolSize, int runTime) throws InterruptedException {
        Thread.sleep(250); // give CPU some breathing room
        ConnectionPoolDataSource cpds = newCpds();
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch startSignal = new CountDownLatch(1);
        CountDownLatch endSignal = new CountDownLatch(threads);
        DataSource pool = buildPool(cpds, poolSize);

        if (PRE_WARM_POOLS) {
            Connection[] cons = new Connection[poolSize];
            for (int i = 0; i < poolSize; i++) {
                try {
                    cons[i] = pool.getConnection();
                } catch (SQLException ex) {
                    throw new RuntimeException(
                            "Exception while getting connection for " +
                            "pre-warming pool.", ex);
                }
            }
            for (int i = 0; i < poolSize; i++) {
                try {
                    cons[i].close();
                } catch (SQLException ex) {
                    throw new RuntimeException(
                            "Exception while closing connection for " +
                            "pre-warming pool.", ex);
                }
            }
        }

        Worker[] workers = new Worker[threads];
        long stopTime = System.currentTimeMillis() + runTime;
        for (int i = 0; i < threads; i++) {
            Worker worker = new Worker(pool, startSignal, endSignal, stopTime);
            executor.execute(worker);
            workers[i] = worker;
        }

        startSignal.countDown();
        try {
            Thread.sleep(runTime);
        } catch (InterruptedException ex) {
            ex.printStackTrace();
        }

        endSignal.await(runTime + 200, TimeUnit.MILLISECONDS);
        
        int sumThroughPut = 0;
        for (Worker worker : workers) {
            sumThroughPut += worker.hits;
        }
        double totalThroughput = sumThroughPut / (runTime / 1000.0);
        double throughputPerCore = totalThroughput / CORES;
        double throughputPerThread = totalThroughput / threads;
        double throughputPerCon = totalThroughput / poolSize;
        System.out.printf("%6d %6d : %9d %11.0f " +
        		"%11.0f %11.0f %11.0f\n",
                threads, poolSize, sumThroughPut, totalThroughput,
                throughputPerCore, throughputPerThread, throughputPerCon);

        shutdown(pool);
    }
    
    private static class Worker implements Runnable {
        public volatile int hits = 0;
        private final DataSource ds;
        private final CountDownLatch startLatch;
        private final CountDownLatch endLatch;
        private final long stopTime;

        public Worker(DataSource ds, CountDownLatch startLatch,
                CountDownLatch endLatch, long stopTime) {
            this.ds = ds;
            this.startLatch = startLatch;
            this.endLatch = endLatch;
            this.stopTime = stopTime;
        }
        
        public void run() {
            int count = 0;
            try {
                startLatch.await();
                while (System.currentTimeMillis() < stopTime) {
                    Connection con = ds.getConnection();
                    closeSafely(con);
                    count++;
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
            hits = count;
            endLatch.countDown();
        }

        private void closeSafely(Connection con) {
            try {
                con.close();
            } catch (SQLException ex) {
                ex.printStackTrace();
            }
        }
    }
}
