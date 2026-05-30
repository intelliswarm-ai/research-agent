package ai.intelliswarm.researchagent.tool;

import ai.intelliswarm.swarmai.tool.base.BaseTool;
import ai.intelliswarm.swarmai.tool.base.PermissionLevel;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Lets the orchestrator capture and update its investigation plan. */
@Component
public class TodoWriteTool implements BaseTool {

    private final TodoList todoList;

    public TodoWriteTool(TodoList todoList) {
        this.todoList = todoList;
    }

    @Override public String getFunctionName() { return "todo_write"; }

    @Override
    public String getDescription() {
        return "Maintain the investigation plan. Use it to (1) capture the multi-step plan before starting, "
             + "(2) mark a step in_progress when you begin it, (3) mark it completed when done. "
             + "Pass the FULL updated list every call — it replaces the existing plan.";
    }

    @Override public PermissionLevel getPermissionLevel() { return PermissionLevel.READ_ONLY; }
    @Override public String getCategory() { return "planning"; }
    @Override public boolean isAsync() { return false; }
    @Override public boolean isDynamic() { return true; }

    @Override
    public Object execute(Map<String, Object> parameters) {
        Object todos = parameters.get("todos");
        if (!(todos instanceof List<?> raw)) {
            return "Error: 'todos' must be an array of {content, status} objects.";
        }
        List<TodoList.Item> items = new ArrayList<>();
        for (Object o : raw) {
            if (!(o instanceof Map<?, ?> mRaw)) return "Error: each todo must be an object";
            @SuppressWarnings("unchecked") Map<String, Object> m = (Map<String, Object>) mRaw;
            String content = String.valueOf(m.get("content"));
            String statusStr = String.valueOf(m.getOrDefault("status", "pending")).toLowerCase();
            TodoList.Status status = switch (statusStr) {
                case "in_progress" -> TodoList.Status.IN_PROGRESS;
                case "completed", "done" -> TodoList.Status.COMPLETED;
                default -> TodoList.Status.PENDING;
            };
            items.add(new TodoList.Item(content, status));
        }
        todoList.replaceAll(items);
        return "Updated plan (" + items.size() + " step" + (items.size() == 1 ? "" : "s") + "):\n" + todoList.render();
    }

    @Override
    public Map<String, Object> getParameterSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> item = new LinkedHashMap<>();
        item.put("type", "object");
        item.put("properties", Map.of(
                "content", Map.of("type", "string", "description", "Imperative description of the step"),
                "status", Map.of("type", "string", "enum", List.of("pending", "in_progress", "completed"))));
        item.put("required", new String[]{"content", "status"});

        Map<String, Object> props = new LinkedHashMap<>();
        props.put("todos", Map.of(
                "type", "array",
                "description", "Full updated plan. Replaces the existing plan.",
                "items", item));

        schema.put("properties", props);
        schema.put("required", new String[]{"todos"});
        return schema;
    }
}
