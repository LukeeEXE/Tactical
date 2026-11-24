package dev.tactical.paper.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import dev.tactical.engine.config.ConfigIssue;
import dev.tactical.engine.pack.CustomBlock;
import dev.tactical.engine.pack.PackIssue;
import dev.tactical.paper.audit.AuditService;
import dev.tactical.paper.core.Tasks;
import dev.tactical.paper.pack.PackService;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** {@code /game pack rebuild|status|send} and {@code /game blocks list|give}. */
public final class PackCommands {

    public static final String PERM = "tactical.admin.pack";

    private final PackService pack;
    private final AuditService audit;
    private final Tasks tasks;

    public PackCommands(PackService pack, AuditService audit, Tasks tasks) {
        this.pack = pack;
        this.audit = audit;
        this.tasks = tasks;
    }

    public void attach(LiteralArgumentBuilder<CommandSourceStack> root) {
        root.then(Commands.literal("pack").requires(src -> src.getSender().hasPermission(PERM))
                .then(Commands.literal("rebuild").executes(this::rebuild))
                .then(Commands.literal("status").executes(this::status))
                .then(Commands.literal("check").executes(ctx -> {
                    pack.current().ifPresent(pack::checkExternal);
                    ctx.getSource().getSender().sendMessage(Component.text("Checking the uploaded pack...", NamedTextColor.GRAY));
                    return 1;
                }))
                .then(Commands.literal("send").executes(ctx -> {
                    if (ctx.getSource().getExecutor() instanceof Player p) pack.send(p);
                    return 1;
                })));
        root.then(Commands.literal("blocks").requires(src -> src.getSender().hasPermission(PERM))
                .then(Commands.literal("list").executes(this::list))
                .then(Commands.literal("give").then(Commands.argument("block", StringArgumentType.word())
                        .suggests((c, b) -> {
                            pack.blocks().keySet().forEach(b::suggest);
                            return b.buildFuture();
                        })
                        .executes(ctx -> give(ctx, 64))
                        .then(Commands.argument("amount", IntegerArgumentType.integer(1, 64))
                                .executes(ctx -> give(ctx, IntegerArgumentType.getInteger(ctx, "amount")))))));
    }

    private int rebuild(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        sender.sendMessage(Component.text("Rebuilding resource pack...", NamedTextColor.GRAY));
        audit.record(sender, "PACK_REBUILD", null, "");
        pack.rebuild().whenCompleteAsync((r, t) -> {
            if (t != null) {
                sender.sendMessage(Component.text("Pack build failed: " + t.getMessage(), NamedTextColor.RED));
                return;
            }
            for (ConfigIssue i : pack.lastBlockIssues()) sender.sendMessage(Component.text(i.toString(), NamedTextColor.RED));
            for (PackIssue i : r.issues()) sender.sendMessage(Component.text(i.toString(), i.error() ? NamedTextColor.RED : NamedTextColor.YELLOW));
            sender.sendMessage(Component.text("Pack built: " + r.stats().get("files") + " files, " + r.zip().length / 1024 + " KiB, "
                    + pack.blocks().size() + " custom block(s). Sent to all players if it changed.",
                    r.hasErrors() ? NamedTextColor.GOLD : NamedTextColor.GREEN));
        }, tasks.main());
        return 1;
    }

    private int status(CommandContext<CommandSourceStack> ctx) {
        CommandSender s = ctx.getSource().getSender();
        pack.current().ifPresentOrElse(r -> {
            s.sendMessage(Component.text("Pack sha1 " + r.sha1() + ", " + r.zip().length / 1024 + " KiB, " + r.stats(), NamedTextColor.YELLOW));
            s.sendMessage(Component.text("Hosting: " + (pack.isBuiltin() ? "builtin" : "external (" + pack.externalStatus() + ")")
                    + ", URL: " + pack.packUrl(), NamedTextColor.GRAY));
            s.sendMessage(Component.text("Pack file: " + pack.zipFile(), NamedTextColor.GRAY));
            long errors = r.issues().stream().filter(PackIssue::error).count();
            s.sendMessage(Component.text(errors + " error(s), " + (r.issues().size() - errors) + " warning(s) in the last build",
                    errors > 0 ? NamedTextColor.RED : NamedTextColor.GREEN));
        }, () -> s.sendMessage(Component.text("No pack built yet.", NamedTextColor.RED)));
        return 1;
    }

    private int list(CommandContext<CommandSourceStack> ctx) {
        CommandSender s = ctx.getSource().getSender();
        if (pack.blocks().isEmpty()) {
            s.sendMessage(Component.text("No custom blocks. Define them in plugins/Tactical/blocks/*.yml (see example.yml).", NamedTextColor.GRAY));
        }
        for (CustomBlock b : pack.blocks().values()) {
            s.sendMessage(Component.text(b.id() + "  " + b.stateKey() + "  " + (b.texture() != null ? "texture " + b.texture() : "model " + b.model()),
                    NamedTextColor.YELLOW));
        }
        return 1;
    }

    private int give(CommandContext<CommandSourceStack> ctx, int amount) {
        if (!(ctx.getSource().getExecutor() instanceof Player p)) return 0;
        String id = StringArgumentType.getString(ctx, "block");
        return pack.paletteItem(id, amount).map(item -> {
            p.getInventory().addItem(item);
            p.sendMessage(Component.text("Given " + amount + "x " + id + ". It always places " + pack.blocks().get(id).stateKey() + ".",
                    NamedTextColor.GREEN));
            return 1;
        }).orElseGet(() -> {
            p.sendMessage(Component.text("Unknown custom block '" + id + "'.", NamedTextColor.RED));
            return 0;
        });
    }
}
