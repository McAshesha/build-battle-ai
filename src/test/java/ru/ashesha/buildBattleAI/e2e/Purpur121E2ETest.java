package ru.ashesha.buildBattleAI.e2e;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * End-to-end driver pinned to the Purpur 1.21.11 server under
 * {@code Servers/1.21/}. Validates that the BuildBattleAI plugin still
 * boots and shuts down cleanly on the modern Purpur fork (Paper fork with
 * extra performance and gameplay flags), which is the closest match to a
 * real production environment.
 * <p>
 * Activated by {@code -Dbbai.e2e=true} (set automatically by the
 * {@code -Pe2e} Maven profile). See {@link AbstractServerE2ETest} for
 * activation semantics and skip rules.
 */
class Purpur121E2ETest extends AbstractServerE2ETest {

    @Override
    protected Path serverDirectory() {
        return Paths.get("Servers", "1.21").toAbsolutePath();
    }

    @Override
    protected String serverFlavor() {
        return "Purpur 1.21.11";
    }
}
