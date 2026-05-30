package ai.intelliswarm.researchagent.config;

import ai.intelliswarm.swarmai.agent.llm.LlmClient;
import ai.intelliswarm.swarmai.agent.llm.SpringAiLlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Core wiring for the dynamic research agent.
 *
 * <p>Bridges Spring AI's {@link ChatModel} (OpenAI {@code gpt-4o-mini} by default,
 * configured in {@code application.yml}) into swarm-ai's provider-neutral
 * {@link LlmClient} so the agent loop can drive tool-calling against it.
 */
@Configuration
@EnableConfigurationProperties(ResearchProperties.class)
public class ResearchConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ResearchConfiguration.class);

    @Bean
    public LlmClient llmClient(ChatModel chatModel) {
        log.info("LLM provider: spring-ai (ChatModel={})", chatModel.getClass().getSimpleName());
        return new SpringAiLlmClient(chatModel);
    }
}
