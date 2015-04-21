/*
 * Copyright (C) 2015 Leo Bayer
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.lbayer.appup.application;

import java.io.File;

public interface IAppupRuntime
{
    String PROP_STARTTIME = "appup.startTime";
    String PROP_CONFDIR = "appup.confDir";
    String PROP_LIBDIR = "appup.libDir";
    String PROP_STARTCLASSES = "appup.startClasses";

    String[] getArguments();

    void install(File file);
    void start(String name) throws Exception;
    void stop(String name) throws Exception;
}
