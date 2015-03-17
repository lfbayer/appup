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
package com.lbayer.appup.registry;

import java.util.Hashtable;

import javax.naming.Context;
import javax.naming.NamingException;
import javax.naming.spi.InitialContextFactory;

public class AppupInitialContextFactory implements InitialContextFactory
{
    private static final AppupContext INSTANCE;

    static
    {
        INSTANCE = new AppupContext();
    }

    @Override
    public Context getInitialContext(Hashtable<?, ?> environment) throws NamingException
    {
        return INSTANCE;
    }
}
