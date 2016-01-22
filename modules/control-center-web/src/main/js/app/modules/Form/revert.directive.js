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

// i.pull-right.fa.fa-undo(
//     ng-show='!preview["atomics"].allDefaults'
//     ng-click='#{model} = {}; $event.stopPropagation()'

const template = `<i ng-show='form.$dirty' class='fa fa-undo pull-right' ng-click='revert($event)'></i>`;

export default ['igniteFormRevert', ['$tooltip', ($tooltip) => {
    const link = (scope, $element, $attrs, [form]) => {
        $tooltip($element, { title: 'Undo unsaved changes' });

        scope.form = form;

        scope.revert = (e) => {
            e.stopPropagation();

            for (const name in form.$defaults) {
                if ({}.hasOwnProperty.call(form.$defaults, name) && form[name]) {
                    console.log(name, form.$defaults[name], form[name]);
                    
                    form[name].$setViewValue(form.$defaults[name]);
                    form[name].$setPristine();
                    form[name].$render();
               }
            }

            form.$setPristine();
        };
    };

    return {
        restrict: 'E',
        scope: {
            model: '=ngModel',
            models: '=models'
        },
        template,
        link,
        replace: true,
        require: ['^form']
    };
}]];
