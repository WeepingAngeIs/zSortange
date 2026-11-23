package dev.anya.storage;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.UuidArgument;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.StringUtil;

final class BackpackHistoryCommands {
    private static final String COMMAND = "/anya backpack history";
    private static final int PAGE_SIZE = 6;
    private static final int MAX_PAGE = Integer.MAX_VALUE / PAGE_SIZE;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm 'UTC'", Locale.ROOT)
            .withZone(ZoneOffset.UTC);

    static LiteralArgumentBuilder<CommandSourceStack> backpack() {
        return Commands.literal("backpack").then(Commands.literal("history")
                .requires(BackpackHistoryCommands::allowed)
                .executes(ctx -> list(ctx.getSource(), 1))
                .then(Commands.argument("page", IntegerArgumentType.integer(1, MAX_PAGE))
                        .executes(ctx -> list(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "page"))))
                .then(Commands.literal("restore").then(Commands.argument("bagId", UuidArgument.uuid())
                        .then(Commands.argument("lossId", UuidArgument.uuid()).executes(ctx -> restore(ctx.getSource(),
                                UuidArgument.getUuid(ctx, "bagId"), UuidArgument.getUuid(ctx, "lossId")))))));
    }

    private static boolean allowed(CommandSourceStack source) {
        return source.hasPermission(2) && source.getEntity() instanceof ServerPlayer player && player.hasPermissions(2);
    }

    private static int list(CommandSourceStack source, int page) {
        if (!allowed(source)) return 0;
        ServerPlayer operator = (ServerPlayer) source.getEntity();
        var rows = BackpackHistory.page(operator, page - 1);
        operator.sendSystemMessage(Component.literal("Backpack History · Page " + page).withStyle(ChatFormatting.GOLD));
        if (rows.isEmpty()) {
            operator.sendSystemMessage(Component.literal(page == 1 ? "No recoverable backpacks." : "No recoverable backpacks on this page."));
        } else {
            operator.sendSystemMessage(Component.literal("Click an underlined backpack to return it to its owner."));
            for (var row : rows) {
                String owner = StringUtil.filterText(row.ownerName()).strip();
                if (owner.isEmpty()) owner = row.owner().toString();
                String bag = StringUtil.filterText(row.bagName()).strip();
                if (bag.isEmpty()) bag = "Backpack";
                Instant lost = Instant.ofEpochMilli(row.time());
                String help = "Lost " + DATE.format(lost) + "\nRecovery expires " + DATE.format(lost.plus(30, ChronoUnit.DAYS));
                var details = new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(help));
                MutableComponent item = row.restorable()
                        ? link(bag, COMMAND + " restore " + row.id() + " " + row.lossId(),
                                "Return to " + owner + "\n" + help)
                        : Component.literal(bag).withStyle(s -> s.withHoverEvent(details));
                operator.sendSystemMessage(Component.empty().append(item)
                        .append(Component.literal(" · " + owner + " · " + row.status())
                                .withStyle(s -> s.withHoverEvent(details))));
            }
        }
        MutableComponent navigation = Component.empty();
        if (page > 1) navigation.append(link("‹ Previous", COMMAND + " " + (page - 1), "Previous page"));
        boolean more = rows.size() == PAGE_SIZE && page < MAX_PAGE && !BackpackHistory.page(operator, page).isEmpty();
        if (more) {
            if (page > 1) navigation.append("   ");
            navigation.append(link("Next ›", COMMAND + " " + (page + 1), "Next page"));
        }
        if (page > 1 || more) operator.sendSystemMessage(navigation);
        return 1;
    }

    private static int restore(CommandSourceStack source, UUID bagId, UUID lossId) {
        if (!allowed(source)) return 0;
        ServerPlayer operator = (ServerPlayer) source.getEntity();
        AnyaStorage.tell(operator, BackpackHistory.restore(operator, bagId, lossId));
        return 1;
    }

    private static MutableComponent link(String name, String command, String hint) {
        return Component.literal(name).withStyle(s -> s.withColor(ChatFormatting.AQUA).withUnderlined(true)
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(hint))));
    }

    private BackpackHistoryCommands() {}
}
