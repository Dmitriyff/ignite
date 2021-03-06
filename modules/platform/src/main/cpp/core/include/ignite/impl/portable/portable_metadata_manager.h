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

#ifndef _IGNITE_IMPL_PORTABLE_METADATA_MANAGER
#define _IGNITE_IMPL_PORTABLE_METADATA_MANAGER

#include <vector>

#include "ignite/ignite_error.h"
#include "ignite/impl/portable/portable_metadata_handler.h"
#include "ignite/impl/portable/portable_metadata_updater.h"

namespace ignite
{    
    namespace impl
    {
        namespace portable
        {
            /**
             * Metadata manager.
             */
            class IGNITE_IMPORT_EXPORT PortableMetadataManager
            {
            public:
                /**
                 * Constructor.
                 */
                PortableMetadataManager();

                /**
                 * Destructor.
                 */
                ~PortableMetadataManager();

                /**
                 * Get handler.
                 *
                 * @param typeId Type ID.
                 */
                ignite::common::concurrent::SharedPointer<PortableMetadataHandler> GetHandler(int32_t typeId);

                /**
                 * Submit handler for processing.
                 * 
                 * @param typeName Type name.
                 * @param typeId Type ID.
                 * @param hnd Handler.
                 */
                void SubmitHandler(std::string typeName, int32_t typeId, PortableMetadataHandler* hnd);

                /**
                 * Get current metadata manager version.
                 *
                 * @param Version.
                 */
                int32_t GetVersion();

                /**
                 * Check whether something is updated since the given version.
                 *
                 * @param oldVer Old version.
                 * @return True if updated and it is very likely that pending metadata exists.
                 */
                bool IsUpdatedSince(int32_t oldVer);

                /**
                 * Process pending updates.
                 *
                 * @param updated Updater.
                 * @param err Error.
                 * @return In case of success.
                 */
                bool ProcessPendingUpdates(PortableMetadataUpdater* updater, IgniteError* err);

            private:
                /** Current snapshots. */
                ignite::common::concurrent::SharedPointer<std::map<int32_t, SPSnap>> snapshots;
                
                /** Pending snapshots. */
                std::vector<SPSnap>* pending;                                          

                /** Critical section. */
                ignite::common::concurrent::CriticalSection* cs;

                /** Version of pending changes. */
                int32_t pendingVer;                                                    
                
                /** Latest version. */
                int32_t ver;          

                IGNITE_NO_COPY_ASSIGNMENT(PortableMetadataManager);

                /**
                 * Copy fields from a snapshot into relevant collections.
                 *
                 * @param snap Target snapshot.
                 * @param fieldIds Field IDs.
                 * @param fields Fields.
                 */
                void CopyFields(Snap* snap, std::set<int32_t>* fieldIds, std::map<std::string, int32_t>* fields);
            };
        }
    }    
}

#endif