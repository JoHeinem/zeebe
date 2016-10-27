/* Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.camunda.tngp.client.event;

import java.util.List;

/**
 * A batch of {@link Event}s.
 */
public interface EventsBatch
{

    /**
     * @return all containing events.
     */
    List<Event> getEvents();

    /**
     * @return the containing task instance events.
     */
    List<TaskInstanceEvent> getTaskInstanceEvents();

    /**
     * @return the containing workflow definition events.
     */
    List<WorkflowDefinitionEvent> getWorkflowDefinitionEvents();

}