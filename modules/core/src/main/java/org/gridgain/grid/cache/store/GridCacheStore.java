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

package org.gridgain.grid.cache.store;

import org.apache.ignite.*;
import org.apache.ignite.lang.*;
import org.apache.ignite.portables.*;
import org.apache.ignite.transactions.*;
import org.gridgain.grid.*;
import org.gridgain.grid.cache.*;
import org.gridgain.grid.cache.store.jdbc.*;
import org.jetbrains.annotations.*;

import java.sql.*;
import java.util.*;
import java.util.Date;

/**
 * API for cache persistent storage for read-through and write-through behavior.
 * Persistent store is configured via {@link GridCacheConfiguration#getStore()}
 * configuration property. If not provided, values will be only kept in cache memory
 * or swap storage without ever being persisted to a persistent storage.
 * <p>
 * {@link GridCacheStoreAdapter} provides default implementation for bulk operations,
 * such as {@link #loadAll(IgniteTx, Collection, org.apache.ignite.lang.IgniteBiInClosure)},
 * {@link #putAll(IgniteTx, Map)}, and {@link #removeAll(IgniteTx, Collection)}
 * by sequentially calling corresponding {@link #load(IgniteTx, Object)},
 * {@link #put(IgniteTx, Object, Object)}, and {@link #remove(IgniteTx, Object)}
 * operations. Use this adapter whenever such behaviour is acceptable. However
 * in many cases it maybe more preferable to take advantage of database batch update
 * functionality, and therefore default adapter implementation may not be the best option.
 * <p>
 * Provided implementations may be used for test purposes:
 * <ul>
 *     <li>{@gglink org.gridgain.grid.cache.store.hibernate.GridCacheHibernateBlobStore}</li>
 *     <li>{@link GridCacheJdbcBlobStore}</li>
 * </ul>
 * <p>
 * All transactional operations of this API are provided with ongoing {@link IgniteTx},
 * if any. As transaction is {@link GridMetadataAware}, you can attach any metadata to
 * it, e.g. to recognize if several operations belong to the same transaction or not.
 * Here is an example of how attach a JDBC connection as transaction metadata:
 * <pre name="code" class="java">
 * Connection conn = tx.meta("some.name");
 *
 * if (conn == null) {
 *     conn = ...; // Get JDBC connection.
 *
 *     // Store connection in transaction metadata, so it can be accessed
 *     // for other operations on the same transaction.
 *     tx.addMeta("some.name", conn);
 * }
 * </pre>
 * <h1 class="header">Working With Portable Objects</h1>
 * When portables are enabled for cache by setting {@link GridCacheConfiguration#isPortableEnabled()} to
 * {@code true}), all portable keys and values are converted to instances of {@link PortableObject}.
 * Therefore, all cache store methods will take parameters in portable format. To avoid class
 * cast exceptions, store must have signature compatible with portables. E.g., if you use {@link Integer}
 * as a key and {@code Value} class as a value (which will be converted to portable format), cache store
 * signature should be the following:
 * <pre name="code" class="java">
 * public class PortableCacheStore implements GridCacheStore&lt;Integer, GridPortableObject&gt; {
 *     public void put(@Nullable GridCacheTx tx, Integer key, GridPortableObject val) throws IgniteCheckedException {
 *         ...
 *     }
 *
 *     ...
 * }
 * </pre>
 * This behavior can be overridden by setting {@link GridCacheConfiguration#setKeepPortableInStore(boolean)}
 * flag value to {@code false}. In this case, GridGain will deserialize keys and values stored in portable
 * format before they are passed to cache store, so that you can use the following cache store signature instead:
 * <pre name="code" class="java">
 * public class ObjectsCacheStore implements GridCacheStore&lt;Integer, Person&gt; {
 *     public void put(@Nullable GridCacheTx tx, Integer key, Person val) throws GridException {
 *         ...
 *     }
 *
 *     ...
 * }
 * </pre>
 * Note that while this can simplify store implementation in some cases, it will cause performance degradation
 * due to additional serializations and deserializations of portable objects. You will also need to have key
 * and value classes on all nodes since portables will be deserialized when store is invoked.
 * <p>
 * Note that only portable classes are converted to {@link PortableObject} format. Following
 * types are stored in cache without changes and therefore should not affect cache store signature:
 * <ul>
 *     <li>All primitives (byte, int, ...) and there boxed versions (Byte, Integer, ...)</li>
 *     <li>Arrays of primitives (byte[], int[], ...)</li>
 *     <li>{@link String} and array of {@link String}s</li>
 *     <li>{@link UUID} and array of {@link UUID}s</li>
 *     <li>{@link Date} and array of {@link Date}s</li>
 *     <li>{@link Timestamp} and array of {@link Timestamp}s</li>
 *     <li>Enums and array of enums</li>
 *     <li>
 *         Maps, collections and array of objects (but objects inside
 *         them will still be converted if they are portable)
 *     </li>
 * </ul>
 *
 * @see org.apache.ignite.IgnitePortables
 */
public interface GridCacheStore<K, V> {
    /**
     * Loads value for the key from underlying persistent storage.
     *
     * @param tx Cache transaction.
     * @param key Key to load.
     * @return Loaded value or {@code null} if value was not found.
     * @throws IgniteCheckedException If load failed.
     */
    @Nullable public V load(@Nullable IgniteTx tx, K key) throws IgniteCheckedException;

    /**
     * Loads all values from underlying persistent storage. Note that keys are not
     * passed, so it is up to implementation to figure out what to load. This method
     * is called whenever {@link GridCache#loadCache(org.apache.ignite.lang.IgniteBiPredicate, long, Object...)}
     * method is invoked which is usually to preload the cache from persistent storage.
     * <p>
     * This method is optional, and cache implementation does not depend on this
     * method to do anything. Default implementation of this method in
     * {@link GridCacheStoreAdapter} does nothing.
     * <p>
     * For every loaded value method {@link org.apache.ignite.lang.IgniteBiInClosure#apply(Object, Object)}
     * should be called on the passed in closure. The closure will then make sure
     * that the loaded value is stored in cache.
     *
     * @param clo Closure for loaded values.
     * @param args Arguments passes into
     *      {@link GridCache#loadCache(org.apache.ignite.lang.IgniteBiPredicate, long, Object...)} method.
     * @throws IgniteCheckedException If loading failed.
     */
    public void loadCache(IgniteBiInClosure<K, V> clo, @Nullable Object... args) throws IgniteCheckedException;

    /**
     * Loads all values for given keys and passes every value to the provided closure.
     * <p>
     * For every loaded value method {@link org.apache.ignite.lang.IgniteInClosure#apply(Object)} should be called on
     * the passed in closure. The closure will then make sure that the loaded value is stored
     * in cache.
     *
     * @param tx Cache transaction.
     * @param keys Collection of keys to load.
     * @param c Closure to call for every loaded element.
     * @throws IgniteCheckedException If load failed.
     */
    public void loadAll(@Nullable IgniteTx tx, Collection<? extends K> keys, IgniteBiInClosure<K, V> c)
        throws IgniteCheckedException;

    /**
     * Stores a given value in persistent storage. Note that if write-behind is configured for a
     * particular cache, transaction object passed in the cache store will be always {@code null}.
     *
     * @param tx Cache transaction, if write-behind is not enabled, {@code null} otherwise.
     * @param key Key to put.
     * @param val Value to put.
     * @throws IgniteCheckedException If put failed.
     */
    public void put(@Nullable IgniteTx tx, K key, V val) throws IgniteCheckedException;

    /**
     * Stores given key value pairs in persistent storage. Note that if write-behind is configured
     * for a particular cache, transaction object passed in the cache store will be always {@code null}.
     *
     * @param tx Cache transaction, if write-behind is not enabled, {@code null} otherwise.
     * @param map Values to store.
     * @throws IgniteCheckedException If store failed.
     */
    public void putAll(@Nullable IgniteTx tx, Map<? extends K, ? extends V> map) throws IgniteCheckedException;

    /**
     * Removes the value identified by given key from persistent storage. Note that  if write-behind is
     * configured for a particular cache, transaction object passed in the cache store will be always
     * {@code null}.
     *
     * @param tx Cache transaction, if write-behind is not enabled, {@code null} otherwise.
     * @param key Key to remove.
     * @throws IgniteCheckedException If remove failed.
     */
    public void remove(@Nullable IgniteTx tx, K key) throws IgniteCheckedException;

    /**
     * Removes all vales identified by given keys from persistent storage. Note that if write-behind
     * is configured for a particular cache, transaction object passed in the cache store will be
     * always {@code null}.
     *
     * @param tx Cache transaction, if write-behind is not enabled, {@code null} otherwise.
     * @param keys Keys to remove.
     * @throws IgniteCheckedException If remove failed.
     */
    public void removeAll(@Nullable IgniteTx tx, Collection<? extends K> keys) throws IgniteCheckedException;

    /**
     * Tells store to commit or rollback a transaction depending on the value of the {@code 'commit'}
     * parameter.
     *
     * @param tx Cache transaction being ended.
     * @param commit {@code True} if transaction should commit, {@code false} for rollback.
     * @throws IgniteCheckedException If commit or rollback failed. Note that commit failure in some cases
     *      may bring cache transaction into {@link IgniteTxState#UNKNOWN} which will
     *      consequently cause all transacted entries to be invalidated.
     */
    public void txEnd(IgniteTx tx, boolean commit) throws IgniteCheckedException;
}