package dev.hearthgate.common.server;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import dev.hearthgate.core.server.PersistentHost;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;

import java.time.Duration;
import java.time.Instant;

/**
 * {@code /hearthgate status|invite|rotate|announce}, for server operators
 * (permission level 3) and the console. Messages are literal text because
 * clients without the mod have no translations for them.
 */
final class HearthgateCommand {
	private HearthgateCommand() {}

	static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("hearthgate")
				.requires(Commands.hasPermission(Commands.LEVEL_ADMINS))
				.executes(HearthgateCommand::status)
				.then(Commands.literal("status").executes(HearthgateCommand::status))
				.then(Commands.literal("invite").executes(HearthgateCommand::invite))
				.then(Commands.literal("rotate").executes(HearthgateCommand::rotate))
				.then(Commands.literal("announce").executes(HearthgateCommand::announce)));
	}

	private static PersistentHost host(CommandContext<CommandSourceStack> ctx) {
		PersistentHost h = HearthgateServerCommon.host();
		if (h == null) {
			ctx.getSource().sendFailure(Component.literal(
					"Hearthgate isn't running on this server (see hearthgate-server.json and the log)."));
		}
		return h;
	}

	private static int status(CommandContext<CommandSourceStack> ctx) {
		PersistentHost h = host(ctx);
		if (h == null) {
			return 0;
		}
		PersistentHost.Status s = h.status();
		MutableComponent msg = Component.literal("Hearthgate: ").withStyle(ChatFormatting.GOLD)
				.append(Component.literal(s.state()).withStyle(s.invite() != null ? ChatFormatting.GREEN : ChatFormatting.YELLOW));
		msg.append(Component.literal(String.format("%nPlayers via Hearthgate: %d (%d direct, %d relayed)",
				s.players(), s.directPlayers(), s.players() - s.directPlayers())).withStyle(ChatFormatting.GRAY));
		if (s.drainingTunnels() > 0) {
			msg.append(Component.literal("\nPrevious invitation still serving connected players")
					.withStyle(ChatFormatting.GRAY));
		}
		msg.append(Component.literal("\nInvitation " + (s.validUntil() == null ? "does not rotate"
				: "rotates in " + human(Duration.between(Instant.now(), s.validUntil())))).withStyle(ChatFormatting.GRAY));
		msg.append(Component.literal("\nHelper: " + s.engine()).withStyle(ChatFormatting.GRAY));
		msg.append(Component.literal("\nDiscord: " + (h.discordEnabled() ? "enabled" : "not configured"))
				.withStyle(ChatFormatting.GRAY));
		if (s.lastError() != null) {
			msg.append(Component.literal("\nLast error: " + s.lastError()).withStyle(ChatFormatting.RED));
		}
		ctx.getSource().sendSuccess(() -> msg, false);
		return 1;
	}

	private static int invite(CommandContext<CommandSourceStack> ctx) {
		PersistentHost h = host(ctx);
		if (h == null) {
			return 0;
		}
		String invite = h.status().invite();
		if (invite == null) {
			ctx.getSource().sendFailure(Component.literal("No invitation yet; Hearthgate is " + h.status().state() + "."));
			return 0;
		}
		Component text = Component.literal(invite).withStyle(style -> style
				.withColor(ChatFormatting.AQUA)
				.withClickEvent(new ClickEvent.CopyToClipboard(invite))
				.withHoverEvent(new HoverEvent.ShowText(Component.literal("Click to copy"))));
		ctx.getSource().sendSuccess(() -> Component.literal("Hearthgate invitation (secret; click to copy):\n")
				.withStyle(ChatFormatting.GOLD).append(text), false);
		return 1;
	}

	private static int rotate(CommandContext<CommandSourceStack> ctx) {
		PersistentHost h = host(ctx);
		if (h == null) {
			return 0;
		}
		h.rotateNow();
		ctx.getSource().sendSuccess(() -> Component.literal(
				"Rotating Hearthgate keys. Players already connected stay connected; "
						+ "in a few seconds the old invitation stops admitting anyone and the new one takes effect"
						+ (h.discordEnabled() ? " and will be posted to Discord." : ".")), true);
		return 1;
	}

	private static int announce(CommandContext<CommandSourceStack> ctx) {
		PersistentHost h = host(ctx);
		if (h == null) {
			return 0;
		}
		if (!h.discordEnabled()) {
			ctx.getSource().sendFailure(Component.literal("No Discord webhook configured in hearthgate-server.json."));
			return 0;
		}
		h.republish();
		ctx.getSource().sendSuccess(() -> Component.literal("Posting the invitation to Discord."), true);
		return 1;
	}

	private static String human(Duration d) {
		if (d.isNegative() || d.isZero()) {
			return "moments";
		}
		long h = d.toHours();
		return h >= 48 ? (h / 24) + " days" : h >= 1 ? h + " hours" : Math.max(1, d.toMinutes()) + " minutes";
	}
}
