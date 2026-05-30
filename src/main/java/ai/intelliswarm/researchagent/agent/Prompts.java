package ai.intelliswarm.researchagent.agent;

import org.springframework.core.io.ClassPathResource;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Loads markdown prompt templates from {@code src/main/resources/prompts/}. */
public final class Prompts {

    private Prompts() {}

    /** Load {@code prompts/<name>} from the classpath, or return {@code fallback} if missing. */
    public static String load(String name, String fallback) {
        try {
            ClassPathResource res = new ClassPathResource("prompts/" + name);
            if (!res.exists()) return fallback;
            return StreamUtils.copyToString(res.getInputStream(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return fallback;
        }
    }
}
