package io.digdag.core.workflow;

import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.HashMap;
import java.util.Map.Entry;
import java.util.stream.Collectors;
import com.google.common.base.*;
import com.google.common.collect.*;
import io.digdag.core.session.TaskType;
import io.digdag.client.config.Config;
import io.digdag.client.config.ConfigException;
import static com.google.common.collect.Maps.immutableEntry;

public class WorkflowCompiler
{
    public WorkflowCompiler()
    { }

    public Workflow compile(String name, Config config)
    {
        return Workflow.workflowBuilder()
            .name(name)
            .meta(config.getNestedOrGetEmpty("meta"))
            .tasks(compileTasks(name, config))
            .build();
    }

    public WorkflowTaskList compileTasks(String name, Config config)
    {
        return new Context().compile(name, config);
    }

    private static class TaskBuilder
    {
        private final int index;
        private final Optional<TaskBuilder> parent;
        private final String name;
        private final TaskType taskType;
        private final Config config;
        private final List<TaskBuilder> children = new ArrayList<TaskBuilder>();
        private final List<TaskBuilder> upstreams = new ArrayList<TaskBuilder>();

        public TaskBuilder(int index, Optional<TaskBuilder> parent, String name,
                TaskType taskType, Config config)
        {
            this.index = index;
            this.parent = parent;
            this.name = name;
            this.taskType = taskType;
            this.config = config;
            if (parent.isPresent()) {
                parent.get().addChild(this);
            }
        }

        public int getIndex()
        {
            return index;
        }

        public String getName()
        {
            return name;
        }

        public Config getConfig()
        {
            return config;
        }

        private void addChild(TaskBuilder child)
        {
            children.add(child);
        }

        public void addUpstream(TaskBuilder up)
        {
            upstreams.add(up);
        }

        public WorkflowTask build()
        {
            return new WorkflowTask.Builder()
                .name(name)
                .index(index)
                .parentIndex(
                        parent.transform(it -> it.index))
                .upstreamIndexes(
                        upstreams
                            .stream()
                            .map(it -> it.index)
                            .collect(Collectors.toList()))
                .taskType(taskType)
                .config(config)
                .build();
        }
    }

    private static class Context
    {
        private List<TaskBuilder> tasks = new ArrayList<>();

        public WorkflowTaskList compile(String name, Config config)
        {
            try {
                collect(Optional.absent(), config.getFactory().create(), name, config);
                return WorkflowTaskList.of(
                        tasks
                        .stream()
                        .map(tb -> tb.build())
                        .collect(Collectors.toList()));
            }
            catch (ConfigException ex) {
                throw ex;
            }
            catch (IllegalStateException ex) {  // thrown by WorkflowTask.check
                throw new ConfigException(ex);
            }
        }

        public TaskBuilder collect(
                Optional<TaskBuilder> parent, Config parentDefaultConfig,
                String name, Config originalConfig)
        {
            Config thisDefaultConfig = originalConfig.getNestedOrGetEmpty("default");
            final Config defaultConfig = parentDefaultConfig.deepCopy().setAll(thisDefaultConfig);
            final Config config = originalConfig.deepCopy().setAll(defaultConfig);

            // +key: {...}
            List<Entry<String, Config>> subtaskConfigs = config.getKeys()
                .stream()
                .filter(key -> key.startsWith("+"))
                .map(key -> immutableEntry(key, config.getNestedOrderedOrGetEmpty(key)))
                .collect(Collectors.toList());

            // other: ...
            config.getKeys()
                .stream()
                .filter(key -> key.startsWith("+"))
                .forEach(key -> config.remove(key));

            if (config.has("type") || config.has("type=") || config.getKeys().stream().anyMatch(key -> key.endsWith(">") || key.endsWith(">="))) {
                // task node
                if (!subtaskConfigs.isEmpty()) {
                    throw new ConfigException("A task can't have subtasks: " + originalConfig);
                }
                return addTask(parent, name, false, config);
            }
            else {
                // group node
                final TaskBuilder tb = addTask(parent, name, true, config);

                List<TaskBuilder> subtasks = subtaskConfigs
                    .stream()
                    .map(pair -> collect(Optional.of(tb), defaultConfig, pair.getKey(), pair.getValue()))
                    .collect(Collectors.toList());

                if (config.get("parallel", boolean.class, false)) {
                    // after: is valid only when parallel: is true
                    Map<String, TaskBuilder> names = new HashMap<>();
                    for (TaskBuilder subtask : subtasks) {
                        for (String upName : subtask.getConfig().getListOrEmpty("after", String.class)) {
                            TaskBuilder up = names.get(upName);
                            if (up == null) {
                                throw new ConfigException("Dependency task '"+upName+"' does not exist");
                            }
                            subtask.addUpstream(up);
                        }
                        names.put(subtask.getName(), subtask);
                    }
                }
                else {
                    // after: is automatically generated if parallel: is false
                    if (config.has("after")) {
                        throw new ConfigException("Option 'after' is valid only if 'parallel' is true");
                    }
                    TaskBuilder before = null;
                    for (TaskBuilder subtask : subtasks) {
                        if (before != null) {
                            subtask.addUpstream(before);
                        }
                        before = subtask;
                    }
                }

                return tb;
            }
        }

        private TaskBuilder addTask(
                Optional<TaskBuilder> parent, String name,
                boolean groupingOnly, Config config)
        {
            TaskBuilder tb = new TaskBuilder(tasks.size(), parent, name,
                    extractTaskOption(config, groupingOnly), config);
            tasks.add(tb);
            return tb;
        }

        private TaskType extractTaskOption(Config config, boolean groupingOnly)
        {
            return new TaskType.Builder()
                .groupingOnly(groupingOnly)
                .build();
        }
    }
}
