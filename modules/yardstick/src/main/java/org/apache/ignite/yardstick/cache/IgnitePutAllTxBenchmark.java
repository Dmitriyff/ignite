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

package org.apache.ignite.yardstick.cache;

import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import org.apache.ignite.IgniteCache;
import org.apache.ignite.cache.affinity.Affinity;
import org.apache.ignite.cluster.ClusterNode;
import org.yardstickframework.BenchmarkConfiguration;

/**
 * Ignite benchmark that performs transactional putAll operations.
 */
public class IgnitePutAllTxBenchmark extends IgniteCacheAbstractBenchmark {
    /** Affinity mapper. */
    private Affinity<Integer> aff;

    /** {@inheritDoc} */
    @Override public void setUp(BenchmarkConfiguration cfg) throws Exception {
        super.setUp(cfg);

        aff = ignite().affinity("tx");
    }

    /** {@inheritDoc} */
    @Override public boolean test(Map<Object, Object> ctx) throws Exception {
        ThreadRange r = threadRange();

        SortedMap<Integer, Integer> vals = new TreeMap<>();

        ClusterNode node = args.collocated() ? aff.mapKeyToNode(r.nextRandom()) : null;

        for (int i = 0; i < args.batch(); ) {
            int key = r.nextRandom();

            if (args.collocated() && !aff.isPrimary(node, key))
                continue;

            ++i;

            vals.put(key, key);
        }

        // Implicit transaction is used.
        cache.putAll(vals);

        return true;
    }

    /** {@inheritDoc} */
    @Override protected IgniteCache<Integer, Object> cache() {
        return ignite().cache("tx");
    }
}