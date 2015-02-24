/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.internal.visor.util;

import org.apache.ignite.*;
import org.apache.ignite.cache.eviction.*;
import org.apache.ignite.cache.eviction.fifo.*;
import org.apache.ignite.cache.eviction.lru.*;
import org.apache.ignite.cache.eviction.random.*;
import org.apache.ignite.cluster.*;
import org.apache.ignite.events.*;
import org.apache.ignite.internal.processors.igfs.*;
import org.apache.ignite.internal.util.typedef.*;
import org.apache.ignite.internal.util.typedef.internal.*;
import org.apache.ignite.internal.visor.event.*;
import org.apache.ignite.internal.visor.file.*;
import org.apache.ignite.internal.visor.log.*;
import org.apache.ignite.lang.*;
import org.jetbrains.annotations.*;

import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.text.*;
import java.util.*;
import java.util.concurrent.atomic.*;

import static java.lang.System.*;
import static org.apache.ignite.configuration.IgfsConfiguration.*;
import static org.apache.ignite.events.EventType.*;

/**
 * Contains utility methods for Visor tasks and jobs.
 */
public class VisorTaskUtils {
    /** Default substitute for {@code null} names. */
    private static final String DFLT_EMPTY_NAME = "<default>";

    /** Throttle count for lost events. */
    private static final int EVENTS_LOST_THROTTLE = 10;

    /** Period to grab events. */
    private static final int EVENTS_COLLECT_TIME_WINDOW = 10 * 60 * 1000;

    /** Empty buffer for file block. */
    private static final byte[] EMPTY_FILE_BUF = new byte[0];

    /** Log files count limit */
    public static final int LOG_FILES_COUNT_LIMIT = 5000;

    /** Only task event types that Visor should collect. */
    public static final int[] VISOR_TASK_EVTS = {
        EVT_JOB_STARTED,
        EVT_JOB_FINISHED,
        EVT_JOB_TIMEDOUT,
        EVT_JOB_FAILED,
        EVT_JOB_FAILED_OVER,
        EVT_JOB_REJECTED,
        EVT_JOB_CANCELLED,

        EVT_TASK_STARTED,
        EVT_TASK_FINISHED,
        EVT_TASK_FAILED,
        EVT_TASK_TIMEDOUT
    };

    /** Only non task event types that Visor should collect. */
    public static final int[] VISOR_NON_TASK_EVTS = {
        EVT_CLASS_DEPLOY_FAILED,
        EVT_TASK_DEPLOY_FAILED
    };

    /** Only non task event types that Visor should collect. */
    public static final int[] VISOR_ALL_EVTS = concat(VISOR_TASK_EVTS, VISOR_NON_TASK_EVTS);

    /** Maximum folder depth. I.e. if depth is 4 we look in starting folder and 3 levels of sub-folders. */
    public static final int MAX_FOLDER_DEPTH = 4;

    /** Comparator for log files by last modified date. */
    private static final Comparator<VisorLogFile> LAST_MODIFIED = new Comparator<VisorLogFile>() {
        @Override public int compare(VisorLogFile f1, VisorLogFile f2) {
            return Long.compare(f2.lastModified(), f1.lastModified());
        }
    };

    /** Debug date format. */
    private static final ThreadLocal<SimpleDateFormat> DEBUG_DATE_FMT = new ThreadLocal<SimpleDateFormat>() {
        /** {@inheritDoc} */
        @Override protected SimpleDateFormat initialValue() {
            return new SimpleDateFormat("HH:mm:ss,SSS");
        }
    };

    /**
     * @param name Grid-style nullable name.
     * @return Name with {@code null} replaced to &lt;default&gt;.
     */
    public static String escapeName(@Nullable String name) {
        return name == null ? DFLT_EMPTY_NAME : name;
    }

    /**
     * @param a First name.
     * @param b Second name.
     * @return {@code true} if both names equals.
     */
    public static boolean safeEquals(@Nullable String a, @Nullable String b) {
        return (a != null && b != null) ? a.equals(b) : (a == null && b == null);
    }

    /**
     * Concat arrays in one.
     *
     * @param arrays Arrays.
     * @return Summary array.
     */
    public static int[] concat(int[]... arrays) {
        assert arrays != null;
        assert arrays.length > 1;

        int len = 0;

        for (int[] a : arrays)
            len += a.length;

        int[] r = Arrays.copyOf(arrays[0], len);

        for (int i = 1, shift = 0; i < arrays.length; i++) {
            shift += arrays[i - 1].length;
            System.arraycopy(arrays[i], 0, r, shift, arrays[i].length);
        }

        return r;
    }

    /**
     * Returns compact class host.
     *
     * @param obj Object to compact.
     * @return String.
     */
    @Nullable public static Object compactObject(Object obj) {
        if (obj == null)
            return null;

        if (obj instanceof Enum)
            return obj.toString();

        if (obj instanceof String || obj instanceof Boolean || obj instanceof Number)
            return obj;

        if (obj instanceof Collection) {
            Collection col = (Collection)obj;

            Object[] res = new Object[col.size()];

            int i = 0;

            for (Object elm : col)
                res[i++] = compactObject(elm);

            return res;
        }

        if (obj.getClass().isArray()) {
            Class<?> arrType = obj.getClass().getComponentType();

            if (arrType.isPrimitive()) {
                if (obj instanceof boolean[])
                    return Arrays.toString((boolean[])obj);
                if (obj instanceof byte[])
                    return Arrays.toString((byte[])obj);
                if (obj instanceof short[])
                    return Arrays.toString((short[])obj);
                if (obj instanceof int[])
                    return Arrays.toString((int[])obj);
                if (obj instanceof long[])
                    return Arrays.toString((long[])obj);
                if (obj instanceof float[])
                    return Arrays.toString((float[])obj);
                if (obj instanceof double[])
                    return Arrays.toString((double[])obj);
            }

            Object[] arr = (Object[])obj;

            int iMax = arr.length - 1;

            StringBuilder sb = new StringBuilder("[");

            for (int i = 0; i <= iMax; i++) {
                sb.append(compactObject(arr[i]));

                if (i != iMax)
                    sb.append(", ");
            }

            sb.append("]");

            return sb.toString();
        }

        return U.compact(obj.getClass().getName());
    }

    /**
     * Compact class names.
     *
     * @param obj Object for compact.
     * @return Compacted string.
     */
    @Nullable public static String compactClass(Object obj) {
        if (obj == null)
            return null;

        return U.compact(obj.getClass().getName());
    }

    /**
     * Joins array elements to string.
     *
     * @param arr Array.
     * @return String.
     */
    @Nullable public static String compactArray(Object[] arr) {
        if (arr == null || arr.length == 0)
            return null;

        String sep = ", ";

        StringBuilder sb = new StringBuilder();

        for (Object s : arr)
            sb.append(s).append(sep);

        if (sb.length() > 0)
            sb.setLength(sb.length() - sep.length());

        return U.compact(sb.toString());
    }

    /**
     * Returns boolean value from system property or provided function.
     *
     * @param propName System property name.
     * @param dflt Function that returns {@code Integer}.
     * @return {@code Integer} value
     */
    public static Integer intValue(String propName, Integer dflt) {
        String sysProp = getProperty(propName);

        return (sysProp != null && !sysProp.isEmpty()) ? Integer.getInteger(sysProp) : dflt;
    }

    /**
     * Returns boolean value from system property or provided function.
     *
     * @param propName System property host.
     * @param dflt Function that returns {@code Boolean}.
     * @return {@code Boolean} value
     */
    public static boolean boolValue(String propName, boolean dflt) {
        String sysProp = getProperty(propName);

        return (sysProp != null && !sysProp.isEmpty()) ? Boolean.getBoolean(sysProp) : dflt;
    }

    /**
     * Helper function to get value from map.
     *
     * @param map Map to take value from.
     * @param key Key to search in map.
     * @param ifNull Default value if {@code null} was returned by map.
     * @param <K> Key type.
     * @param <V> Value type.
     * @return Value from map or default value if map return {@code null}.
     */
    public static <K, V> V getOrElse(Map<K, V> map, K key, V ifNull) {
        assert map != null;

        V res = map.get(key);

        return res != null ? res : ifNull;
    }

    /**
     * Checks for explicit events configuration.
     *
     * @param ignite Grid instance.
     * @return {@code true} if all task events explicitly specified in configuration.
     */
    public static boolean checkExplicitTaskMonitoring(Ignite ignite) {
        int[] evts = ignite.configuration().getIncludeEventTypes();

        if (F.isEmpty(evts))
            return false;

        for (int evt : VISOR_TASK_EVTS) {
            if (!F.contains(evts, evt))
                return false;
        }

        return true;
    }

    /** Events comparator by event local order. */
    private static final Comparator<Event> EVTS_ORDER_COMPARATOR = new Comparator<Event>() {
        @Override public int compare(Event o1, Event o2) {
            return Long.compare(o1.localOrder(), o2.localOrder());
        }
    };

    /** Mapper from grid event to Visor data transfer object. */
    private static final VisorEventMapper EVT_MAPPER = new VisorEventMapper();

    /**
     * Grabs local events and detects if events was lost since last poll.
     *
     * @param ignite Target grid.
     * @param evtOrderKey Unique key to take last order key from node local map.
     * @param evtThrottleCntrKey Unique key to take throttle count from node local map.
     * @param all If {@code true} then collect all events otherwise collect only non task events.
     * @return Collections of node events
     */
    public static Collection<VisorGridEvent> collectEvents(Ignite ignite, String evtOrderKey, String evtThrottleCntrKey,
        final boolean all) {
        return collectEvents(ignite, evtOrderKey, evtThrottleCntrKey, all ? VISOR_ALL_EVTS : VISOR_NON_TASK_EVTS,
            EVT_MAPPER);
    }

    /**
     * Grabs local events and detects if events was lost since last poll.
     *
     * @param ignite Target grid.
     * @param evtOrderKey Unique key to take last order key from node local map.
     * @param evtThrottleCntrKey Unique key to take throttle count from node local map.
     * @param evtTypes Event types to collect.
     * @param evtMapper Closure to map grid events to Visor data transfer objects.
     * @return Collections of node events
     */
    public static Collection<VisorGridEvent> collectEvents(Ignite ignite, String evtOrderKey, String evtThrottleCntrKey,
        final int[] evtTypes, IgniteClosure<Event, VisorGridEvent> evtMapper) {
        assert ignite != null;
        assert evtTypes != null && evtTypes.length > 0;

        ClusterNodeLocalMap<String, Long> nl = ignite.cluster().nodeLocalMap();

        final long lastOrder = getOrElse(nl, evtOrderKey, -1L);
        final long throttle = getOrElse(nl, evtThrottleCntrKey, 0L);

        // When we first time arrive onto a node to get its local events,
        // we'll grab only last those events that not older than given period to make sure we are
        // not grabbing GBs of data accidentally.
        final long notOlderThan = System.currentTimeMillis() - EVENTS_COLLECT_TIME_WINDOW;

        // Flag for detecting gaps between events.
        final AtomicBoolean lastFound = new AtomicBoolean(lastOrder < 0);

        IgnitePredicate<Event> p = new IgnitePredicate<Event>() {
            @Override public boolean apply(Event e) {
                // Detects that events were lost.
                if (!lastFound.get() && (lastOrder == e.localOrder()))
                    lastFound.set(true);

                // Retains events by lastOrder, period and type.
                return e.localOrder() > lastOrder && e.timestamp() > notOlderThan && F.contains(evtTypes, e.type());
            }
        };

        Collection<Event> evts = ignite.events().localQuery(p);

        // Update latest order in node local, if not empty.
        if (!evts.isEmpty()) {
            Event maxEvt = Collections.max(evts, EVTS_ORDER_COMPARATOR);

            nl.put(evtOrderKey, maxEvt.localOrder());
        }

        // Update throttle counter.
        if (!lastFound.get())
            nl.put(evtThrottleCntrKey, throttle == 0 ? EVENTS_LOST_THROTTLE : throttle - 1);

        boolean lost = !lastFound.get() && throttle == 0;

        Collection<VisorGridEvent> res = new ArrayList<>(evts.size() + (lost ? 1 : 0));

        if (lost)
            res.add(new VisorGridEventsLost(ignite.cluster().localNode().id()));

        for (Event e : evts) {
            VisorGridEvent visorEvt = evtMapper.apply(e);

            if (visorEvt != null)
                res.add(visorEvt);
        }

        return res;
    }

    /**
     * Finds all files in folder and in it's sub-tree of specified depth.
     *
     * @param file Starting folder
     * @param maxDepth Depth of the tree. If 1 - just look in the folder, no sub-folders.
     * @param filter file filter.
     */
    public static List<VisorLogFile> fileTree(File file, int maxDepth, @Nullable FileFilter filter) {
        if (file.isDirectory()) {
            File[] files = (filter == null) ? file.listFiles() : file.listFiles(filter);

            if (files == null)
                return Collections.emptyList();

            List<VisorLogFile> res = new ArrayList<>(files.length);

            for (File f : files) {
                if (f.isFile() && f.length() > 0)
                    res.add(new VisorLogFile(f));
                else if (maxDepth > 1)
                    res.addAll(fileTree(f, maxDepth - 1, filter));
            }

            return res;
        }

        return F.asList(new VisorLogFile(file));
    }

    /**
     * @param fld Folder with files to match.
     * @param ptrn Pattern to match against file name.
     * @return Collection of matched files.
     */
    public static List<VisorLogFile> matchedFiles(File fld, final String ptrn) {
        List<VisorLogFile> files = fileTree(fld, MAX_FOLDER_DEPTH,
            new FileFilter() {
                @Override public boolean accept(File f) {
                    return !f.isHidden() && (f.isDirectory() || f.isFile() && f.getName().matches(ptrn));
                }
            }
        );

        Collections.sort(files, LAST_MODIFIED);

        return files;
    }

    /** Text files mime types. */
    private static final String[] TEXT_MIME_TYPE = new String[] {"text/plain", "application/xml", "text/html", "x-sh"};

    /**
     * Check is text file.
     *
     * @param f file reference.
     * @param emptyOk default value if empty file.
     * @return Is text file.
     */
    public static boolean textFile(File f, boolean emptyOk) {
        if (f.length() == 0)
            return emptyOk;

        String detected = VisorMimeTypes.getContentType(f);

        for (String mime : TEXT_MIME_TYPE)
            if (mime.equals(detected))
                return true;

        return false;
    }

    /**
     * Decode file charset.
     *
     * @param f File to process.
     * @return File charset.
     * @throws IOException
     */
    public static Charset decode(File f) throws IOException {
        SortedMap<String, Charset> charsets = Charset.availableCharsets();

        String[] firstCharsets = {Charset.defaultCharset().name(), "US-ASCII", "UTF-8", "UTF-16BE", "UTF-16LE"};

        Collection<Charset> orderedCharsets = U.newLinkedHashSet(charsets.size());

        for (String c : firstCharsets)
            if (charsets.containsKey(c))
                orderedCharsets.add(charsets.get(c));

        orderedCharsets.addAll(charsets.values());

        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            FileChannel ch = raf.getChannel();

            ByteBuffer buf = ByteBuffer.allocate(4096);

            ch.read(buf);

            buf.flip();

            for (Charset charset : orderedCharsets) {
                CharsetDecoder decoder = charset.newDecoder();

                decoder.reset();

                try {
                    decoder.decode(buf);

                    return charset;
                }
                catch (CharacterCodingException ignored) {
                }
            }
        }

        return Charset.defaultCharset();
    }

    /**
     * Read block from file.
     *
     * @param file - File to read.
     * @param off - Marker position in file to start read from if {@code -1} read last blockSz bytes.
     * @param blockSz - Maximum number of chars to read.
     * @param lastModified - File last modification time.
     * @return Read file block.
     * @throws IOException In case of error.
     */
    public static VisorFileBlock readBlock(File file, long off, int blockSz, long lastModified) throws IOException {
        RandomAccessFile raf = null;

        try {
            long fSz = file.length();
            long fLastModified = file.lastModified();

            long pos = off >= 0 ? off : Math.max(fSz - blockSz, 0);

            // Try read more that file length.
            if (fLastModified == lastModified && fSz != 0 && pos >= fSz)
                throw new IOException("Trying to read file block with wrong offset: " + pos + " while file size: " + fSz);

            if (fSz == 0)
                return new VisorFileBlock(file.getPath(), pos, fLastModified, 0, false, EMPTY_FILE_BUF);
            else {
                int toRead = Math.min(blockSz, (int)(fSz - pos));

                byte[] buf = new byte[toRead];

                raf = new RandomAccessFile(file, "r");

                raf.seek(pos);

                int cntRead = raf.read(buf, 0, toRead);

                if (cntRead != toRead)
                    throw new IOException("Count of requested and actually read bytes does not match [cntRead=" +
                        cntRead + ", toRead=" + toRead + ']');

                boolean zipped = buf.length > 512;

                return new VisorFileBlock(file.getPath(), pos, fSz, fLastModified, zipped, zipped ? U.zipBytes(buf) : buf);
            }
        }
        finally {
            U.close(raf, null);
        }
    }

    /**
     * Resolve IGFS profiler logs directory.
     *
     * @param igfs IGFS instance to resolve logs dir for.
     * @return {@link Path} to log dir or {@code null} if not found.
     * @throws IgniteCheckedException if failed to resolve.
     */
    public static Path resolveIgfsProfilerLogsDir(IgniteFs igfs) throws IgniteCheckedException {
        String logsDir;

        if (igfs instanceof IgfsEx)
            logsDir = ((IgfsEx)igfs).clientLogDirectory();
        else if (igfs == null)
            throw new IgniteCheckedException("Failed to get profiler log folder (IGFS instance not found)");
        else
            throw new IgniteCheckedException("Failed to get profiler log folder (unexpected IGFS instance type)");

        URL logsDirUrl = U.resolveIgniteUrl(logsDir != null ? logsDir : DFLT_IGFS_LOG_DIR);

        return logsDirUrl != null ? new File(logsDirUrl.getPath()).toPath() : null;
    }

    /**
     * Extract max size from eviction policy if available.
     *
     * @param plc Eviction policy.
     * @return Extracted max size.
     */
    public static Integer evictionPolicyMaxSize(CacheEvictionPolicy plc) {
        if (plc instanceof CacheLruEvictionPolicyMBean)
            return ((CacheLruEvictionPolicyMBean)plc).getMaxSize();

        if (plc instanceof CacheRandomEvictionPolicyMBean)
            return ((CacheRandomEvictionPolicyMBean)plc).getMaxSize();

        if (plc instanceof CacheFifoEvictionPolicyMBean)
            return ((CacheFifoEvictionPolicyMBean)plc).getMaxSize();

        return null;
    }

    /**
     * Pretty-formatting for duration.
     *
     * @param ms Millisecond to format.
     * @return Formatted presentation.
     */
    private static String formatDuration(long ms) {
        assert ms >= 0;

        if (ms == 0)
            return "< 1 ms";

        SB sb = new SB();

        long dd = ms / 1440000; // 1440 mins = 60 mins * 24 hours

        if (dd > 0)
            sb.a(dd).a(dd == 1 ? " day " : " days ");

        ms %= 1440000;

        long hh = ms / 60000;

        if (hh > 0)
            sb.a(hh).a(hh == 1 ? " hour " : " hours ");

        long min = ms / 60000;

        if (min > 0)
            sb.a(min).a(min == 1 ? " min " : " mins ");

        ms %= 60000;

        if (ms > 0)
            sb.a(ms).a(" ms ");

        return sb.toString().trim();
    }

    /**
     * @param log Logger.
     * @param time Time.
     * @param msg Message.
     */
    private static void log0(@Nullable IgniteLogger log, long time, String msg) {
        if (log != null) {
            if (log.isDebugEnabled())
                log.debug(msg);
            else
                log.warning(msg);
        }
        else
            X.println("[" + DEBUG_DATE_FMT.get().format(time) + "]" +
                String.format("%30s %s", "<" + Thread.currentThread().getName() + ">", msg));
    }

    /**
     * Log start.
     *
     * @param log Logger.
     * @param clazz Class.
     * @param start Start time.
     */
    public static void logStart(@Nullable IgniteLogger log, Class<?> clazz, long start) {
        log0(log, start, "[" + clazz.getSimpleName() + "]: STARTED");
    }

    /**
     * Log finished.
     *
     * @param log Logger.
     * @param clazz Class.
     * @param start Start time.
     */
    public static void logFinish(@Nullable IgniteLogger log, Class<?> clazz, long start) {
        final long end = U.currentTimeMillis();

        log0(log, end, String.format("[%s]: FINISHED, duration: %s", clazz.getSimpleName(), formatDuration(end - start)));
    }

    /**
     * Log task mapped.
     *
     * @param log Logger.
     * @param clazz Task class.
     * @param nodes Mapped nodes.
     */
    public static void logMapped(@Nullable IgniteLogger log, Class<?> clazz, Collection<ClusterNode> nodes) {
        log0(log, U.currentTimeMillis(),
            String.format("[%s]: MAPPED: %s", clazz.getSimpleName(), U.toShortString(nodes)));
    }

    /**
     * Log message.
     *
     * @param log Logger.
     * @param clazz class.
     * @param start start time.
     */
    public static long log(@Nullable IgniteLogger log, String msg, Class<?> clazz, long start) {
        final long end = U.currentTimeMillis();

        log0(log, end, String.format("[%s]: %s, duration: %s", clazz.getSimpleName(), msg, formatDuration(end - start)));

        return end;
    }

    /**
     * Checks if address can be reached using one argument InetAddress.isReachable() version or ping command if failed.
     *
     * @param addr Address to check.
     * @param reachTimeout Timeout for the check.
     * @return {@code True} if address is reachable.
     */
    public static boolean reachableByPing(InetAddress addr, int reachTimeout) {
        try {
            if (addr.isReachable(reachTimeout))
                return true;

            String cmd = String.format("ping -%s 1 %s", U.isWindows() ? "n" : "c", addr.getHostAddress());

            Process myProc = Runtime.getRuntime().exec(cmd);

            myProc.waitFor();

            return myProc.exitValue() == 0;
        }
        catch (IOException ignore) {
            return false;
        }
        catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();

            return false;
        }
    }

    /**
     * Run command in separated console.
     *
     * @param args A string array containing the program and its arguments.
     * @return Started process.
     */
    public static Process openInConsole(String... args) throws IOException {
        return openInConsole(null, args);
    }

    /**
     * Run command in separated console.
     *
     * @param workFolder Work folder for command.
     * @param args A string array containing the program and its arguments.
     * @return Started process.
     * @throws IOException If failed to start process.
     */
    public static Process openInConsole(@Nullable File workFolder, String... args)
        throws IOException {
        String[] commands = args;

        String cmd = F.concat(Arrays.asList(args), " ");

        if (U.isWindows())
            commands = F.asArray("cmd", "/c", String.format("start %s", cmd));

        if (U.isMacOs())
            commands = F.asArray("osascript", "-e",
                String.format("tell application \"Terminal\" to do script \"%s\"", cmd));

        if (U.isUnix())
            commands = F.asArray("xterm", "-sl", "1024", "-geometry", "200x50", "-e", cmd);

        ProcessBuilder pb = new ProcessBuilder(commands);

        if (workFolder != null)
            pb.directory(workFolder);

        return pb.start();
    }
}