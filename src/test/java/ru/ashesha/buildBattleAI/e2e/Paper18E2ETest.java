package ru.ashesha.buildBattleAI.e2e;

import org.junit.jupiter.api.Tag;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * End-to-end driver pinned to the Paper 1.8.8 server under
 * {@code Servers/1.8/}. Validates that the BuildBattleAI plugin still boots
 * and shuts down cleanly on the oldest supported Minecraft version.
 * <p>
 * Activated by {@code -Dbbai.e2e=true} (set automatically by the
 * {@code -Pe2e} Maven profile). See {@link AbstractServerE2ETest} for
 * activation semantics and skip rules.
 */
@Tag("e2e")
class Paper18E2ETest extends AbstractServerE2ETest {

    @Override
    protected Path serverDirectory() {
        return Paths.get("Servers", "1.8").toAbsolutePath();
    }

    @Override
    protected String serverFlavor() {
        return "Paper 1.8.8";
    }
}
