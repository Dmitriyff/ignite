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

// Getting started pages.
import PAGES from 'app/data/getting-started.json!';

export default ['IgniteGettingStarted', ['$rootScope', '$modal', function($root, $modal) {
    const _model = PAGES;

    let _page = 0;

    const scope = $root.$new();

    scope.ui = {
        showGettingStarted: false
    };

    function _fillPage() {
        scope.title = _model[_page].title;
        scope.message = _model[_page].message.join(' ');
    }

    scope.isFirst = () => _page === 0;

    scope.isLast = () => _page === _model.length - 1;

    scope.next = () => {
        _page += 1;

        _fillPage();
    };

    scope.prev = () => {
        _page -= 1;

        _fillPage();
    };

    const dialog = $modal({templateUrl: '/templates/getting-started.html', scope, placement: 'center', show: false, backdrop: 'static'});

    scope.close = () => {
        try {
            localStorage.showGettingStarted = scope.ui.showGettingStarted;
        }
        catch (ignore) {
            // No-op.
        }

        dialog.hide();
    };

    return {
        tryShow: (force) => {
            try {
                scope.ui.showGettingStarted = typeof localStorage.showGettingStarted === 'undefined'
                    || localStorage.showGettingStarted === 'true';
            }
            catch (ignore) {
                // No-op.
            }

            if (force || scope.ui.showGettingStarted) {
                _page = 0;

                _fillPage();

                dialog.show();
            }
        }
    };
}]];