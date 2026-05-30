package ai.intelliswarm.researchagent.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Minimal {@code .env} loader — called before Spring starts so credentials are
 * available to Spring AI's OpenAI autoconfiguration.
 *
 * <p>Reads {@code .env} in the working directory (or the path given by the
 * {@code DOTENV_PATH} system property). Lines starting with {@code #} or blank are
 * skipped. Each {@code KEY=VALUE} pair is set as a system property unless the key is
 * already set in the environment.
 */
public final class DotenvLoader {

    private static final Logger log = LoggerFactory.getLogger(DotenvLoader.class);

    private DotenvLoader() {}

    public static void load() {
        String override = System.getProperty("DOTENV_PATH");
        Path path = override != null ? Paths.get(override) : Paths.get(".env");
        if (!Files.exists(path)) return;
        try {
            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                line = line.strip();
                if (line.isEmpty() || line.startsWith("#")) continue;
                int eq = line.indexOf('=');
                if (eq < 1) continue;
                String key = line.substring(0, eq).strip();
                String value = line.substring(eq + 1).strip();
                // Strip surrounding quotes
                if ((value.startsWith("\"") && value.endsWith("\""))
                        || (value.startsWith("'") && value.endsWith("'"))) {
                    value = value.substring(1, value.length() - 1);
                }
                // Don't override real env vars (allow tests to set them)
                if (System.getenv(key) == null && System.getProperty(key) == null) {
                    System.setProperty(key, value);
                }
            }
            log.debug("Loaded .env from {}", path.toAbsolutePath());
        } catch (IOException e) {
            log.warn("Could not read .env: {}", e.getMessage());
        }
    }
}
