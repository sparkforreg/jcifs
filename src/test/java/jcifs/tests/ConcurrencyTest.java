/*
 * © 2016 AgNO3 Gmbh & Co. KG
 * 
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 * 
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 * 
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */
package jcifs.tests;


import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.MalformedURLException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.apache.log4j.Logger;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import jcifs.smb.NtStatus;
import jcifs.smb.SmbException;
import jcifs.smb.SmbFile;


/**
 * @author mbechler
 *
 */
@RunWith ( Parameterized.class )
@SuppressWarnings ( "javadoc" )
public class ConcurrencyTest extends BaseCIFSTest {

    static final Logger log = Logger.getLogger(ConcurrencyTest.class);
    private ExecutorService executor;


    public ConcurrencyTest ( String name, Map<String, String> properties ) {
        super(name, properties);
    }


    @Override
    @Before
    public void setUp () throws Exception {
        super.setUp();
        this.executor = Executors.newCachedThreadPool();
    }


    @After
    @Override
    public void tearDown () throws Exception {
        this.executor.shutdown();
        this.executor.awaitTermination(10, TimeUnit.SECONDS);
        super.tearDown();
    }


    @Test
    public void testExclusiveLock () throws InterruptedException, MalformedURLException, UnknownHostException {

        String fname = makeRandomName();

        ExclusiveLockFirst f = new ExclusiveLockFirst(new SmbFile(getDefaultShareRoot(), fname, SmbFile.FILE_NO_SHARE));
        ExclusiveLockSecond s = new ExclusiveLockSecond(f, new SmbFile(getDefaultShareRoot(), fname, SmbFile.FILE_NO_SHARE));

        List<MultiTestCase> runnables = new ArrayList<>();
        runnables.add(f);
        runnables.add(s);
        runMultiTestCase(runnables, 5);
    }

    private class ExclusiveLockFirst extends MultiTestCase {

        private Object startedLock = new Object();
        private volatile boolean started;

        private Object shutdownLock = new Object();
        private volatile boolean shutdown;
        private SmbFile file;


        /**
         * @param smbFile
         * 
         */
        public ExclusiveLockFirst ( SmbFile smbFile ) {
            this.file = smbFile;
        }


        public void waitForStart () throws InterruptedException {
            synchronized ( this.startedLock ) {
                while ( !this.started ) {
                    this.startedLock.wait();
                }
            }
        }


        public void shutdown () {
            this.shutdown = true;
            synchronized ( this.shutdownLock ) {
                this.shutdownLock.notify();
            }
        }


        /**
         * {@inheritDoc}
         *
         * @see java.lang.Runnable#run()
         */
        @Override
        public void run () {

            try {
                SmbFile f = this.file;
                f.createNewFile();
                try {
                    try ( OutputStream os = f.getOutputStream() ) {
                        log.error("Open1");
                        synchronized ( this.startedLock ) {
                            this.started = true;
                            this.startedLock.notify();
                        }
                        while ( !this.shutdown ) {
                            synchronized ( this.shutdownLock ) {
                                this.shutdownLock.wait();
                            }
                        }
                    }
                    catch ( InterruptedException e ) {}
                    log.error("Closed1");
                }
                finally {
                    f.delete();
                }
                this.completed = true;
            }
            catch ( IOException e ) {
                log.error("Test case failed", e);
            }
        }

    }

    private class ExclusiveLockSecond extends MultiTestCase {

        private ExclusiveLockFirst first;
        private SmbFile file;


        /**
         * @param f
         * @param smbFile
         */
        public ExclusiveLockSecond ( ExclusiveLockFirst f, SmbFile smbFile ) {
            this.first = f;
            this.file = smbFile;
        }


        /**
         * {@inheritDoc}
         *
         * @see java.lang.Runnable#run()
         */
        @Override
        public void run () {
            try {
                SmbFile f = this.file;
                this.first.waitForStart();
                try ( OutputStream os = f.getOutputStream() ) {
                    log.error("Open2");
                }
                catch ( IOException e ) {
                    if ( e instanceof SmbException && ( (SmbException) e ).getNtStatus() == NtStatus.NT_STATUS_SHARING_VIOLATION ) {
                        this.completed = true;
                        return;
                    }
                    throw e;
                }
                finally {
                    this.first.shutdown();
                }
            }
            catch (
                IOException |
                InterruptedException e ) {
                log.error("Test case failed", e);
            }
        }

    }


    @Test
    public void testMultiThread () throws InterruptedException {
        List<MutiThreadTestCase> runnables = new ArrayList<>();
        for ( int i = 0; i < 10; i++ ) {
            runnables.add(new MutiThreadTestCase());
        }
        runMultiTestCase(runnables, 60);
    }


    private void runMultiTestCase ( List<? extends MultiTestCase> testcases, int timeoutSecs ) throws InterruptedException {
        for ( Runnable r : testcases ) {
            this.executor.submit(r);
        }
        this.executor.shutdown();
        this.executor.awaitTermination(timeoutSecs, TimeUnit.SECONDS);
        for ( MultiTestCase r : testcases ) {
            assertTrue(r.completed);
        }
    }

    private static abstract class MultiTestCase implements Runnable {

        public MultiTestCase () {}

        boolean completed;
    }

    private class MutiThreadTestCase extends MultiTestCase {

        public MutiThreadTestCase () {}


        @Override
        public void run () {

            try {
                SmbFile f = createTestFile();
                try {
                    f.exists();
                    try ( OutputStream os = f.getOutputStream() ) {
                        os.write(new byte[] {
                            1, 2, 3, 4, 5, 6, 7, 8
                        });
                    }

                    try ( InputStream is = f.getInputStream() ) {
                        byte data[] = new byte[8];
                        is.read(data);
                    }
                }
                finally {
                    f.delete();
                }
                this.completed = true;
            }
            catch ( IOException e ) {
                log.error("Test case failed", e);
            }
        }

    }
}
