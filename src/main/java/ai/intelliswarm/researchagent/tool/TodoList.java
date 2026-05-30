package ai.intelliswarm.researchagent.tool;

import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/** Mutable session-scoped plan, rendered for the user and replaced wholesale by {@code todo_write}. */
@Component
public class TodoList {

    public enum Status { PENDING, IN_PROGRESS, COMPLETED }

    public record Item(String content, Status status) {
        public String marker() {
            return switch (status) {
                case PENDING -> "[ ]";
                case IN_PROGRESS -> "[~]";
                case COMPLETED -> "[x]";
            };
        }
    }

    private volatile List<Item> items = Collections.emptyList();

    public void replaceAll(List<Item> updated) { this.items = List.copyOf(updated); }

    public List<Item> snapshot() { return items; }

    public boolean isEmpty() { return items.isEmpty(); }

    public void clear() { this.items = Collections.emptyList(); }

    public String render() {
        if (items.isEmpty()) return "(no plan yet)";
        StringBuilder sb = new StringBuilder();
        for (Item i : items) sb.append(i.marker()).append(' ').append(i.content()).append('\n');
        return sb.toString();
    }
}
