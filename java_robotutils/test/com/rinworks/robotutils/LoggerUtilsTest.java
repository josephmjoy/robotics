/**
 * 
 */
package com.rinworks.robotutils;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.io.FileFilter;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.rinworks.robotutils.StructuredLogger;
import com.rinworks.robotutils.StructuredMessageMapper;

/**
 * @author josephj
 *
 */
class LoggerUtilsTest {
    
    static final String LOGDIRTAG = "logdir";
    static final String  SYSNAME = "testsys";


    @Test
    void testBasicTest() {
        clearLogdir(null);
        StructuredLogger logger = LoggerUtils.makeStandardLogger(SYSNAME, null);
        logger.beginLogging();
        logger.info("This is a test INFO log message");
        logger.endLogging();
        verifyLogdirContents(null);
        clearLogdir(null);
    }
    
    // Wipe out any files in the specified log dir, if it exists. Sub-directories are not deleted,
    // just files.
    // The file MUST contain the substring "log" in any combination of
    // upper and lower case letters.
    private void clearLogdir(String configFile) {

        Map<String, String> configMap = getConfigMap(configFile);
        String logdirName = configMap.get(LOGDIRTAG);
        if (logdirName.toLowerCase().indexOf("log") < 0) {
            
            fail("logdir doesn't contain substring 'log': " + logdirName);
            return;
        }
        File logDirPath = new File(logdirName);        
        if (!logDirPath.exists()) {
            return; // ******** EARLY RETURN 
        }

        if (!logDirPath.isDirectory()) {
            assert(false);
            return; // ******* EARLY RETURN
        }
        
        File[] files = logDirPath.listFiles();
        for (File f : files) {
            if (f.isFile()) {
                if (!f.delete()) {
                    errPrint("WARNING: could not delete file " + f);
                }
            }
        }
    }


    private void errPrint(String string) {
        System.err.println(string);
        
    }

    private void verifyLogdirContents(String configFile) {
        Map<String, String> configMap = getConfigMap(configFile);
        String logdirName = configMap.get(LOGDIRTAG);
        
        File logDirPath = new File(logdirName);        
        if (!logDirPath.exists()) {
            return; // ******** EARLY RETURN 
        }
        String sessionsFname = SYSNAME + "_sessions.txt";
        File sessionsLog = new File(logDirPath.getAbsolutePath(), "");
        assertTrue(sessionsLog.exists(), "session log does not exist");
        boolean gotSessionFile = false;
        for (File f: logDirPath.listFiles()) {
            if (f.getName().indexOf(SYSNAME + "_") == 0) {
                gotSessionFile = true;
            }
        }
        assertTrue(gotSessionFile);
    }

    
    private Map<String, String> getConfigMap(String configFile) {
        if (configFile == null) {
            return defaultConfigMap();
        }
        FileReader reader;
        try {
            reader = new FileReader(configFile);
            return ConfigurationHelper.readSection(reader, "logging");
        } catch (FileNotFoundException e) {
            fail("Could not find config file: " + configFile);
            e.printStackTrace();
            return defaultConfigMap();
        }
    }

    private Map<String, String> defaultConfigMap() {
        String defaultLogDir = (new File(System.getProperty("user.home"), LoggerUtils.DEFAULT_LOGDIR)).getAbsolutePath();
        HashMap<String, String> map = new HashMap<>();
        map.put(LOGDIRTAG, defaultLogDir);
        map.put("maxdirsize_mb", ""+LoggerUtils.DEFAULT_MAXDIRSIZE_MB);
        return map;
    }
}
