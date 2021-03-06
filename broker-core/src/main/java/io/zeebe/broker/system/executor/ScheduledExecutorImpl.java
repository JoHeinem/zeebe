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
package io.zeebe.broker.system.executor;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import io.zeebe.broker.Loggers;
import org.agrona.concurrent.ManyToOneConcurrentArrayQueue;

import io.zeebe.util.actor.Actor;
import io.zeebe.util.actor.ActorReference;
import io.zeebe.util.actor.ActorScheduler;
import io.zeebe.util.time.ClockUtil;
import org.slf4j.Logger;

public class ScheduledExecutorImpl implements Actor, ScheduledExecutor
{
    public static final Logger LOG = Loggers.SYSTEM_LOGGER;

    protected static final String NAME = "scheduled-executor";

    protected final List<ScheduledCommandImpl> scheduledCommands = new ArrayList<>();

    protected final ManyToOneConcurrentArrayQueue<Runnable> cmdQueue = new ManyToOneConcurrentArrayQueue<>(100);
    protected final Consumer<Runnable> cmdConsumer = Runnable::run;

    protected final AtomicBoolean isRunning = new AtomicBoolean(false);
    protected final ActorScheduler actorScheduler;
    protected ActorReference actorRef;

    public ScheduledExecutorImpl(ActorScheduler actorScheduler)
    {
        this.actorScheduler = actorScheduler;
    }

    @Override
    public ScheduledCommand schedule(Runnable command, Duration delay)
    {
        final long dueDate = ClockUtil.getCurrentTimeInMillis() + delay.toMillis();
        final ScheduledCommandImpl scheduledCommand = new ScheduledCommandImpl(command, dueDate);

        cmdQueue.add(() -> scheduledCommands.add(scheduledCommand));

        return scheduledCommand;
    }

    @Override
    public ScheduledCommand scheduleAtFixedRate(Runnable command, Duration period)
    {
        final long dueDate = ClockUtil.getCurrentTimeInMillis();
        final ScheduledCommandImpl scheduledCommand = new ScheduledCommandImpl(command, dueDate, period.toMillis());

        cmdQueue.add(() -> scheduledCommands.add(scheduledCommand));

        return scheduledCommand;
    }

    @Override
    public ScheduledCommand scheduleAtFixedRate(Runnable command, Duration initialDelay, Duration period)
    {
        final long dueDate = ClockUtil.getCurrentTimeInMillis() + initialDelay.toMillis();
        final ScheduledCommandImpl scheduledCommand = new ScheduledCommandImpl(command, dueDate, period.toMillis());

        cmdQueue.add(() -> scheduledCommands.add(scheduledCommand));

        return scheduledCommand;
    }

    @Override
    public int doWork() throws Exception
    {
        int workCount = 0;

        workCount += cmdQueue.drain(cmdConsumer);

        final long now = ClockUtil.getCurrentTimeInMillis();

        int i = 0;

        while (i < scheduledCommands.size() && isRunning.get())
        {
            final ScheduledCommandImpl scheduledCommand = scheduledCommands.get(i);

            if (scheduledCommand.getDueDate() <= now)
            {
                workCount += 1;

                final boolean reSchedule = executeCommand(scheduledCommand);

                if (reSchedule)
                {
                    i += 1;
                }
                else
                {
                    scheduledCommands.remove(i);
                }
            }
            else
            {
                i++;
            }
        }

        return workCount;
    }

    protected boolean executeCommand(final ScheduledCommandImpl scheduledCommand)
    {
        boolean reSchedule = false;

        if (!scheduledCommand.isCancelled())
        {
            try
            {
                scheduledCommand.getCommand().run();

                final long period = scheduledCommand.getPeriod();
                if (period >= 0)
                {
                    final long nextDueDate = ClockUtil.getCurrentTimeInMillis() + period;
                    scheduledCommand.setDueDateInMillis(nextDueDate);

                    reSchedule = true;
                }
            }
            catch (Exception e)
            {
                LOG.error("Failed to execute scheduled command", e);
            }
        }

        return reSchedule;
    }

    public void start()
    {
        if (isRunning.compareAndSet(false, true))
        {
            actorRef = actorScheduler.schedule(this);
        }
    }

    public void stop()
    {
        stopAsync().join();
    }

    public CompletableFuture<Void> stopAsync()
    {
        final CompletableFuture<Void> future = new CompletableFuture<>();

        if (isRunning.compareAndSet(true, false))
        {
            cmdQueue.add(() ->
            {
                actorRef.close();

                future.complete(null);
            });
        }
        else
        {
            future.complete(null);
        }

        return future;
    }

    @Override
    public int getPriority(long now)
    {
        return PRIORITY_LOW;
    }

    @Override
    public String name()
    {
        return NAME;
    }

    static class ScheduledCommandImpl implements ScheduledCommand
    {
        protected final Runnable command;
        protected final long periodInMillis;

        protected long dueDateInMillis;

        protected boolean isCancelled = false;

        ScheduledCommandImpl(Runnable command, long dueDateInMillis)
        {
            this(command, dueDateInMillis, -1L);
        }

        ScheduledCommandImpl(Runnable command, long dueDateInMillis, long periodInMillis)
        {
            this.command = command;
            this.periodInMillis = periodInMillis;
            this.dueDateInMillis = dueDateInMillis;
        }

        public Runnable getCommand()
        {
            return command;
        }

        @Override
        public long getPeriod()
        {
            return periodInMillis;
        }

        @Override
        public void cancel()
        {
            isCancelled = true;
        }

        @Override
        public boolean isCancelled()
        {
            return isCancelled;
        }

        @Override
        public long getDueDate()
        {
            return dueDateInMillis;
        }

        public void setDueDateInMillis(long dueDateInMillis)
        {
            this.dueDateInMillis = dueDateInMillis;
        }

    }

}
