// Copyright 2006, 2007, 2008, 2009, 2010, 2011 The Apache Software Foundation
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package org.apache.tapestry5.ioc.internal;

import static java.lang.String.format;

import java.io.ObjectStreamException;
import java.io.Serializable;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.Map;
import java.util.Set;

import org.apache.tapestry5.ioc.AdvisorDef;
import org.apache.tapestry5.ioc.Invokable;
import org.apache.tapestry5.ioc.Markable;
import org.apache.tapestry5.ioc.ObjectCreator;
import org.apache.tapestry5.ioc.ObjectLocator;
import org.apache.tapestry5.ioc.OperationTracker;
import org.apache.tapestry5.ioc.ServiceBuilderResources;
import org.apache.tapestry5.ioc.ServiceLifecycle2;
import org.apache.tapestry5.ioc.ServiceResources;
import org.apache.tapestry5.ioc.annotations.Local;
import org.apache.tapestry5.ioc.def.ContributionDef;
import org.apache.tapestry5.ioc.def.ContributionDef2;
import org.apache.tapestry5.ioc.def.DecoratorDef;
import org.apache.tapestry5.ioc.def.ModuleDef;
import org.apache.tapestry5.ioc.def.ModuleDef2;
import org.apache.tapestry5.ioc.def.ServiceDef;
import org.apache.tapestry5.ioc.def.ServiceDef2;
import org.apache.tapestry5.ioc.internal.services.JustInTimeObjectCreator;
import org.apache.tapestry5.ioc.internal.util.CollectionFactory;
import org.apache.tapestry5.ioc.internal.util.ConcurrentBarrier;
import org.apache.tapestry5.ioc.internal.util.InjectionResources;
import org.apache.tapestry5.ioc.internal.util.InternalUtils;
import org.apache.tapestry5.ioc.internal.util.MapInjectionResources;
import org.apache.tapestry5.ioc.services.AspectDecorator;
import org.apache.tapestry5.ioc.services.ClassFactory;
import org.apache.tapestry5.ioc.services.PlasticProxyFactory;
import org.apache.tapestry5.ioc.services.Status;
import org.apache.tapestry5.plastic.ClassInstantiator;
import org.apache.tapestry5.plastic.InstructionBuilder;
import org.apache.tapestry5.plastic.InstructionBuilderCallback;
import org.apache.tapestry5.plastic.MethodDescription;
import org.apache.tapestry5.plastic.PlasticClass;
import org.apache.tapestry5.plastic.PlasticClassTransformer;
import org.apache.tapestry5.plastic.PlasticField;
import org.apache.tapestry5.plastic.PlasticMethod;
import org.slf4j.Logger;

@SuppressWarnings("all")
public class ModuleImpl implements Module
{
    private final InternalRegistry registry;

    private final ServiceActivityTracker tracker;

    private final ModuleDef2 moduleDef;

    private final ClassFactory classFactory;

    private final PlasticProxyFactory proxyFactory;

    private final Logger logger;

    /**
     * Lazily instantiated. Access is guarded by BARRIER.
     */
    private Object moduleInstance;

    // Set to true when invoking the module constructor. Used to
    // detect endless loops caused by irresponsible dependencies in
    // the constructor.
    private boolean insideConstructor;

    /**
     * Keyed on fully qualified service id; values are instantiated services (proxies). Guarded by BARRIER.
     */
    private final Map<String, Object> services = CollectionFactory.newCaseInsensitiveMap();

    private final Map<String, ServiceDef2> serviceDefs = CollectionFactory.newCaseInsensitiveMap();

    /**
     * The barrier is shared by all modules, which means that creation of *any* service for any module is single
     * threaded.
     */
    private final static ConcurrentBarrier BARRIER = new ConcurrentBarrier();

    public ModuleImpl(InternalRegistry registry, ServiceActivityTracker tracker, ModuleDef moduleDef,
            ClassFactory classFactory, PlasticProxyFactory proxyFactory, Logger logger)
    {
        this.registry = registry;
        this.tracker = tracker;
        this.proxyFactory = proxyFactory;
        this.moduleDef = InternalUtils.toModuleDef2(moduleDef);
        this.classFactory = classFactory;
        this.logger = logger;

        for (String id : moduleDef.getServiceIds())
        {
            ServiceDef sd = moduleDef.getServiceDef(id);

            ServiceDef2 sd2 = InternalUtils.toServiceDef2(sd);

            serviceDefs.put(id, sd2);
        }
    }

    public <T> T getService(String serviceId, Class<T> serviceInterface)
    {
        assert InternalUtils.isNonBlank(serviceId);
        assert serviceInterface != null;
        ServiceDef2 def = getServiceDef(serviceId);

        // RegistryImpl should already have checked that the service exists.
        assert def != null;

        Object service = findOrCreate(def, null);

        try
        {
            return serviceInterface.cast(service);
        }
        catch (ClassCastException ex)
        {
            // This may be overkill: I don't know how this could happen
            // given that the return type of the method determines
            // the service interface.

            throw new RuntimeException(IOCMessages.serviceWrongInterface(serviceId, def.getServiceInterface(),
                    serviceInterface));
        }
    }

    public Set<DecoratorDef> findMatchingDecoratorDefs(ServiceDef serviceDef)
    {
        Set<DecoratorDef> result = CollectionFactory.newSet();

        for (DecoratorDef def : moduleDef.getDecoratorDefs())
        {
            if (def.matches(serviceDef) || markerMatched(serviceDef, InternalUtils.toDecoratorDef2(def)))
                result.add(def);
        }

        return result;
    }

    public Set<AdvisorDef> findMatchingServiceAdvisors(ServiceDef serviceDef)
    {
        Set<AdvisorDef> result = CollectionFactory.newSet();

        for (AdvisorDef def : moduleDef.getAdvisorDefs())
        {
            if (def.matches(serviceDef) || markerMatched(serviceDef, InternalUtils.toAdvisorDef2(def)))
                result.add(def);
        }

        return result;
    }

    @SuppressWarnings("unchecked")
    public Collection<String> findServiceIdsForInterface(Class serviceInterface)
    {
        assert serviceInterface != null;
        Collection<String> result = CollectionFactory.newList();

        for (ServiceDef2 def : serviceDefs.values())
        {
            if (serviceInterface.isAssignableFrom(def.getServiceInterface()))
                result.add(def.getServiceId());
        }

        return result;
    }

    /**
     * Locates the service proxy for a particular service (from the service definition).
     * 
     * @param def
     *            defines the service
     * @param eagerLoadProxies
     *            collection into which proxies for eager loaded services are added (or null)
     * @return the service proxy
     */
    private Object findOrCreate(final ServiceDef2 def, final Collection<EagerLoadServiceProxy> eagerLoadProxies)
    {
        final String key = def.getServiceId();

        final Invokable create = new Invokable()
        {
            public Object invoke()
            {
                // In a race condition, two threads may try to create the same service simulatenously.
                // The second will block until after the first creates the service.

                Object result = services.get(key);

                // Normally, result is null, unless some other thread slipped in and created the service
                // proxy.

                if (result == null)
                {
                    result = create(def, eagerLoadProxies);

                    services.put(key, result);
                }

                return result;
            }
        };

        Invokable find = new Invokable()
        {
            public Object invoke()
            {
                Object result = services.get(key);

                if (result == null)
                    result = BARRIER.withWrite(create);

                return result;
            }
        };

        return BARRIER.withRead(find);
    }

    public void collectEagerLoadServices(final Collection<EagerLoadServiceProxy> proxies)
    {
        Runnable work = new Runnable()
        {
            public void run()
            {
                for (ServiceDef2 def : serviceDefs.values())
                {
                    if (def.isEagerLoad())
                        findOrCreate(def, proxies);
                }
            }
        };

        registry.run("Eager loading services", work);
    }

    /**
     * Creates the service and updates the cache of created services.
     * 
     * @param eagerLoadProxies
     *            a list into which any eager loaded proxies should be added
     */
    private Object create(final ServiceDef2 def, final Collection<EagerLoadServiceProxy> eagerLoadProxies)
    {
        final String serviceId = def.getServiceId();

        final Logger logger = registry.getServiceLogger(serviceId);

        String description = IOCMessages.creatingService(serviceId);

        if (logger.isDebugEnabled())
            logger.debug(description);

        final Module module = this;

        Invokable operation = new Invokable()
        {
            public Object invoke()
            {
                try
                {
                    ServiceBuilderResources resources = new ServiceResourcesImpl(registry, module, def, classFactory,
                            logger);

                    // Build up a stack of operations that will be needed to realize the service
                    // (by the proxy, at a later date).

                    ObjectCreator creator = def.createServiceCreator(resources);

                    Class serviceInterface = def.getServiceInterface();

                    ServiceLifecycle2 lifecycle = registry.getServiceLifecycle(def.getServiceScope());

                    // For non-proxyable services, we immediately create the service implementation
                    // and return it. There's no interface to proxy, which throws out the possibility of
                    // deferred instantiation, service lifecycles, and decorators.

                    if (!serviceInterface.isInterface())
                    {
                        if (lifecycle.requiresProxy())
                            throw new IllegalArgumentException(
                                    String.format(
                                            "Service scope '%s' requires a proxy, but the service does not have a service interface (necessary to create a proxy). Provide a service interface or select a different service scope.",
                                            def.getServiceScope()));

                        return creator.createObject();
                    }

                    creator = new OperationTrackingObjectCreator(registry, "Invoking " + creator.toString(), creator);

                    creator = new LifecycleWrappedServiceCreator(lifecycle, resources, creator);

                    // Marked services (or services inside marked modules) are not decorated.
                    // TapestryIOCModule prevents decoration of its services. Note that all decorators will decorate
                    // around the aspect interceptor, which wraps around the core service implementation.

                    boolean allowDecoration = !def.isPreventDecoration();

                    if (allowDecoration)
                    {
                        creator = new AdvisorStackBuilder(def, creator, getAspectDecorator(), registry);
                        creator = new InterceptorStackBuilder(def, creator, registry);
                    }

                    // Add a wrapper that checks for recursion.

                    creator = new RecursiveServiceCreationCheckWrapper(def, creator, logger);

                    creator = new OperationTrackingObjectCreator(registry, "Realizing service " + serviceId, creator);

                    JustInTimeObjectCreator delegate = new JustInTimeObjectCreator(tracker, creator, serviceId);

                    Object proxy = createProxy(resources, delegate);

                    registry.addRegistryShutdownListener(delegate);

                    // Occasionally eager load service A may invoke service B from its service builder method; if
                    // service B is eager loaded, we'll hit this method but eagerLoadProxies will be null. That's OK
                    // ... service B is being realized anyway.

                    if (def.isEagerLoad() && eagerLoadProxies != null)
                        eagerLoadProxies.add(delegate);

                    tracker.setStatus(serviceId, Status.VIRTUAL);

                    return proxy;
                }
                catch (Exception ex)
                {
                    throw new RuntimeException(IOCMessages.errorBuildingService(serviceId, def, ex), ex);
                }
            }
        };

        return registry.invoke(description, operation);
    }

    private AspectDecorator getAspectDecorator()
    {
        return registry.invoke("Obtaining AspectDecorator service", new Invokable<AspectDecorator>()
        {
            public AspectDecorator invoke()
            {
                return registry.getService(AspectDecorator.class);
            }
        });
    }

    private final Runnable instantiateModule = new Runnable()
    {
        public void run()
        {
            moduleInstance = registry.invoke("Constructing module class " + moduleDef.getBuilderClass().getName(),
                    new Invokable()
                    {
                        public Object invoke()
                        {
                            return instantiateModuleInstance();
                        }
                    });
        }
    };

    private final Invokable provideModuleInstance = new Invokable<Object>()
    {
        public Object invoke()
        {
            if (moduleInstance == null)
                BARRIER.withWrite(instantiateModule);

            return moduleInstance;
        }
    };

    public Object getModuleBuilder()
    {
        return BARRIER.withRead(provideModuleInstance);
    }

    private Object instantiateModuleInstance()
    {
        Class moduleClass = moduleDef.getBuilderClass();

        Constructor[] constructors = moduleClass.getConstructors();

        if (constructors.length == 0)
            throw new RuntimeException(IOCMessages.noPublicConstructors(moduleClass));

        if (constructors.length > 1)
        {
            // Sort the constructors ascending by number of parameters (descending); this is really
            // just to allow the test suite to work properly across different JVMs (which will
            // often order the constructors differently).

            Comparator<Constructor> comparator = new Comparator<Constructor>()
            {
                public int compare(Constructor c1, Constructor c2)
                {
                    return c2.getParameterTypes().length - c1.getParameterTypes().length;
                }
            };

            Arrays.sort(constructors, comparator);

            logger.warn(IOCMessages.tooManyPublicConstructors(moduleClass, constructors[0]));
        }

        Constructor constructor = constructors[0];

        if (insideConstructor)
            throw new RuntimeException(IOCMessages.recursiveModuleConstructor(moduleClass, constructor));

        ObjectLocator locator = new ObjectLocatorImpl(registry, this);
        Map<Class, Object> resourcesMap = CollectionFactory.newMap();

        resourcesMap.put(Logger.class, logger);
        resourcesMap.put(ObjectLocator.class, locator);
        resourcesMap.put(OperationTracker.class, registry);

        InjectionResources resources = new MapInjectionResources(resourcesMap);

        Throwable fail = null;

        try
        {
            insideConstructor = true;

            Object[] parameterValues = InternalUtils.calculateParameters(locator, resources,
                    constructor.getParameterTypes(), constructor.getGenericParameterTypes(),
                    constructor.getParameterAnnotations(), registry);

            Object result = constructor.newInstance(parameterValues);

            InternalUtils.injectIntoFields(result, locator, resources, registry);

            return result;
        }
        catch (InvocationTargetException ex)
        {
            fail = ex.getTargetException();
        }
        catch (Exception ex)
        {
            fail = ex;
        }
        finally
        {
            insideConstructor = false;
        }

        throw new RuntimeException(IOCMessages.instantiateBuilderError(moduleClass, fail), fail);
    }

    private Object createProxy(ServiceResources resources, ObjectCreator creator)
    {
        String serviceId = resources.getServiceId();
        Class serviceInterface = resources.getServiceInterface();

        String toString = format("<Proxy for %s(%s)>", serviceId, serviceInterface.getName());

        ServiceProxyToken token = SerializationSupport.createToken(serviceId);

        return createProxyInstance(creator, token, serviceInterface, resources.getImplementationClass(), toString);
    }

    /** "Magic" method related to Externalizable that allows the Proxy object to replace itself with the token. */
    private static final MethodDescription WRITE_REPLACE = new MethodDescription(Modifier.PRIVATE, "java.lang.Object",
            "writeReplace", null, new String[]
            { ObjectStreamException.class.getName() });

    private Object createProxyInstance(final ObjectCreator creator, final ServiceProxyToken token,
            final Class serviceInterface, Class serviceImplementation, final String description)
    {
        ClassInstantiator instantiator = proxyFactory.createProxy(serviceInterface, new PlasticClassTransformer()
        {
            public void transform(final PlasticClass plasticClass)
            {
                plasticClass.introduceInterface(Serializable.class);

                final PlasticField creatorField = plasticClass.introduceField(ObjectCreator.class, "creator").inject(
                        creator);

                final PlasticField tokenField = plasticClass.introduceField(ServiceProxyToken.class, "token").inject(
                        token);

                // TODO: Choose a simpler name, unless it conflicts with a service interface method name.

                PlasticMethod delegateMethod = plasticClass.introduceMethod(new MethodDescription(Modifier.PRIVATE,
                        serviceInterface.getName(), "_$delegate", null, null));

                // If not concerned with efficiency, this might be done with method advice instead.
                delegateMethod.changeImplementation(new InstructionBuilderCallback()
                {
                    public void doBuild(InstructionBuilder builder)
                    {
                        builder.loadThis().getField(plasticClass.getClassName(), creatorField.getName(),
                                ObjectCreator.class);
                        builder.invoke(ObjectCreator.class, Object.class, "createObject").checkcast(serviceInterface)
                                .returnResult();
                    }
                });

                for (Method m : serviceInterface.getMethods())
                {
                    plasticClass.introduceMethod(m).delegateTo(delegateMethod);
                }

                plasticClass.introduceMethod(WRITE_REPLACE).changeImplementation(new InstructionBuilderCallback()
                {
                    public void doBuild(InstructionBuilder builder)
                    {
                        builder.loadThis()
                                .getField(plasticClass.getClassName(), tokenField.getName(), ServiceProxyToken.class)
                                .returnResult();
                    }
                });

                /*
                 * TODO:
                 * if (serviceImplementation != null)
                 * {
                 * classFab.copyClassAnnotationsFromDelegate(serviceImplementation);
                 * classFab.copyMethodAnnotationsFromDelegate(serviceInterface, serviceImplementation);
                 * }
                 */

                plasticClass.addToString(description);
            }
        });

        return instantiator.newInstance();
    }

    @SuppressWarnings("all")
    public Set<ContributionDef2> getContributorDefsForService(ServiceDef serviceDef)
    {
        Set<ContributionDef2> result = CollectionFactory.newSet();

        for (ContributionDef next : moduleDef.getContributionDefs())
        {
            ContributionDef2 def = InternalUtils.toContributionDef2(next);

            if (serviceDef.getServiceId().equalsIgnoreCase(def.getServiceId()))
            {
                result.add(def);
            }
            else
            {
                if (markerMatched(serviceDef, def))
                {
                    result.add(def);
                }
            }
        }

        return result;
    }

    private boolean markerMatched(ServiceDef serviceDef, Markable markable)
    {
        if (!serviceDef.getServiceInterface().equals(markable.getServiceInterface()))
            return false;
        ;

        Set<Class> contributionMarkers = CollectionFactory.newSet(markable.getMarkers());

        if (contributionMarkers.contains(Local.class))
        {
            // If @Local is present, filter out services that aren't in the same module.
            // Don't consider @Local to be a marker annotation
            // for the later match, however.

            if (!isLocalServiceDef(serviceDef))
                return false;

            contributionMarkers.remove(Local.class);
        }

        // Filter out any stray annotations that aren't used by some
        // service, in any module, as a marker annotation.

        contributionMarkers.retainAll(registry.getMarkerAnnotations());

        return serviceDef.getMarkers().containsAll(contributionMarkers);
    }

    private boolean isLocalServiceDef(ServiceDef serviceDef)
    {
        return serviceDefs.containsKey(serviceDef.getServiceId());
    }

    public ServiceDef2 getServiceDef(String serviceId)
    {
        return serviceDefs.get(serviceId);
    }

    public String getLoggerName()
    {
        return moduleDef.getLoggerName();
    }

    @Override
    public String toString()
    {
        return String.format("ModuleImpl[%s]", moduleDef.getLoggerName());
    }
}
