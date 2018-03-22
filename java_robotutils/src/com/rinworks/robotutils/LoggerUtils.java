// Various helper classes to make it easier to use the StructuredLogger logging infrastructure.
// Created by Joseph M. Joy (https://github.com/josephmjoy)

package com.rinworks.robotutils;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.function.ToIntFunction;

import com.rinworks.robotutils.StructuredLogger.RawLogger;

public class LoggerUtils {

    /**
     * Creates a raw logger that generates per-session log files of the form
     * {prefix}{session id}{suffix}. If a file already exists with the same name
     * when StructuredLogger.openSession is called, that file WILL NOT be touched,
     * and no logging will be done. No IOExceptions are thrown. Instead various
     * error conditions produce one-time error messages that messages are written to
     * System.err. A valid RawLogger is always returned, even on error.
     * 
     * @param logDirectory
     *            - directory where log files will reside.
     * @param prefix
     *            - filename prefix
     * @param suffix
     *            - filename suffix
     * @param long
     *            - The maximum size the log file is allowed to grow.
     * @param maxPriFunc
     *            - if null: accept all messages, else this method is called to
     *            determine the maximum numerical priority of messages excepted for
     *            this log.
     * @return A StructuredLogger.RawLogger object that may be passed into a
     *         StructuredLogger constructor
     */
    public static RawLogger createFileRawLogger(File logDirectory, String prefix, String suffix, long maxSize,
            ToIntFunction<String> maxPriFunc) {
        return new FileRawLogger(logDirectory, prefix, suffix, maxSize, maxPriFunc);
    }

    /**
     * Creates a raw logger that logs multiple sessions to a single file. If a file
     * already exists with the same name when StructuredLogger.openSession is
     * called, that file WILL be appended to. The path name MUST contain the string
     * "log" (a case-insensitive check is made). No IOExceptions are thrown. Instead
     * various error conditions produce one-time error messages that are written to
     * System.err. A valid RawLogger is always returned, even on error.
     * 
     * @param logFile
     *            - File object representing log file path.
     * @param long
     *            - The maximum size the log file is allowed to reach.
     * @param maxPriFunc
     *            - if null: accept all messages, else this method is call to
     *            determine the maximum numerical priority of messages to accept
     *            from a Log.
     * @return A StructuredLogger.Logger object that may be passed into a
     *         StructuredLogger constructor
     */
    public static RawLogger createFileRawLogger(File logFile, long maxSize, ToIntFunction<String> maxPriFunc) {
        return new FileRawLogger(logFile, maxSize, maxPriFunc);
    }

    /**
     * Creates a raw logger that transmits log messages as UDP packets to the
     * specified destination.
     * 
     * @param address
     *            - Destination host name or IP Address
     * @param port
     *            - Destination port.
     * @param maxPriFunc
     *            - if null: accept all messages, else this method is call to
     *            determine the maximum numerical priority of messages to accept
     *            from a Log.
     * @return A StructuredLogger.Logger object that may be passed into a
     *         StructuredLogger constructor
     */
    public static RawLogger createUDPRawLogger(String address, int port, ToIntFunction<String> maxPriFunc) {
        return new UDPRawLogger(address, port, maxPriFunc);
    }

    /**
     * Creates a raw logger that writes log messages to the console (System.out or
     * System.err).
     * 
     * @param maxPriFunc
     *            - Optional filter - if non-null, will be called to decide what to
     *            log.
     * @return A StructuredLogger.Logger object that may be passed into a
     *         StructuredLogger constructor
     */
    public static RawLogger createConsoleRawLogger(ToIntFunction<String> maxPriFunc) {
        return new ConsoleRawLogger(maxPriFunc);
    }

    // This is a static class because it is constructed from within static methods
    // of
    // LoggerUtils.
    // It is intended to be constructed BEFORE a StructuredLogger exists and
    // therefore does not have access
    // (and in fact anyways has no business accessing!) instance fields of
    // StructuredLogger.
    private static class FileRawLogger implements RawLogger {
        static final int MAX_EXCEPTION_COUNT = 100; // after which we will disable logging.
        final boolean perSessionLog;
        final long maxSize;
        final File logDirectory;
        final ToIntFunction<String> maxPriFunc;
        File logFile;
        final String prefix;
        final String suffix;

        boolean loggingDisabled; // Typically because of an unrecoverable error
        long remainingCapacity; // Bytes we can still log before reaching maxSize.
        BufferedWriter out;
        boolean logErrorNotified; // we generate on err msg on write error.
        int exceptionCount = 0;

        // Logger that creates per-session log files
        public FileRawLogger(File logDirectory, String prefix, String suffix, long maxSize,
                ToIntFunction<String> maxPriFunc) {
            this.perSessionLog = true;
            this.logDirectory = logDirectory;
            this.prefix = prefix;
            this.suffix = suffix;
            this.maxSize = maxSize;
            this.maxPriFunc = maxPriFunc == null ? (s -> Integer.MAX_VALUE) : maxPriFunc;

        }

        // Logger that logs to a single log file
        public FileRawLogger(File logFile, long maxSize, ToIntFunction<String> maxPriFunc) {
            this.perSessionLog = false;
            this.logDirectory = null;
            this.logFile = logFile;
            this.prefix = null;
            this.suffix = null;
            this.maxSize = maxSize;
            this.maxPriFunc = maxPriFunc == null ? (s -> Integer.MAX_VALUE) : maxPriFunc;
        }

        @Override
        public void beginSession(String sessionId) {

            // We don't throw any exceptions on error, just write the error to the err
            // console.

            if (!loggingDisabled) {
                setFileName(sessionId);
            }

            if (!loggingDisabled) {
                try {
                    // per-session (filename generated based on sessionID) ==> do NOT append
                    // otherwise (filename specified explicitly) ==> DO append
                    boolean append = !perSessionLog;
                    FileWriter fr = new FileWriter(logFile, append);
                    out = new BufferedWriter(fr);
                    long len = logFile.length();
                    this.remainingCapacity = maxSize - len; // could potentially be negative.
                } catch (IOException e) {
                    printErr(String.format(
                            "FileRawLogger: Cannot log. Could not create/open log file {%s}. Exception: %s",
                            logDirectory.getAbsolutePath(), e));
                    out = null;
                    loggingDisabled = true;
                }
            }

            if (loggingDisabled) {
                printErr("FileRawLogger: NOT starting session with ID " + sessionId
                        + " because of earlier unrecoverable error.");
                return;
            } else {
                assert out != null;
            }

        }

        @Override
        public int maxPriority(String logName) {            
            return this.maxPriFunc.applyAsInt(logName);
        }

        @Override
        public void write(String msg) {

            if (loggingDisabled)
                return; // *** EARLY RETURN ****

            try {
                // WARNING: the following length calculation does not
                // account for any Unicode character expansion when converting
                // to bytes. If client-supplied messages start containing
                // significant amounts of non-ASCI characters this approach
                // needs to be revisited.
                int len = msg.length() + 1; // 1 for newline.
                if (remainingCapacity > len) {
                    out.write(msg, 0, msg.length());
                    out.newLine();
                    remainingCapacity -= len;
                } else {
                    printErr(String.format(
                            "FileRawLogger: log file {%s} has reached max capacity %d. Stopping further logging.",
                            logFile.getAbsolutePath(), this.maxSize));
                    logErrorNotified = true;
                    loggingDisabled = true;
                    out.flush();
                    out.close();
                    out = null;
                }
            } catch (IOException e) {
                exceptionCount++;
                if (!logErrorNotified) {
                    printErr(String.format("FileRawLogger: could not write to log file {%s}. Exception: %s",
                            logFile.getAbsolutePath(), e));
                    logErrorNotified = true;
                    if (exceptionCount > MAX_EXCEPTION_COUNT) {
                        loggingDisabled = true;
                    }
                }
            }

        }

        @Override
        public void flush() {

            if (loggingDisabled)
                return; // *** EARLY RETURN ****

            try {
                if (out != null) {
                    out.flush();
                }
            } catch (IOException e) {
                exceptionCount++;
                printErr(String.format("FileRawLogger: could not flush log file {%s}. Exception: %s",
                        logFile.getAbsolutePath(), e));
                if (exceptionCount > MAX_EXCEPTION_COUNT) {
                    loggingDisabled = true;
                }
            }
        }

        @Override
        public void close() {
            try {
                if (out != null) {
                    out.close();
                    out = null;
                }
            } catch (IOException e) {
                exceptionCount++;
                printErr(String.format("FileRawLogger: could not close log file {%s}. Exception: %s",
                        logFile.getAbsolutePath(), e));
            }
        }

        private void setFileName(String sessionId) {
            if (perSessionLog) {

                if (!logDirectory.canWrite()) {
                    printErr(String.format("FileRawLogger: log directory {%s} cannot be written to.",
                            logDirectory.getAbsolutePath()));
                }
                String name = prefix + sessionId + suffix;
                logFile = new File(logDirectory, name);
                if (logFile.exists()) {
                    // OOPS- we WILL not touch a log file that exists in per-session mode. This
                    // should 'never'
                    // happen because the file includes the supposedly unique session ID (which is
                    // typically
                    // System.currentTimeMillis()).
                    printErr(String.format("FileRawLogger: log name {%s} already exists and will NOT be overridden.",
                            logFile.getAbsolutePath()));
                    loggingDisabled = true;
                }

            } else {

                // Check that the path name contains 'log'.
                String fullName = logFile.getAbsolutePath();
                if (fullName.toLowerCase().indexOf("log") < 0) {
                    printErr(String.format("FileRawLogger: disabling logging because the specified log path {%s}"
                            + "does not contain the string \"log\"", fullName));
                    loggingDisabled = true;
                }
            }
        }
    }

    private static class UDPRawLogger implements RawLogger {
        final String destAddress;
        final int destPort;
        final ToIntFunction<String> maxPriFunc;
        boolean logErrorNotified; // we generate one err msg if there is an error message on write..
        DatagramSocket clientSocket;
        InetAddress destIPAddress;
        boolean canLog = false;

        // Logger that logs by sending UDP traffic to the specified address and port.
        public UDPRawLogger(String address, int port, ToIntFunction<String> maxPriFunc) {
            this.destAddress = address;
            this.destPort = port;
            this.maxPriFunc = maxPriFunc == null ? (s -> Integer.MAX_VALUE) : maxPriFunc;
        }

        @Override
        public void beginSession(String sessionId) {

            try {
                clientSocket = new DatagramSocket();
                destIPAddress = InetAddress.getByName(destAddress);
                printErr(String.format("UDPRawLogger: logging session %s to IP Address %s, port %d", sessionId,
                        destIPAddress, destPort));
                canLog = true;
            } catch (SocketException e) {
                printErr("UDPRawLogger: Cannot log. Could not create DatagramSocket. Exception: " + e);
            } catch (UnknownHostException e) {
                printErr("UDPRawLogger: Cannot log. Could not resolve address " + destAddress + ". Exception: " + e);
            }
        }

        @Override
        public int maxPriority(String logName) {

            return this.maxPriFunc.applyAsInt(logName);
        }

        @Override
        public void write(String msg) {
            try {
                if (canLog) {
                    byte[] sendData = msg.getBytes();
                    DatagramPacket sendPacket = new DatagramPacket(sendData, sendData.length, destIPAddress, destPort);
                    clientSocket.send(sendPacket);
                }
            } catch (IOException e) {
                if (!logErrorNotified) {
                    printErr(String.format("UDPRawLogger: could not send msg to IP Address %s, port %d. Exception: %s",
                            destIPAddress.toString(), destPort, e));
                    logErrorNotified = true;
                }
            }
        }

        @Override
        public void flush() {
            // Nothing to do as we don't buffer messages.
        }

        @Override
        public void close() {
            if (clientSocket != null) {
                clientSocket.close();
                clientSocket = null;
            }
        }
    }

    private static class ConsoleRawLogger implements RawLogger {
        final ToIntFunction<String> maxPriFunc;

        // Logger that logs by sending UDP traffic to the specified address and port.
        public ConsoleRawLogger(ToIntFunction<String> maxPriFunc) {
            this.maxPriFunc = maxPriFunc == null ? (s -> Integer.MAX_VALUE) : maxPriFunc;
        }

        @Override
        public void beginSession(String sessionId) {
            // Nothing to do here
        }

        @Override
        public int maxPriority(String logName) {
            return this.maxPriFunc.applyAsInt(logName);
        }

        @Override
        public void write(String msg) {
            // Total hack to decide whether to log to System.err or System.out!
            if (msg.indexOf("ERR") >= 0 || msg.indexOf("WARN") >= 0) {
                System.err.println(msg);
            } else {
                System.out.println(msg);
            }
        }

        @Override
        public void flush() {
            System.out.flush();
            System.err.flush();
        }

        @Override
        public void close() {
        }
    }

    // Wrapper to System,err.println, should we decide to use some other means to
    // communicate errors inn the logger.
    private static void printErr(String s) {
        System.err.println(s);
    }
}
