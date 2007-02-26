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
package org.apache.felix.framework.searchpolicy;

import java.net.URL;
import java.util.*;

import org.apache.felix.framework.searchpolicy.R4SearchPolicyCore.ResolvedPackage;
import org.apache.felix.framework.searchpolicy.R4SearchPolicyCore.PackageSource;
import org.apache.felix.framework.util.Util;
import org.apache.felix.framework.util.manifestparser.Capability;
import org.apache.felix.moduleloader.*;

public class R4WireModule implements IWire
{
    private IModule m_importer = null;
    private IModule m_exporter = null;
    private ICapability m_capability = null;
    private Map m_pkgMap = null;
    
    public R4WireModule(IModule importer, IModule exporter, ICapability capability, Map pkgMap)
    {
        m_importer = importer;
        m_exporter = exporter;
        m_capability = capability;
        m_pkgMap = pkgMap;
    }
    
    /* (non-Javadoc)
     * @see org.apache.felix.framework.searchpolicy.IWire#getImportingModule()
     */
    public IModule getImporter()
    {
        return m_importer;
    }
    
    /* (non-Javadoc)
     * @see org.apache.felix.framework.searchpolicy.IWire#getExportingModule()
     */
    public IModule getExporter()
    {
        return m_exporter;
    }
    
    /* (non-Javadoc)
     * @see org.apache.felix.framework.searchpolicy.IWire#getExport()
     */
    public ICapability getCapability()
    {
        return m_capability;
    }

    /* (non-Javadoc)
     * @see org.apache.felix.framework.searchpolicy.IWire#getClass(java.lang.String)
     */
    public Class getClass(String name) throws ClassNotFoundException
    {
        // Get the package of the target class.
        String pkgName = Util.getClassPackage(name);

        ResolvedPackage rp = (ResolvedPackage) m_pkgMap.get(pkgName);
        if (rp != null)
        {
            for (Iterator srcIter = rp.m_sourceSet.iterator(); srcIter.hasNext(); )
            {
                PackageSource ps = (PackageSource) srcIter.next();
                if ((ps.m_module == m_importer) ||
                    ((ps.m_capability instanceof Capability) &&
                    ((Capability) ps.m_capability).isIncluded(name)))
                {
                    Class clazz = ps.m_module.getContentLoader().getClass(name);
                    if (clazz != null)
                    {
                        return clazz;
                    }
                }
            }
        }

        return null;
    }

    /* (non-Javadoc)
     * @see org.apache.felix.framework.searchpolicy.IWire#getResource(java.lang.String)
     */
    public URL getResource(String name) throws ResourceNotFoundException
    {
        // Get the package of the target class.
        String pkgName = Util.getResourcePackage(name);

        ResolvedPackage rp = (ResolvedPackage) m_pkgMap.get(pkgName);
        if (rp != null)
        {
            for (Iterator srcIter = rp.m_sourceSet.iterator(); srcIter.hasNext(); )
            {
                PackageSource ps = (PackageSource) srcIter.next();
                URL url = ps.m_module.getContentLoader().getResource(name);
                if (url != null)
                {
                    return url;
                }
            }
        }

        return null;
    }

    public Enumeration getResources(String name) throws ResourceNotFoundException
    {
// TODO: RB - Implement R4WireModule.getResources()
        return null;
    }

    public String toString()
    {
        return m_importer + " -> " + m_capability + " -> " + m_exporter;
    }
}