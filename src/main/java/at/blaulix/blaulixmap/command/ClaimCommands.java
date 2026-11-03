package at.blaulix.blaulixmap.command;

import at.blaulix.blaulixmap.BlaulixMap;
import at.blaulix.blaulixmap.claims.Claim;
import at.blaulix.blaulixmap.claims.ClaimManager;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

/**
 * Registriert /claim (create, addpoint, confirm, cancel, list, remove) und /map login.
 * Es gibt bewusst keine Gamemode-Pruefung -> alle Befehle funktionieren auch im Spectator-Modus.
 */
public final class ClaimCommands {
    private ClaimCommands() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, BlaulixMap mod) {
        dispatcher.register(Commands.literal("claim")
                .then(Commands.literal("create")
                        .then(Commands.argument("name", StringArgumentType.string())
                                .executes(ctx -> create(ctx, mod))))
                .then(Commands.literal("addpoint").executes(ctx -> addPoint(ctx, mod)))
                .then(Commands.literal("confirm").executes(ctx -> confirm(ctx, mod)))
                .then(Commands.literal("cancel").executes(ctx -> cancel(ctx, mod)))
                .then(Commands.literal("list").executes(ctx -> list(ctx, mod)))
                .then(Commands.literal("remove")
                        .then(Commands.argument("name", StringArgumentType.string())
                                .executes(ctx -> remove(ctx, mod)))));

        dispatcher.register(Commands.literal("map")
                .then(Commands.literal("login").executes(ctx -> login(ctx, mod))));
    }

    // ------------------------------------------------------------------ Hilfen

    private static void ok(CommandContext<CommandSourceStack> ctx, String msg) {
        // false = nicht an andere Admins/Ops broadcasten -> bleibt privat beim Ausfuehrenden
        ctx.getSource().sendSuccess(() -> Component.literal(msg), false);
    }

    private static int fail(CommandContext<CommandSourceStack> ctx, String msg) {
        ctx.getSource().sendFailure(Component.literal(msg));
        return 0;
    }

    private static ServerPlayer player(CommandContext<CommandSourceStack> ctx) {
        return ctx.getSource().getPlayer();
    }

    private static String name(ServerPlayer p) { return p.getName().getString(); }

    /** Admin = Konsole, in admin_players eingetragen oder Operator (per Reflection, versionsunabhaengig). */
    private static boolean isAdmin(CommandSourceStack src, BlaulixMap mod) {
        ServerPlayer p = src.getPlayer();
        if (p == null) return true; // Konsole / Command-Block
        List<String> admins = mod.config().admin_players;
        for (String a : admins) {
            if (a.equalsIgnoreCase(name(p)) || a.equalsIgnoreCase(p.getUUID().toString())) return true;
        }
        try {
            Object r = CommandSourceStack.class.getMethod("hasPermission", int.class).invoke(src, 2);
            return r instanceof Boolean b && b;
        } catch (Throwable t) {
            return false;
        }
    }

    // ------------------------------------------------------------------ /claim

    private static int create(CommandContext<CommandSourceStack> ctx, BlaulixMap mod) {
        ServerPlayer p = player(ctx);
        if (p == null) return fail(ctx, "Nur Spieler können Claims erstellen.");
        ClaimManager.Result r = mod.claims().startDraft(p.getUUID(), StringArgumentType.getString(ctx, "name"),
                BlaulixMap.dimensionId(p));
        if (!r.ok()) return fail(ctx, r.message());
        ok(ctx, r.message());
        return 1;
    }

    private static int addPoint(CommandContext<CommandSourceStack> ctx, BlaulixMap mod) {
        ServerPlayer p = player(ctx);
        if (p == null) return fail(ctx, "Nur Spieler können Punkte setzen.");
        ClaimManager.Draft d = mod.claims().draft(p.getUUID());
        if (d == null) return fail(ctx, "Kein Entwurf aktiv. Starte mit /claim create <name>.");
        if (!d.dimension.equals(BlaulixMap.dimensionId(p))) return fail(ctx, "Du bist in einer anderen Dimension als dein Entwurf.");
        if (d.points.size() >= 64) return fail(ctx, "Maximal 64 Eckpunkte.");
        double x = Math.floor(p.getX()), z = Math.floor(p.getZ());
        synchronized (d) { d.points.add(new double[]{x, z}); }
        ok(ctx, "Punkt " + d.points.size() + " gesetzt bei X=" + (int) x + " Z=" + (int) z + ".");
        return 1;
    }

    private static int confirm(CommandContext<CommandSourceStack> ctx, BlaulixMap mod) {
        ServerPlayer p = player(ctx);
        if (p == null) return fail(ctx, "Nur Spieler können Claims bestätigen.");
        ClaimManager.Result r = mod.claims().confirmDraft(p.getUUID(), name(p));
        if (!r.ok()) return fail(ctx, r.message());
        ok(ctx, r.message());
        return 1;
    }

    private static int cancel(CommandContext<CommandSourceStack> ctx, BlaulixMap mod) {
        ServerPlayer p = player(ctx);
        if (p == null) return fail(ctx, "Nur Spieler.");
        mod.claims().cancelDraft(p.getUUID());
        ok(ctx, "Entwurf verworfen.");
        return 1;
    }

    private static int list(CommandContext<CommandSourceStack> ctx, BlaulixMap mod) {
        ServerPlayer p = player(ctx);
        List<Claim> all = mod.claims().all();
        int n = 0;
        for (Claim c : all) {
            if (p != null && !c.owner.equals(p.getUUID().toString())) continue;
            ok(ctx, "- " + c.name + " (" + c.dimension + ", " + Math.round(c.area) + " Blöcke, " + c.ownerName + ")");
            n++;
        }
        if (n == 0) ok(ctx, "Keine Claims vorhanden.");
        return n;
    }

    private static int remove(CommandContext<CommandSourceStack> ctx, BlaulixMap mod) {
        ServerPlayer p = player(ctx);
        boolean admin = isAdmin(ctx.getSource(), mod);
        String requester = p == null ? "console" : p.getUUID().toString();
        ClaimManager.Result r = mod.claims().remove(StringArgumentType.getString(ctx, "name"), requester, admin);
        if (!r.ok()) return fail(ctx, r.message());
        ok(ctx, r.message());
        return 1;
    }

    // ------------------------------------------------------------------ /map login

    private static int login(CommandContext<CommandSourceStack> ctx, BlaulixMap mod) {
        ServerPlayer p = player(ctx);
        if (p == null) return fail(ctx, "Nur Spieler können einen Login-Code erzeugen.");
        // Code ist fest an UUID + Name dieses Accounts gebunden (siehe AuthManager)
        String code = mod.auth().createCode(p.getUUID(), name(p));
        // sendSuccess(..., false): nur an den ausfuehrenden Spieler, nie an andere Ops/Chat
        ok(ctx, "Dein Web-Login-Code: " + code + " (5 Minuten gültig, einmalig). Port: " + mod.config().web_port);
        return 1;
    }
}
