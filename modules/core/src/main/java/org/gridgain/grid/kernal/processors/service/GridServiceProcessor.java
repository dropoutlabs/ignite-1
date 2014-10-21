/* @java.file.header */

/*  _________        _____ __________________        _____
 *  __  ____/___________(_)______  /__  ____/______ ____(_)_______
 *  _  / __  __  ___/__  / _  __  / _  / __  _  __ `/__  / __  __ \
 *  / /_/ /  _  /    _  /  / /_/ /  / /_/ /  / /_/ / _  /  _  / / /
 *  \____/   /_/     /_/   \_,__/   \____/   \__,_/  /_/   /_/ /_/
 */

package org.gridgain.grid.kernal.processors.service;

import org.gridgain.grid.*;
import org.gridgain.grid.cache.*;
import org.gridgain.grid.cache.query.GridCacheContinuousQueryEntry;
import org.gridgain.grid.events.*;
import org.gridgain.grid.kernal.*;
import org.gridgain.grid.kernal.managers.eventstorage.*;
import org.gridgain.grid.kernal.processors.*;
import org.gridgain.grid.kernal.processors.cache.*;
import org.gridgain.grid.kernal.processors.cache.query.continuous.*;
import org.gridgain.grid.kernal.processors.timeout.*;
import org.gridgain.grid.lang.*;
import org.gridgain.grid.marshaller.*;
import org.gridgain.grid.resources.*;
import org.gridgain.grid.service.*;
import org.gridgain.grid.thread.*;
import org.gridgain.grid.util.*;
import org.gridgain.grid.util.future.*;
import org.gridgain.grid.util.typedef.*;
import org.gridgain.grid.util.typedef.internal.*;
import org.jdk8.backport.*;
import org.jetbrains.annotations.*;

import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static java.util.Map.*;
import static org.gridgain.grid.GridDeploymentMode.*;
import static org.gridgain.grid.cache.GridCacheTxConcurrency.*;
import static org.gridgain.grid.cache.GridCacheTxIsolation.*;
import static org.gridgain.grid.events.GridEventType.*;
import static org.gridgain.grid.kernal.GridClosureCallMode.*;
import static org.gridgain.grid.kernal.processors.cache.GridCacheUtils.*;

/**
 * Grid service processor.
 */
@SuppressWarnings({"SynchronizationOnLocalVariableOrMethodParameter", "ConstantConditions"})
public class GridServiceProcessor extends GridProcessorAdapter {
    /** Time to wait before reassignment retries. */
    private static final long RETRY_TIMEOUT = 1000;

    /** Local service instances. */
    private final Map<String, Collection<GridServiceContextImpl>> locSvcs = new HashMap<>();

    /** Deployment futures. */
    private final ConcurrentMap<String, GridServiceDeploymentFuture> depFuts = new ConcurrentHashMap8<>();

    /** Deployment futures. */
    private final ConcurrentMap<String, GridFutureAdapter<?>> undepFuts = new ConcurrentHashMap8<>();

    /** Deployment executor service. */
    private final ExecutorService depExe = Executors.newSingleThreadExecutor();

    /** Busy lock. */
    private final GridSpinBusyLock busyLock = new GridSpinBusyLock();

    /** Thread factory. */
    private ThreadFactory threadFactory = new GridThreadFactory(ctx.gridName());

    /** Thread local for service name. */
    private ThreadLocal<String> svcName = new ThreadLocal<>();

    /**
     * Service configuration cache.
     *
     * @deprecated Object are used for projection for preserving backward compatibility.
     *      Need to return strongly-typed projection (GridServiceDeploymentKey -> GridServiceDeployment)
     *      in the next major release.
     */
    @Deprecated
    private GridCacheProjectionEx<Object, Object> depCache;

    /**
     * Service assignments cache.
     *
     * @deprecated Object are used for projection for preserving backward compatibility.
     *      Need to return strongly-typed projection (GridServiceAssignmentsKey -> GridServiceAssignments)
     *      in the next major release.
     */
    @Deprecated
    private GridCacheProjectionEx<Object, Object> assignCache;

    /** Topology listener. */
    private GridLocalEventListener topLsnr = new TopologyListener();

    /** Deployment listener. */
    private GridCacheContinuousQueryAdapter<Object, Object> cfgQry;

    /** Assignment listener. */
    private GridCacheContinuousQueryAdapter<Object, Object> assignQry;

    /**
     * @param ctx Kernal context.
     */
    public GridServiceProcessor(GridKernalContext ctx) {
        super(ctx);
    }

    /** {@inheritDoc} */
    @Override public void start() throws GridException {
        if (ctx.isDaemon())
            return;

        GridConfiguration cfg = ctx.config();

        GridDeploymentMode depMode = cfg.getDeploymentMode();

        if (cfg.isPeerClassLoadingEnabled() && (depMode == PRIVATE || depMode == ISOLATED) &&
            !F.isEmpty(cfg.getServiceConfiguration()))
            throw new GridException("Cannot deploy services in PRIVATE or ISOLATED deployment mode: " + depMode);
    }

    /** {@inheritDoc} */
    @Override public void onKernalStart() throws GridException {
        if (ctx.isDaemon())
            return;

        depCache = (GridCacheProjectionEx<Object, Object>)ctx.cache().utilityCache();
        assignCache = (GridCacheProjectionEx<Object, Object>)ctx.cache().utilityCache();

        ctx.event().addLocalEventListener(topLsnr, EVTS_DISCOVERY);

        try {
            if (ctx.deploy().enabled())
                ctx.cache().internalCache(UTILITY_CACHE_NAME).context().deploy().ignoreOwnership(true);

            cfgQry = (GridCacheContinuousQueryAdapter<Object, Object>)depCache.queries().createContinuousQuery();

            cfgQry.localCallback(new DeploymentListener());

            cfgQry.execute(ctx.grid().forLocal(), true);

            assignQry = (GridCacheContinuousQueryAdapter<Object, Object>)assignCache.queries().createContinuousQuery();

            assignQry.localCallback(new AssignmentListener());

            assignQry.execute(ctx.grid().forLocal(), true);
        }
        finally {
            if (ctx.deploy().enabled())
                ctx.cache().internalCache(UTILITY_CACHE_NAME).context().deploy().ignoreOwnership(false);
        }

        GridServiceConfiguration[] cfgs = ctx.config().getServiceConfiguration();

        if (cfgs != null) {
            Collection<GridFuture<?>> futs = new ArrayList<>();

            for (GridServiceConfiguration c : ctx.config().getServiceConfiguration())
                futs.add(deploy(c));

            // Await for services to deploy.
            for (GridFuture<?> f : futs)
                f.get();
        }

        if (log.isDebugEnabled())
            log.debug("Started service processor.");
    }

    /** {@inheritDoc} */
    @Override public void onKernalStop(boolean cancel) {
        if (ctx.isDaemon())
            return;

        busyLock.block();

        ctx.event().removeLocalEventListener(topLsnr);

        try {
            if (cfgQry != null)
                cfgQry.close();
        }
        catch (GridException e) {
            log.error("Failed to unsubscribe service configuration notifications.", e);
        }

        try {
            if (assignQry != null)
                assignQry.close();
        }
        catch (GridException e) {
            log.error("Failed to unsubscribe service assignment notifications.", e);
        }

        Collection<GridServiceContextImpl> ctxs = new ArrayList<>();

        synchronized (locSvcs) {
            for (Collection<GridServiceContextImpl> ctxs0 : locSvcs.values())
                ctxs.addAll(ctxs0);
        }

        for (GridServiceContextImpl ctx : ctxs) {
            ctx.setCancelled(true);
            ctx.service().cancel(ctx);

            ctx.executor().shutdownNow();
        }

        for (GridServiceContextImpl ctx : ctxs) {
            try {
                if (log.isInfoEnabled() && !ctxs.isEmpty())
                    log.info("Shutting down distributed service [name=" + ctx.name() + ", execId8=" +
                        U.id8(ctx.executionId()) + ']');

                ctx.executor().awaitTermination(Long.MAX_VALUE, TimeUnit.MILLISECONDS);
            }
            catch (InterruptedException ignore) {
                Thread.currentThread().interrupt();

                U.error(log, "Got interrupted while waiting for service to shutdown (will continue stopping node): " +
                    ctx.name());
            }
        }

        U.shutdownNow(GridServiceProcessor.class, depExe, log);

        if (log.isDebugEnabled())
            log.debug("Stopped service processor.");
    }

    /**
     * Validates service configuration.
     *
     * @param c Service configuration.
     * @throws GridRuntimeException If validation failed.
     */
    private void validate(GridServiceConfiguration c) throws GridRuntimeException {
        GridConfiguration cfg = ctx.config();

        GridDeploymentMode depMode = cfg.getDeploymentMode();

        if (cfg.isPeerClassLoadingEnabled() && (depMode == PRIVATE || depMode == ISOLATED))
            throw new GridRuntimeException("Cannot deploy services in PRIVATE or ISOLATED deployment mode: " + depMode);

        ensure(c.getName() != null, "getName() != null", null);
        ensure(c.getTotalCount() >= 0, "getTotalCount() >= 0", c.getTotalCount());
        ensure(c.getMaxPerNodeCount() >= 0, "getMaxPerNodeCount() >= 0", c.getMaxPerNodeCount());
        ensure(c.getService() != null, "getService() != null", c.getService());
        ensure(c.getTotalCount() > 0 || c.getMaxPerNodeCount() > 0,
            "c.getTotalCount() > 0 || c.getMaxPerNodeCount() > 0", null);
    }

    /**
     * @param cond Condition.
     * @param desc Description.
     * @param v Value.
     */
    private void ensure(boolean cond, String desc, @Nullable Object v) {
        if (!cond)
            if (v != null)
                throw new GridRuntimeException("Service configuration check failed (" + desc + "): " + v);
            else
                throw new GridRuntimeException("Service configuration check failed (" + desc + ")");
    }

    /**
     * @param name Service name.
     * @param svc Service.
     * @return Future.
     */
    public GridFuture<?> deployNodeSingleton(GridProjection prj, String name, GridService svc) {
        return deployMultiple(prj, name, svc, 0, 1);
    }

    /**
     * @param name Service name.
     * @param svc Service.
     * @return Future.
     */
    public GridFuture<?> deployClusterSingleton(GridProjection prj, String name, GridService svc) {
        return deployMultiple(prj, name, svc, 1, 1);
    }

    /**
     * @param name Service name.
     * @param svc Service.
     * @param totalCnt Total count.
     * @param maxPerNodeCnt Max per-node count.
     * @return Future.
     */
    public GridFuture<?> deployMultiple(GridProjection prj, String name, GridService svc, int totalCnt,
        int maxPerNodeCnt) {
        GridServiceConfiguration cfg = new GridServiceConfiguration();

        cfg.setName(name);
        cfg.setService(svc);
        cfg.setTotalCount(totalCnt);
        cfg.setMaxPerNodeCount(maxPerNodeCnt);
        cfg.setNodeFilter(F.<GridNode>alwaysTrue() == prj.predicate() ? null : prj.predicate());

        return deploy(cfg);
    }

    /**
     * @param name Service name.
     * @param svc Service.
     * @param cacheName Cache name.
     * @param  affKey Affinity key.
     * @return Future.
     */
    public GridFuture<?> deployKeyAffinitySingleton(String name, GridService svc, String cacheName, Object affKey) {
        A.notNull(affKey, "affKey");

        GridServiceConfiguration cfg = new GridServiceConfiguration();

        cfg.setName(name);
        cfg.setService(svc);
        cfg.setCacheName(cacheName);
        cfg.setAffinityKey(affKey);
        cfg.setTotalCount(1);
        cfg.setMaxPerNodeCount(1);

        return deploy(cfg);
    }

    /**
     * @param cfg Service configuration.
     * @return Future for deployment.
     */
    public GridFuture<?> deploy(GridServiceConfiguration cfg) {
        A.notNull(cfg, "cfg");

        validate(cfg);

        while (true) {
            try {
                GridServiceDeploymentFuture fut = new GridServiceDeploymentFuture(ctx, cfg);

                GridServiceDeploymentFuture old;

                if ((old = depFuts.putIfAbsent(cfg.getName(), fut)) != null) {
                    if (!old.configuration().equalsIgnoreNodeFilter(cfg)) {
                        fut.onDone(new GridException("Failed to deploy service (service already exists with " +
                            "different configuration) [deployed=" + old.configuration() + ", new=" + cfg + ']'));

                        return fut;
                    }

                    fut = old;
                }
                else {
                    GridServiceDeploymentKey key = new GridServiceDeploymentKey(cfg.getName());

                    if (ctx.deploy().enabled())
                        ctx.cache().internalCache(UTILITY_CACHE_NAME).context().deploy().ignoreOwnership(true);

                    try {
                        GridServiceDeployment dep = (GridServiceDeployment)depCache.putIfAbsent(key,
                            new GridServiceDeployment(ctx.localNodeId(), cfg));

                        if (dep != null) {
                            if (!dep.configuration().equalsIgnoreNodeFilter(cfg)) {
                                // Remove future from local map.
                                depFuts.remove(cfg.getName(), fut);

                                fut.onDone(new GridException("Failed to deploy service (service already exists with " +
                                    "different configuration) [deployed=" + dep.configuration() + ", new=" + cfg + ']'));
                            }
                            else {
                                for (GridCacheEntry<Object, Object> e : assignCache.entrySetx()) {
                                    if (e.getKey() instanceof GridServiceAssignmentsKey) {
                                        GridServiceAssignments assigns = (GridServiceAssignments)e.getValue();

                                        if (assigns.name().equals(cfg.getName())) {
                                            // Remove future from local map.
                                            depFuts.remove(cfg.getName(), fut);

                                            fut.onDone();

                                            break;
                                        }
                                    }
                                }

                                if (!dep.configuration().equalsIgnoreNodeFilter(cfg))
                                    U.warn(log, "Service already deployed with different configuration (will ignore) " +
                                        "[deployed=" + dep.configuration() + ", new=" + cfg + ']');
                            }
                        }
                    }
                    finally {
                        if (ctx.deploy().enabled())
                            ctx.cache().internalCache(UTILITY_CACHE_NAME).context().deploy().ignoreOwnership(false);
                    }
                }

                return fut;
            }
            catch (GridTopologyException e) {
                if (log.isDebugEnabled())
                    log.debug("Topology changed while deploying service (will retry): " + e.getMessage());
            }
            catch (GridException e) {
                log.error("Failed to deploy service: " + cfg.getName(), e);

                return new GridFinishedFuture<>(ctx, e);
            }
        }
    }

    /**
     * @param name Service name.
     * @return Future.
     */
    public GridFuture<?> cancel(String name) {
        while (true) {
            try {
                GridFutureAdapter<?> fut = new GridFutureAdapter<>(ctx);

                GridFutureAdapter<?> old;

                if ((old = undepFuts.putIfAbsent(name, fut)) != null)
                    fut = old;
                else {
                    GridServiceDeploymentKey key = new GridServiceDeploymentKey(name);

                    if (depCache.remove(key) == null) {
                        // Remove future from local map if service was not deployed.
                        undepFuts.remove(name);

                        fut.onDone();
                    }
                }

                return fut;
            }
            catch (GridTopologyException e) {
                if (log.isDebugEnabled())
                    log.debug("Topology changed while deploying service (will retry): " + e.getMessage());
            }
            catch (GridException e) {
                log.error("Failed to undeploy service: " + name, e);

                return new GridFinishedFuture<>(ctx, e);
            }
        }
    }

    /**
     * @return Future.
     */
    @SuppressWarnings("unchecked")
    public GridFuture<?> cancelAll() {
        Collection<GridFuture<?>> futs = new ArrayList<>();

        for (GridCacheEntry<Object, Object> e : depCache.entrySetx()) {
            if (!(e.getKey() instanceof GridServiceDeploymentKey))
                continue;

            GridServiceDeployment dep = (GridServiceDeployment)e.getValue();

            // Cancel each service separately.
            futs.add(cancel(dep.configuration().getName()));
        }

        return futs.isEmpty() ? new GridFinishedFuture<>(ctx) : new GridCompoundFuture(ctx, null, futs);
    }

    /**
     * @return Collection of service descriptors.
     */
    public Collection<GridServiceDescriptor> deployedServices() {
        Collection<GridServiceDescriptor> descs = new ArrayList<>();

        for (GridCacheEntry<Object, Object> e : depCache.entrySetx()) {
            if (!(e.getKey() instanceof GridServiceDeploymentKey))
                continue;

            GridServiceDeployment dep = (GridServiceDeployment)e.getValue();

            GridServiceDescriptorImpl desc = new GridServiceDescriptorImpl(dep);

            try {
                GridServiceAssignments assigns = (GridServiceAssignments)assignCache.//flagsOn(GridCacheFlag.GET_PRIMARY).
                    get(new GridServiceAssignmentsKey(dep.configuration().getName()));

                if (assigns != null) {
                    desc.topologySnapshot(assigns.assigns());

                    descs.add(desc);
                }
            }
            catch (GridException ex) {
                log.error("Failed to get assignments from replicated cache for service: " +
                    dep.configuration().getName(), ex);
            }
        }

        return descs;
    }

    /**
     * @param name Service name.
     * @param <T> Service type.
     * @return Service by specified service name.
     */
    @SuppressWarnings("unchecked")
    public <T> T service(String name) {
        Collection<GridServiceContextImpl> ctxs;

        synchronized (locSvcs) {
            ctxs = locSvcs.get(name);
        }

        if (ctxs == null)
            return null;

        synchronized (ctxs) {
            if (ctxs.isEmpty())
                return null;

            return (T)ctxs.iterator().next().service();
        }
    }

    /**
     * @param name Service name.
     * @return Service by specified service name.
     */
    public GridServiceContextImpl serviceContext(String name) {
        Collection<GridServiceContextImpl> ctxs;

        synchronized (locSvcs) {
            ctxs = locSvcs.get(name);
        }

        if (ctxs == null)
            return null;

        synchronized (ctxs) {
            if (ctxs.isEmpty())
                return null;

            return ctxs.iterator().next();
        }
    }

    /**
     * @param prj Grid projection.
     * @param name Service name.
     * @param svcItf Service class.
     * @param sticky Whether multi-node request should be done.
     * @param <T> Service interface type.
     * @return The proxy of a service by its name and class.
     */
    public <T> T serviceProxy(GridProjection prj, String name, Class<T> svcItf, boolean sticky) throws GridException {
        A.ensure(svcItf.isInterface(), "Service class must be an interface: " + svcItf);

        return new ServiceProxy<>(prj, name, svcItf, sticky).proxy();
    }

    /**
     * @param name Service name.
     * @param <T> Service type.
     * @return Services by specified service name.
     */
    public <T> Collection<T> services(String name) {
        Collection<GridServiceContextImpl> ctxs;

        synchronized (locSvcs) {
             ctxs = locSvcs.get(name);
        }

        if (ctxs == null)
            return null;

        synchronized (ctxs) {
            Collection<T> res = new ArrayList<>(ctxs.size());

            for (GridServiceContextImpl ctx : ctxs)
                res.add((T)ctx.service());

            return res;
        }
    }

    /**
     * Reassigns service to nodes.
     *
     * @param dep Service deployment.
     * @param topVer Topology version.
     * @throws GridException If failed.
     */
    private void reassign(GridServiceDeployment dep, long topVer) throws GridException {
        GridServiceConfiguration cfg = dep.configuration();

        int totalCnt = cfg.getTotalCount();
        int maxPerNodeCnt = cfg.getMaxPerNodeCount();
        String cacheName = cfg.getCacheName();
        Object affKey = cfg.getAffinityKey();

        while (true) {
            try (GridCacheTx tx = assignCache.txStart(PESSIMISTIC, REPEATABLE_READ)) {
                GridServiceAssignmentsKey key = new GridServiceAssignmentsKey(cfg.getName());

                GridServiceAssignments oldAssigns = (GridServiceAssignments)assignCache.get(key);

                GridServiceAssignments assigns = new GridServiceAssignments(cfg, dep.nodeId(), topVer);

                Map<UUID, Integer> cnts = null;

                if (affKey != null) {
                    GridNode n = ctx.affinity().mapKeyToNode(cacheName, affKey, topVer);

                    if (n != null) {
                        cnts = new HashMap<>();

                        int cnt = maxPerNodeCnt == 0 ? totalCnt == 0 ? 1 : totalCnt : maxPerNodeCnt;

                        cnts.put(n.id(), cnt);
                    }
                }
                else {
                    Collection<GridNode> nodes =
                        assigns.nodeFilter() == null ?
                            ctx.discovery().nodes(topVer) :
                            F.view(ctx.discovery().nodes(topVer), assigns.nodeFilter());

                    if (!nodes.isEmpty()) {
                        cnts = new HashMap<>();

                        int size = nodes.size();

                        int perNodeCnt = totalCnt != 0 ? totalCnt / size : maxPerNodeCnt;
                        int remainder = totalCnt != 0 ? totalCnt % size : 0;

                        if (perNodeCnt > maxPerNodeCnt && maxPerNodeCnt != 0) {
                            perNodeCnt = maxPerNodeCnt;
                            remainder = 0;
                        }

                        for (GridNode n : nodes)
                            cnts.put(n.id(), perNodeCnt);

                        assert perNodeCnt >= 0;
                        assert remainder >= 0;

                        if (remainder > 0) {
                            int cnt = perNodeCnt + 1;

                            if (oldAssigns != null) {
                                Collection<UUID> used = new HashSet<>();

                                // Avoid redundant moving of services.
                                for (Entry<UUID, Integer> e : oldAssigns.assigns().entrySet()) {
                                    // Do not assign services to left nodes.
                                    if (ctx.discovery().node(e.getKey()) == null)
                                        continue;

                                    // If old count and new count match, then reuse the assignment.
                                    if (e.getValue() == cnt) {
                                        cnts.put(e.getKey(), cnt);

                                        used.add(e.getKey());

                                        if (--remainder == 0)
                                            break;
                                    }
                                }

                                if (remainder > 0) {
                                    List<Entry<UUID, Integer>> entries = new ArrayList<>(cnts.entrySet());

                                    // Randomize.
                                    Collections.shuffle(entries);

                                    for (Entry<UUID, Integer> e : entries) {
                                        // Assign only the ones that have not been reused from previous assignments.
                                        if (!used.contains(e.getKey())) {
                                            if (e.getValue() < maxPerNodeCnt) {
                                                e.setValue(e.getValue() + 1);

                                                if (--remainder == 0)
                                                    break;
                                            }
                                        }
                                    }
                                }
                            }
                            else {
                                List<Entry<UUID, Integer>> entries = new ArrayList<>(cnts.entrySet());

                                // Randomize.
                                Collections.shuffle(entries);

                                for (Entry<UUID, Integer> e : entries) {
                                    e.setValue(e.getValue() + 1);

                                    if (--remainder == 0)
                                        break;
                                }
                            }
                        }
                    }
                }

                if (cnts != null) {
                    assigns.assigns(cnts);

                    assignCache.put(key, assigns);
                }
                else
                    // Remove assignments if no nodes found.
                    assignCache.removex(key);

                tx.commit();

                break;
            }
            catch (GridTopologyException e) {
                if (log.isDebugEnabled())
                    log.debug("Topology changed while reassigning (will retry): " + e.getMessage());
            }
        }
    }

    /**
     * Redeploys local services based on assignments.
     *
     * @param assigns Assignments.
     */
    private void redeploy(GridServiceAssignments assigns) {
        String svcName = assigns.name();

        Integer assignCnt = assigns.assigns().get(ctx.localNodeId());

        if (assignCnt == null)
            assignCnt = 0;

        GridService svc = assigns.service();

        Collection<GridServiceContextImpl> ctxs;

        synchronized (locSvcs) {
            ctxs = locSvcs.get(svcName);

            if (ctxs == null)
                locSvcs.put(svcName, ctxs = new ArrayList<>());
        }

        synchronized (ctxs) {
            if (ctxs.size() > assignCnt) {
                int cancelCnt = ctxs.size() - assignCnt;

                cancel(ctxs, cancelCnt);
            }
            else if (ctxs.size() < assignCnt) {
                int createCnt = assignCnt - ctxs.size();

                for (int i = 0; i < createCnt; i++) {
                    final GridService cp = copyAndInject(svc);

                    final ExecutorService exe = Executors.newSingleThreadExecutor(threadFactory);

                    final GridServiceContextImpl svcCtx = new GridServiceContextImpl(assigns.name(),
                        UUID.randomUUID(), assigns.cacheName(), assigns.affinityKey(), cp, exe);

                    ctxs.add(svcCtx);

                    try {
                        // Initialize service.
                        cp.init(svcCtx);
                    }
                    catch (Throwable e) {
                        log.error("Failed to initialize service (service will not be deployed): " + assigns.name(), e);

                        ctxs.remove(svcCtx);

                        if (e instanceof Error)
                            throw (Error)e;

                        if (e instanceof RuntimeException)
                            throw (RuntimeException)e;

                        return;
                    }

                    if (log.isInfoEnabled())
                        log.info("Starting service instance [name=" + svcCtx.name() + ", execId=" +
                            svcCtx.executionId() + ']');

                    // Start service in its own thread.
                    exe.submit(new Runnable() {
                        @Override public void run() {
                            try {
                                cp.execute(svcCtx);
                            }
                            catch (InterruptedException | GridInterruptedException ignore) {
                                if (log.isDebugEnabled())
                                    log.debug("Service thread was interrupted [name=" + svcCtx.name() + ", execId=" +
                                        svcCtx.executionId() + ']');
                            }
                            catch (GridRuntimeException e) {
                                if (e.hasCause(InterruptedException.class) ||
                                    e.hasCause(GridInterruptedException.class)) {
                                    if (log.isDebugEnabled())
                                        log.debug("Service thread was interrupted [name=" + svcCtx.name() +
                                            ", execId=" + svcCtx.executionId() + ']');
                                }
                                else {
                                    U.error(log, "Service execution stopped with error [name=" + svcCtx.name() +
                                        ", execId=" + svcCtx.executionId() + ']', e);
                                }
                            }
                            catch (Throwable e) {
                                log.error("Service execution stopped with error [name=" + svcCtx.name() +
                                    ", execId=" + svcCtx.executionId() + ']', e);
                            }
                            finally {
                                // Suicide.
                                exe.shutdownNow();

                                try {
                                    ctx.resource().cleanup(cp);
                                }
                                catch (GridException e) {
                                    log.error("Failed to clean up service (will ignore): " + svcCtx.name(), e);
                                }
                            }
                        }
                    });
                }
            }
        }
    }

    /**
     * @param svc Service.
     * @return Copy of service.
     */
    private GridService copyAndInject(GridService svc) {
        GridMarshaller m = ctx.config().getMarshaller();

        try {
            byte[] bytes = m.marshal(svc);

            GridService cp = m.unmarshal(bytes, svc.getClass().getClassLoader());

            ctx.resource().inject(cp);

            return cp;
        }
        catch (GridException e) {
            log.error("Failed to copy service (will reuse same instance): " + svc.getClass(), e);

            return svc;
        }
    }

    /**
     * @param ctxs Contexts to cancel.
     * @param cancelCnt Number of contexts to cancel.
     */
    private void cancel(Iterable<GridServiceContextImpl> ctxs, int cancelCnt) {
        for (Iterator<GridServiceContextImpl> it = ctxs.iterator(); it.hasNext();) {
            GridServiceContextImpl ctx = it.next();

            // Flip cancelled flag.
            ctx.setCancelled(true);

            // Notify service about cancellation.
            try {
                ctx.service().cancel(ctx);
            }
            catch (Throwable e) {
                log.error("Failed to cancel service (ignoring) [name=" + ctx.name() +
                    ", execId=" + ctx.executionId() + ']', e);
            }

            // Close out executor thread for the service.
            // This will cause the thread to be interrupted.
            ctx.executor().shutdownNow();

            it.remove();

            if (log.isInfoEnabled())
                log.info("Cancelled service instance [name=" + ctx.name() + ", execId=" +
                    ctx.executionId() + ']');

            if (--cancelCnt == 0)
                break;
        }
    }

    /**
     * Service deployment listener.
     */
    private class DeploymentListener
        implements GridBiPredicate<UUID, Collection<GridCacheContinuousQueryEntry<Object, Object>>> {
        /** Serial version ID. */
        private static final long serialVersionUID = 0L;

        /** {@inheritDoc} */
        @Override public boolean apply(
            UUID nodeId,
            final Collection<GridCacheContinuousQueryEntry<Object, Object>> deps) {
            depExe.submit(new BusyRunnable() {
                @Override public void run0() {
                    for (Entry<Object, Object> e : deps) {
                        if (!(e.getKey() instanceof GridServiceDeploymentKey))
                            continue;

                        GridServiceDeployment dep = (GridServiceDeployment)e.getValue();

                        if (dep != null) {
                            svcName.set(dep.configuration().getName());

                            // Ignore other utility cache events.
                            long topVer = ctx.discovery().topologyVersion();

                            GridNode oldest = U.oldest(ctx.discovery().nodes(topVer), null);

                            if (oldest.isLocal())
                                onDeployment(dep, topVer);
                        }
                        // Handle undeployment.
                        else {
                            String name = ((GridServiceDeploymentKey)e.getKey()).name();

                            svcName.set(name);

                            Collection<GridServiceContextImpl> ctxs;

                            synchronized (locSvcs) {
                                ctxs = locSvcs.remove(name);
                            }

                            if (ctxs != null) {
                                synchronized (ctxs) {
                                    cancel(ctxs, ctxs.size());
                                }
                            }

                            // Finish deployment futures if undeployment happened.
                            GridFutureAdapter<?> fut = depFuts.remove(name);

                            if (fut != null)
                                fut.onDone();

                            // Complete undeployment future.
                            fut = undepFuts.remove(name);

                            if (fut != null)
                                fut.onDone();
                        }
                    }
                }
            });

            return true;
        }

        /**
         * Deployment callback.
         *
         * @param dep Service deployment.
         * @param topVer Topology version.
         */
        private void onDeployment(final GridServiceDeployment dep, final long topVer) {
            // Retry forever.
            try {
                long newTopVer = ctx.discovery().topologyVersion();

                // If topology version changed, reassignment will happen from topology event.
                if (newTopVer == topVer)
                    reassign(dep, topVer);
            }
            catch (GridException e) {
                if (!(e instanceof GridTopologyException))
                    log.error("Failed to do service reassignment (will retry): " + dep.configuration().getName(), e);

                long newTopVer = ctx.discovery().topologyVersion();

                if (newTopVer != topVer) {
                    assert newTopVer > topVer;

                    // Reassignment will happen from topology event.
                    return;
                }

                ctx.timeout().addTimeoutObject(new GridTimeoutObject() {
                    private GridUuid id = GridUuid.randomUuid();

                    private long start = System.currentTimeMillis();

                    @Override public GridUuid timeoutId() {
                        return id;
                    }

                    @Override public long endTime() {
                        return start + RETRY_TIMEOUT;
                    }

                    @Override public void onTimeout() {
                        if (!busyLock.enterBusy())
                            return;

                        try {
                            // Try again.
                            onDeployment(dep, topVer);
                        }
                        finally {
                            busyLock.leaveBusy();
                        }
                    }
                });
            }
        }
    }

    /**
     * Topology listener.
     */
    private class TopologyListener implements GridLocalEventListener {
        /** {@inheritDoc} */
        @Override public void onEvent(final GridEvent evt) {
            if (!busyLock.enterBusy())
                return;

            try {
                depExe.submit(new BusyRunnable() {
                    @Override public void run0() {
                        long topVer = ((GridDiscoveryEvent)evt).topologyVersion();

                        GridNode oldest = U.oldest(ctx.discovery().nodes(topVer), null);

                        if (oldest.isLocal()) {
                            final Collection<GridServiceDeployment> retries = new ConcurrentLinkedQueue<>();

                            if (ctx.deploy().enabled())
                                ctx.cache().internalCache(UTILITY_CACHE_NAME).context().deploy().ignoreOwnership(true);

                            try {
                                for (GridCacheEntry<Object, Object> e : depCache.entrySetx()) {
                                    if (!(e.getKey() instanceof GridServiceDeploymentKey))
                                        continue;

                                    GridServiceDeployment dep = (GridServiceDeployment)e.getValue();

                                    try {
                                        svcName.set(dep.configuration().getName());

                                        ctx.cache().internalCache(UTILITY_CACHE_NAME).context().affinity().
                                            affinityReadyFuture(topVer).get();

                                        reassign(dep, topVer);
                                    }
                                    catch (GridException ex) {
                                        if (!(e instanceof GridTopologyException))
                                            LT.error(log, ex, "Failed to do service reassignment (will retry): " +
                                                dep.configuration().getName());

                                        retries.add(dep);
                                    }
                                }
                            }
                            finally {
                                if (ctx.deploy().enabled())
                                    ctx.cache().internalCache(UTILITY_CACHE_NAME).context().deploy()
                                        .ignoreOwnership(false);
                            }

                            if (!retries.isEmpty())
                                onReassignmentFailed(topVer, retries);
                        }
                    }
                });
            }
            finally {
                busyLock.leaveBusy();
            }
        }

        /**
         * Handler for reassignment failures.
         *
         * @param topVer Topology version.
         * @param retries Retries.
         */
        private void onReassignmentFailed(final long topVer, final Collection<GridServiceDeployment> retries) {
            if (!busyLock.enterBusy())
                return;

            try {
                // If topology changed again, let next event handle it.
                if (ctx.discovery().topologyVersion() != topVer)
                    return;

                for (Iterator<GridServiceDeployment> it = retries.iterator(); it.hasNext(); ) {
                    GridServiceDeployment dep = it.next();

                    try {
                        svcName.set(dep.configuration().getName());

                        reassign(dep, topVer);

                        it.remove();
                    }
                    catch (GridException e) {
                        if (!(e instanceof GridTopologyException))
                            LT.error(log, e, "Failed to do service reassignment (will retry): " +
                                dep.configuration().getName());
                    }
                }

                if (!retries.isEmpty()) {
                    ctx.timeout().addTimeoutObject(new GridTimeoutObject() {
                        private GridUuid id = GridUuid.randomUuid();

                        private long start = System.currentTimeMillis();

                        @Override public GridUuid timeoutId() {
                            return id;
                        }

                        @Override public long endTime() {
                            return start + RETRY_TIMEOUT;
                        }

                        @Override public void onTimeout() {
                            onReassignmentFailed(topVer, retries);
                        }
                    });
                }
            }
            finally {
                busyLock.leaveBusy();
            }
        }
    }

    /**
     * Assignment listener.
     */
    private class AssignmentListener
        implements GridBiPredicate<UUID, Collection<GridCacheContinuousQueryEntry<Object, Object>>> {
        /** Serial version ID. */
        private static final long serialVersionUID = 0L;

        /** {@inheritDoc} */
        @Override public boolean apply(
            UUID nodeId,
            final Collection<GridCacheContinuousQueryEntry<Object, Object>> assignCol) {
            depExe.submit(new BusyRunnable() {
                @Override public void run0() {
                    for (Entry<Object, Object> e : assignCol) {
                        if (!(e.getKey() instanceof GridServiceAssignmentsKey))
                            continue;

                        GridServiceAssignments assigns = (GridServiceAssignments)e.getValue();

                        if (assigns != null) {
                            svcName.set(assigns.name());

                            Throwable t = null;

                            try {
                                redeploy(assigns);
                            }
                            catch (Error | RuntimeException th) {
                                t = th;
                            }

                            GridServiceDeploymentFuture fut = depFuts.get(assigns.name());

                            if (fut != null && fut.configuration().equalsIgnoreNodeFilter(assigns.configuration())) {
                                depFuts.remove(assigns.name(), fut);

                                // Complete deployment futures once the assignments have been stored in cache.
                                fut.onDone(null, t);
                            }
                        }
                        // Handle undeployment.
                        else {
                            String name = ((GridServiceAssignmentsKey)e.getKey()).name();

                            svcName.set(name);

                            Collection<GridServiceContextImpl> ctxs;

                            synchronized (locSvcs) {
                                ctxs = locSvcs.remove(name);
                            }

                            if (ctxs != null) {
                                synchronized (ctxs) {
                                    cancel(ctxs, ctxs.size());
                                }
                            }
                        }
                    }
                }
            });

            return true;
        }
    }

    /**
     *
     */
    private abstract class BusyRunnable implements Runnable {
        /** {@inheritDoc} */
        @Override public void run() {
            if (!busyLock.enterBusy())
                return;

            svcName.set(null);

            try {
                run0();
            }
            catch (Throwable t) {
                log.error("Error when executing service: " + svcName.get(), t);
            }
            finally {
                busyLock.leaveBusy();

                svcName.set(null);
            }
        }

        /**
         * Abstract run method protected by busy lock.
         */
        public abstract void run0();
    }

    /**
     * Callable proxy class.
     */
    private static class ServiceProxyCallable implements GridCallable<Object>, Externalizable {
        /** Serial version UID. */
        private static final long serialVersionUID = 0L;

        /** Method name. */
        private String mtdName;

        /** Service name. */
        private String svcName;

        /** Args. */
        private Object[] args;

        /** Grid instance. */
        @GridInstanceResource
        private transient Grid grid;

        /**
         * Empty constructor required for {@link Externalizable}.
         */
        public ServiceProxyCallable() {
            // No-op.
        }

        /**
         * @param mtdName Service method to invoke.
         * @param svcName Service name.
         * @param args Arguments for invocation.
         */
        private ServiceProxyCallable(String mtdName, String svcName, Object[] args) {
            this.mtdName = mtdName;
            this.svcName = svcName;
            this.args = args;
        }

        /** {@inheritDoc} */
        @Override public Object call() throws Exception {
            GridServiceContextImpl svcCtx = ((GridKernal)grid).context().service().serviceContext(svcName);

            if (svcCtx == null)
                throw new GridServiceNotFoundException(svcName);

            GridServiceMethodReflectKey key = new GridServiceMethodReflectKey(mtdName, args);

            Method mtd = svcCtx.method(key);

            if (mtd == null)
                throw new GridServiceMethodNotFoundException(svcName, mtdName, key.argTypes());

            return mtd.invoke(svcCtx.service(), args);
        }

        /** {@inheritDoc} */
        @Override public void writeExternal(ObjectOutput out) throws IOException {
            U.writeString(out, svcName);
            U.writeString(out, mtdName);
            U.writeArray(out, args);
        }

        /** {@inheritDoc} */
        @Override public void readExternal(ObjectInput in) throws IOException, ClassNotFoundException {
            svcName = U.readString(in);
            mtdName = U.readString(in);
            args = U.readArray(in);
        }

        /** {@inheritDoc} */
        @Override public String toString() {
            return S.toString(ServiceProxyCallable.class, this);
        }
    }

    /**
     * Wrapper for making {@link GridService} class proxies.
     */
    private class ServiceProxy<T> implements Serializable {
        /** Proxy object. */
        private final T proxy;

        /** Grid projection. */
        private final GridProjection prj;

        /** {@code True} if projection includes local node. */
        private boolean hasLocNode;

        /** Remote node to use for proxy invocation. */
        private final AtomicReference<GridNode> rmtNode = new AtomicReference<>();

        /**
         * @param prj Grid projection.
         * @param name Service name.
         * @param svc Service type class.
         * @param sticky Whether multi-node request should be done.
         */
        @SuppressWarnings("unchecked")
        private ServiceProxy(GridProjection prj, final String name, Class<T> svc, final boolean sticky) {
            this.prj = prj;

            for (GridNode n : prj.nodes()) {
                if (n.isLocal()) {
                    hasLocNode = true;

                    break;
                }
            }

            proxy = (T)Proxy.newProxyInstance(svc.getClassLoader(), new Class[] {svc}, new InvocationHandler() {
                @Override public Object invoke(Object proxy, final Method mtd, final Object[] args) throws Throwable {
                    while (true) {
                        GridNode node = null;

                        try {
                            node = nodeForService(name, sticky);

                            if (node == null)
                                throw new GridException("Failed to find deployed service: " + name);

                            // If service is deployed locally, then execute locally.
                            if (node.isLocal()) {
                                Object svc = service(name);

                                if (svc != null)
                                    return mtd.invoke(svc, args);
                            }
                            else
                                // Execute service remotely.
                                return ctx.closure().callAsyncNoFailover(
                                    BALANCE,
                                    new ServiceProxyCallable(mtd.getName(), name, args),
                                    Collections.singleton(node),
                                    false
                                ).get();
                        }
                        catch (GridServiceNotFoundException | GridTopologyException e) {
                            if (log.isDebugEnabled())
                                log.debug("Service was not found or topology changed (will retry): " + e.getMessage());
                        }

                        // If we are here, that means that service was not found
                        // or topology was changed. In this case, we erase the
                        // previous sticky node and try again.
                        rmtNode.compareAndSet(node, null);

                        // Add sleep between retries to avoid busy-wait loops.
                        Thread.sleep(10);
                    }
                }
            });
        }

        /**
         * @param sticky Whether multi-node request should be done.
         * @param name Service name.
         * @return Node with deployed service or {@code null} if there is no such node.
         */
        private GridNode nodeForService(String name, boolean sticky) {
            do { // Repeat if reference to remote node was changed.
                if (sticky) {
                    GridNode curNode = rmtNode.get();

                    if (curNode != null)
                        return curNode;

                    curNode = randomNodeForService(name);

                    if (curNode == null)
                        return null;

                    if (rmtNode.compareAndSet(null, curNode))
                        return curNode;
                }
                else
                    return randomNodeForService(name);
            }
            while (true);
        }

        /**
         * @param name Service name.
         * @return Local node if it has a given service deployed or randomly chosen remote node,
         *      otherwise ({@code null} if given service is not deployed on any node.
         */
        private GridNode randomNodeForService(String name) {
            if (hasLocNode) {
                Object svc = service(name);

                if (svc != null)
                    return ctx.discovery().localNode();
            }

            Map<UUID, Integer> snapshot = serviceTopology(name);

            if (snapshot == null || snapshot.isEmpty())
                return null;

            // Optimization for cluster singletons.
            if (snapshot.size() == 1) {
                UUID nodeId = snapshot.keySet().iterator().next();

                return prj.node(nodeId);
            }

            Collection<GridNode> nodes = prj.nodes();

            // Optimization for 1 node in projection.
            if (nodes.size() == 1) {
                GridNode n = nodes.iterator().next();

                return snapshot.containsKey(n.id()) ? n : null;
            }

            // Optimization if projection is the whole grid.
            if (prj.predicate() == F.<GridNode>alwaysTrue()) {
                int idx = ThreadLocalRandom8.current().nextInt(snapshot.size());

                int i = 0;

                // Get random node.
                for (Map.Entry<UUID, Integer> e : snapshot.entrySet()) {
                    if (i++ >= idx) {
                        if (e.getValue() > 0)
                            return ctx.discovery().node(e.getKey());
                    }
                }

                i = 0;

                // Circle back.
                for (Map.Entry<UUID, Integer> e : snapshot.entrySet()) {
                    if (e.getValue() > 0)
                        return ctx.discovery().node(e.getKey());

                    if (i++ == idx)
                        return null;
                }
            }
            else {
                List<GridNode> nodeList = new ArrayList<>(nodes.size());

                for (GridNode n : nodeList) {
                    Integer cnt = snapshot.get(n.id());

                    if (cnt != null && cnt > 0)
                        nodeList.add(n);
                }

                int idx = ThreadLocalRandom8.current().nextInt(nodeList.size());

                return nodeList.get(idx);
            }

            return null;
        }

        /**
         * @param name Service name.
         * @return Map of number of service instances per node ID.
         */
        private Map<UUID, Integer> serviceTopology(String name) {
            for (GridServiceDescriptor desc : deployedServices()) {
                if (desc.name().equals(name))
                    return desc.topologySnapshot();
            }

            return null;
        }

        /**
         * @return Proxy object for a given instance.
         */
        private T proxy() {
            return proxy;
        }
    }
}
