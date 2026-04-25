package ru.ashesha.buildBattleAI.entity.hologram.api;

import lombok.NonNull;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import ru.ashesha.buildBattleAI.entity.hologram.HologramService;

import java.util.Collection;
import java.util.List;

/**
 * Service for creating and managing client-side packet-based holograms.
 * <p>
 * Holograms are multiline floating text labels built from stacked invisible
 * armor stands with custom names. They are purely packet-based — the server
 * never tracks them as real entities. Each viewer can see the same hologram
 * independently; all visibility is managed by the caller.
 * <p>
 * <b>Stateless design:</b> holograms store only their entity IDs — no location,
 * no line text. All positional and textual state is the caller's responsibility,
 * consistent with the NPC service pattern. The caller passes location and lines
 * to every method that needs them.
 * <p>
 * Lines are rendered top-to-bottom: index 0 is the topmost line. Each line
 * is a separate invisible armor stand spaced {@code 0.3} blocks apart.
 * <p>
 * All packets are sent through PacketEvents, providing fully cross-version
 * hologram display (1.8–1.21+) without NMS reflection.
 */
public interface BBAIHologramService {

    /**
     * Creates a new hologram handle with the given number of lines.
     * <p>
     * The hologram is not yet visible to any player — call {@link #spawn} to show it.
     * Each call allocates unique entity IDs for every line in the hologram.
     * No location or text is stored on the hologram itself.
     *
     * @param lineCount the number of text lines (must be at least 1)
     * @return a new hologram instance ready to be spawned
     * @throws IllegalArgumentException if lineCount is less than 1
     */
    @NonNull
    HologramService.Hologram createHologram(int lineCount);

    /**
     * Shows the hologram to the specified viewer at the given location.
     * <p>
     * Sends spawn and metadata packets for every line (armor stand) in the hologram.
     * The line count must match the hologram's entity count.
     *
     * @param viewer   the player who will see the hologram
     * @param hologram the hologram to display
     * @param location the world position to anchor the hologram
     * @param lines    the text lines (index 0 = topmost line); supports {@code &} color codes
     * @throws IllegalArgumentException if lines size does not match hologram line count
     */
    void spawn(@NonNull Player viewer, @NonNull HologramService.Hologram hologram,
               @NonNull Location location, @NonNull List<String> lines);

    /**
     * Shows the hologram to the specified viewers at the given location.
     *
     * @param viewers  the players who will see the hologram
     * @param hologram the hologram to display
     * @param location the world position to anchor the hologram
     * @param lines    the text lines (index 0 = topmost line); supports {@code &} color codes
     * @throws IllegalArgumentException if lines size does not match hologram line count
     * @see #spawn(Player, HologramService.Hologram, Location, List)
     */
    void spawn(@NonNull Collection<Player> viewers, @NonNull HologramService.Hologram hologram,
               @NonNull Location location, @NonNull List<String> lines);

    /**
     * Removes the hologram from the specified viewer's client.
     * Sends entity destroy packets for all lines.
     *
     * @param viewer   the player who will no longer see the hologram
     * @param hologram the hologram to hide
     */
    void despawn(@NonNull Player viewer, @NonNull HologramService.Hologram hologram);

    /**
     * Removes the hologram from the specified viewers' clients.
     *
     * @param viewers  the players who will no longer see the hologram
     * @param hologram the hologram to hide
     * @see #despawn(Player, HologramService.Hologram)
     */
    void despawn(@NonNull Collection<Player> viewers, @NonNull HologramService.Hologram hologram);

    /**
     * Updates a single line of text on the hologram for the specified viewer.
     * <p>
     * Sends only the metadata packet for the affected armor stand — no respawn needed.
     * No state is stored on the hologram; the caller tracks what text each line has.
     *
     * @param viewer    the player who will see the updated text
     * @param hologram  the hologram to update
     * @param lineIndex zero-based index of the line to update (0 = top line)
     * @param text      the new text for this line; supports {@code &} color codes
     * @throws IndexOutOfBoundsException if lineIndex is out of range
     */
    void updateLine(@NonNull Player viewer, @NonNull HologramService.Hologram hologram,
                    int lineIndex, @NonNull String text);

    /**
     * Updates a single line of text on the hologram for the specified viewers.
     *
     * @param viewers   the players who will see the updated text
     * @param hologram  the hologram to update
     * @param lineIndex zero-based index of the line to update (0 = top line)
     * @param text      the new text for this line; supports {@code &} color codes
     * @throws IndexOutOfBoundsException if lineIndex is out of range
     * @see #updateLine(Player, HologramService.Hologram, int, String)
     */
    void updateLine(@NonNull Collection<Player> viewers, @NonNull HologramService.Hologram hologram,
                    int lineIndex, @NonNull String text);

    /**
     * Replaces all lines on the hologram for the specified viewer.
     * <p>
     * Handles dynamic line count changes: if the new line count differs from the
     * current count, armor stands are spawned or despawned as needed, and existing
     * lines are repositioned to maintain correct spacing. This follows the same
     * pattern as {@code BoardMicroService.Board.setLines}.
     * <p>
     * When the line count stays the same, only metadata packets are sent (no teleport).
     *
     * @param viewer   the player who will see the updated text
     * @param hologram the hologram to update
     * @param location the current anchor location (needed for repositioning when line count changes)
     * @param lines    the new lines; must have at least 1 entry; supports {@code &} color codes
     * @throws IllegalArgumentException if lines is empty
     */
    void updateLines(@NonNull Player viewer, @NonNull HologramService.Hologram hologram,
                     @NonNull Location location, @NonNull List<String> lines);

    /**
     * Replaces all lines on the hologram for the specified viewers.
     *
     * @param viewers  the players who will see the updated text
     * @param hologram the hologram to update
     * @param location the current anchor location (needed for repositioning when line count changes)
     * @param lines    the new lines; must have at least 1 entry; supports {@code &} color codes
     * @throws IllegalArgumentException if lines is empty
     * @see #updateLines(Player, HologramService.Hologram, Location, List)
     */
    void updateLines(@NonNull Collection<Player> viewers, @NonNull HologramService.Hologram hologram,
                     @NonNull Location location, @NonNull List<String> lines);

    /**
     * Teleports the hologram to a new location for the specified viewer.
     * <p>
     * Sends entity teleport packets for every armor stand in the hologram,
     * preserving the line spacing from the new anchor point.
     *
     * @param viewer      the player who will see the hologram move
     * @param hologram    the hologram to teleport
     * @param destination the new anchor location
     */
    void teleport(@NonNull Player viewer, @NonNull HologramService.Hologram hologram,
                  @NonNull Location destination);

    /**
     * Teleports the hologram to a new location for the specified viewers.
     *
     * @param viewers     the players who will see the hologram move
     * @param hologram    the hologram to teleport
     * @param destination the new anchor location
     * @see #teleport(Player, HologramService.Hologram, Location)
     */
    void teleport(@NonNull Collection<Player> viewers, @NonNull HologramService.Hologram hologram,
                  @NonNull Location destination);
}
