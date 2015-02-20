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

package org.apache.ignite.logger.log4j;

import org.apache.ignite.lang.*;
import org.apache.log4j.varia.*;
import org.gridgain.grid.util.typedef.internal.*;

import java.io.*;

/**
 * Log4J {@link ExternallyRolledFileAppender} with added support for grid node IDs.
 */
public class IgniteLog4jExternallyRolledFileAppender extends ExternallyRolledFileAppender implements IgniteLog4jFileAware {
    /** Basic log file name. */
    private String baseFileName;

    /**
     * Default constructor (does not do anything).
     */
    public IgniteLog4jExternallyRolledFileAppender() {
        init();
    }

    /**
     *
     */
    private void init() {
        IgniteLog4jLogger.addAppender(this);
    }

    /** {@inheritDoc} */
    @Override public synchronized void updateFilePath(IgniteClosure<String, String> filePathClos) {
        A.notNull(filePathClos, "filePathClos");

        if (baseFileName == null)
            baseFileName = fileName;

        fileName = filePathClos.apply(baseFileName);
    }

    /** {@inheritDoc} */
    @Override public synchronized void setFile(String fileName, boolean fileAppend, boolean bufIO, int bufSize)
        throws IOException {
        if (baseFileName != null)
            super.setFile(fileName, fileAppend, bufIO, bufSize);
    }
}