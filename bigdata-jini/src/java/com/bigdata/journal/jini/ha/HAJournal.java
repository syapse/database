/**

Copyright (C) SYSTAP, LLC 2006-2007.  All rights reserved.

Contact:
     SYSTAP, LLC
     4501 Tower Road
     Greensboro, NC 27410
     licenses@bigdata.com

This program is free software; you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation; version 2 of the License.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program; if not, write to the Free Software
Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
*/
package com.bigdata.journal.jini.ha;

import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.net.InetSocketAddress;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;

import net.jini.config.Configuration;
import net.jini.export.Exporter;

import com.bigdata.concurrent.FutureTaskMon;
import com.bigdata.ha.HAGlue;
import com.bigdata.ha.QuorumService;
import com.bigdata.io.writecache.WriteCache;
import com.bigdata.journal.BufferMode;
import com.bigdata.journal.IRootBlockView;
import com.bigdata.journal.Journal;
import com.bigdata.journal.ValidationError;
import com.bigdata.journal.WORMStrategy;
import com.bigdata.journal.ha.HAWriteMessage;
import com.bigdata.quorum.Quorum;
import com.bigdata.quorum.zk.ZKQuorumImpl;
import com.bigdata.rwstore.RWStore;
import com.bigdata.service.AbstractTransactionService;
import com.bigdata.service.proxy.ThickFuture;

/**
 * A {@link Journal} that that participates in a write replication pipeline. The
 * {@link HAJournal} is configured an River {@link Configuration}. The
 * configuration includes properties to configured the underlying
 * {@link Journal} and information about the {@link HAJournal}s that will
 * participate in the replication pattern.
 * <p>
 * All instances declared in the {@link Configuration} must be up, running, and
 * able to connect for any write operation to succeed. All instances must vote
 * to commit for an operation to commit. If any units fail (or timeout) then the
 * operation will abort. All instances are 100% synchronized at all commit
 * points. Read-only operations can be load balanced across the instances and
 * uncommitted data will never be visible to readers. Writes must be directed to
 * the first instance in the write replication pipeline. A read error on an
 * instance will internally failover to another instance in an attempt to read
 * from good data.
 * <p>
 * The write replication pipeline is statically configured. If an instance is
 * lost, then the configuration file must be changed, the change propagated to
 * all nodes, and the services "bounced" before writes can resume. Bouncing a
 * service only requires that the Journal is closed and reopened. Services do
 * not have to be "bounced" at the same time, and (possibly new) leader must be
 * "bounced" last to ensure that writes do not propagate until the write
 * pipeline is in a globally consistent order that excludes the down node.
 * 
 * @author <a href="mailto:thompsonbry@users.sourceforge.net">Bryan Thompson</a>
 * @see <a href="https://sourceforge.net/apps/trac/bigdata/ticket/530"> Journal HA </a>
 */
public class HAJournal extends Journal {

//    private static final Logger log = Logger.getLogger(HAJournal.class);

    public interface Options extends Journal.Options {
        
        /**
         * The address at which this journal exposes its write pipeline
         * interface.
         */
        String WRITE_PIPELINE_ADDR = HAJournal.class.getName()
                + ".writePipelineAddr";

        /**
         * The required property whose value is the name of the directory in
         * which write ahead log files will be created to support
         * resynchronization services trying to join an HA quorum.
         * <p>
         * The directory should not contain any other files. It will be
         * populated with files whose names correspond to commit counters. The
         * commit counter is recorded in the root block at each commit. It is
         * used to identify the write set for a given commit. A log file is
         * written for each commit point. Each log files is normally deleted at
         * the commit. However, if the quorum is not fully met at the commit,
         * then the log files not be deleted. Instead, they will be used to
         * resynchronize the other quorum members.
         * <p>
         * The log file name includes the value of the commit counter for the
         * commit point that will be achieved when that log file is applied to a
         * journal whose current commit point is [commitCounter-1]. The commit
         * counter for a new journal (without any commit points) is ZERO (0).
         * This the first log file will be labeled with the value ONE (1). The
         * commit counter is written out with leading zeros in the log file name
         * so the natural sort order of the log files should correspond to the
         * ascending commit order.
         * <p>
         * The log files are a sequence of zero or more {@link HAWriteMessage}
         * objects. For the {@link RWStore}, each {@link HAWriteMessage} is
         * followed by the data from the corresponding {@link WriteCache} block.
         * For the {@link WORMStrategy}, the {@link WriteCache} block is omitted
         * since this data can be trivially reconstructed from the backing file.
         * When the quorum prepares for a commit, the proposed root block is
         * written onto the end of the log file.
         * <p>
         * The log files are deleted once the quorum is fully met (k out of k
         * nodes have met in the quorum). It is possible for a quorum to form
         * with only <code>(k+1)/2</code> nodes. When this happens, the nodes in
         * the quorum will all write log files into the {@link #HA_LOG_DIR}.
         * Those files will remain until the other nodes in the quorum
         * synchronize and join the quorum. Once the quorum is fully met, the
         * files in the log directory will be deleted.
         * <p>
         * If some or all log files are not present, then any node that is not
         * synchronized with the quorum must be rebuilt from scratch rather than
         * by incrementally applying logged write sets until it catches up and
         * can join the quorum.
         * 
         * @see IRootBlockView#getCommitCounter()
         * 
         *      TODO We may need to also write a marker either on the head or
         *      tail of the log file to indicate that the commit was applied.
         *      Work this out when we work through the extension to the 2-phase
         *      commit protocol that supports resynchronization.
         */
        String HA_LOG_DIR = HAJournal.class.getName() + ".haLogDir";
        
    }
    
    /**
     * @see Options#WRITE_PIPELINE_ADDR
     */
    private final InetSocketAddress writePipelineAddr;

    /**
     * @see Options#HA_LOG_DIR
     */
    private final File haLogDir;
    
    public HAJournal(final Properties properties) {

        this(properties, null);
        
    }

    public HAJournal(final Properties properties,
            final Quorum<HAGlue, QuorumService<HAGlue>> quorum) {

        super(checkProperties(properties), quorum);

        /*
         * Note: We need this so pass it through to the HAGlue class below.
         * Otherwise this service does not know where to setup its write
         * replication pipeline listener.
         */
        
        writePipelineAddr = (InetSocketAddress) properties
                .get(Options.WRITE_PIPELINE_ADDR);

        final String logDirStr = properties.getProperty(Options.HA_LOG_DIR);

        haLogDir = new File(logDirStr);

        if (!haLogDir.exists()) {

            // Create the directory.
            haLogDir.mkdirs();

        }
        
    }

    /**
     * Perform some checks on the {@link HAJournal} configuration properties.
     * 
     * @param properties
     *            The configuration properties.
     *            
     * @return The argument.
     */
    protected static Properties checkProperties(final Properties properties) {

        final long minReleaseAge = Long.valueOf(properties.getProperty(
                AbstractTransactionService.Options.MIN_RELEASE_AGE,
                AbstractTransactionService.Options.DEFAULT_MIN_RELEASE_AGE));

        final BufferMode bufferMode = BufferMode.valueOf(properties
                .getProperty(Options.BUFFER_MODE, Options.DEFAULT_BUFFER_MODE));

        switch (bufferMode) {
        case DiskRW: {
            if (minReleaseAge <= 0) {
                /*
                 * Note: Session protection is used by the RWStore when the
                 * minReleaseAge is ZERO (0). However, session protection is not
                 * compatible with HA. We MUST log delete blocks in order to
                 * ensure that the question of allocation slot recycling is
                 * deferred until the ACID decision at the commit point,
                 * otherwise we can not guarantee that the read locks asserted
                 * when lose a service and no longer have a fully met quorum
                 * will be effective as of the *start* of the writer (that is,
                 * atomically as of the last commit point).)
                 */
                throw new IllegalArgumentException(
                        AbstractTransactionService.Options.MIN_RELEASE_AGE
                                + "=" + minReleaseAge
                                + " : must be GTE ONE (1) for HA.");
            }
            break;
        }
        case DiskWORM:
            break;
        default:
            throw new IllegalArgumentException(Options.BUFFER_MODE + "="
                    + bufferMode + " : does not support HA");
        }

        final boolean writeCacheEnabled = Boolean.valueOf(properties
                .getProperty(Options.WRITE_CACHE_ENABLED,
                        Options.DEFAULT_WRITE_CACHE_ENABLED));

        if (!writeCacheEnabled)
            throw new IllegalArgumentException(Options.WRITE_CACHE_ENABLED
                    + " : must be true.");

        if (properties.get(Options.WRITE_PIPELINE_ADDR) == null) {

            throw new RuntimeException(Options.WRITE_PIPELINE_ADDR
                    + " : required property not found.");
        
        }

        final String logDirStr = properties.getProperty(Options.HA_LOG_DIR);

        if (logDirStr == null) {

            throw new IllegalArgumentException(Options.HA_LOG_DIR
                    + " : must be specified");

        }

        return properties;

    }
    
    @Override
    protected HAGlue newHAGlue(final UUID serviceId) {

        return new HAGlueService(serviceId, writePipelineAddr);

    }

    /**
     * {@inheritDoc}
     * <p>
     * Overridden to expose this method to the {@link HAJournalServer}.
     */
    @Override
    protected final void setQuorumToken(final long newValue) {
    
        super.setQuorumToken(newValue);
        
    }

    /**
     * Extended implementation supports RMI.
     */
    protected class HAGlueService extends BasicHA {

        protected HAGlueService(final UUID serviceId,
                final InetSocketAddress writePipelineAddr) {

            super(serviceId, writePipelineAddr);

        }

        /*
         * ITransactionService
         * 
         * This interface is delegated to the Journal's local transaction
         * service. This service MUST be the quorum leader.
         * 
         * Note: If the quorum breaks, the service which was the leader will
         * invalidate all open transactions. This is handled in AbstractJournal.
         * 
         * FIXME We should really pair the quorum token with the transaction
         * identifier in order to guarantee that the quorum token does not
         * change (e.g., that the quorum does not break) across the scope of the
         * transaction. That will require either changing the
         * ITransactionService API and/or defining an HA variant of that API.
         */
        
        @Override
        public long newTx(final long timestamp) throws IOException {

            getQuorum().assertLeader(getQuorumToken());

            // Delegate to the Journal's local transaction service.
            return HAJournal.this.newTx(timestamp);

        }

        @Override
        public long commit(final long tx) throws ValidationError, IOException {

            getQuorum().assertLeader(getQuorumToken());

            // Delegate to the Journal's local transaction service.
            return HAJournal.this.commit(tx);

        }

        @Override
        public void abort(final long tx) throws IOException {

            getQuorum().assertLeader(getQuorumToken());
            
            // Delegate to the Journal's local transaction service.
            HAJournal.this.abort(tx);

        }

        @Override
        public Future<Void> bounceZookeeperConnection() {
            final FutureTask<Void> ft = new FutureTaskMon<Void>(new Runnable() {
                @SuppressWarnings("rawtypes")
                public void run() {

                    if (haLog.isInfoEnabled())
                        haLog.info("");

                    if (getQuorum() instanceof ZKQuorumImpl) {

                        try {

                            // Close the current connection (if any).
                            ((ZKQuorumImpl) getQuorum()).getZookeeper().close();
                            
                        } catch (InterruptedException e) {
                            
                            // Propagate the interrupt.
                            Thread.currentThread().interrupt();
                            
                        }
                        
                    }
                }
            }, null);
            ft.run();
            return getProxy(ft);

//          
        }
        
//        /**
//         * Note: The invocation layer factory is reused for each exported proxy (but
//         * the exporter itself is paired 1:1 with the exported proxy).
//         */
//        final private InvocationLayerFactory invocationLayerFactory = new BasicILFactory();
//        
//        /**
//         * Return an {@link Exporter} for a single object that implements one or
//         * more {@link Remote} interfaces.
//         * <p>
//         * Note: This uses TCP Server sockets.
//         * <p>
//         * Note: This uses [port := 0], which means a random port is assigned.
//         * <p>
//         * Note: The VM WILL NOT be kept alive by the exported proxy (keepAlive is
//         * <code>false</code>).
//         * 
//         * @param enableDGC
//         *            if distributed garbage collection should be used for the
//         *            object to be exported.
//         * 
//         * @return The {@link Exporter}.
//         */
//        protected Exporter getExporter(final boolean enableDGC) {
//            
//            return new BasicJeriExporter(TcpServerEndpoint
//                    .getInstance(0/* port */), invocationLayerFactory, enableDGC,
//                    false/* keepAlive */);
//            
//        }

        /**
         * Note that {@link Future}s generated by
         * <code>java.util.concurrent</code> are NOT {@link Serializable}.
         * Futher note the proxy as generated by an {@link Exporter} MUST be
         * encapsulated so that the object returned to the caller can implement
         * {@link Future} without having to declare that the methods throw
         * {@link IOException} (for RMI).
         * 
         * @param future
         *            The future.
         * 
         * @return A proxy for that {@link Future} that masquerades any RMI
         *         exceptions.
         */
        @Override
        protected <E> Future<E> getProxy(final Future<E> future) {

            /*
             * This was borrowed from a fix for a DGC thread leak on the
             * clustered database. Returning a Future so the client can
             * wait on the outcome is often less desirable than having
             * the service compute the Future and then return a think
             * future.
             * 
             * @see https://sourceforge.net/apps/trac/bigdata/ticket/433
             * 
             * @see https://sourceforge.net/apps/trac/bigdata/ticket/437
             */
            return new ThickFuture<E>(future);

//            /*
//             * Setup the Exporter for the Future.
//             * 
//             * Note: Distributed garbage collection is enabled since the proxied
//             * future CAN become locally weakly reachable sooner than the client can
//             * get() the result. Distributed garbage collection handles this for us
//             * and automatically unexports the proxied iterator once it is no longer
//             * strongly referenced by the client.
//             */
//            final Exporter exporter = getExporter(true/* enableDGC */);
//            
//            // wrap the future in a proxyable object.
//            final RemoteFuture<E> impl = new RemoteFutureImpl<E>(future);
//
//            /*
//             * Export the proxy.
//             */
//            final RemoteFuture<E> proxy;
//            try {
//
//                // export proxy.
//                proxy = (RemoteFuture<E>) exporter.export(impl);
//
//                if (log.isInfoEnabled()) {
//
//                    log.info("Exported proxy: proxy=" + proxy + "("
//                            + proxy.getClass() + ")");
//
//                }
//
//            } catch (ExportException ex) {
//
//                throw new RuntimeException("Export error: " + ex, ex);
//
//            }
//
//            // return proxy to caller.
//            return new ClientFuture<E>(proxy);

        }

    }

}