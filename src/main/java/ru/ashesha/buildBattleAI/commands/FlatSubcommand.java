package ru.ashesha.buildBattleAI.commands;

import lombok.NonNull;
import org.bukkit.command.CommandSender;
import ru.ashesha.buildBattleAI.BuildBattleAI;

import java.util.List;

/**
 * Thin top-level command wrapper used when {@code commands.style: flat} is set
 * in {@code config.yml}. Each instance corresponds to one public subcommand of
 * {@link ArenaCommand} (e.g. {@code "create"}, {@code "join"}, {@code "lang"})
 * and is registered as its own root command (e.g. {@code /create}) so players
 * do not have to type the {@code /bbai} prefix.
 * <p>
 * The wrapper does not duplicate any business logic — it simply prepends its
 * own subcommand name to the player's argument array and forwards the call to
 * the parent {@link ArenaCommand}. That guarantees flat and subcommand modes
 * behave identically (including player-state-aware tab completion, error
 * messages, and the per-arena join logic).
 * <p>
 * Flat commands are registered only when explicitly opted in — see the
 * {@code commands.style} key in the default {@code config.yml} for the
 * trade-offs (notably, conflict with vanilla {@code /list} and with proxy
 * plugins owning {@code /join}/{@code /leave}).
 */
public final class FlatSubcommand extends CommandService.PluginCommand {

    /**
     * The umbrella command we delegate to. Holds the actual switch over
     * subcommand names and all per-subcommand handlers.
     */
    private final ArenaCommand parent;

    /**
     * Canonical subcommand name this flat alias represents (e.g.
     * {@code "create"}). Prepended to the player-supplied argument array on
     * every dispatch so {@link ArenaCommand#dispatch} sees the same input
     * shape as a real {@code /bbai <sub> ...} invocation.
     */
    private final String subcommand;

    /**
     * Creates a flat alias for the given subcommand.
     *
     * @param plugin      the plugin instance
     * @param parent      the umbrella {@code /bbai} command holding the handlers
     * @param subcommand  the canonical subcommand name (lower case)
     * @param description short human-readable description shown in {@code /help}
     * @param usage       usage hint shown after {@code /<subcommand>}
     */
    public FlatSubcommand(@NonNull BuildBattleAI plugin,
                          @NonNull ArenaCommand parent,
                          @NonNull String subcommand,
                          @NonNull String description,
                          @NonNull String usage) {
        super(plugin, subcommand, description, usage);
        this.parent = parent;
        this.subcommand = subcommand;
    }

    /**
     * Forwards the call to {@link ArenaCommand#dispatch} with the subcommand
     * name prepended, so the parent's existing switch table picks the right
     * handler without any flat-aware branches.
     */
    @Override
    protected void execute(CommandSender sender, String[] args) {
        parent.dispatch(sender, prependSubcommand(args));
    }

    /**
     * Forwards tab completion the same way {@link #execute} forwards execution.
     * The prepended subcommand name makes the second-arg branch of
     * {@link ArenaCommand#dispatchSuggest} fire — that is where arena-name
     * and lang-code completions live.
     */
    @Override
    protected List<String> suggest(CommandSender sender, String[] args) {
        return parent.dispatchSuggest(sender, prependSubcommand(args));
    }

    /**
     * Builds {@code [subcommand, args...]} — the input shape expected by
     * {@link ArenaCommand#execute}. Allocates a fresh array on every call so
     * the caller's args reference is not aliased into the parent.
     */
    private String[] prependSubcommand(String[] args) {
        String[] out = new String[args.length + 1];
        out[0] = subcommand;
        System.arraycopy(args, 0, out, 1, args.length);
        return out;
    }
}
