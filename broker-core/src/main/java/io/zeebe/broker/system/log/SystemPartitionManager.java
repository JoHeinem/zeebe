/*
 * Zeebe Broker Core
 * Copyright © 2017 camunda services GmbH (info@camunda.com)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package io.zeebe.broker.system.log;

import static io.zeebe.broker.logstreams.LogStreamServiceNames.SNAPSHOT_STORAGE_SERVICE;
import static io.zeebe.broker.system.SystemServiceNames.ACTOR_SCHEDULER_SERVICE;

import java.time.Duration;

import io.zeebe.broker.clustering.management.ClusterManager;
import io.zeebe.broker.logstreams.processor.StreamProcessorIds;
import io.zeebe.broker.logstreams.processor.StreamProcessorService;
import io.zeebe.broker.system.SystemServiceNames;
import io.zeebe.broker.system.executor.ScheduledCommand;
import io.zeebe.broker.system.executor.ScheduledExecutor;
import io.zeebe.broker.transport.clientapi.CommandResponseWriter;
import io.zeebe.logstreams.log.LogStream;
import io.zeebe.servicecontainer.Injector;
import io.zeebe.servicecontainer.Service;
import io.zeebe.servicecontainer.ServiceGroupReference;
import io.zeebe.servicecontainer.ServiceName;
import io.zeebe.servicecontainer.ServiceStartContext;
import io.zeebe.servicecontainer.ServiceStopContext;
import io.zeebe.transport.ServerTransport;

public class SystemPartitionManager implements Service<SystemPartitionManager>
{
    protected ServiceStartContext serviceContext;

    protected final Injector<ServerTransport> clientApiTransportInjector = new Injector<>();
    protected final Injector<ClusterManager> clusterManagerInjector = new Injector<>();
    protected final Injector<ScheduledExecutor> executorInjector = new Injector<>();

    protected ServerTransport clientApiTransport;
    protected ClusterManager clusterManager;
    protected ScheduledExecutor executor;

    protected ScheduledCommand partitionCheck;

    protected final ServiceGroupReference<LogStream> logStreamsGroupReference = ServiceGroupReference.<LogStream>create()
        .onAdd((name, stream) -> addSystemPartition(stream, name))
        .build();

    public void addSystemPartition(LogStream logStream, ServiceName<LogStream> serviceName)
    {
        final CommandResponseWriter responseWriter = new CommandResponseWriter(clientApiTransport.getOutput());
        final CreateTopicStreamProcessor processor =
                new CreateTopicStreamProcessor(responseWriter, clusterManager);

        final StreamProcessorService streamProcessorService = new StreamProcessorService(
            "system",
            StreamProcessorIds.SYSTEM_PROCESSOR_ID,
            processor)
            .eventFilter(CreateTopicStreamProcessor.eventFilter());

        serviceContext.createService(SystemServiceNames.SYSTEM_PROCESSOR, streamProcessorService)
            .dependency(serviceName, streamProcessorService.getSourceStreamInjector())
            .dependency(serviceName, streamProcessorService.getTargetStreamInjector())
            .dependency(SNAPSHOT_STORAGE_SERVICE, streamProcessorService.getSnapshotStorageInjector())
            .dependency(ACTOR_SCHEDULER_SERVICE, streamProcessorService.getActorSchedulerInjector())
            .install()
            .thenRun(() ->
            {
                partitionCheck = executor.scheduleAtFixedRate(processor::checkPendingPartitionsAsync, Duration.ofMillis(100));
            });
    }


    @Override
    public void start(ServiceStartContext startContext)
    {
        this.serviceContext = startContext;
        this.clientApiTransport = clientApiTransportInjector.getValue();
        this.clusterManager = clusterManagerInjector.getValue();
        this.executor = executorInjector.getValue();
    }

    @Override
    public void stop(ServiceStopContext stopContext)
    {
        if (partitionCheck != null)
        {
            partitionCheck.cancel();
            partitionCheck = null;
        }
    }

    @Override
    public SystemPartitionManager get()
    {
        return this;
    }

    public ServiceGroupReference<LogStream> getLogStreamsGroupReference()
    {
        return logStreamsGroupReference;
    }

    public Injector<ServerTransport> getClientApiTransportInjector()
    {
        return clientApiTransportInjector;
    }

    public Injector<ClusterManager> getClusterManagerInjector()
    {
        return clusterManagerInjector;
    }

    public Injector<ScheduledExecutor> getExecutorInjector()
    {
        return executorInjector;
    }

}
