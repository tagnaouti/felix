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
package org.apache.felix.scr.impl.manager;


import java.security.Permission;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Dictionary;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.felix.scr.Component;
import org.apache.felix.scr.Reference;
import org.apache.felix.scr.impl.BundleComponentActivator;
import org.apache.felix.scr.impl.helper.BindMethods;
import org.apache.felix.scr.impl.helper.MethodResult;
import org.apache.felix.scr.impl.metadata.ReferenceMetadata;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.Filter;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.framework.ServicePermission;
import org.osgi.framework.ServiceReference;
import org.osgi.service.component.ComponentConstants;
import org.osgi.service.log.LogService;


/**
 * The <code>DependencyManager</code> manages the references to services
 * declared by a single <code>&lt;reference&gt;</code element in component
 * descriptor.
 */
public class DependencyManager<S, T> implements Reference
{
    // mask of states ok to send events
    private static final int STATE_MASK = //Component.STATE_UNSATISFIED |
         Component.STATE_ACTIVE | Component.STATE_REGISTERED | Component.STATE_FACTORY;

    // the component to which this dependency belongs
    private final AbstractComponentManager<S> m_componentManager;

    // Reference to the metadata
    private final ReferenceMetadata m_dependencyMetadata;

    private final AtomicReference<ServiceTracker<T, RefPair<T>>> trackerRef = new AtomicReference<ServiceTracker<T, RefPair<T>>>();

    private final AtomicReference<Customizer<T>> customizerRef = new AtomicReference<Customizer<T>>();

    private BindMethods m_bindMethods;

    // the target service filter string
    private volatile String m_target;

    // the target service filter
    private volatile Filter m_targetFilter;

    private boolean registered;

    private final Map<S, EdgeInfo> edgeInfoMap = new IdentityHashMap<S, EdgeInfo>(  );

    private static class EdgeInfo
    {
        private int open = -1;
        private int close = -1;
        private CountDownLatch latch;

        public void setClose( int close )
        {
            this.close = close;
        }

        public CountDownLatch getLatch()
        {
            return latch;
        }

        public void setLatch( CountDownLatch latch )
        {
            this.latch = latch;
        }

        public void setOpen( int open )
        {
            this.open = open;
        }

        public boolean outOfRange( int trackingCount )
        {
            return (open != -1 && trackingCount < open)
                || (close != -1 && trackingCount > close);
        }
    }

    /**
     * Constructor that receives several parameters.
     *
     * @param dependency An object that contains data about the dependency
     */
    DependencyManager( AbstractComponentManager<S> componentManager, ReferenceMetadata dependency )
    {
        m_componentManager = componentManager;
        m_dependencyMetadata = dependency;

        // dump the reference information if DEBUG is enabled
        if ( m_componentManager.isLogEnabled( LogService.LOG_DEBUG ) )
        {
            m_componentManager
                .log(
                    LogService.LOG_DEBUG,
                    "Dependency Manager {0} created: interface={1}, filter={2}, policy={3}, cardinality={4}, bind={5}, unbind={6}",
                    new Object[]
                        { getName(), dependency.getInterface(), dependency.getTarget(), dependency.getPolicy(),
                            dependency.getCardinality(), dependency.getBind(), dependency.getUnbind() }, null );
        }
    }

    /**
     * Initialize binding methods.
     */
    void initBindingMethods(BindMethods bindMethods)
    {
       m_bindMethods = bindMethods;
    }

    private interface Customizer<T> extends ServiceTrackerCustomizer<T, RefPair<T>>
    {
        boolean open();

        void close();

        Collection<RefPair<T>> getRefs( AtomicInteger trackingCount );

        boolean isSatisfied();
        
        void setTracker( ServiceTracker<T, RefPair<T>> tracker );

        void setTrackerOpened();

        void setPreviousRefMap( Map<ServiceReference<T>, RefPair<T>> previousRefMap );
    }

    private abstract class AbstractCustomizer implements Customizer<T>
    {
        private final Map<ServiceReference<T>, RefPair<T>> EMPTY_REF_MAP = Collections.emptyMap();

        private ServiceTracker<T, RefPair<T>> tracker;

        private volatile boolean trackerOpened;

        private volatile Map<ServiceReference<T>, RefPair<T>> previousRefMap = EMPTY_REF_MAP;

        public void setTracker( ServiceTracker<T, RefPair<T>> tracker )
        {
            this.tracker = tracker;
        }

        public boolean isSatisfied()
        {
            return isOptional() || !tracker.isEmpty();
        }

        protected ServiceTracker<T, RefPair<T>> getTracker()
        {
            return tracker;
        }

        /**
         *
         * @return whether the tracker
         */
        protected boolean isActive()
        {
            return tracker.isActive();
        }

        protected boolean isTrackerOpened()
        {
            return trackerOpened;
        }

        public void setTrackerOpened()
        {
            trackerOpened = true;
        }

        protected Map<ServiceReference<T>, RefPair<T>> getPreviousRefMap()
        {
            return previousRefMap;
        }

        public void setPreviousRefMap( Map<ServiceReference<T>, RefPair<T>> previousRefMap )
        {
            if ( previousRefMap != null )
            {
                this.previousRefMap = previousRefMap;
            }
            else
            {
                this.previousRefMap = EMPTY_REF_MAP;
            }

        }

        protected void ungetService( RefPair<T> ref )
        {
            synchronized ( ref )
            {
                if ( ref.getServiceObject() != null )
                {
                    ref.setServiceObject( null );
                    m_componentManager.getActivator().getBundleContext().ungetService( ref.getRef() );
                }
            }
        }

        protected void tracked( int trackingCount )
        {
            m_componentManager.tracked( trackingCount );
        }

    }


    private class FactoryCustomizer extends AbstractCustomizer {

        public RefPair<T> addingService( ServiceReference<T> serviceReference )
        {
            RefPair<T> refPair = new RefPair<T>( serviceReference  );
            return refPair;
        }

        public void addedService( ServiceReference<T> serviceReference, RefPair<T> refPair, int trackingCount )
        {
            if ( !isOptional() )
            {
                m_componentManager.activateInternal( trackingCount );
            }
        }

        public void modifiedService( ServiceReference<T> serviceReference, RefPair<T> refPair, int trackingCount )
        {
        }

        public void removedService( ServiceReference<T> serviceReference, RefPair<T> refPair, int trackingCount )
        {
            if ( !isOptional() )
            {
                if (getTracker().isEmpty())
                {
                    m_componentManager.deactivateInternal( ComponentConstants.DEACTIVATION_REASON_REFERENCE, false, trackingCount );
                }
            }
        }

        public boolean open()
        {
            boolean success = m_dependencyMetadata.isOptional() || !getTracker().isEmpty();
            AtomicInteger trackingCount = new AtomicInteger( );
            getTracker().getTracked( true, trackingCount );   //TODO activate method??
            return success;
        }

        public void close()
        {
            getTracker().deactivate();
        }

        public Collection<RefPair<T>> getRefs( AtomicInteger trackingCount )
        {
            return Collections.emptyList();
        }
    }

    private class MultipleDynamicCustomizer extends AbstractCustomizer {

        private RefPair<T> lastRefPair;
        private int lastRefPairTrackingCount;

        public RefPair<T> addingService( ServiceReference<T> serviceReference )
        {
            RefPair<T> refPair = getPreviousRefMap().get( serviceReference );
            if ( refPair == null )
            {
                refPair = new RefPair<T>( serviceReference  );
            }
            if (isActive())
            {
                 m_bindMethods.getBind().getServiceObject( refPair, m_componentManager.getActivator().getBundleContext(), m_componentManager );
            }
            return refPair;
        }

        public void addedService( ServiceReference<T> serviceReference, RefPair<T> refPair, int trackingCount )
        {
        	boolean tracked = false;
            if ( getPreviousRefMap().remove( serviceReference ) == null )
            {
                if (isActive())
                {
                    if ( !refPair.isFailed() )
                    {
                        m_componentManager.invokeBindMethod( DependencyManager.this, refPair, trackingCount );
                    }
                    else {
                        m_componentManager.getActivator().registerMissingDependency( DependencyManager.this, serviceReference, trackingCount );
                    }
                }
                else if ( isTrackerOpened() && !isOptional() )
                {
                    tracked( trackingCount );
                    tracked = true;
                    m_componentManager.activateInternal( trackingCount );
                }
            }
            m_componentManager.log( LogService.LOG_DEBUG, "dm {0} tracking {1} MultipleDynamic added {2}", new Object[] {m_dependencyMetadata.getName(), trackingCount, serviceReference}, null );
            if ( !tracked )
            {
				tracked(trackingCount);
			}
        }

        public void modifiedService( ServiceReference<T> serviceReference, RefPair<T> refPair, int trackingCount )
        {
            if (isActive())
            {
                m_componentManager.update( DependencyManager.this, refPair, trackingCount );
            }
            m_componentManager.log( LogService.LOG_DEBUG, "dm {0} tracking {1} MultipleDynamic modified {2}", new Object[] {m_dependencyMetadata.getName(), trackingCount, serviceReference}, null );
            tracked( trackingCount );
        }

        public void removedService( ServiceReference<T> serviceReference, RefPair<T> refPair, int trackingCount )
        {
            if ( isActive() )
            {
                boolean unbind = isOptional() || !getTracker().isEmpty();
                if ( unbind )
                {
                    m_componentManager.invokeUnbindMethod( DependencyManager.this, refPair, trackingCount );
                    m_componentManager.log( LogService.LOG_DEBUG, "dm {0} tracking {1} MultipleDynamic removed (unbind) {2}", new Object[] {m_dependencyMetadata.getName(), trackingCount, serviceReference}, null );
                    tracked( trackingCount );
                }
                else
                {
                    lastRefPair = refPair;
                    lastRefPairTrackingCount = trackingCount;
                    m_componentManager.log( LogService.LOG_DEBUG, "dm {0} tracking {1} MultipleDynamic removed (deactivate) {2}", new Object[] {m_dependencyMetadata.getName(), trackingCount, serviceReference}, null );
                    tracked( trackingCount );
                    m_componentManager.deactivateInternal( ComponentConstants.DEACTIVATION_REASON_REFERENCE, false, trackingCount );
                    lastRefPair = null;
                }
            }
            else
            {
                m_componentManager.log( LogService.LOG_DEBUG, "dm {0} tracking {1} MultipleDynamic removed (inactive) {2}", new Object[] {m_dependencyMetadata.getName(), trackingCount, serviceReference}, null );
                tracked( trackingCount );
            }
            ungetService( refPair );
        }

        public boolean open()
        {
            boolean success = m_dependencyMetadata.isOptional();
            AtomicInteger trackingCount = new AtomicInteger( );
            SortedMap<ServiceReference<T>, RefPair<T>> tracked = getTracker().getTracked( true, trackingCount );
            for (RefPair<T> refPair: tracked.values())
            {
                synchronized (refPair)
                {
                    if (m_bindMethods.getBind().getServiceObject( refPair, m_componentManager.getActivator().getBundleContext(), m_componentManager ))
                    {
                         success = true;
                    }
                    else
                    {
                         m_componentManager.getActivator().registerMissingDependency( DependencyManager.this, refPair.getRef(), trackingCount.get() );
                    }
                }
            }
            return success;
        }

        public void close()
        {
            AtomicInteger trackingCount = new AtomicInteger( );
            for ( RefPair<T> ref : getRefs( trackingCount ) )
            {
                ungetService( ref );
            }
            getTracker().deactivate();
        }


        public Collection<RefPair<T>> getRefs( AtomicInteger trackingCount )
        {
            if ( lastRefPair == null )
            {
                return getTracker().getTracked( true, trackingCount ).values();
            }
            else
            {
                trackingCount.set( lastRefPairTrackingCount );
                return Collections.singletonList( lastRefPair );
            }
        }
    }

    private class MultipleStaticGreedyCustomizer extends AbstractCustomizer {


        public RefPair<T> addingService( ServiceReference<T> serviceReference )
        {
            RefPair<T> refPair = new RefPair<T>( serviceReference  );
            if (isActive())
            {
                 m_bindMethods.getBind().getServiceObject( refPair, m_componentManager.getActivator().getBundleContext(), m_componentManager );
            }
            return refPair;
        }

        public void addedService( ServiceReference<T> serviceReference, RefPair<T> refPair, int trackingCount )
        {
            tracked( trackingCount );
            if (isActive())
            {
                m_componentManager.log( LogService.LOG_DEBUG,
                        "Dependency Manager: Static dependency on {0}/{1} is broken", new Object[]
                        {m_dependencyMetadata.getName(), m_dependencyMetadata.getInterface()}, null );
                m_componentManager.deactivateInternal( ComponentConstants.DEACTIVATION_REASON_REFERENCE, false, trackingCount );
                m_componentManager.activateInternal( trackingCount );

            }
            else if ( isTrackerOpened() &&  !isOptional() )
            {
                m_componentManager.activateInternal( trackingCount );
            }
            m_componentManager.log( LogService.LOG_DEBUG, "dm {0} tracking {1} MultipleStaticGreedy added {2}", new Object[] {m_dependencyMetadata.getName(), trackingCount, serviceReference}, null );
        }

        public void modifiedService( ServiceReference<T> serviceReference, RefPair<T> refPair, int trackingCount )
        {
            if (isActive())
            {
                m_componentManager.update( DependencyManager.this, refPair, trackingCount );
            }
            m_componentManager.log( LogService.LOG_DEBUG, "dm {0} tracking {1} MultipleStaticGreedy modified {2}", new Object[] {m_dependencyMetadata.getName(), trackingCount, serviceReference}, null );
            tracked( trackingCount );
        }

        public void removedService( ServiceReference<T> serviceReference, RefPair<T> refPair, int trackingCount )
        {
            tracked( trackingCount );
            if ( isActive() )
            {
                //deactivate while ref is still tracked
                m_componentManager.log( LogService.LOG_DEBUG,
                        "Dependency Manager: Static dependency on {0}/{1} is broken", new Object[]
                        {m_dependencyMetadata.getName(), m_dependencyMetadata.getInterface()}, null );
                m_componentManager.deactivateInternal( ComponentConstants.DEACTIVATION_REASON_REFERENCE, false, trackingCount );
                //try to reactivate after ref is no longer tracked.
                m_componentManager.activateInternal( trackingCount );
            }
            //This is unlikely
            ungetService( refPair );
            m_componentManager.log( LogService.LOG_DEBUG, "dm {0} tracking {1} MultipleStaticGreedy removed {2}", new Object[] {m_dependencyMetadata.getName(), trackingCount, serviceReference}, null );
        }

        public boolean open()
        {
            boolean success = m_dependencyMetadata.isOptional();
            AtomicInteger trackingCount = new AtomicInteger( );
            SortedMap<ServiceReference<T>, RefPair<T>> tracked = getTracker().getTracked( success || !getTracker().isEmpty(), trackingCount );
            for (RefPair<T> refPair: tracked.values())
            {
                synchronized (refPair)
                {
                    success |= m_bindMethods.getBind().getServiceObject( refPair, m_componentManager.getActivator().getBundleContext(), m_componentManager );
                }
            }
            return success;
        }

        public void close()
        {
            AtomicInteger trackingCount = new AtomicInteger( );
            for ( RefPair<T> ref: getRefs( trackingCount ))
            {
                ungetService( ref );
            }
            getTracker().deactivate();
        }

        public Collection<RefPair<T>> getRefs( AtomicInteger trackingCount )
        {
            return getTracker().getTracked( null, trackingCount ).values();
        }
    }

    private class MultipleStaticReluctantCustomizer extends AbstractCustomizer {

        private final Collection<RefPair<T>> refs = new ArrayList<RefPair<T>>();
        private int trackingCount;

        public RefPair<T> addingService( ServiceReference<T> serviceReference )
        {
            RefPair<T> refPair = new RefPair<T>( serviceReference  );
            return refPair;
        }

        public void addedService( ServiceReference<T> serviceReference, RefPair<T> refPair, int trackingCount )
        {
            tracked( trackingCount );
            if ( isTrackerOpened() && !isOptional() && !isActive())
            {
                m_componentManager.activateInternal( trackingCount );
            }
            m_componentManager.log( LogService.LOG_DEBUG, "dm {0} tracking {1} MultipleStaticReluctant added {2}", new Object[] {m_dependencyMetadata.getName(), trackingCount, serviceReference}, null );
        }

        public void modifiedService( ServiceReference<T> serviceReference, RefPair<T> refPair, int trackingCount )
        {
            if (isActive())
            {
                m_componentManager.update( DependencyManager.this, refPair, trackingCount );
            }
            m_componentManager.log( LogService.LOG_DEBUG, "dm {0} tracking {1} MultipleStaticReluctant modified {2}", new Object[] {m_dependencyMetadata.getName(), trackingCount, serviceReference}, null );
            tracked( trackingCount );
        }

        public void removedService( ServiceReference<T> serviceReference, RefPair<T> refPair, int trackingCount )
        {
            tracked( trackingCount );
            if ( isActive() )
            {
                if (refs.contains( refPair ))
                {
                    //we are tracking the used refs, so we can deactivate here.
                    m_componentManager.log( LogService.LOG_DEBUG,
                        "Dependency Manager: Static dependency on {0}/{1} is broken", new Object[]
                            { m_dependencyMetadata.getName(), m_dependencyMetadata.getInterface() }, null );
                    m_componentManager.deactivateInternal( ComponentConstants.DEACTIVATION_REASON_REFERENCE, false, trackingCount );

                    // FELIX-2368: immediately try to reactivate
                    m_componentManager.activateInternal( trackingCount );

                }
            }
            ungetService( refPair );
            m_componentManager.log( LogService.LOG_DEBUG, "dm {0} tracking {1} MultipleStaticReluctant removed {2}", new Object[] {m_dependencyMetadata.getName(), trackingCount, serviceReference}, null );
        }

        public boolean open()
        {
            boolean success = m_dependencyMetadata.isOptional();
            AtomicInteger trackingCount = new AtomicInteger( );
            SortedMap<ServiceReference<T>, RefPair<T>> tracked = getTracker().getTracked( true, trackingCount );
            for (RefPair<T> refPair: tracked.values())
            {
                synchronized (refPair)
                {
                    success |= m_bindMethods.getBind().getServiceObject( refPair, m_componentManager.getActivator().getBundleContext(), m_componentManager );
                }
                refs.add(refPair) ;
            }
            this.trackingCount = trackingCount.get();
            return success;
        }

        public void close()
        {
            AtomicInteger trackingCount = new AtomicInteger( );
            for ( RefPair<T> ref: getRefs( trackingCount ))
            {
                ungetService( ref );
            }
            refs.clear();
            getTracker().deactivate();
        }

        public Collection<RefPair<T>> getRefs( AtomicInteger trackingCount )
        {
            trackingCount.set( this.trackingCount );
            return refs;
        }
    }

    private class SingleDynamicCustomizer extends AbstractCustomizer {

        private RefPair<T> refPair;
        private int trackingCount;

        public RefPair<T> addingService( ServiceReference<T> serviceReference )
        {
            RefPair<T> refPair = getPreviousRefMap().get( serviceReference );
            if ( refPair == null )
            {
                refPair = new RefPair<T>( serviceReference  );
            }
            return refPair;
        }

        public void addedService( ServiceReference<T> serviceReference, RefPair<T> refPair, int trackingCount )
        {
        	boolean tracked = false;
            if ( getPreviousRefMap().get( serviceReference ) == null )
            {
                if (isActive() )
                {
                    if ( this.refPair == null || ( !isReluctant() && refPair.getRef().compareTo( this.refPair.getRef() ) > 0 ) )
                    {
                        synchronized ( refPair )
                        {
                            m_bindMethods.getBind().getServiceObject( refPair, m_componentManager.getActivator().getBundleContext(), m_componentManager );
                        }
                        if ( !refPair.isFailed() )
                        {
                            m_componentManager.invokeBindMethod( DependencyManager.this, refPair, trackingCount );
                            if ( this.refPair != null )
                            {
                                m_componentManager.invokeUnbindMethod( DependencyManager.this, this.refPair, trackingCount );
                                closeRefPair();
                            }
                        }
                        else
                        {
                            m_componentManager.getActivator().registerMissingDependency( DependencyManager.this, serviceReference, trackingCount );
                        }
                        this.refPair = refPair;
                    }
                }
                else if ( isTrackerOpened() && !isOptional() )
                {
                    tracked( trackingCount );
                    tracked = true;
                    m_componentManager.activateInternal( trackingCount );
                }
            }
            this.trackingCount = trackingCount;
            m_componentManager.log( LogService.LOG_DEBUG, "dm {0} tracking {1} SingleDynamic added {2}", new Object[] {m_dependencyMetadata.getName(), trackingCount, serviceReference}, null );
            if ( !tracked )
            {
				tracked(trackingCount);
			}
        }

        public void modifiedService( ServiceReference<T> serviceReference, RefPair<T> refPair, int trackingCount )
        {
            if (isActive())
            {
                m_componentManager.update( DependencyManager.this, refPair, trackingCount );
            }
            this.trackingCount = trackingCount;
            m_componentManager.log( LogService.LOG_DEBUG, "dm {0} tracking {1} SingleDynamic modified {2}", new Object[] {m_dependencyMetadata.getName(), trackingCount, serviceReference}, null );
            tracked( trackingCount );
        }

        public void removedService( ServiceReference<T> serviceReference, RefPair<T> refPair, int trackingCount )
        {
            if (refPair == this.refPair)
            {
                if ( isActive() )
                {
                    RefPair<T> nextRefPair = null;
                    if ( !getTracker().isEmpty() )
                    {
                        AtomicInteger trackingCount2 = new AtomicInteger( );
                        SortedMap<ServiceReference<T>, RefPair<T>> tracked = getTracker().getTracked( true, trackingCount2 );
                        nextRefPair = tracked.values().iterator().next();
                        synchronized ( nextRefPair )
                        {
                            if (!m_bindMethods.getBind().getServiceObject( nextRefPair, m_componentManager.getActivator().getBundleContext(), m_componentManager ))
                            {
                                //TODO error???
                            }
                        }
                        if ( !refPair.isFailed() )
                        {
                            m_componentManager.invokeBindMethod( DependencyManager.this, nextRefPair, trackingCount );
                        }
                    }

                    if ( isOptional() || nextRefPair != null)
                    {
                        RefPair<T> oldRefPair = this.refPair;
                        this.refPair = null;
                        this.trackingCount = trackingCount;
                        m_componentManager.invokeUnbindMethod( DependencyManager.this, oldRefPair, trackingCount );
                        ungetService( oldRefPair );
                        this.refPair = nextRefPair;
                        tracked( trackingCount );
                    }
                    else //required and no replacement service, deactivate
                    {
                        this.trackingCount = trackingCount;
                        tracked( trackingCount );
                        m_componentManager.deactivateInternal( ComponentConstants.DEACTIVATION_REASON_REFERENCE, false, trackingCount );
                    }
                }
                else
                {
                    this.trackingCount = trackingCount;
                    tracked( trackingCount );
                }
            }
            else
            {
                this.trackingCount = trackingCount;
                tracked( trackingCount );
            }
            m_componentManager.log( LogService.LOG_DEBUG, "dm {0} tracking {1} SingleDynamic removed {2}", new Object[] {m_dependencyMetadata.getName(), trackingCount, serviceReference}, null );
        }

        public boolean open()
        {
            boolean success = m_dependencyMetadata.isOptional();
            if ( success || !getTracker().isEmpty() )
            {
                AtomicInteger trackingCount = new AtomicInteger( );
                SortedMap<ServiceReference<T>, RefPair<T>> tracked = getTracker().getTracked( true, trackingCount );
                if ( !tracked.isEmpty() )
                {
                    RefPair<T> refPair = tracked.values().iterator().next();
                    synchronized ( refPair )
                    {
                        success |= m_bindMethods.getBind().getServiceObject( refPair, m_componentManager.getActivator().getBundleContext(), m_componentManager );
                    }
                    if (refPair.isFailed())
                    {
                        m_componentManager.getActivator().registerMissingDependency( DependencyManager.this, refPair.getRef(), trackingCount.get() );
                    }
                    this.refPair = refPair;
                }
            }
            return success;
        }

        public void close()
        {
            closeRefPair();
            getTracker().deactivate();
        }

        private void closeRefPair()
        {
            if ( refPair != null )
            {
                ungetService( refPair );
            }
            refPair = null;
        }

        public Collection<RefPair<T>> getRefs( AtomicInteger trackingCount )
        {
            trackingCount.set( this.trackingCount );
            return refPair == null? Collections.<RefPair<T>>emptyList(): Collections.singleton( refPair );
        }
    }

    private class SingleStaticCustomizer extends AbstractCustomizer
    {

        private RefPair<T> refPair;
        private int trackingCount;

        public RefPair<T> addingService( ServiceReference<T> serviceReference )
        {
            RefPair<T> refPair = new RefPair<T>( serviceReference );
            return refPair;
        }

        public void addedService( ServiceReference<T> serviceReference, RefPair<T> refPair, int trackingCount )
        {
            this.trackingCount = trackingCount;
            tracked( trackingCount );
            if ( isActive() )
            {
                if ( !isReluctant() && ( this.refPair == null || refPair.getRef().compareTo( this.refPair.getRef() ) > 0 ) )
                {
                    m_componentManager.deactivateInternal( ComponentConstants.DEACTIVATION_REASON_REFERENCE, false, trackingCount );
                    m_componentManager.activateInternal( trackingCount );
                }
            }
            else if (isTrackerOpened() && !isOptional() )
            {
                m_componentManager.activateInternal( trackingCount );
            }
            m_componentManager.log( LogService.LOG_DEBUG, "dm {0} tracking {1} SingleStatic added {2}", new Object[] {m_dependencyMetadata.getName(), trackingCount, serviceReference}, null );
        }

        public void modifiedService( ServiceReference<T> serviceReference, RefPair<T> refPair, int trackingCount )
        {
            if ( isActive() )
            {
                m_componentManager.update( DependencyManager.this, refPair, trackingCount );
            }
            this.trackingCount = trackingCount;
            m_componentManager.log( LogService.LOG_DEBUG, "dm {0} tracking {1} SingleStatic modified {2}", new Object[] {m_dependencyMetadata.getName(), trackingCount, serviceReference}, null );
            tracked( trackingCount );
        }

        public void removedService( ServiceReference<T> serviceReference, RefPair<T> refPair, int trackingCount )
        {
            this.trackingCount = trackingCount;
            tracked( trackingCount );
            if ( isActive() && refPair == this.refPair )
            {
                m_componentManager.deactivateInternal( ComponentConstants.DEACTIVATION_REASON_REFERENCE, false, trackingCount );
                m_componentManager.activateInternal( trackingCount );
            }
            m_componentManager.log( LogService.LOG_DEBUG, "dm {0} tracking {1} SingleStatic removed {2}", new Object[] {m_dependencyMetadata.getName(), trackingCount, serviceReference}, null );
        }

        public boolean open()
        {
            boolean success = m_dependencyMetadata.isOptional();
            if ( success || !getTracker().isEmpty() )
            {
                AtomicInteger trackingCount = new AtomicInteger( );
                SortedMap<ServiceReference<T>, RefPair<T>> tracked = getTracker().getTracked( true, trackingCount );
                if ( !tracked.isEmpty() )
                {
                    RefPair<T> refPair = tracked.values().iterator().next();
                    synchronized ( refPair )
                    {
                        success |= m_bindMethods.getBind().getServiceObject( refPair, m_componentManager.getActivator().getBundleContext(), m_componentManager );
                    }
                    this.refPair = refPair;
                }
            }
            return success;
        }

        public void close()
        {
            if ( refPair != null )
            {
                ungetService( refPair );
            }
            refPair = null;
            getTracker().deactivate();
        }

        public Collection<RefPair<T>> getRefs( AtomicInteger trackingCount )
        {
            trackingCount.set( this.trackingCount );
            return refPair == null ? Collections.<RefPair<T>>emptyList() : Collections.singleton( refPair );
        }
    }

    private class NoPermissionsCustomizer implements Customizer<T>
    {

        public boolean open()
        {
            return false;
        }

        public void close()
        {
        }

        public Collection<RefPair<T>> getRefs( AtomicInteger trackingCount )
        {
            return null;
        }

        public boolean isSatisfied()
        {
            return isOptional();
        }

        public void setTracker( ServiceTracker<T, RefPair<T>> tRefPairServiceTracker )
        {
        }

        public void setTrackerOpened()
        {
        }

        public void setPreviousRefMap( Map<ServiceReference<T>, RefPair<T>> previousRefMap )
        {
        }

        public RefPair<T> addingService( ServiceReference<T> tServiceReference )
        {
            return null;
        }

        public void addedService( ServiceReference<T> tServiceReference, RefPair<T> service, int trackingCount )
        {
        }

        public void modifiedService( ServiceReference<T> tServiceReference, RefPair<T> service, int trackingCount )
        {
        }

        public void removedService( ServiceReference<T> tServiceReference, RefPair<T> service, int trackingCount )
        {
        }
    }

    //---------- Reference interface ------------------------------------------

    public String getServiceName()
    {
        return m_dependencyMetadata.getInterface();
    }

    public boolean isOptional()
    {
        return m_dependencyMetadata.isOptional();
    }


    public boolean isMultiple()
    {
        return m_dependencyMetadata.isMultiple();
    }


    public boolean isStatic()
    {
        return m_dependencyMetadata.isStatic();
    }

    public boolean isReluctant()
    {
        return m_dependencyMetadata.isReluctant();
    }

    public String getBindMethodName()
    {
        return m_dependencyMetadata.getBind();
    }


    public String getUnbindMethodName()
    {
        return m_dependencyMetadata.getUnbind();
    }


    public String getUpdatedMethodName()
    {
        return m_dependencyMetadata.getUpdated();
    }


    //---------- Service tracking support -------------------------------------


    void deactivate()
    {
        customizerRef.get().close();
    }


    /**
     * Returns the number of services currently registered in the system,
     * which match the service criteria (interface and optional target filter)
     * configured for this dependency. The number returned by this method has
     * no correlation to the number of services bound to this dependency
     * manager. It is actually the maximum number of services which may be
     * bound to this dependency manager.
     *
     * @see #isSatisfied()
     */
    int size()
    {
        AtomicInteger trackingCount = new AtomicInteger( );
        return trackerRef.get().getTracked( null, trackingCount ).size();
    }


    private ServiceReference<T>[] getFrameworkServiceReferences( String targetFilter )
    {
        if ( hasGetPermission() )
        {
            // component activator may be null if disposed concurrently
            BundleComponentActivator bca = m_componentManager.getActivator();
            if ( bca == null )
            {
                return null;
            }

            // get bundle context, may be null if component deactivated since getting bca
            BundleContext bc = bca.getBundleContext();
            if ( bc == null )
            {
                return null;
            }

            try
            {
                return ( ServiceReference<T>[] ) bc.getServiceReferences(
                    m_dependencyMetadata.getInterface(), targetFilter );
            }
            catch ( IllegalStateException ise )
            {
                // bundle context is not valid any longer, cannot log
            }
            catch ( InvalidSyntaxException ise )
            {
                m_componentManager.log( LogService.LOG_ERROR, "Unexpected problem with filter ''{0}''", new Object[]
                    { targetFilter }, ise );
                return null;
            }
        }

        m_componentManager.log( LogService.LOG_DEBUG, "No permission to access the services", null );
        return null;
    }


    /**
     * Returns a <code>ServiceReference</code> instances for a service
     * implementing the interface and complying to the (optional) target filter
     * declared for this dependency. If no matching service can be found
     * <code>null</code> is returned. If the configured target filter is
     * syntactically incorrect an error message is logged with the LogService
     * and <code>null</code> is returned. If multiple matching services are
     * registered the service with the highest service.ranking value is
     * returned. If multiple matching services have the same service.ranking
     * value, the service with the lowest service.id is returned.
     * <p>
     */
    private RefPair<T> getBestRefPair()
    {
        Customizer customizer = customizerRef.get( );
        if (customizer == null )
        {
            return null;
        }
        Collection<RefPair<T>> refs = customizer.getRefs( new AtomicInteger( ) );
        if (refs.isEmpty())
        {
            return null;
        }
        return refs.iterator().next();
    }


    /**
     * Returns the service instance for the service reference returned by the
     * {@link #getBestRefPair()} method. If this returns a
     * non-<code>null</code> service instance the service is then considered
     * bound to this instance.
     */
    T getService()
    {
        RefPair<T> sr = getBestRefPair();
        return getService( sr );
    }


    /**
     * Returns an array of service instances for the service references returned
     * by the customizer. If no services
     * match the criteria configured for this dependency <code>null</code> is
     * returned. All services returned by this method will be considered bound
     * after this method returns.
     */
    T[] getServices()
    {
        Customizer customizer = customizerRef.get( );
        if (customizer == null )
        {
            return null;
        }
        Collection<RefPair<T>> refs = customizer.getRefs(  new AtomicInteger( ) );
        List<T> services = new ArrayList<T>( refs.size() );
        for ( RefPair<T> ref: refs)
        {
            T service = getService(ref);
            if (service != null)
            {
                services.add( service );
            }
        }
        return services.isEmpty()? null: (T[])services.toArray( new Object[ services.size()] );
    }


    //---------- bound services maintenance -----------------------------------

    /**
     * Returns an array of <code>ServiceReference</code> instances of all
     * services this instance is bound to or <code>null</code> if no services
     * are actually bound.
     */
    public ServiceReference<T>[] getServiceReferences()
    {
        Customizer<T> customizer = customizerRef.get();
        if (customizer == null)
        {
            return null;
        }
        Collection<RefPair<T>> bound = customizer.getRefs(  new AtomicInteger( ) );
        if ( bound.isEmpty() )
        {
            return null;
        }
        ServiceReference<T>[] result = new ServiceReference[bound.size()];
        int i = 0;
        for (RefPair<T> ref: bound)
        {
            result[i++] = ref.getRef();
        }
        return result;
    }

    /**
     * a mistake, use getServiceReferences instead
     */
    @Deprecated
    public ServiceReference[] getBoundServiceReferences() 
    {
        return getServiceReferences();
    }
    
    /**
     * Returns the RefPair containing the given service reference and the bound service
     * or <code>null</code> if this is instance is not currently bound to that
     * service.
     *
     * @param serviceReference The reference to the bound service
     *
     * @return RefPair the reference and service for the reference
     *      if the service is bound or <code>null</code> if the service is not
     *      bound.
     */
    private RefPair<T> getRefPair( ServiceReference<T> serviceReference )
    {
        AtomicInteger trackingCount = new AtomicInteger( );
        return trackerRef.get().getTracked( null, trackingCount ).get( serviceReference );
    }


    /**
     * Returns the service described by the ServiceReference. If this instance
     * is already bound the given service, that bound service instance is
     * returned. Otherwise the service retrieved from the service registry
     * and kept as a bound service for future use.
     *
     * @param serviceReference The reference to the service to be returned
     *
     * @return The requested service or <code>null</code> if no service is
     *      registered for the service reference (any more).
     */
    T getService( ServiceReference<T> serviceReference )
    {
        // check whether we already have the service and return that one
        RefPair<T> refPair = getRefPair( serviceReference );
        return getService( refPair );
    }

    private T getService( RefPair<T> refPair )
    {
        if (refPair == null)
        {
            //we don't know about this reference
            return null;
        }
        if ( refPair.getServiceObject() != null )
        {
            return refPair.getServiceObject();
        }
        T serviceObject;
        // otherwise acquire the service
        try
        {
            serviceObject = m_componentManager.getActivator().getBundleContext().getService( refPair.getRef() );
        }
        catch ( Exception e )
        {
            // caused by getService() called on invalid bundle context
            // or if there is a service reference cycle involving service
            // factories !
            m_componentManager.log( LogService.LOG_ERROR, "Failed getting service {0} ({1}/{2,number,#})", new Object[]
                { m_dependencyMetadata.getName(), m_dependencyMetadata.getInterface(),
                    refPair.getRef().getProperty( Constants.SERVICE_ID ) }, e );
            return null;
        }

        // keep the service for later ungetting
        if ( serviceObject != null )
        {
            refPair.setServiceObject( serviceObject );
        }

        // return the acquired service (may be null of course)
        return serviceObject;
    }

    //---------- DependencyManager core ---------------------------------------

    /**
     * Returns the name of the service reference.
     */
    public String getName()
    {
        return m_dependencyMetadata.getName();
    }


    /**
     * Returns <code>true</code> if this dependency manager is satisfied, that
     * is if either the dependency is optional or the number of services
     * registered in the framework and available to this dependency manager is
     * not zero.
     */
    public boolean isSatisfied()
    {
        Customizer<T> customizer = customizerRef.get();
        return customizer != null && customizer.isSatisfied();
    }


    /**
     * Returns <code>true</code> if the component providing bundle has permission
     * to get the service described by this reference.
     */
    public boolean hasGetPermission()
    {
        if ( System.getSecurityManager() != null )
        {
            Permission perm = new ServicePermission( getServiceName(), ServicePermission.GET );
            return m_componentManager.getBundle().hasPermission( perm );
        }

        // no security manager, hence permission given
        return true;
    }

    boolean prebind()
    {
        return customizerRef.get().open();
    }

    /**
     * initializes a dependency. This method binds all of the service
     * occurrences to the instance object
     *
     * @return true if the dependency is satisfied and at least the minimum
     *      number of services could be bound. Otherwise false is returned.
     */
    boolean open( S componentInstance )
    {
        // If no references were received, we have to check if the dependency
        // is optional, if it is not then the dependency is invalid
        if ( !isSatisfied() )
        {
            m_componentManager.log( LogService.LOG_DEBUG,
                "For dependency {0}, no longer satisfied, bind fails",
                new Object[]{ m_dependencyMetadata.getName() }, null );
            return false;
        }

        // if no bind method is configured or if this is a delayed component,
        // we have nothing to do and just signal success
        if ( componentInstance == null || m_dependencyMetadata.getBind() == null )
        {
            return true;
        }

        // assume success to begin with: if the dependency is optional,
        // we don't care, whether we can bind a service. Otherwise, we
        // require at least one service to be bound, thus we require
        // flag being set in the loop below
        boolean success = m_dependencyMetadata.isOptional();
        AtomicInteger trackingCount =  new AtomicInteger( );
        Collection<RefPair<T>> refs;
        synchronized ( trackerRef.get().tracked() )
        {
            refs = customizerRef.get().getRefs( trackingCount );
            EdgeInfo info = getEdgeInfo( componentInstance );
            info.setOpen( trackingCount.get() );
        }
        m_componentManager.log( LogService.LOG_DEBUG,
            "For dependency {0}, optional: {1}; to bind: {2}",
            new Object[]{ m_dependencyMetadata.getName(), success, refs }, null );
        for ( RefPair<T> refPair : refs )
        {
            if ( !refPair.isFailed() )
            {
                if ( !invokeBindMethod( componentInstance, refPair, trackingCount.get() ) )
                {
                    m_componentManager.log( LogService.LOG_DEBUG,
                            "For dependency {0}, failed to invoke bind method on object {1}",
                            new Object[] {m_dependencyMetadata.getName(), refPair}, null );

                }
                success = true;
            }
        }
        return success;
    }


    private EdgeInfo getEdgeInfo( S componentInstance )
    {
        EdgeInfo info = edgeInfoMap.get( componentInstance );
        if ( info == null )
        {
            info = new EdgeInfo();
            edgeInfoMap.put( componentInstance, info );
        }
        return info;
    }
    /**
     * Revoke the given bindings. This method cannot throw an exception since
     * it must try to complete all that it can
     */
    void close( S componentInstance )
    {
        // only invoke the unbind method if there is an instance (might be null
        // in the delayed component situation) and the unbind method is declared.
        boolean doUnbind = componentInstance != null && m_dependencyMetadata.getUnbind() != null;

        AtomicInteger trackingCount = new AtomicInteger();
        Collection<RefPair<T>> refPairs;
        CountDownLatch latch = new CountDownLatch( 1 );
        synchronized ( trackerRef.get().tracked() )
        {
            refPairs = customizerRef.get().getRefs( trackingCount );
            EdgeInfo info = getEdgeInfo( componentInstance );
            info.setClose( trackingCount.get() );
            info.setLatch( latch );
        }

        m_componentManager.log( LogService.LOG_DEBUG,
                "DependencyManager : close {0} for {1} at tracking count {2} refpairs: {3}",
                new Object[] {this, componentInstance, trackingCount.get(), refPairs}, null );
        m_componentManager.waitForTracked( trackingCount.get() );
        for ( RefPair<T> boundRef : refPairs )
        {
            if ( doUnbind && !boundRef.isFailed() )
            {
                invokeUnbindMethod( componentInstance, boundRef, trackingCount.get() );
            }

            // unget the service, we call it here since there might be a
            // bind method (or the locateService method might have been
            // called) but there is no unbind method to actually unbind
            // the service (see FELIX-832)
//                ungetService( boundRef );
        }
        latch.countDown();
    }

    void cleanup( S componentInstance)
    {
        edgeInfoMap.remove( componentInstance );
    }

    public void invokeBindMethodLate( final ServiceReference<T> ref, int trackingCount )
    {
        if ( !isSatisfied() )
        {
            return;
        }
        if ( !isMultiple() )
        {
            Collection<RefPair<T>> refs = customizerRef.get().getRefs( new AtomicInteger( ) );
            if (refs.isEmpty())
            {
                return;
            }
            RefPair<T> test = refs.iterator().next();
            if ( ref != test.getRef())
            {
                //another ref is now better
                return;
            }
        }
        //TODO dynamic reluctant
        RefPair<T> refPair = trackerRef.get().getService( ref );
        synchronized ( refPair )
        {
            if (refPair.getServiceObject() != null)
            {
                m_componentManager.log( LogService.LOG_DEBUG,
                        "DependencyManager : late binding of service reference {1} skipped as service has already been located",
                        new Object[] {ref}, null );
                //something else got the reference and may be binding it.
                return;
            }
            m_bindMethods.getBind().getServiceObject( refPair, m_componentManager.getActivator().getBundleContext(), m_componentManager );
        }
        m_componentManager.invokeBindMethod( this, refPair, trackingCount );
    }

    /**
     * Calls the bind method. In case there is an exception while calling the
     * bind method, the service is not considered to be bound to the instance
     * object
     * <p>
     * If the reference is singular and a service has already been bound to the
     * component this method has no effect and just returns <code>true</code>.
     *
     *
     *
     * @param componentInstance
     * @param refPair the service reference, service object tuple.
     *
     * @param trackingCount
     * @return true if the service should be considered bound. If no bind
     *      method is found or the method call fails, <code>true</code> is
     *      returned. <code>false</code> is only returned if the service must
     *      be handed over to the bind method but the service cannot be
     *      retrieved using the service reference.
     */
    boolean invokeBindMethod( S componentInstance, RefPair refPair, int trackingCount )
    {
        // The bind method is only invoked if the implementation object is not
        // null. This is valid for both immediate and delayed components
        if ( componentInstance != null )
        {
            synchronized ( trackerRef.get().tracked() )
            {
                EdgeInfo info = edgeInfoMap.get( componentInstance );
                if (info != null && info.outOfRange( trackingCount ) )
                {
                    //ignore events before open started or we will have duplicate binds.
                    return true;
                }
            }
            MethodResult result = m_bindMethods.getBind().invoke( componentInstance, refPair, MethodResult.VOID, m_componentManager );
            if ( result == null )
            {
                return false;
            }
            m_componentManager.setServiceProperties( result );
            return true;

            // Concurrency Issue: The component instance still exists but
            // but the defined bind method field is null, fail binding
//            m_componentManager.log( LogService.LOG_INFO,
//                    "DependencyManager : Component instance present, but DependencyManager shut down (no bind method)",
//                    null );
//            return false;
        }
        else
        {
            m_componentManager.log( LogService.LOG_DEBUG,
                    "DependencyManager : component not yet created, assuming bind method call succeeded",
                    null );

            return true;
        }
    }


    /**
     * Calls the updated method.
     *
     * @param componentInstance
     * @param refPair A service reference corresponding to the service whose service
     */
    void invokeUpdatedMethod( S componentInstance, final RefPair<T> refPair, int trackingCount )
    {
        if ( m_dependencyMetadata.getUpdated() == null )
        {
            return;
        }
        // The updated method is only invoked if the implementation object is not
        // null. This is valid for both immediate and delayed components
        if ( componentInstance != null )
        {
            if (refPair == null)
            {

                //TODO should this be possible? If so, reduce or eliminate logging
                m_componentManager.log( LogService.LOG_WARNING,
                        "DependencyManager : invokeUpdatedMethod : Component set, but reference not present", null );
                return;
            }
            synchronized ( trackerRef.get().tracked() )
            {
                EdgeInfo info = edgeInfoMap.get( componentInstance );
                if (info != null && info.outOfRange( trackingCount ) )
                {
                    //ignore events after close started or we will have duplicate unbinds.
                    return;
                }
            }
            if ( !m_bindMethods.getUpdated().getServiceObject( refPair, m_componentManager.getActivator().getBundleContext(), m_componentManager ))
            {
                m_componentManager.log( LogService.LOG_WARNING,
                        "DependencyManager : invokeUpdatedMethod : Service not available from service registry for ServiceReference {0} for reference {1}",
                        new Object[] {refPair.getRef(), getName()}, null );
                return;

            }
            MethodResult methodResult = m_bindMethods.getUpdated().invoke( componentInstance, refPair, MethodResult.VOID, m_componentManager );
            if ( methodResult != null)
            {
                m_componentManager.setServiceProperties( methodResult );
            }
        }
        else
        {
            // don't care whether we can or cannot call the updated method
            // if the component instance has already been cleared by the
            // close() method
            m_componentManager.log( LogService.LOG_DEBUG,
                    "DependencyManager : Component not set, no need to call updated method", null );
        }
    }


    /**
     * Calls the unbind method.
     * <p>
     * If the reference is singular and the given service is not the one bound
     * to the component this method has no effect and just returns
     * <code>true</code>.
     *
     * @param componentInstance
     * @param refPair A service reference corresponding to the service that will be
     * @param trackingCount
     */
    void invokeUnbindMethod( S componentInstance, final RefPair<T> refPair, int trackingCount )
    {
        // The unbind method is only invoked if the implementation object is not
        // null. This is valid for both immediate and delayed components
        if ( componentInstance != null )
        {
            EdgeInfo info;
            synchronized ( trackerRef.get().tracked() )
            {
                info = edgeInfoMap.get( componentInstance );
            }
            if (info != null && info.outOfRange( trackingCount ) )
            {
                //wait for unbinds to complete
                if (info.getLatch() != null)
                {
                    try
                    {
                        info.getLatch().await(  );
                    }
                    catch ( InterruptedException e )
                    {
                        //ignore
                    }
                }
                //ignore events after close started or we will have duplicate unbinds.
                return;
            }

            if (refPair == null)
            {
                //TODO should this be possible? If so, reduce or eliminate logging
                m_componentManager.log( LogService.LOG_WARNING,
                        "DependencyManager : invokeUnbindMethod : Component set, but reference not present", null );
                return;
            }
            if ( !m_bindMethods.getUnbind().getServiceObject( refPair, m_componentManager.getActivator().getBundleContext(), m_componentManager ))
            {
                m_componentManager.log( LogService.LOG_WARNING,
                        "DependencyManager : invokeUnbindMethod : Service not available from service registry for ServiceReference {0} for reference {1}",
                        new Object[] {refPair.getRef(), getName()}, null );
                return;

            }
            MethodResult methodResult = m_bindMethods.getUnbind().invoke( componentInstance, refPair, MethodResult.VOID, m_componentManager );
            if ( methodResult != null )
            {
                m_componentManager.setServiceProperties( methodResult );
            }
        }
        else
        {
            // don't care whether we can or cannot call the unbind method
            // if the component instance has already been cleared by the
            // close() method
            m_componentManager.log( LogService.LOG_DEBUG,
                "DependencyManager : Component not set, no need to call unbind method", null );
        }
    }


    //------------- Service target filter support -----------------------------

    /**
     * Returns <code>true</code> if the <code>properties</code> can be
     * dynamically applied to the component to which the dependency manager
     * belongs.
     * <p>
     * This method applies the following heuristics (in the given order):
     * <ol>
     * <li>If there is no change in the target filter for this dependency, the
     * properties can be applied</li>
     * <li>If the dependency is static and there are changes in the target
     * filter we cannot dynamically apply the configuration because the filter
     * may (assume they do for simplicity here) cause the bindings to change.</li>
     * <li>If there is still at least one service matching the new target filter
     * we can apply the configuration because the depdency is dynamic.</li>
     * <li>If there are no more services matching the filter, we can still
     * apply the configuration if the dependency is optional.</li>
     * <li>Ultimately, if all other checks do not apply we cannot dynamically
     * apply.</li>
     * </ol>
     */
    boolean canUpdateDynamically( Dictionary<String, Object> properties )
    {
        // 1. no target filter change
        final String newTarget = ( String ) properties.get( m_dependencyMetadata.getTargetPropertyName() );
        final String currentTarget = getTarget();
        if ( ( currentTarget == null && newTarget == null )
            || ( currentTarget != null && currentTarget.equals( newTarget ) ) )
        {
            // can update if target filter is not changed, since there is
            // no change is service binding
            return true;
        }
        // invariant: target filter change

        // 2. if static policy, cannot update dynamically
        // (for simplicity assuming change in target service binding)
        if ( m_dependencyMetadata.isStatic() )
        {
            // cannot update if services are statically bound and the target
            // filter is modified, since there is (potentially at least)
            // a change is service bindings
            return false;
        }
        // invariant: target filter change + dynamic policy

        // 3. check optionality
        if ( m_dependencyMetadata.isOptional() )
        {
            // can update since even if no service matches the new filter, this
            // makes no difference because the dependency is optional
            return true;
        }
        // invariant: target filter change + mandatory + dynamic policy

        // 4. check target services matching the new filter
        ServiceReference<T>[] refs = getFrameworkServiceReferences( newTarget );
        if ( refs != null && refs.length > 0 )
        {
            // can update since there is at least on service matching the
            // new target filter and the services may be exchanged dynamically
            return true;
        }
        // invariant: target filter change + dynamic policy + no more matching service + required

        // 5. cannot dynamically update because the target filter results in
        // no more applicable services which is not acceptable
        return false;
    }


    /**
     * Sets the target filter from target filter property contained in the
     * properties. The filter is taken from a property whose name is derived
     * from the dependency name and the suffix <code>.target</code> as defined
     * for target properties on page 302 of the Declarative Services
     * Specification, section 112.6.
     *
     * @param properties The properties containing the optional target service
     *      filter property
     */
    void setTargetFilter( Dictionary<String, Object> properties )
    {
        try
        {
            setTargetFilter( ( String ) properties.get( m_dependencyMetadata.getTargetPropertyName() ) );
        }
        catch ( InvalidSyntaxException e )
        {
            // this should not occur.  The only choice would be if the filter for the object class was invalid,
            // but we already set this once when we enabled.
        }
    }


    /**
     * Sets the target filter of this dependency to the new filter value. If the
     * new target filter is the same as the old target filter, this method has
     * not effect. Otherwise any services currently bound but not matching the
     * new filter are unbound. Likewise any registered services not currently
     * bound but matching the new filter are bound.
     *
     * @param target The new target filter to be set. This may be
     *      <code>null</code> if no target filtering is to be used.
     */
    private void setTargetFilter( String target) throws InvalidSyntaxException
    {
        if (!hasGetPermission())
        {
            customizerRef.set( new NoPermissionsCustomizer() );
            m_componentManager.log( LogService.LOG_INFO, "No permission to get services for {0}", new Object[]
                    {m_dependencyMetadata.getName()}, null );
            return;
        }
        // if configuration does not set filter, use the value from metadata
        if (target == null)
        {
            target = m_dependencyMetadata.getTarget();
        }
        // do nothing if target filter does not change
        if ( ( m_target == null && target == null ) || ( m_target != null && m_target.equals( target ) ) )
        {
            m_componentManager.log( LogService.LOG_DEBUG, "No change in target property for dependency {0}: currently registered: {1}", new Object[]
                    {m_dependencyMetadata.getName(), registered}, null );
            if (registered)
            {
                return;
            }
        }
        m_target = target;
        String filterString = "(" + Constants.OBJECTCLASS + "=" + m_dependencyMetadata.getInterface() + ")";
        if (m_target != null)
        {
            filterString = "(&" + filterString + m_target + ")";
        }

        SortedMap<ServiceReference<T>, RefPair<T>> refMap;
        if ( registered )
        {
            refMap = unregisterServiceListener();
        }
        else
        {
            refMap = new TreeMap<ServiceReference<T>, RefPair<T>>(Collections.reverseOrder());
        }
        m_componentManager.log( LogService.LOG_DEBUG, "Setting target property for dependency {0} to {1}", new Object[]
                {m_dependencyMetadata.getName(), target}, null );
        try
        {
            m_targetFilter = m_componentManager.getActivator().getBundleContext().createFilter( filterString );
        }
        catch ( InvalidSyntaxException ise )
        {
            m_componentManager.log( LogService.LOG_ERROR, "Invalid syntax in target property for dependency {0} to {1}", new Object[]
                    {m_dependencyMetadata.getName(), target}, null );
            // TODO this is an error, how do we recover?
            m_targetFilter = null;
        }

        registerServiceListener( refMap );
    }

    private void registerServiceListener( SortedMap<ServiceReference<T>, RefPair<T>> refMap ) throws InvalidSyntaxException
    {
        final ServiceTracker<T, RefPair<T>> oldTracker = trackerRef.get();
        Customizer<T> customizer = newCustomizer();
        customizer.setPreviousRefMap( refMap );
        boolean initialActive = oldTracker != null && oldTracker.isActive();
        ServiceTracker<T, RefPair<T>> tracker = new ServiceTracker<T, RefPair<T>>( m_componentManager.getActivator().getBundleContext(), m_targetFilter, customizer, initialActive );
        customizer.setTracker( tracker );
        trackerRef.set( tracker );
        registered = true;
        tracker.open( m_componentManager.getTrackingCount() );
        customizer.setTrackerOpened();
        if ( oldTracker != null )
        {
            oldTracker.completeClose( refMap );
        }
        m_componentManager.log( LogService.LOG_DEBUG, "registering service listener for dependency {0}", new Object[]
                {m_dependencyMetadata.getName()}, null );
    }

    private Customizer<T> newCustomizer()
    {
        Customizer<T> customizer;
        if (m_componentManager.isFactory())
        {
            customizer = new FactoryCustomizer();
        }
        else if ( isMultiple() )
        {
            if ( isStatic() )
            {
                if ( isReluctant() )
                {
                    customizer = new MultipleStaticReluctantCustomizer();
                }
                else
                {
                    customizer = new MultipleStaticGreedyCustomizer();
                }
            }
            else
            {
                customizer = new MultipleDynamicCustomizer();
            }
        }
        else
        {
            if ( isStatic() )
            {
                customizer = new SingleStaticCustomizer();
            }
            else
            {
                customizer = new SingleDynamicCustomizer();
            }
        }
        customizerRef.set( customizer );
        return customizer;
    }

    SortedMap<ServiceReference<T>, RefPair<T>> unregisterServiceListener()
    {
        SortedMap<ServiceReference<T>, RefPair<T>> refMap;
        ServiceTracker<T, RefPair<T>> tracker = trackerRef.get();
//        trackerRef.set( null ); //???
        if ( tracker != null )
        {
            AtomicInteger trackingCount = new AtomicInteger( );
            refMap = tracker.close( trackingCount );
        }
        else
        {
            refMap = new TreeMap<ServiceReference<T>, RefPair<T>>(Collections.reverseOrder());
        }
        registered = false;
        m_componentManager.log( LogService.LOG_DEBUG, "unregistering service listener for dependency {0}", new Object[]
                {m_dependencyMetadata.getName()}, null );
        return refMap;
    }


    /**
     * Returns the target filter of this dependency as a string or
     * <code>null</code> if this dependency has no target filter set.
     *
     * @return The target filter of this dependency or <code>null</code> if
     *      none is set.
     */
    public String getTarget()
    {
        return m_target;
    }


    public String toString()
    {
        return "DependencyManager: Component [" + m_componentManager + "] reference [" + m_dependencyMetadata.getName() + "]";
    }
}
