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

package org.apache.ignite.internal.processors.query.h2.twostep;

import org.apache.ignite.internal.processors.query.h2.twostep.messages.*;
import org.apache.ignite.internal.util.typedef.internal.*;

/**
 * Page result.
 */
public class GridResultPage<Z> {
    /** */
    private final Z src;

    /** */
    protected final GridNextPageResponse res;

    /**
     * @param src Source.
     * @param res Response.
     */
    protected GridResultPage(Z src, GridNextPageResponse res) {
        this.src = src;
        this.res = res;
    }

    /**
     * @return Result source.
     */
    public Z source() {
        return src;
    }

    /**
     * @return Response.
     */
    public GridNextPageResponse response() {
        return res;
    }

    /**
     * Request next page.
     */
    public void fetchNextPage() {
        throw new UnsupportedOperationException();
    }

    /** {@inheritDoc} */
    @Override public String toString() {
        return S.toString(GridResultPage.class, this);
    }
}