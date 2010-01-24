/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.felix.dm.runtime;

import org.apache.felix.dm.DependencyActivatorBase;
import org.apache.felix.dm.DependencyManager;
import org.osgi.framework.BundleContext;
import org.osgi.service.log.LogService;

/*
 * This is the Activator for our DependencyManager Component Runtime.
 * Here, we'll track started/stopped bundles which have some DependencyManager
 * descriptors (OSGI-INF/*.dm). Such descriptors are generated by the Bnd 
 * plugin which parses DependencyManager annotations at compile time.
 */
public class Activator extends DependencyActivatorBase
{
    @Override
    public void init(BundleContext context, DependencyManager dm) throws Exception
    {
        // If the "dm.log=true" parameter is configured in the OSGi config.properties
        // then, we'll wait for the LogService, else we'll use an optional dependency over it,
        // and we'll eventually use a Null LogService Object.
        // Notice that the Felix log service must also be configured from the OSGi config.properites, using
        // the "org.apache.felix.log.storeDebug=true" property, in order to display DEBUG log levels.

        boolean logActive = "true".equals(context.getProperty("dm.log"));
        dm.add(createService()
               .setImplementation(ComponentManager.class)
               .add(createServiceDependency()
                   .setService(LogService.class)
                   .setRequired(logActive)
                   .setAutoConfig(true)));
    }

    @Override
    public void destroy(BundleContext context, DependencyManager dm) throws Exception
    {
    }
}
