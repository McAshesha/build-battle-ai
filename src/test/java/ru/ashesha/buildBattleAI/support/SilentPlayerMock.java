package ru.ashesha.buildBattleAI.support;

import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.UUID;

/**
 * {@link PlayerMock} that silently no-ops every {@code playSound} overload.
 * <p>
 * MockBukkit 4.50.0's {@link PlayerMock#playSound} throws
 * {@code UnimplementedOperationException} for most overloads. Any code
 * under test that goes through {@code XSound#play(Player)} (which the
 * production codebase calls from MLTestCommand, GameListener, etc.)
 * will explode in a test that uses a default {@code PlayerMock}.
 * <p>
 * Subclasses of {@link IntegrationTestSupport} obtain players via
 * {@link IntegrationTestSupport#addSilentPlayer(String)} so this gotcha
 * is opt-in and centralised.
 */
public class SilentPlayerMock extends PlayerMock {

    public SilentPlayerMock(ServerMock server, String name) {
        super(server, name);
    }

    public SilentPlayerMock(ServerMock server, String name, UUID uuid) {
        super(server, name, uuid);
    }

    // ── playSound overloads — MockBukkit's PlayerMock throws on all of these ──

    @Override public void playSound(Location l, Sound s, float v, float p) { /* no-op */ }
    @Override public void playSound(Location l, String s, float v, float p) { /* no-op */ }
    @Override public void playSound(Location l, Sound s, SoundCategory c, float v, float p) { /* no-op */ }
    @Override public void playSound(Location l, String s, SoundCategory c, float v, float p) { /* no-op */ }
    @Override public void playSound(org.bukkit.entity.Entity e, Sound s, float v, float p) { /* no-op */ }
    @Override public void playSound(org.bukkit.entity.Entity e, String s, float v, float p) { /* no-op */ }
    @Override public void playSound(org.bukkit.entity.Entity e, Sound s, SoundCategory c, float v, float p) { /* no-op */ }
    @Override public void playSound(org.bukkit.entity.Entity e, String s, SoundCategory c, float v, float p) { /* no-op */ }
}
