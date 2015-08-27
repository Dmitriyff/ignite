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

package org.apache.ignite.internal.processors.platform.cache;

import org.apache.ignite.*;
import org.apache.ignite.internal.portable.*;
import org.apache.ignite.internal.processors.platform.*;

import javax.cache.*;
import java.util.*;

/**
 * Interop cache iterator.
 */
public class PlatformCacheIterator extends PlatformAbstractTarget {
    /** Operation: next entry. */
    private static final int OP_NEXT = 1;

    /** Iterator. */
    private final Iterator<Cache.Entry> iter;

    /**
     * Constructor.
     *
     * @param platformCtx Context.
     * @param iter Iterator.
     */
    public PlatformCacheIterator(PlatformContext platformCtx, Iterator<Cache.Entry> iter) {
        super(platformCtx);

        this.iter = iter;
    }

    /** {@inheritDoc} */
    @Override protected void processOutOp(int type, PortableRawWriterEx writer) throws IgniteCheckedException {
        switch (type) {
            case OP_NEXT:
                if (iter.hasNext()) {
                    Cache.Entry e = iter.next();

                    assert e != null;

                    writer.writeBoolean(true);

                    writer.writeObjectDetached(e.getKey());
                    writer.writeObjectDetached(e.getValue());
                }
                else
                    writer.writeBoolean(false);

                break;

            default:
                throwUnsupported(type);
        }
    }
}