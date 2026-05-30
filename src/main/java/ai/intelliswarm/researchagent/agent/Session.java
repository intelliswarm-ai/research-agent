package ai.intelliswarm.researchagent.agent;

import ai.intelliswarm.swarmai.agent.llm.LlmMessage;
import ai.intelliswarm.swarmai.agent.llm.TokenUsage;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/** Per-process session state: orchestrator message history + cumulative token usage. */
@Component
public class Session {

    private final String id = UUID.randomUUID().toString();
    private final List<LlmMessage> messages = Collections.synchronizedList(new ArrayList<>());
    private final AtomicLong inputTokens = new AtomicLong();
    private final AtomicLong outputTokens = new AtomicLong();

    public String id() { return id; }

    public void append(LlmMessage m) { messages.add(m); }

    public List<LlmMessage> messages() {
        synchronized (messages) { return List.copyOf(messages); }
    }

    public int messageCount() { return messages.size(); }

    public void clear() { messages.clear(); }

    public void recordUsage(TokenUsage u) {
        if (u == null) return;
        inputTokens.addAndGet(u.inputTokens());
        outputTokens.addAndGet(u.outputTokens());
    }

    public long inputTokens() { return inputTokens.get(); }
    public long outputTokens() { return outputTokens.get(); }
    public long totalTokens() { return inputTokens.get() + outputTokens.get(); }
}
