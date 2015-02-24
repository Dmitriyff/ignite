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

package org.apache.ignite.internal.visor.cache;

import org.apache.ignite.configuration.*;
import org.apache.ignite.internal.util.typedef.internal.*;

import java.io.*;

/**
 * Data transfer object for default cache configuration properties.
 */
public class VisorCacheDefaultConfiguration implements Serializable {
    /** */
    private static final long serialVersionUID = 0L;

    /** TTL value. */
    private long ttl;

    /** Default transaction timeout. */
    private long txLockTimeout;

    /** Default query timeout. */
    private long qryTimeout;

    /**
     * @param ccfg Cache configuration.
     * @return Data transfer object for default cache configuration properties.
     */
    public static VisorCacheDefaultConfiguration from(CacheConfiguration ccfg) {
        VisorCacheDefaultConfiguration cfg = new VisorCacheDefaultConfiguration();

        cfg.timeToLive(ccfg.getDefaultTimeToLive());
        cfg.txLockTimeout(ccfg.getDefaultLockTimeout());
        cfg.queryTimeout(ccfg.getDefaultQueryTimeout());

        return cfg;
    }

    /**
     * @return TTL value.
     */
    public long timeToLive() {
        return ttl;
    }

    /**
     * @param ttl New tTL value.
     */
    public void timeToLive(long ttl) {
        this.ttl = ttl;
    }

    /**
     * @return Default transaction timeout.
     */
    public long txLockTimeout() {
        return txLockTimeout;
    }

    /**
     * @param txLockTimeout New default transaction timeout.
     */
    public void txLockTimeout(long txLockTimeout) {
        this.txLockTimeout = txLockTimeout;
    }

    /**
     * @return Default query timeout.
     */
    public long queryTimeout() {
        return qryTimeout;
    }

    /**
     * @param qryTimeout New default query timeout.
     */
    public void queryTimeout(long qryTimeout) {
        this.qryTimeout = qryTimeout;
    }

    /** {@inheritDoc} */
    @Override public String toString() {
        return S.toString(VisorCacheDefaultConfiguration.class, this);
    }
}