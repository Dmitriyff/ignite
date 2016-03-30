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

import gulp from 'gulp';
import sequence from 'gulp-sequence';
import sass from 'gulp-sass';

const paths = [
    './public/stylesheets/style.scss'
];

gulp.task('sass', () =>
    gulp.src(paths)
        .pipe(sass({ outputStyle: 'nested' }).on('error', sass.logError))
        .pipe(gulp.dest('./public/stylesheets'))
);

gulp.task('sass:watch', (cb) =>
    gulp.watch(paths, () => sequence('sass', 'bundle', cb))
);