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

package org.apache.ignite.testsuites;

import junit.framework.*;
import org.apache.ignite.internal.processors.cache.*;
import org.apache.ignite.internal.processors.query.h2.*;
import org.apache.ignite.internal.processors.query.h2.opt.*;

/**
 * H2 indexing SPI tests.
 */
public class IgniteH2IndexingSpiTestSuite extends TestSuite {
    /**
     * @return Test suite.
     * @throws Exception Thrown in case of the failure.
     */
    public static TestSuite suite() throws Exception {
        TestSuite suite = new TestSuite("H2 Indexing SPI Test Suite");

        // H2 Optimized table test.
        suite.addTest(new TestSuite(GridH2TableSelfTest.class));

        // H2 Indexing in-memory.
        suite.addTest(new TestSuite(GridH2IndexingInMemSelfTest.class));

        // H2 Off-heap memory.
        suite.addTest(new TestSuite(GridH2IndexingOffheapSelfTest.class));

        // Index rebuilding.
        suite.addTest(new TestSuite(GridH2IndexRebuildTest.class));

        // Tests moved to this suite since they require GridH2IndexingSpi.
        suite.addTestSuite(GridCacheOffHeapAndSwapSelfTest.class);
        suite.addTestSuite(GridIndexingWithNoopSwapSelfTest.class);
        suite.addTestSuite(GridCacheSwapSelfTest.class);
        suite.addTestSuite(GridCacheOffHeapSelfTest.class);

        return suite;
    }
}