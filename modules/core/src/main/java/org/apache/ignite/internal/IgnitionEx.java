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

package org.apache.ignite.internal;

import org.apache.ignite.*;
import org.apache.ignite.cache.affinity.rendezvous.*;
import org.apache.ignite.configuration.*;
import org.apache.ignite.internal.processors.resource.*;
import org.apache.ignite.internal.processors.spring.*;
import org.apache.ignite.internal.util.*;
import org.apache.ignite.internal.util.typedef.*;
import org.apache.ignite.internal.util.typedef.internal.*;
import org.apache.ignite.lang.*;
import org.apache.ignite.logger.*;
import org.apache.ignite.logger.java.*;
import org.apache.ignite.marshaller.*;
import org.apache.ignite.marshaller.jdk.*;
import org.apache.ignite.marshaller.optimized.*;
import org.apache.ignite.mxbean.*;
import org.apache.ignite.plugin.segmentation.*;
import org.apache.ignite.spi.*;
import org.apache.ignite.spi.indexing.*;
import org.apache.ignite.spi.indexing.noop.*;
import org.apache.ignite.streamer.*;
import org.apache.ignite.thread.*;
import org.apache.ignite.internal.processors.resource.*;
import org.apache.ignite.internal.processors.spring.*;
import org.apache.ignite.plugin.segmentation.*;
import org.apache.ignite.spi.checkpoint.*;
import org.apache.ignite.spi.checkpoint.noop.*;
import org.apache.ignite.spi.collision.*;
import org.apache.ignite.spi.collision.noop.*;
import org.apache.ignite.spi.communication.*;
import org.apache.ignite.spi.communication.tcp.*;
import org.apache.ignite.spi.deployment.*;
import org.apache.ignite.spi.deployment.local.*;
import org.apache.ignite.spi.discovery.*;
import org.apache.ignite.spi.discovery.tcp.*;
import org.apache.ignite.spi.discovery.tcp.ipfinder.multicast.*;
import org.apache.ignite.spi.eventstorage.*;
import org.apache.ignite.spi.eventstorage.memory.*;
import org.apache.ignite.spi.failover.*;
import org.apache.ignite.spi.failover.always.*;
import org.apache.ignite.spi.indexing.*;
import org.apache.ignite.spi.indexing.noop.*;
import org.apache.ignite.spi.loadbalancing.*;
import org.apache.ignite.spi.loadbalancing.roundrobin.*;
import org.apache.ignite.spi.swapspace.*;
import org.apache.ignite.spi.swapspace.file.*;
import org.apache.ignite.spi.swapspace.noop.*;
import org.apache.ignite.streamer.*;
import org.apache.ignite.thread.*;
import org.jdk8.backport.*;
import org.jetbrains.annotations.*;

import javax.management.*;
import java.io.*;
import java.lang.management.*;
import java.lang.reflect.*;
import java.net.*;
import java.util.*;
import java.util.Map.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.logging.*;

import static org.apache.ignite.IgniteState.*;
import static org.apache.ignite.IgniteSystemProperties.*;
import static org.apache.ignite.cache.CacheAtomicityMode.*;
import static org.apache.ignite.cache.CacheDistributionMode.*;
import static org.apache.ignite.cache.CacheMode.*;
import static org.apache.ignite.cache.CachePreloadMode.*;
import static org.apache.ignite.cache.CacheWriteSynchronizationMode.*;
import static org.apache.ignite.configuration.IgniteConfiguration.*;
import static org.apache.ignite.internal.IgniteComponentType.*;
import static org.apache.ignite.plugin.segmentation.GridSegmentationPolicy.*;

/**
 * This class defines a factory for the main Ignite API. It controls Grid life cycle
 * and allows listening for grid events.
 * <h1 class="header">Grid Loaders</h1>
 * Although user can apply grid factory directly to start and stop grid, grid is
 * often started and stopped by grid loaders. Grid loaders can be found in
 * {@link org.apache.ignite.startup} package, for example:
 * <ul>
 * <li>{@code GridCommandLineStartup}</li>
 * <li>{@code GridServletStartup}</li>
 * </ul>
 * <h1 class="header">Examples</h1>
 * Use {@link #start()} method to start grid with default configuration. You can also use
 * {@link org.apache.ignite.configuration.IgniteConfiguration} to override some default configuration. Below is an
 * example on how to start grid with <strong>URI deployment</strong>.
 * <pre name="code" class="java">
 * GridConfiguration cfg = new GridConfiguration();
 */
public class IgnitionEx {
    /** Default configuration path relative to Ignite home. */
    public static final String DFLT_CFG = "config/default-config.xml";

    /** Map of named grids. */
    private static final ConcurrentMap<Object, IgniteNamedInstance> grids = new ConcurrentHashMap8<>();

    /** Map of grid states ever started in this JVM. */
    private static final Map<Object, IgniteState> gridStates = new ConcurrentHashMap8<>();

    /** Mutex to synchronize updates of default grid reference. */
    private static final Object dfltGridMux = new Object();

    /** Default grid. */
    private static volatile IgniteNamedInstance dfltGrid;

    /** Default grid state. */
    private static volatile IgniteState dfltGridState;

    /** List of state listeners. */
    private static final Collection<IgnitionListener> lsnrs = new GridConcurrentHashSet<>(4);

    /** */
    private static volatile boolean daemon;

    /**
     * Checks runtime version to be 1.7.x or 1.8.x.
     * This will load pretty much first so we must do these checks here.
     */
    static {
        // Check 1.8 just in case for forward compatibility.
        if (!U.jdkVersion().contains("1.7") &&
            !U.jdkVersion().contains("1.8"))
            throw new IllegalStateException("Ignite requires Java 7 or above. Current Java version " +
                "is not supported: " + U.jdkVersion());

        // To avoid nasty race condition in UUID.randomUUID() in JDK prior to 6u34.
        // For details please see:
        // http://bugs.sun.com/bugdatabase/view_bug.do?bug_id=7071826
        // http://www.oracle.com/technetwork/java/javase/2col/6u34-bugfixes-1733379.html
        // http://hg.openjdk.java.net/jdk6/jdk6/jdk/rev/563d392b3e5c
        UUID.randomUUID();
    }

    /**
     * Enforces singleton.
     */
    private IgnitionEx() {
        // No-op.
    }

    /**
     * Sets daemon flag.
     * <p>
     * If daemon flag is set then all grid instances created by the factory will be
     * daemon, i.e. the local node for these instances will be a daemon node. Note that
     * if daemon flag is set - it will override the same settings in {@link org.apache.ignite.configuration.IgniteConfiguration#isDaemon()}.
     * Note that you can set on and off daemon flag at will.
     *
     * @param daemon Daemon flag to set.
     */
    public static void setDaemon(boolean daemon) {
        IgnitionEx.daemon = daemon;
    }

    /**
     * Gets daemon flag.
     * <p>
     * If daemon flag it set then all grid instances created by the factory will be
     * daemon, i.e. the local node for these instances will be a daemon node. Note that
     * if daemon flag is set - it will override the same settings in {@link org.apache.ignite.configuration.IgniteConfiguration#isDaemon()}.
     * Note that you can set on and off daemon flag at will.
     *
     * @return Daemon flag.
     */
    public static boolean isDaemon() {
        return daemon;
    }

    /**
     * Gets state of grid default grid.
     *
     * @return Default grid state.
     */
    public static IgniteState state() {
        return state(null);
    }

    /**
     * Gets states of named grid. If name is {@code null}, then state of
     * default no-name grid is returned.
     *
     * @param name Grid name. If name is {@code null}, then state of
     *      default no-name grid is returned.
     * @return Grid state.
     */
    public static IgniteState state(@Nullable String name) {
        IgniteNamedInstance grid = name != null ? grids.get(name) : dfltGrid;

        if (grid == null) {
            IgniteState state = name != null ? gridStates.get(name) : dfltGridState;

            return state != null ? state : STOPPED;
        }

        return grid.state();
    }

    /**
     * Stops default grid. This method is identical to {@code G.stop(null, cancel)} apply.
     * Note that method does not wait for all tasks to be completed.
     *
     * @param cancel If {@code true} then all jobs currently executing on
     *      default grid will be cancelled by calling {@link org.apache.ignite.compute.ComputeJob#cancel()}
     *      method. Note that just like with {@link Thread#interrupt()}, it is
     *      up to the actual job to exit from execution
     * @return {@code true} if default grid instance was indeed stopped,
     *      {@code false} otherwise (if it was not started).
     */
    public static boolean stop(boolean cancel) {
        return stop(null, cancel);
    }

    /**
     * Stops named grid. If {@code cancel} flag is set to {@code true} then
     * all jobs currently executing on local node will be interrupted. If
     * grid name is {@code null}, then default no-name grid will be stopped.
     * If wait parameter is set to {@code true} then grid will wait for all
     * tasks to be finished.
     *
     * @param name Grid name. If {@code null}, then default no-name grid will
     *      be stopped.
     * @param cancel If {@code true} then all jobs currently will be cancelled
     *      by calling {@link org.apache.ignite.compute.ComputeJob#cancel()} method. Note that just like with
     *      {@link Thread#interrupt()}, it is up to the actual job to exit from
     *      execution. If {@code false}, then jobs currently running will not be
     *      canceled. In either case, grid node will wait for completion of all
     *      jobs running on it before stopping.
     * @return {@code true} if named grid instance was indeed found and stopped,
     *      {@code false} otherwise (the instance with given {@code name} was
     *      not found).
     */
    public static boolean stop(@Nullable String name, boolean cancel) {
        IgniteNamedInstance grid = name != null ? grids.get(name) : dfltGrid;

        if (grid != null && grid.state() == STARTED) {
            grid.stop(cancel);

            boolean fireEvt;

            if (name != null)
                fireEvt = grids.remove(name, grid);
            else {
                synchronized (dfltGridMux) {
                    fireEvt = dfltGrid == grid;

                    if (fireEvt)
                        dfltGrid = null;
                }
            }

            if (fireEvt)
                notifyStateChange(grid.getName(), grid.state());

            return true;
        }

        // We don't have log at this point...
        U.warn(null, "Ignoring stopping grid instance that was already stopped or never started: " + name);

        return false;
    }

    /**
     * Stops <b>all</b> started grids. If {@code cancel} flag is set to {@code true} then
     * all jobs currently executing on local node will be interrupted.
     * If wait parameter is set to {@code true} then grid will wait for all
     * tasks to be finished.
     * <p>
     * <b>Note:</b> it is usually safer and more appropriate to stop grid instances individually
     * instead of blanket operation. In most cases, the party that started the grid instance
     * should be responsible for stopping it.
     *
     * @param cancel If {@code true} then all jobs currently executing on
     *      all grids will be cancelled by calling {@link org.apache.ignite.compute.ComputeJob#cancel()}
     *      method. Note that just like with {@link Thread#interrupt()}, it is
     *      up to the actual job to exit from execution
     */
    public static void stopAll(boolean cancel) {
        IgniteNamedInstance dfltGrid0 = dfltGrid;

        if (dfltGrid0 != null) {
            dfltGrid0.stop(cancel);

            boolean fireEvt;

            synchronized (dfltGridMux) {
                fireEvt = dfltGrid == dfltGrid0;

                if (fireEvt)
                    dfltGrid = null;
            }

            if (fireEvt)
                notifyStateChange(dfltGrid0.getName(), dfltGrid0.state());
        }

        // Stop the rest and clear grids map.
        for (IgniteNamedInstance grid : grids.values()) {
            grid.stop(cancel);

            boolean fireEvt = grids.remove(grid.getName(), grid);

            if (fireEvt)
                notifyStateChange(grid.getName(), grid.state());
        }
    }

    /**
     * Restarts <b>all</b> started grids. If {@code cancel} flag is set to {@code true} then
     * all jobs currently executing on the local node will be interrupted.
     * If {@code wait} parameter is set to {@code true} then grid will wait for all
     * tasks to be finished.
     * <p>
     * <b>Note:</b> it is usually safer and more appropriate to stop grid instances individually
     * instead of blanket operation. In most cases, the party that started the grid instance
     * should be responsible for stopping it.
     * <p>
     * Note also that restarting functionality only works with the tools that specifically
     * support Ignite's protocol for restarting. Currently only standard <tt>ignite.{sh|bat}</tt>
     * scripts support restarting of JVM Ignite's process.
     *
     * @param cancel If {@code true} then all jobs currently executing on
     *      all grids will be cancelled by calling {@link org.apache.ignite.compute.ComputeJob#cancel()}
     *      method. Note that just like with {@link Thread#interrupt()}, it is
     *      up to the actual job to exit from execution.
     * @see org.apache.ignite.Ignition#RESTART_EXIT_CODE
     */
    public static void restart(boolean cancel) {
        String file = System.getProperty(IGNITE_SUCCESS_FILE);

        if (file == null)
            U.warn(null, "Cannot restart node when restart not enabled.");
        else {
            try {
                new File(file).createNewFile();
            }
            catch (IOException e) {
                U.error(null, "Failed to create restart marker file (restart aborted): " + e.getMessage());

                return;
            }

            U.log(null, "Restarting node. Will exit (" + Ignition.RESTART_EXIT_CODE + ").");

            // Set the exit code so that shell process can recognize it and loop
            // the start up sequence again.
            System.setProperty(IGNITE_RESTART_CODE, Integer.toString(Ignition.RESTART_EXIT_CODE));

            stopAll(cancel);

            // This basically leaves loaders hang - we accept it.
            System.exit(Ignition.RESTART_EXIT_CODE);
        }
    }

    /**
     * Stops <b>all</b> started grids. If {@code cancel} flag is set to {@code true} then
     * all jobs currently executing on the local node will be interrupted.
     * If {@code wait} parameter is set to {@code true} then grid will wait for all
     * tasks to be finished.
     * <p>
     * <b>Note:</b> it is usually safer and more appropriate to stop grid instances individually
     * instead of blanket operation. In most cases, the party that started the grid instance
     * should be responsible for stopping it.
     * <p>
     * Note that upon completion of this method, the JVM with forcefully exist with
     * exit code {@link org.apache.ignite.Ignition#KILL_EXIT_CODE}.
     *
     * @param cancel If {@code true} then all jobs currently executing on
     *      all grids will be cancelled by calling {@link org.apache.ignite.compute.ComputeJob#cancel()}
     *      method. Note that just like with {@link Thread#interrupt()}, it is
     *      up to the actual job to exit from execution.
     * @see org.apache.ignite.Ignition#KILL_EXIT_CODE
     */
    public static void kill(boolean cancel) {
        stopAll(cancel);

        // This basically leaves loaders hang - we accept it.
        System.exit(Ignition.KILL_EXIT_CODE);
    }

    /**
     * Starts grid with default configuration. By default this method will
     * use grid configuration defined in {@code IGNITE_HOME/config/default-config.xml}
     * configuration file. If such file is not found, then all system defaults will be used.
     *
     * @return Started grid.
     * @throws IgniteCheckedException If default grid could not be started. This exception will be thrown
     *      also if default grid has already been started.
     */
    public static Ignite start() throws IgniteCheckedException {
        return start((GridSpringResourceContext)null);
    }

    /**
     * Starts grid with default configuration. By default this method will
     * use grid configuration defined in {@code IGNITE_HOME/config/default-config.xml}
     * configuration file. If such file is not found, then all system defaults will be used.
     *
     * @param springCtx Optional Spring application context, possibly {@code null}.
     *      Spring bean definitions for bean injection are taken from this context.
     *      If provided, this context can be injected into grid tasks and grid jobs using
     *      {@link org.apache.ignite.resources.SpringApplicationContextResource @IgniteSpringApplicationContextResource} annotation.
     * @return Started grid.
     * @throws IgniteCheckedException If default grid could not be started. This exception will be thrown
     *      also if default grid has already been started.
     */
    public static Ignite start(@Nullable GridSpringResourceContext springCtx) throws IgniteCheckedException {
        URL url = U.resolveIgniteUrl(DFLT_CFG);

        if (url != null)
            return start(DFLT_CFG, null, springCtx);

        U.warn(null, "Default Spring XML file not found (is IGNITE_HOME set?): " + DFLT_CFG);

        return start0(new GridStartContext(new IgniteConfiguration(), null, springCtx)).grid();
    }

    /**
     * Starts grid with given configuration. Note that this method is no-op if grid with the name
     * provided in given configuration is already started.
     *
     * @param cfg Grid configuration. This cannot be {@code null}.
     * @return Started grid.
     * @throws IgniteCheckedException If grid could not be started. This exception will be thrown
     *      also if named grid has already been started.
     */
    public static Ignite start(IgniteConfiguration cfg) throws IgniteCheckedException {
        return start(cfg, null);
    }

    /**
     * Starts grid with given configuration. Note that this method is no-op if grid with the name
     * provided in given configuration is already started.
     *
     * @param cfg Grid configuration. This cannot be {@code null}.
     * @param springCtx Optional Spring application context, possibly {@code null}.
     *      Spring bean definitions for bean injection are taken from this context.
     *      If provided, this context can be injected into grid tasks and grid jobs using
     *      {@link org.apache.ignite.resources.SpringApplicationContextResource @IgniteSpringApplicationContextResource} annotation.
     * @return Started grid.
     * @throws IgniteCheckedException If grid could not be started. This exception will be thrown
     *      also if named grid has already been started.
     */
    public static Ignite start(IgniteConfiguration cfg, @Nullable GridSpringResourceContext springCtx) throws IgniteCheckedException {
        A.notNull(cfg, "cfg");

        return start0(new GridStartContext(cfg, null, springCtx)).grid();
    }

    /**
     * Starts all grids specified within given Spring XML configuration file. If grid with given name
     * is already started, then exception is thrown. In this case all instances that may
     * have been started so far will be stopped too.
     * <p>
     * Usually Spring XML configuration file will contain only one Grid definition. Note that
     * Grid configuration bean(s) is retrieved form configuration file by type, so the name of
     * the Grid configuration bean is ignored.
     *
     * @param springCfgPath Spring XML configuration file path or URL.
     * @return Started grid. If Spring configuration contains multiple grid instances,
     *      then the 1st found instance is returned.
     * @throws IgniteCheckedException If grid could not be started or configuration
     *      read. This exception will be thrown also if grid with given name has already
     *      been started or Spring XML configuration file is invalid.
     */
    public static Ignite start(@Nullable String springCfgPath) throws IgniteCheckedException {
        return springCfgPath == null ? start() : start(springCfgPath, null);
    }

    /**
     * Starts all grids specified within given Spring XML configuration file. If grid with given name
     * is already started, then exception is thrown. In this case all instances that may
     * have been started so far will be stopped too.
     * <p>
     * Usually Spring XML configuration file will contain only one Grid definition. Note that
     * Grid configuration bean(s) is retrieved form configuration file by type, so the name of
     * the Grid configuration bean is ignored.
     *
     * @param springCfgPath Spring XML configuration file path or URL.
     * @param gridName Grid name that will override default.
     * @return Started grid. If Spring configuration contains multiple grid instances,
     *      then the 1st found instance is returned.
     * @throws IgniteCheckedException If grid could not be started or configuration
     *      read. This exception will be thrown also if grid with given name has already
     *      been started or Spring XML configuration file is invalid.
     */
    public static Ignite start(@Nullable String springCfgPath, @Nullable String gridName) throws IgniteCheckedException {
        if (springCfgPath == null) {
            IgniteConfiguration cfg = new IgniteConfiguration();

            if (cfg.getGridName() == null && !F.isEmpty(gridName))
                cfg.setGridName(gridName);

            return start(cfg);
        }
        else
            return start(springCfgPath, gridName, null);
    }

    /**
     * Start Grid for interop scenario.
     *
     * @param springCfgPath Spring config path.
     * @param gridName Grid name.
     * @param cfgClo Configuration closure.
     * @return Started Grid.
     * @throws IgniteCheckedException If failed.
     */
    public static Ignite startInterop(@Nullable String springCfgPath, @Nullable String gridName,
        IgniteClosure<IgniteConfiguration, IgniteConfiguration> cfgClo) throws IgniteCheckedException {
        URL url = resolveSpringUrl(springCfgPath);

        return start(url, gridName, null, cfgClo);
    }

    /**
     * Loads all grid configurations specified within given Spring XML configuration file.
     * <p>
     * Usually Spring XML configuration file will contain only one Grid definition. Note that
     * Grid configuration bean(s) is retrieved form configuration file by type, so the name of
     * the Grid configuration bean is ignored.
     *
     * @param springCfgUrl Spring XML configuration file path or URL. This cannot be {@code null}.
     * @return Tuple containing all loaded configurations and Spring context used to load them.
     * @throws IgniteCheckedException If grid could not be started or configuration
     *      read. This exception will be thrown also if grid with given name has already
     *      been started or Spring XML configuration file is invalid.
     */
    public static IgniteBiTuple<Collection<IgniteConfiguration>, ? extends GridSpringResourceContext> loadConfigurations(
        URL springCfgUrl) throws IgniteCheckedException {
        IgniteSpringProcessor spring = SPRING.create(false);

        return spring.loadConfigurations(springCfgUrl);
    }

    /**
     * Loads all grid configurations specified within given Spring XML configuration file.
     * <p>
     * Usually Spring XML configuration file will contain only one Grid definition. Note that
     * Grid configuration bean(s) is retrieved form configuration file by type, so the name of
     * the Grid configuration bean is ignored.
     *
     * @param springCfgPath Spring XML configuration file path. This cannot be {@code null}.
     * @return Tuple containing all loaded configurations and Spring context used to load them.
     * @throws IgniteCheckedException If grid could not be started or configuration
     *      read. This exception will be thrown also if grid with given name has already
     *      been started or Spring XML configuration file is invalid.
     */
    public static IgniteBiTuple<Collection<IgniteConfiguration>, ? extends GridSpringResourceContext> loadConfigurations(
        String springCfgPath) throws IgniteCheckedException {
        A.notNull(springCfgPath, "springCfgPath");

        URL url;

        try {
            url = new URL(springCfgPath);
        }
        catch (MalformedURLException e) {
            url = U.resolveIgniteUrl(springCfgPath);

            if (url == null)
                throw new IgniteCheckedException("Spring XML configuration path is invalid: " + springCfgPath +
                    ". Note that this path should be either absolute or a relative local file system path, " +
                    "relative to META-INF in classpath or valid URL to IGNITE_HOME.", e);
        }

        return loadConfigurations(url);
    }

    /**
     * Loads first found grid configuration specified within given Spring XML configuration file.
     * <p>
     * Usually Spring XML configuration file will contain only one Grid definition. Note that
     * Grid configuration bean(s) is retrieved form configuration file by type, so the name of
     * the Grid configuration bean is ignored.
     *
     * @param springCfgUrl Spring XML configuration file path or URL. This cannot be {@code null}.
     * @return First found configuration and Spring context used to load it.
     * @throws IgniteCheckedException If grid could not be started or configuration
     *      read. This exception will be thrown also if grid with given name has already
     *      been started or Spring XML configuration file is invalid.
     */
    public static IgniteBiTuple<IgniteConfiguration, GridSpringResourceContext> loadConfiguration(URL springCfgUrl)
        throws IgniteCheckedException {
        IgniteBiTuple<Collection<IgniteConfiguration>, ? extends GridSpringResourceContext> t = loadConfigurations(springCfgUrl);

        return F.t(F.first(t.get1()), t.get2());
    }

    /**
     * Loads first found grid configuration specified within given Spring XML configuration file.
     * <p>
     * Usually Spring XML configuration file will contain only one Grid definition. Note that
     * Grid configuration bean(s) is retrieved form configuration file by type, so the name of
     * the Grid configuration bean is ignored.
     *
     * @param springCfgPath Spring XML configuration file path. This cannot be {@code null}.
     * @return First found configuration and Spring context used to load it.
     * @throws IgniteCheckedException If grid could not be started or configuration
     *      read. This exception will be thrown also if grid with given name has already
     *      been started or Spring XML configuration file is invalid.
     */
    public static IgniteBiTuple<IgniteConfiguration, GridSpringResourceContext> loadConfiguration(String springCfgPath)
        throws IgniteCheckedException {
        IgniteBiTuple<Collection<IgniteConfiguration>, ? extends GridSpringResourceContext> t =
            loadConfigurations(springCfgPath);

        return F.t(F.first(t.get1()), t.get2());
    }

    /**
     * Starts all grids specified within given Spring XML configuration file. If grid with given name
     * is already started, then exception is thrown. In this case all instances that may
     * have been started so far will be stopped too.
     * <p>
     * Usually Spring XML configuration file will contain only one Grid definition. Note that
     * Grid configuration bean(s) is retrieved form configuration file by type, so the name of
     * the Grid configuration bean is ignored.
     *
     * @param springCfgPath Spring XML configuration file path or URL. This cannot be {@code null}.
     * @param gridName Grid name that will override default.
     * @param springCtx Optional Spring application context, possibly {@code null}.
     *      Spring bean definitions for bean injection are taken from this context.
     *      If provided, this context can be injected into grid tasks and grid jobs using
     *      {@link org.apache.ignite.resources.SpringApplicationContextResource @IgniteSpringApplicationContextResource} annotation.
     * @return Started grid. If Spring configuration contains multiple grid instances,
     *      then the 1st found instance is returned.
     * @throws IgniteCheckedException If grid could not be started or configuration
     *      read. This exception will be thrown also if grid with given name has already
     *      been started or Spring XML configuration file is invalid.
     */
    public static Ignite start(String springCfgPath, @Nullable String gridName,
        @Nullable GridSpringResourceContext springCtx) throws IgniteCheckedException {
        URL url = resolveSpringUrl(springCfgPath);

        return start(url, gridName, springCtx);
    }

    /**
     * Starts all grids specified within given Spring XML configuration file URL. If grid with given name
     * is already started, then exception is thrown. In this case all instances that may
     * have been started so far will be stopped too.
     * <p>
     * Usually Spring XML configuration file will contain only one Grid definition. Note that
     * Grid configuration bean(s) is retrieved form configuration file by type, so the name of
     * the Grid configuration bean is ignored.
     *
     * @param springCfgUrl Spring XML configuration file URL. This cannot be {@code null}.
     * @return Started grid. If Spring configuration contains multiple grid instances,
     *      then the 1st found instance is returned.
     * @throws IgniteCheckedException If grid could not be started or configuration
     *      read. This exception will be thrown also if grid with given name has already
     *      been started or Spring XML configuration file is invalid.
     */
    public static Ignite start(URL springCfgUrl) throws IgniteCheckedException {
        return start(springCfgUrl, null, null);
    }

    /**
     * Starts all grids specified within given Spring XML configuration file URL. If grid with given name
     * is already started, then exception is thrown. In this case all instances that may
     * have been started so far will be stopped too.
     * <p>
     * Usually Spring XML configuration file will contain only one Grid definition. Note that
     * Grid configuration bean(s) is retrieved form configuration file by type, so the name of
     * the Grid configuration bean is ignored.
     *
     * @param springCfgUrl Spring XML configuration file URL. This cannot be {@code null}.
     * @param gridName Grid name that will override default.
     * @param springCtx Optional Spring application context, possibly {@code null}.
     *      Spring bean definitions for bean injection are taken from this context.
     *      If provided, this context can be injected into grid tasks and grid jobs using
     *      {@link org.apache.ignite.resources.SpringApplicationContextResource @IgniteSpringApplicationContextResource} annotation.
     * @return Started grid. If Spring configuration contains multiple grid instances,
     *      then the 1st found instance is returned.
     * @throws IgniteCheckedException If grid could not be started or configuration
     *      read. This exception will be thrown also if grid with given name has already
     *      been started or Spring XML configuration file is invalid.
     */
    public static Ignite start(URL springCfgUrl, @Nullable String gridName,
        @Nullable GridSpringResourceContext springCtx) throws IgniteCheckedException {
        return start(springCfgUrl, gridName, springCtx, null);
    }

    /**
     * Internal Spring-based start routine.
     *
     * @param springCfgUrl Spring XML configuration file URL. This cannot be {@code null}.
     * @param gridName Grid name that will override default.
     * @param springCtx Optional Spring application context.
     * @param cfgClo Optional closure to change configuration before it is used to start the grid.
     * @return Started grid.
     * @throws IgniteCheckedException If failed.
     */
    private static Ignite start(final URL springCfgUrl, @Nullable String gridName,
        @Nullable GridSpringResourceContext springCtx,
        @Nullable IgniteClosure<IgniteConfiguration, IgniteConfiguration> cfgClo)
        throws IgniteCheckedException {
        A.notNull(springCfgUrl, "springCfgUrl");

        boolean isLog4jUsed = U.gridClassLoader().getResource("org/apache/log4j/Appender.class") != null;

        IgniteBiTuple<Object, Object> t = null;

        Collection<Handler> savedHnds = null;

        if (isLog4jUsed) {
            try {
                t = U.addLog4jNoOpLogger();
            }
            catch (IgniteCheckedException e) {
                isLog4jUsed = false;
            }
        }

        if (!isLog4jUsed)
            savedHnds = U.addJavaNoOpLogger();

        IgniteBiTuple<Collection<IgniteConfiguration>, ? extends GridSpringResourceContext> cfgMap;

        try {
            cfgMap = loadConfigurations(springCfgUrl);
        }
        finally {
            if (isLog4jUsed && t != null)
                U.removeLog4jNoOpLogger(t);

            if (!isLog4jUsed)
                U.removeJavaNoOpLogger(savedHnds);
        }

        List<IgniteNamedInstance> grids = new ArrayList<>(cfgMap.size());

        try {
            for (IgniteConfiguration cfg : cfgMap.get1()) {
                assert cfg != null;

                if (cfg.getGridName() == null && !F.isEmpty(gridName))
                    cfg.setGridName(gridName);

                if (cfgClo != null) {
                    cfg = cfgClo.apply(cfg);

                    assert cfg != null;
                }

                // Use either user defined context or our one.
                IgniteNamedInstance grid = start0(
                    new GridStartContext(cfg, springCfgUrl, springCtx == null ? cfgMap.get2() : springCtx));

                // Add it if it was not stopped during startup.
                if (grid != null)
                    grids.add(grid);
            }
        }
        catch (IgniteCheckedException e) {
            // Stop all instances started so far.
            for (IgniteNamedInstance grid : grids) {
                try {
                    grid.stop(true);
                }
                catch (Exception e1) {
                    U.error(grid.log, "Error when stopping grid: " + grid, e1);
                }
            }

            throw e;
        }

        // Return the first grid started.
        IgniteNamedInstance res = !grids.isEmpty() ? grids.get(0) : null;

        return res != null ? res.grid() : null;
    }

    /**
     * Resolve Spring configuration URL.
     *
     * @param springCfgPath Spring XML configuration file path or URL. This cannot be {@code null}.
     * @return URL.
     * @throws IgniteCheckedException If failed.
     */
    private static URL resolveSpringUrl(String springCfgPath) throws IgniteCheckedException {
        A.notNull(springCfgPath, "springCfgPath");

        URL url;

        try {
            url = new URL(springCfgPath);
        }
        catch (MalformedURLException e) {
            url = U.resolveIgniteUrl(springCfgPath);

            if (url == null)
                throw new IgniteCheckedException("Spring XML configuration path is invalid: " + springCfgPath +
                    ". Note that this path should be either absolute or a relative local file system path, " +
                    "relative to META-INF in classpath or valid URL to IGNITE_HOME.", e);
        }

        return url;
    }

    /**
     * Starts grid with given configuration.
     *
     * @param startCtx Start context.
     * @return Started grid.
     * @throws IgniteCheckedException If grid could not be started.
     */
    private static IgniteNamedInstance start0(GridStartContext startCtx) throws IgniteCheckedException {
        assert startCtx != null;

        String name = startCtx.config().getGridName();

        if (name != null && name.isEmpty())
            throw new IgniteCheckedException("Non default grid instances cannot have empty string name.");

        IgniteNamedInstance grid = new IgniteNamedInstance(name);

        IgniteNamedInstance old;

        if (name != null)
            old = grids.putIfAbsent(name, grid);
        else {
            synchronized (dfltGridMux) {
                old = dfltGrid;

                if (old == null)
                    dfltGrid = grid;
            }
        }

        if (old != null) {
            if (name == null)
                throw new IgniteCheckedException("Default grid instance has already been started.");
            else
                throw new IgniteCheckedException("Ignite instance with this name has already been started: " + name);
        }

        if (startCtx.config().getWarmupClosure() != null)
            startCtx.config().getWarmupClosure().apply(startCtx.config());

        startCtx.single(grids.size() == 1);

        boolean success = false;

        try {
            grid.start(startCtx);

            notifyStateChange(name, STARTED);

            success = true;
        }
        finally {
            if (!success) {
                if (name != null)
                    grids.remove(name, grid);
                else {
                    synchronized (dfltGridMux) {
                        if (dfltGrid == grid)
                            dfltGrid = null;
                    }
                }

                grid = null;
            }
        }

        if (grid == null)
            throw new IgniteCheckedException("Failed to start grid with provided configuration.");

        return grid;
    }

    /**
     * Gets an instance of default no-name grid. Note that
     * caller of this method should not assume that it will return the same
     * instance every time.
     * <p>
     * This method is identical to {@code G.grid(null)} apply.
     *
     * @return An instance of default no-name grid. This method never returns
     *      {@code null}.
     * @throws org.apache.ignite.IgniteIllegalStateException Thrown if default grid was not properly
     *      initialized or grid instance was stopped or was not started.
     */
    public static Ignite grid() throws IgniteIllegalStateException {
        return grid((String)null);
    }

    /**
     * Gets a list of all grids started so far.
     *
     * @return List of all grids started so far.
     */
    public static List<Ignite> allGrids() {
        List<Ignite> allIgnites = new ArrayList<>(grids.size() + 1);

        for (IgniteNamedInstance grid : grids.values()) {
            Ignite g = grid.grid();

            if (g != null)
                allIgnites.add(g);
        }

        IgniteNamedInstance dfltGrid0 = dfltGrid;

        if (dfltGrid0 != null) {
            IgniteKernal g = dfltGrid0.grid();

            if (g != null)
                allIgnites.add(g);
        }

        return allIgnites;
    }

    /**
     * Gets a grid instance for given local node ID. Note that grid instance and local node have
     * one-to-one relationship where node has ID and instance has name of the grid to which
     * both grid instance and its node belong. Note also that caller of this method
     * should not assume that it will return the same instance every time.
     *
     * @param locNodeId ID of local node the requested grid instance is managing.
     * @return An instance of named grid. This method never returns
     *      {@code null}.
     * @throws org.apache.ignite.IgniteIllegalStateException Thrown if grid was not properly
     *      initialized or grid instance was stopped or was not started.
     */
    public static Ignite grid(UUID locNodeId) throws IgniteIllegalStateException {
        A.notNull(locNodeId, "locNodeId");

        IgniteNamedInstance dfltGrid0 = dfltGrid;

        if (dfltGrid0 != null) {
            IgniteKernal g = dfltGrid0.grid();

            if (g != null && g.getLocalNodeId().equals(locNodeId))
                return g;
        }

        for (IgniteNamedInstance grid : grids.values()) {
            IgniteKernal g = grid.grid();

            if (g != null && g.getLocalNodeId().equals(locNodeId))
                return g;
        }

        throw new IgniteIllegalStateException("Grid instance with given local node ID was not properly " +
            "started or was stopped: " + locNodeId);
    }

    /**
     * Gets an named grid instance. If grid name is {@code null} or empty string,
     * then default no-name grid will be returned. Note that caller of this method
     * should not assume that it will return the same instance every time.
     * <p>
     * Note that Java VM can run multiple grid instances and every grid instance (and its
     * node) can belong to a different grid. Grid name defines what grid a particular grid
     * instance (and correspondingly its node) belongs to.
     *
     * @param name Grid name to which requested grid instance belongs to. If {@code null},
     *      then grid instance belonging to a default no-name grid will be returned.
     * @return An instance of named grid. This method never returns
     *      {@code null}.
     * @throws org.apache.ignite.IgniteIllegalStateException Thrown if default grid was not properly
     *      initialized or grid instance was stopped or was not started.
     */
    public static Ignite grid(@Nullable String name) throws IgniteIllegalStateException {
        IgniteNamedInstance grid = name != null ? grids.get(name) : dfltGrid;

        Ignite res;

        if (grid == null || (res = grid.grid()) == null)
            throw new IgniteIllegalStateException("Grid instance was not properly started " +
                "or was already stopped: " + name);

        return res;
    }

    /**
     * Gets grid instance without waiting its initialization.
     *
     * @param name Grid name.
     * @return Grid instance.
     */
    public static IgniteKernal gridx(@Nullable String name) {
        IgniteNamedInstance grid = name != null ? grids.get(name) : dfltGrid;

        IgniteKernal res;

        if (grid == null || (res = grid.gridx()) == null)
            throw new IllegalStateException("Grid instance was not properly started or was already stopped: " + name);

        return res;
    }

    /**
     * Adds a lsnr for grid life cycle events.
     * <p>
     * Note that unlike other listeners in Ignite this listener will be
     * notified from the same thread that triggers the state change. Because of
     * that it is the responsibility of the user to make sure that listener logic
     * is light-weight and properly handles (catches) any runtime exceptions, if any
     * are expected.
     *
     * @param lsnr Listener for grid life cycle events. If this listener was already added
     *      this method is no-op.
     */
    public static void addListener(IgnitionListener lsnr) {
        A.notNull(lsnr, "lsnr");

        lsnrs.add(lsnr);
    }

    /**
     * Removes lsnr added by {@link #addListener(org.apache.ignite.IgnitionListener)} method.
     *
     * @param lsnr Listener to remove.
     * @return {@code true} if lsnr was added before, {@code false} otherwise.
     */
    public static boolean removeListener(IgnitionListener lsnr) {
        A.notNull(lsnr, "lsnr");

        return lsnrs.remove(lsnr);
    }

    /**
     * @param gridName Grid instance name.
     * @param state Factory state.
     */
    private static void notifyStateChange(@Nullable String gridName, IgniteState state) {
        if (gridName != null)
            gridStates.put(gridName, state);
        else
            dfltGridState = state;

        for (IgnitionListener lsnr : lsnrs)
            lsnr.onStateChange(gridName, state);
    }

    /**
     * Start context encapsulates all starting parameters.
     */
    private static final class GridStartContext {
        /** User-defined configuration. */
        private IgniteConfiguration cfg;

        /** Optional configuration path. */
        private URL cfgUrl;

        /** Optional Spring application context. */
        private GridSpringResourceContext springCtx;

        /** Whether or not this is a single grid instance in current VM. */
        private boolean single;

        /**
         *
         * @param cfg User-defined configuration.
         * @param cfgUrl Optional configuration path.
         * @param springCtx Optional Spring application context.
         */
        GridStartContext(IgniteConfiguration cfg, @Nullable URL cfgUrl, @Nullable GridSpringResourceContext springCtx) {
            assert(cfg != null);

            this.cfg = cfg;
            this.cfgUrl = cfgUrl;
            this.springCtx = springCtx;
        }

        /**
         * @return Whether or not this is a single grid instance in current VM.
         */
        public boolean single() {
            return single;
        }

        /**
         * @param single Whether or not this is a single grid instance in current VM.
         */
        public void single(boolean single) {
            this.single = single;
        }

        /**
         * @return User-defined configuration.
         */
        IgniteConfiguration config() {
            return cfg;
        }

        /**
         * @param cfg User-defined configuration.
         */
        void config(IgniteConfiguration cfg) {
            this.cfg = cfg;
        }

        /**
         * @return Optional configuration path.
         */
        URL configUrl() {
            return cfgUrl;
        }

        /**
         * @param cfgUrl Optional configuration path.
         */
        void configUrl(URL cfgUrl) {
            this.cfgUrl = cfgUrl;
        }

        /**
         * @return Optional Spring application context.
         */
        public GridSpringResourceContext springContext() {
            return springCtx;
        }
    }

    /**
     * Grid data container.
     */
    private static final class IgniteNamedInstance {
        /** Map of registered MBeans. */
        private static final Map<MBeanServer, GridMBeanServerData> mbeans =
            new HashMap<>();

        /** */
        private static final String[] EMPTY_STR_ARR = new String[0];

        /** Grid name. */
        private final String name;

        /** Grid instance. */
        private volatile IgniteKernal grid;

        /** Executor service. */
        private ExecutorService execSvc;

        /** System executor service. */
        private ExecutorService sysExecSvc;

        /** Management executor service. */
        private ExecutorService mgmtExecSvc;

        /** P2P executor service. */
        private ExecutorService p2pExecSvc;

        /** IGFS executor service. */
        private ExecutorService igfsExecSvc;

        /** REST requests executor service. */
        private ExecutorService restExecSvc;

        /** Utility cache executor service. */
        private ExecutorService utilityCacheExecSvc;

        /** Grid state. */
        private volatile IgniteState state = STOPPED;

        /** Shutdown hook. */
        private Thread shutdownHook;

        /** Grid log. */
        private IgniteLogger log;

        /** Start guard. */
        private final AtomicBoolean startGuard = new AtomicBoolean();

        /** Start latch. */
        private final CountDownLatch startLatch = new CountDownLatch(1);

        /**
         * Thread that starts this named instance. This field can be non-volatile since
         * it makes sense only for thread where it was originally initialized.
         */
        @SuppressWarnings("FieldAccessedSynchronizedAndUnsynchronized")
        private Thread starterThread;

        /**
         * Creates un-started named instance.
         *
         * @param name Grid name (possibly {@code null} for default grid).
         */
        IgniteNamedInstance(@Nullable String name) {
            this.name = name;
        }

        /**
         * Gets grid name.
         *
         * @return Grid name.
         */
        String getName() {
            return name;
        }

        /**
         * Gets grid instance.
         *
         * @return Grid instance.
         */
        IgniteKernal grid() {
            if (starterThread != Thread.currentThread())
                U.awaitQuiet(startLatch);

            return grid;
        }

        /**
         * Gets grid instance without waiting for its initialization.
         *
         * @return Grid instance.
         */
        public IgniteKernal gridx() {
            return grid;
        }

        /**
         * Gets grid state.
         *
         * @return Grid state.
         */
        IgniteState state() {
            if (starterThread != Thread.currentThread())
                U.awaitQuiet(startLatch);

            return state;
        }

        /**
         * @param spi SPI implementation.
         * @throws IgniteCheckedException Thrown in case if multi-instance is not supported.
         */
        private void ensureMultiInstanceSupport(IgniteSpi spi) throws IgniteCheckedException {
            IgniteSpiMultipleInstancesSupport ann = U.getAnnotation(spi.getClass(),
                IgniteSpiMultipleInstancesSupport.class);

            if (ann == null || !ann.value())
                throw new IgniteCheckedException("SPI implementation doesn't support multiple grid instances in " +
                    "the same VM: " + spi);
        }

        /**
         * @param spis SPI implementations.
         * @throws IgniteCheckedException Thrown in case if multi-instance is not supported.
         */
        private void ensureMultiInstanceSupport(IgniteSpi[] spis) throws IgniteCheckedException {
            for (IgniteSpi spi : spis)
                ensureMultiInstanceSupport(spi);
        }

        /**
         * Starts grid with given configuration.
         *
         * @param startCtx Starting context.
         * @throws IgniteCheckedException If start failed.
         */
        synchronized void start(GridStartContext startCtx) throws IgniteCheckedException {
            if (startGuard.compareAndSet(false, true)) {
                try {
                    starterThread = Thread.currentThread();

                    start0(startCtx);
                }
                catch (Exception e) {
                    if (log != null)
                        stopExecutors(log);

                    throw e;
                }
                finally {
                    startLatch.countDown();
                }
            }
            else
                U.awaitQuiet(startLatch);
        }

        /**
         * @param startCtx Starting context.
         * @throws IgniteCheckedException If start failed.
         */
        @SuppressWarnings({"unchecked", "TooBroadScope"})
        private void start0(GridStartContext startCtx) throws IgniteCheckedException {
            assert grid == null : "Grid is already started: " + name;

            IgniteConfiguration cfg = startCtx.config();

            if (cfg == null)
                cfg = new IgniteConfiguration();

            IgniteConfiguration myCfg = new IgniteConfiguration();

            String ggHome = cfg.getIgniteHome();

            // Set Ignite home.
            if (ggHome == null)
                ggHome = U.getIgniteHome();
            else
                // If user provided IGNITE_HOME - set it as a system property.
                U.setIgniteHome(ggHome);

            U.setWorkDirectory(cfg.getWorkDirectory(), ggHome);

            /*
             * Set up all defaults and perform all checks.
             */

            // Ensure invariant.
            // It's a bit dirty - but this is a result of late refactoring
            // and I don't want to reshuffle a lot of code.
            assert F.eq(name, cfg.getGridName());

            // Set configuration URL, if any, into system property.
            if (startCtx.configUrl() != null)
                System.setProperty(IGNITE_CONFIG_URL, startCtx.configUrl().toString());

            myCfg.setGridName(cfg.getGridName());

            UUID nodeId = cfg.getNodeId();

            if (nodeId == null)
                nodeId = UUID.randomUUID();

            IgniteLogger cfgLog = initLogger(cfg.getGridLogger(), nodeId);

            assert cfgLog != null;

            cfgLog = new GridLoggerProxy(cfgLog, null, name, U.id8(nodeId));

            // Initialize factory's log.
            log = cfgLog.getLogger(G.class);

            // Check Ignite home folder (after log is available).
            if (ggHome != null) {
                File ggHomeFile = new File(ggHome);

                if (!ggHomeFile.exists() || !ggHomeFile.isDirectory())
                    throw new IgniteCheckedException("Invalid Ignite installation home folder: " + ggHome);
            }

            myCfg.setIgniteHome(ggHome);

            // Copy values that don't need extra processing.
            myCfg.setPeerClassLoadingEnabled(cfg.isPeerClassLoadingEnabled());
            myCfg.setDeploymentMode(cfg.getDeploymentMode());
            myCfg.setNetworkTimeout(cfg.getNetworkTimeout());
            myCfg.setClockSyncSamples(cfg.getClockSyncSamples());
            myCfg.setClockSyncFrequency(cfg.getClockSyncFrequency());
            myCfg.setDiscoveryStartupDelay(cfg.getDiscoveryStartupDelay());
            myCfg.setMetricsHistorySize(cfg.getMetricsHistorySize());
            myCfg.setMetricsExpireTime(cfg.getMetricsExpireTime());
            myCfg.setMetricsUpdateFrequency(cfg.getMetricsUpdateFrequency());
            myCfg.setLifecycleBeans(cfg.getLifecycleBeans());
            myCfg.setLocalEventListeners(cfg.getLocalEventListeners());
            myCfg.setPeerClassLoadingMissedResourcesCacheSize(cfg.getPeerClassLoadingMissedResourcesCacheSize());
            myCfg.setIncludeEventTypes(cfg.getIncludeEventTypes());
            myCfg.setDaemon(cfg.isDaemon());
            myCfg.setIncludeProperties(cfg.getIncludeProperties());
            myCfg.setMetricsLogFrequency(cfg.getMetricsLogFrequency());
            myCfg.setNetworkSendRetryDelay(cfg.getNetworkSendRetryDelay());
            myCfg.setNetworkSendRetryCount(cfg.getNetworkSendRetryCount());
            myCfg.setServiceConfiguration(cfg.getServiceConfiguration());
            myCfg.setWarmupClosure(cfg.getWarmupClosure());
            myCfg.setPluginConfigurations(cfg.getPluginConfigurations());
            myCfg.setTransactionConfiguration(new TransactionConfiguration(cfg.getTransactionConfiguration()));
            myCfg.setClassLoader(cfg.getClassLoader());
            myCfg.setAtomicConfiguration(cfg.getAtomicConfiguration());

            ConnectorConfiguration clientCfg = cfg.getConnectorConfiguration();

            if (clientCfg != null)
                clientCfg = new ConnectorConfiguration(clientCfg);

            // Local host.
            String locHost = IgniteSystemProperties.getString(IGNITE_LOCAL_HOST);

            myCfg.setLocalHost(F.isEmpty(locHost) ? cfg.getLocalHost() : locHost);

            // Override daemon flag if it was set on the factory.
            if (daemon)
                myCfg.setDaemon(true);

            // Check for deployment mode override.
            String depModeName = IgniteSystemProperties.getString(IGNITE_DEP_MODE_OVERRIDE);

            if (!F.isEmpty(depModeName)) {
                if (!F.isEmpty(cfg.getCacheConfiguration())) {
                    U.quietAndInfo(log, "Skipping deployment mode override for caches (custom closure " +
                        "execution may not work for console Visor)");
                }
                else {
                    try {
                        DeploymentMode depMode = DeploymentMode.valueOf(depModeName);

                        if (myCfg.getDeploymentMode() != depMode)
                            myCfg.setDeploymentMode(depMode);
                    }
                    catch (IllegalArgumentException e) {
                        throw new IgniteCheckedException("Failed to override deployment mode using system property " +
                            "(are there any misspellings?)" +
                            "[name=" + IGNITE_DEP_MODE_OVERRIDE + ", value=" + depModeName + ']', e);
                    }
                }
            }

            Map<String, ?> attrs = cfg.getUserAttributes();

            if (attrs == null)
                attrs = Collections.emptyMap();

            MBeanServer mbSrv = cfg.getMBeanServer();

            Marshaller marsh = cfg.getMarshaller();

            String[] p2pExclude = cfg.getPeerClassLoadingLocalClassPathExclude();

            CommunicationSpi commSpi = cfg.getCommunicationSpi();
            DiscoverySpi discoSpi = cfg.getDiscoverySpi();
            EventStorageSpi evtSpi = cfg.getEventStorageSpi();
            CollisionSpi colSpi = cfg.getCollisionSpi();
            DeploymentSpi deploySpi = cfg.getDeploymentSpi();
            CheckpointSpi[] cpSpi = cfg.getCheckpointSpi();
            FailoverSpi[] failSpi = cfg.getFailoverSpi();
            LoadBalancingSpi[] loadBalancingSpi = cfg.getLoadBalancingSpi();
            SwapSpaceSpi swapspaceSpi = cfg.getSwapSpaceSpi();
            IndexingSpi indexingSpi = cfg.getIndexingSpi();

            execSvc = new IgniteThreadPoolExecutor(
                "pub-" + cfg.getGridName(),
                cfg.getPublicThreadPoolSize(),
                cfg.getPublicThreadPoolSize(),
                DFLT_PUBLIC_KEEP_ALIVE_TIME,
                new LinkedBlockingQueue<Runnable>(DFLT_PUBLIC_THREADPOOL_QUEUE_CAP));

            // Pre-start all threads as they are guaranteed to be needed.
            ((ThreadPoolExecutor) execSvc).prestartAllCoreThreads();

            // Note that since we use 'LinkedBlockingQueue', number of
            // maximum threads has no effect.
            sysExecSvc = new IgniteThreadPoolExecutor(
                "sys-" + cfg.getGridName(),
                cfg.getSystemThreadPoolSize(),
                cfg.getSystemThreadPoolSize(),
                DFLT_SYSTEM_KEEP_ALIVE_TIME,
                new LinkedBlockingQueue<Runnable>(DFLT_SYSTEM_THREADPOOL_QUEUE_CAP));

            // Pre-start all threads as they are guaranteed to be needed.
            ((ThreadPoolExecutor) sysExecSvc).prestartAllCoreThreads();

            // Note that since we use 'LinkedBlockingQueue', number of
            // maximum threads has no effect.
            // Note, that we do not pre-start threads here as management pool may
            // not be needed.
            mgmtExecSvc = new IgniteThreadPoolExecutor(
                "mgmt-" + cfg.getGridName(),
                cfg.getManagementThreadPoolSize(),
                cfg.getManagementThreadPoolSize(),
                0,
                new LinkedBlockingQueue<Runnable>());

            // Note that since we use 'LinkedBlockingQueue', number of
            // maximum threads has no effect.
            // Note, that we do not pre-start threads here as class loading pool may
            // not be needed.
            p2pExecSvc = new IgniteThreadPoolExecutor(
                "p2p-" + cfg.getGridName(),
                cfg.getPeerClassLoadingThreadPoolSize(),
                cfg.getPeerClassLoadingThreadPoolSize(),
                0,
                new LinkedBlockingQueue<Runnable>());

            // Note that we do not pre-start threads here as igfs pool may not be needed.
            igfsExecSvc = new IgniteThreadPoolExecutor(
                "igfs-" + cfg.getGridName(),
                cfg.getIgfsThreadPoolSize(),
                cfg.getIgfsThreadPoolSize(),
                0,
                new LinkedBlockingQueue<Runnable>());

            if (clientCfg != null) {
                restExecSvc = new IgniteThreadPoolExecutor(
                    "rest-" + cfg.getGridName(),
                    clientCfg.getThreadPoolSize(),
                    clientCfg.getThreadPoolSize(),
                    ConnectorConfiguration.DFLT_KEEP_ALIVE_TIME,
                    new LinkedBlockingQueue<Runnable>(ConnectorConfiguration.DFLT_THREADPOOL_QUEUE_CAP)
                );
            }

            utilityCacheExecSvc = new IgniteThreadPoolExecutor(
                "utility-" + cfg.getGridName(),
                DFLT_SYSTEM_CORE_THREAD_CNT,
                DFLT_SYSTEM_MAX_THREAD_CNT,
                DFLT_SYSTEM_KEEP_ALIVE_TIME,
                new LinkedBlockingQueue<Runnable>(DFLT_SYSTEM_THREADPOOL_QUEUE_CAP));

            if (marsh == null) {
                if (!U.isHotSpot()) {
                    U.warn(log, "GridOptimizedMarshaller is not supported on this JVM " +
                        "(only Java HotSpot VMs are supported). Switching to standard JDK marshalling - " +
                        "object serialization performance will be significantly slower.",
                        "To enable fast marshalling upgrade to recent 1.6 or 1.7 HotSpot VM release.");

                    marsh = new JdkMarshaller();
                }
                else if (!OptimizedMarshaller.available()) {
                    U.warn(log, "GridOptimizedMarshaller is not supported on this JVM " +
                        "(only recent 1.6 and 1.7 versions HotSpot VMs are supported). " +
                        "To enable fast marshalling upgrade to recent 1.6 or 1.7 HotSpot VM release. " +
                        "Switching to standard JDK marshalling - " +
                        "object serialization performance will be significantly slower.",
                        "To enable fast marshalling upgrade to recent 1.6 or 1.7 HotSpot VM release.");

                    marsh = new JdkMarshaller();
                }
                else
                    marsh = new OptimizedMarshaller();
            }
            else if (marsh instanceof OptimizedMarshaller && !U.isHotSpot()) {
                U.warn(log, "Using GridOptimizedMarshaller on untested JVM (only Java HotSpot VMs were tested) - " +
                    "object serialization behavior could yield unexpected results.",
                    "Using GridOptimizedMarshaller on untested JVM.");
            }

            myCfg.setUserAttributes(attrs);
            myCfg.setMBeanServer(mbSrv == null ? ManagementFactory.getPlatformMBeanServer() : mbSrv);
            myCfg.setGridLogger(cfgLog);
            myCfg.setMarshaller(marsh);
            myCfg.setMarshalLocalJobs(cfg.isMarshalLocalJobs());
            myCfg.setNodeId(nodeId);

            IgfsConfiguration[] igfsCfgs = cfg.getIgfsConfiguration();

            if (igfsCfgs != null) {
                IgfsConfiguration[] clone = igfsCfgs.clone();

                for (int i = 0; i < igfsCfgs.length; i++)
                    clone[i] = new IgfsConfiguration(igfsCfgs[i]);

                myCfg.setIgfsConfiguration(clone);
            }

            StreamerConfiguration[] streamerCfgs = cfg.getStreamerConfiguration();

            if (streamerCfgs != null) {
                StreamerConfiguration[] clone = streamerCfgs.clone();

                for (int i = 0; i < streamerCfgs.length; i++)
                    clone[i] = new StreamerConfiguration(streamerCfgs[i]);

                myCfg.setStreamerConfiguration(clone);
            }

            if (p2pExclude == null)
                p2pExclude = EMPTY_STR_ARR;

            myCfg.setPeerClassLoadingLocalClassPathExclude(p2pExclude);

            /*
             * Initialize default SPI implementations.
             */

            if (commSpi == null)
                commSpi = new TcpCommunicationSpi();

            if (discoSpi == null)
                discoSpi = new TcpDiscoverySpi();

            if (discoSpi instanceof TcpDiscoverySpi) {
                TcpDiscoverySpi tcpDisco = (TcpDiscoverySpi)discoSpi;

                if (tcpDisco.getIpFinder() == null)
                    tcpDisco.setIpFinder(new TcpDiscoveryMulticastIpFinder());
            }

            if (evtSpi == null)
                evtSpi = new MemoryEventStorageSpi();

            if (colSpi == null)
                colSpi = new NoopCollisionSpi();

            if (deploySpi == null)
                deploySpi = new LocalDeploymentSpi();

            if (cpSpi == null)
                cpSpi = new CheckpointSpi[] {new NoopCheckpointSpi()};

            if (failSpi == null)
                failSpi = new FailoverSpi[] {new AlwaysFailoverSpi()};

            if (loadBalancingSpi == null)
                loadBalancingSpi = new LoadBalancingSpi[] {new RoundRobinLoadBalancingSpi()};

            if (swapspaceSpi == null) {
                boolean needSwap = false;

                CacheConfiguration[] caches = cfg.getCacheConfiguration();

                if (caches != null) {
                    for (CacheConfiguration c : caches) {
                        if (c.isSwapEnabled()) {
                            needSwap = true;

                            break;
                        }
                    }
                }

                swapspaceSpi = needSwap ? new FileSwapSpaceSpi() : new NoopSwapSpaceSpi();
            }

            if (indexingSpi == null)
                indexingSpi = new NoopIndexingSpi();

            myCfg.setCommunicationSpi(commSpi);
            myCfg.setDiscoverySpi(discoSpi);
            myCfg.setCheckpointSpi(cpSpi);
            myCfg.setEventStorageSpi(evtSpi);
            myCfg.setDeploymentSpi(deploySpi);
            myCfg.setFailoverSpi(failSpi);
            myCfg.setCollisionSpi(colSpi);
            myCfg.setLoadBalancingSpi(loadBalancingSpi);
            myCfg.setSwapSpaceSpi(swapspaceSpi);
            myCfg.setIndexingSpi(indexingSpi);

            myCfg.setAddressResolver(cfg.getAddressResolver());

            // REST configuration.
            myCfg.setConnectorConfiguration(clientCfg);

            // Hadoop configuration.
            myCfg.setHadoopConfiguration(cfg.getHadoopConfiguration());

            // Validate segmentation configuration.
            GridSegmentationPolicy segPlc = cfg.getSegmentationPolicy();

            // 1. Warn on potential configuration problem: grid is not configured to wait
            // for correct segment after segmentation happens.
            if (!F.isEmpty(cfg.getSegmentationResolvers()) && segPlc == RESTART_JVM && !cfg.isWaitForSegmentOnStart()) {
                U.warn(log, "Found potential configuration problem (forgot to enable waiting for segment" +
                    "on start?) [segPlc=" + segPlc + ", wait=false]");
            }

            myCfg.setSegmentationResolvers(cfg.getSegmentationResolvers());
            myCfg.setSegmentationPolicy(segPlc);
            myCfg.setSegmentCheckFrequency(cfg.getSegmentCheckFrequency());
            myCfg.setWaitForSegmentOnStart(cfg.isWaitForSegmentOnStart());
            myCfg.setAllSegmentationResolversPassRequired(cfg.isAllSegmentationResolversPassRequired());

            CacheConfiguration[] cacheCfgs = cfg.getCacheConfiguration();

            final boolean hasHadoop = IgniteComponentType.HADOOP.inClassPath();

            final boolean hasAtomics = cfg.getAtomicConfiguration() != null;

            final boolean clientDisco = discoSpi instanceof TcpClientDiscoverySpi;

            CacheConfiguration[] copies;

            if (cacheCfgs != null && cacheCfgs.length > 0) {
                if (!U.discoOrdered(discoSpi) && !U.relaxDiscoveryOrdered())
                    throw new IgniteCheckedException("Discovery SPI implementation does not support node ordering and " +
                        "cannot be used with cache (use SPI with @GridDiscoverySpiOrderSupport annotation, " +
                        "like GridTcpDiscoverySpi)");

                for (CacheConfiguration ccfg : cacheCfgs) {
                    if (CU.isHadoopSystemCache(ccfg.getName()))
                        throw new IgniteCheckedException("Cache name cannot be \"" + CU.SYS_CACHE_HADOOP_MR +
                            "\" because it is reserved for internal purposes.");

                    if (CU.isAtomicsCache(ccfg.getName()))
                        throw new IgniteCheckedException("Cache name cannot be \"" + CU.ATOMICS_CACHE_NAME +
                            "\" because it is reserved for internal purposes.");

                    if (CU.isUtilityCache(ccfg.getName()))
                        throw new IgniteCheckedException("Cache name cannot start with \"" + CU.UTILITY_CACHE_NAME +
                            "\" because this prefix is reserved for internal purposes.");
                }

                int addCacheCnt = 1; // Always add utility cache.

                if (hasHadoop)
                    addCacheCnt++;

                if (hasAtomics)
                    addCacheCnt++;

                copies = new CacheConfiguration[cacheCfgs.length + addCacheCnt];

                int cloneIdx = 1;

                if (hasHadoop)
                    copies[cloneIdx++] = CU.hadoopSystemCache();

                if (hasAtomics)
                    copies[cloneIdx++] = atomicsSystemCache(cfg.getAtomicConfiguration(), clientDisco);

                for (CacheConfiguration ccfg : cacheCfgs)
                    copies[cloneIdx++] = new CacheConfiguration(ccfg);
            }
            else {
                int cacheCnt = 1; // Always add utility cache.

                if (hasHadoop)
                    cacheCnt++;

                if (hasAtomics)
                    cacheCnt++;

                copies = new CacheConfiguration[cacheCnt];

                int cacheIdx = 1;

                if (hasHadoop)
                    copies[cacheIdx++] = CU.hadoopSystemCache();

                if (hasAtomics)
                    copies[cacheIdx] = atomicsSystemCache(cfg.getAtomicConfiguration(), clientDisco);
            }

            // Always add utility cache.
            copies[0] = utilitySystemCache(clientDisco);

            myCfg.setCacheConfiguration(copies);

            myCfg.setCacheSanityCheckEnabled(cfg.isCacheSanityCheckEnabled());

            try {
                // Use reflection to avoid loading undesired classes.
                Class helperCls = Class.forName("org.apache.ignite.util.GridConfigurationHelper");

                helperCls.getMethod("overrideConfiguration", IgniteConfiguration.class, Properties.class,
                    String.class, IgniteLogger.class).invoke(helperCls, myCfg, System.getProperties(), name, log);
            }
            catch (Exception ignored) {
                // No-op.
            }

            // Ensure that SPIs support multiple grid instances, if required.
            if (!startCtx.single()) {
                ensureMultiInstanceSupport(deploySpi);
                ensureMultiInstanceSupport(commSpi);
                ensureMultiInstanceSupport(discoSpi);
                ensureMultiInstanceSupport(cpSpi);
                ensureMultiInstanceSupport(evtSpi);
                ensureMultiInstanceSupport(colSpi);
                ensureMultiInstanceSupport(failSpi);
                ensureMultiInstanceSupport(loadBalancingSpi);
                ensureMultiInstanceSupport(swapspaceSpi);
            }

            // Register Ignite MBean for current grid instance.
            registerFactoryMbean(myCfg.getMBeanServer());

            boolean started = false;

            try {
                IgniteKernal grid0 = new IgniteKernal(startCtx.springContext());

                // Init here to make grid available to lifecycle listeners.
                grid = grid0;

                grid0.start(myCfg, utilityCacheExecSvc, execSvc, sysExecSvc, p2pExecSvc, mgmtExecSvc, igfsExecSvc,
                    restExecSvc,
                    new CA() {
                        @Override public void apply() {
                        startLatch.countDown();
                    }
                });

                state = STARTED;

                if (log.isDebugEnabled())
                    log.debug("Grid factory started ok: " + name);

                started = true;
            }
            catch (IgniteCheckedException e) {
                unregisterFactoryMBean();

                throw e;
            }
            // Catch Throwable to protect against any possible failure.
            catch (Throwable e) {
                unregisterFactoryMBean();

                throw new IgniteCheckedException("Unexpected exception when starting grid.", e);
            }
            finally {
                if (!started)
                    // Grid was not started.
                    grid = null;
            }

            // Do NOT set it up only if IGNITE_NO_SHUTDOWN_HOOK=TRUE is provided.
            if (!IgniteSystemProperties.getBoolean(IGNITE_NO_SHUTDOWN_HOOK, false)) {
                try {
                    Runtime.getRuntime().addShutdownHook(shutdownHook = new Thread() {
                        @Override public void run() {
                            if (log.isInfoEnabled())
                                log.info("Invoking shutdown hook...");

                            IgniteNamedInstance.this.stop(true);
                        }
                    });

                    if (log.isDebugEnabled())
                        log.debug("Shutdown hook is installed.");
                }
                catch (IllegalStateException e) {
                    stop(true);

                    throw new IgniteCheckedException("Failed to install shutdown hook.", e);
                }
            }
            else {
                if (log.isDebugEnabled())
                    log.debug("Shutdown hook has not been installed because environment " +
                        "or system property " + IGNITE_NO_SHUTDOWN_HOOK + " is set.");
            }
        }

        /**
         * @param cfgLog Configured logger.
         * @param nodeId Local node ID.
         * @return Initialized logger.
         * @throws IgniteCheckedException If failed.
         */
        private IgniteLogger initLogger(@Nullable IgniteLogger cfgLog, UUID nodeId) throws IgniteCheckedException {
            try {
                Exception log4jInitErr = null;

                if (cfgLog == null) {
                    Class<?> log4jCls;

                    try {
                        log4jCls = Class.forName("org.apache.ignite.logger.log4j.Log4JLogger");
                    }
                    catch (ClassNotFoundException | NoClassDefFoundError ignored) {
                        log4jCls = null;
                    }

                    if (log4jCls != null) {
                        try {
                            URL url = U.resolveIgniteUrl("config/ignite-log4j.xml");

                            if (url == null) {
                                File cfgFile = new File("config/ignite-log4j.xml");

                                if (!cfgFile.exists())
                                    cfgFile = new File("../config/ignite-log4j.xml");

                                if (cfgFile.exists()) {
                                    try {
                                        url = cfgFile.toURI().toURL();
                                    }
                                    catch (MalformedURLException ignore) {
                                        // No-op.
                                    }
                                }
                            }

                            if (url != null) {
                                boolean configured = (Boolean)log4jCls.getMethod("isConfigured").invoke(null);

                                if (configured)
                                    url = null;
                            }

                            if (url != null) {
                                Constructor<?> ctor = log4jCls.getConstructor(URL.class);

                                cfgLog = (IgniteLogger)ctor.newInstance(url);
                            }
                            else
                                cfgLog = (IgniteLogger)log4jCls.newInstance();
                        }
                        catch (Exception e) {
                            log4jInitErr = e;
                        }
                    }

                    if (log4jCls == null || log4jInitErr != null)
                        cfgLog = new JavaLogger();
                }

                // Set node IDs for all file appenders.
                if (cfgLog instanceof LoggerNodeIdAware)
                    ((LoggerNodeIdAware)cfgLog).setNodeId(nodeId);

                if (log4jInitErr != null)
                    U.warn(cfgLog, "Failed to initialize Log4JLogger (falling back to standard java logging): "
                        + log4jInitErr.getCause());

                return cfgLog;
            }
            catch (Exception e) {
                throw new IgniteCheckedException("Failed to create logger.", e);
            }
        }

        /**
         * Creates utility system cache configuration.
         *
         * @param client If {@code true} creates client-only cache configuration.
         * @return Utility system cache configuration.
         */
        private static CacheConfiguration utilitySystemCache(boolean client) {
            CacheConfiguration cache = new CacheConfiguration();

            cache.setName(CU.UTILITY_CACHE_NAME);
            cache.setCacheMode(REPLICATED);
            cache.setAtomicityMode(TRANSACTIONAL);
            cache.setSwapEnabled(false);
            cache.setQueryIndexEnabled(false);
            cache.setPreloadMode(SYNC);
            cache.setWriteSynchronizationMode(FULL_SYNC);
            cache.setAffinity(new CacheRendezvousAffinityFunction(false, 100));

            if (client)
                cache.setDistributionMode(CLIENT_ONLY);

            return cache;
        }

        /**
         * Creates cache configuration for atomic data structures.
         *
         * @param cfg Atomic configuration.
         * @param client If {@code true} creates client-only cache configuration.
         * @return Cache configuration for atomic data structures.
         */
        private static CacheConfiguration atomicsSystemCache(AtomicConfiguration cfg, boolean client) {
            CacheConfiguration ccfg = new CacheConfiguration();

            ccfg.setName(CU.ATOMICS_CACHE_NAME);
            ccfg.setAtomicityMode(TRANSACTIONAL);
            ccfg.setSwapEnabled(false);
            ccfg.setQueryIndexEnabled(false);
            ccfg.setPreloadMode(SYNC);
            ccfg.setWriteSynchronizationMode(FULL_SYNC);
            ccfg.setCacheMode(cfg.getCacheMode());

            if (cfg.getCacheMode() == PARTITIONED) {
                ccfg.setBackups(cfg.getBackups());

                ccfg.setDistributionMode(client ? NEAR_ONLY : NEAR_PARTITIONED);
            }
            else
                ccfg.setDistributionMode(client ? NEAR_ONLY : PARTITIONED_ONLY);

            return ccfg;
        }

        /**
         * Stops grid.
         *
         * @param cancel Flag indicating whether all currently running jobs
         *      should be cancelled.
         */
        void stop(boolean cancel) {
            // Stop cannot be called prior to start from public API,
            // since it checks for STARTED state. So, we can assert here.
            assert startGuard.get();

            stop0(cancel);
        }

        /**
         * @param cancel Flag indicating whether all currently running jobs
         *      should be cancelled.
         */
        private synchronized void stop0(boolean cancel) {
            IgniteKernal grid0 = grid;

            // Double check.
            if (grid0 == null) {
                if (log != null)
                    U.warn(log, "Attempting to stop an already stopped grid instance (ignore): " + name);

                return;
            }

            if (shutdownHook != null)
                try {
                    Runtime.getRuntime().removeShutdownHook(shutdownHook);

                    shutdownHook = null;

                    if (log.isDebugEnabled())
                        log.debug("Shutdown hook is removed.");
                }
                catch (IllegalStateException e) {
                    // Shutdown is in progress...
                    if (log.isDebugEnabled())
                        log.debug("Shutdown is in progress (ignoring): " + e.getMessage());
                }

            // Unregister Ignite MBean.
            unregisterFactoryMBean();

            try {
                grid0.stop(cancel);

                if (log.isDebugEnabled())
                    log.debug("Grid instance stopped ok: " + name);
            }
            catch (Throwable e) {
                U.error(log, "Failed to properly stop grid instance due to undeclared exception.", e);
            }
            finally {
                state = grid0.context().segmented() ? STOPPED_ON_SEGMENTATION : STOPPED;

                grid = null;

                stopExecutors(log);

                log = null;
            }
        }

        /**
         * Stops executor services if they has been started.
         *
         * @param log Grid logger.
         */
        private void stopExecutors(IgniteLogger log) {
            boolean interrupted = Thread.interrupted();

            try {
                stopExecutors0(log);
            }
            finally {
                if (interrupted)
                    Thread.currentThread().interrupt();
            }
        }

        /**
         * Stops executor services if they has been started.
         *
         * @param log Grid logger.
         */
        private void stopExecutors0(IgniteLogger log) {
            assert log != null;

            U.shutdownNow(getClass(), execSvc, log);

            execSvc = null;

            U.shutdownNow(getClass(), sysExecSvc, log);

            sysExecSvc = null;

            U.shutdownNow(getClass(), mgmtExecSvc, log);

            mgmtExecSvc = null;

            U.shutdownNow(getClass(), p2pExecSvc, log);

            p2pExecSvc = null;

            U.shutdownNow(getClass(), igfsExecSvc, log);

            igfsExecSvc = null;

            if (restExecSvc != null)
                U.shutdownNow(getClass(), restExecSvc, log);

            restExecSvc = null;

            U.shutdownNow(getClass(), utilityCacheExecSvc, log);

            utilityCacheExecSvc = null;
        }

        /**
         * Registers delegate Mbean instance for {@link org.apache.ignite.Ignition}.
         *
         * @param srv MBeanServer where mbean should be registered.
         * @throws IgniteCheckedException If registration failed.
         */
        private void registerFactoryMbean(MBeanServer srv) throws IgniteCheckedException {
            synchronized (mbeans) {
                GridMBeanServerData data = mbeans.get(srv);

                if (data == null) {
                    try {
                        IgnitionMXBean mbean = new IgnitionMXBeanAdapter();

                        ObjectName objName = U.makeMBeanName(
                            null,
                            "Kernal",
                            Ignition.class.getSimpleName()
                        );

                        // Make check if MBean was already registered.
                        if (!srv.queryMBeans(objName, null).isEmpty())
                            throw new IgniteCheckedException("MBean was already registered: " + objName);
                        else {
                            objName = U.registerMBean(
                                srv,
                                null,
                                "Kernal",
                                Ignition.class.getSimpleName(),
                                mbean,
                                IgnitionMXBean.class
                            );

                            data = new GridMBeanServerData(objName);

                            mbeans.put(srv, data);

                            if (log.isDebugEnabled())
                                log.debug("Registered MBean: " + objName);
                        }
                    }
                    catch (JMException e) {
                        throw new IgniteCheckedException("Failed to register MBean.", e);
                    }
                }

                assert data != null;

                data.addGrid(name);
                data.setCounter(data.getCounter() + 1);
            }
        }

        /**
         * Unregister delegate Mbean instance for {@link org.apache.ignite.Ignition}.
         */
        private void unregisterFactoryMBean() {
            synchronized (mbeans) {
                Iterator<Entry<MBeanServer, GridMBeanServerData>> iter = mbeans.entrySet().iterator();

                while (iter.hasNext()) {
                    Entry<MBeanServer, GridMBeanServerData> entry = iter.next();

                    if (entry.getValue().containsGrid(name)) {
                        GridMBeanServerData data = entry.getValue();

                        assert data != null;

                        // Unregister MBean if no grid instances started for current MBeanServer.
                        if (data.getCounter() == 1) {
                            try {
                                entry.getKey().unregisterMBean(data.getMbean());

                                if (log.isDebugEnabled())
                                    log.debug("Unregistered MBean: " + data.getMbean());
                            }
                            catch (JMException e) {
                                U.error(log, "Failed to unregister MBean.", e);
                            }

                            iter.remove();
                        }
                        else {
                            // Decrement counter.
                            data.setCounter(data.getCounter() - 1);
                            data.removeGrid(name);
                        }
                    }
                }
            }
        }

        /**
         * Grid factory MBean data container.
         * Contains necessary data for selected MBeanServer.
         */
        private static class GridMBeanServerData {
            /** Set of grid names for selected MBeanServer. */
            private Collection<String> gridNames = new HashSet<>();

            /** */
            private ObjectName mbean;

            /** Count of grid instances. */
            private int cnt;

            /**
             * Create data container.
             *
             * @param mbean Object name of MBean.
             */
            GridMBeanServerData(ObjectName mbean) {
                assert mbean != null;

                this.mbean = mbean;
            }

            /**
             * Add grid name.
             *
             * @param gridName Grid name.
             */
            public void addGrid(String gridName) {
                gridNames.add(gridName);
            }

            /**
             * Remove grid name.
             *
             * @param gridName Grid name.
             */
            public void removeGrid(String gridName) {
                gridNames.remove(gridName);
            }

            /**
             * Returns {@code true} if data contains the specified
             * grid name.
             *
             * @param gridName Grid name.
             * @return {@code true} if data contains the specified grid name.
             */
            public boolean containsGrid(String gridName) {
                return gridNames.contains(gridName);
            }

            /**
             * Gets name used in MBean server.
             *
             * @return Object name of MBean.
             */
            public ObjectName getMbean() {
                return mbean;
            }

            /**
             * Gets number of grid instances working with MBeanServer.
             *
             * @return Number of grid instances.
             */
            public int getCounter() {
                return cnt;
            }

            /**
             * Sets number of grid instances working with MBeanServer.
             *
             * @param cnt Number of grid instances.
             */
            public void setCounter(int cnt) {
                this.cnt = cnt;
            }
        }
    }
}