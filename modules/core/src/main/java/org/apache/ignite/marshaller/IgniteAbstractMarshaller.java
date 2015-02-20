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

package org.apache.ignite.marshaller;

import org.apache.ignite.*;
import org.gridgain.grid.util.*;
import org.gridgain.grid.util.io.*;
import org.gridgain.grid.util.typedef.internal.*;
import org.jetbrains.annotations.*;

/**
 * Base class for marshallers. Provides default implementations of methods
 * that work with byte array or {@link GridByteArrayList}. These implementations
 * use {@link GridByteArrayInputStream} or {@link GridByteArrayOutputStream}
 * to marshal and unmarshal objects.
 */
public abstract class IgniteAbstractMarshaller implements IgniteMarshaller {
    /** Default initial buffer size for the {@link GridByteArrayOutputStream}. */
    public static final int DFLT_BUFFER_SIZE = 512;

    /** {@inheritDoc} */
    @Override public byte[] marshal(@Nullable Object obj) throws IgniteCheckedException {
        GridByteArrayOutputStream out = null;

        try {
            out = new GridByteArrayOutputStream(DFLT_BUFFER_SIZE);

            marshal(obj, out);

            return out.toByteArray();
        }
        finally {
            U.close(out, null);
        }
    }

    /** {@inheritDoc} */
    @Override public <T> T unmarshal(byte[] arr, @Nullable ClassLoader clsLdr) throws IgniteCheckedException {
        GridByteArrayInputStream in = null;

        try {
            in = new GridByteArrayInputStream(arr, 0, arr.length);

            return unmarshal(in, clsLdr);
        }
        finally {
            U.close(in, null);
        }
    }
}