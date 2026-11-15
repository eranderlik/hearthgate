package dev.hearthgate.common.client;

import dev.hearthgate.core.HelperEvent;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Shows the invitation for the shared world and who is connected.
 *
 * <p>Built only from widgets (no custom rendering), which keeps it independent
 * of the rendering API changes between Minecraft versions.
 */
public final class ShareScreen extends Screen {
	private final Screen parent;
	private MultiLineTextWidget status;
	private MultiLineTextWidget invite;
	private MultiLineTextWidget peers;
	private Button copy;
	private String error;
	private boolean requested;
	private String shownInvite;
	private int shownPeers = -1;

	public ShareScreen(Screen parent) {
		super(Component.translatable("hearthgate.share.title"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		int cx = width / 2;
		int top = height / 2 - 70;
		addRenderableWidget(Ui.centered(title, font, width, top));
		status = addRenderableWidget(Ui.centered(Component.empty(), font, width, top + 22));
		invite = addRenderableWidget(Ui.centered(Component.empty(), font, width, top + 44));
		peers = addRenderableWidget(Ui.centered(Component.empty(), font, width, top + 62));

		copy = addRenderableWidget(Button.builder(Component.translatable("hearthgate.share.copy"), b -> {
			Sessions.Status s = HearthgateClientCommon.sessions().status();
			if (s != null && s.invite() != null) {
				minecraft.keyboardHandler.setClipboard(s.invite());
				b.setMessage(Component.translatable("hearthgate.share.copied"));
			}
		}).bounds(cx - 155, top + 88, 150, 20).build());
		copy.setTooltip(Tooltip.create(Component.translatable("hearthgate.share.copy_tooltip")));
		addRenderableWidget(Button.builder(Component.translatable("hearthgate.share.stop"), b -> {
			HearthgateClientCommon.sessions().stop();
			onClose();
		}).bounds(cx + 5, top + 88, 150, 20).build());
		addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose())
				.bounds(cx - 100, top + 116, 200, 20).build());
		addRenderableWidget(Ui.centered(Component.translatable("hearthgate.share.secret_hint")
				.withStyle(ChatFormatting.GRAY), font, width, top + 146));

		shownInvite = null;
		shownPeers = -1;
		if (!requested) {
			requested = true;
			HearthgateClientCommon.sessions().share().whenComplete((value, err) -> minecraft.execute(() -> {
				if (err != null) {
					error = ErrorMessages.rootMessage(err);
				}
			}));
		}
		refresh();
	}

	@Override
	public void tick() {
		refresh();
	}

	private void refresh() {
		Sessions.Status s = HearthgateClientCommon.sessions().status();
		boolean ready = s != null && s.mode() == Sessions.Mode.HOST && s.ready();
		copy.active = ready;
		if (error != null) {
			Ui.set(status, Component.translatable("hearthgate.share.failed", error).withStyle(ChatFormatting.RED), width);
			Ui.set(invite, Component.empty(), width);
			Ui.set(peers, Component.empty(), width);
			return;
		}
		if (!ready) {
			Ui.set(status, Component.translatable("hearthgate.starting"), width);
			return;
		}
		if (!s.invite().equals(shownInvite)) {
			shownInvite = s.invite();
			Ui.set(status, Component.translatable("hearthgate.share.sharing").withStyle(ChatFormatting.GREEN), width);
			Ui.set(invite, Component.literal(Ui.shorten(s.invite(), 44)).withStyle(ChatFormatting.GRAY), width);
		}
		if (s.peers().size() != shownPeers || shownPeers > 0) {
			shownPeers = s.peers().size();
			Ui.set(peers, describePeers(s), width);
		}
	}

	private static Component describePeers(Sessions.Status s) {
		if (s.peers().isEmpty()) {
			return Component.translatable("hearthgate.share.no_players").withStyle(ChatFormatting.GRAY);
		}
		long direct = s.peers().stream().filter(HelperEvent.Peer::direct).count();
		long relayed = s.peers().size() - direct;
		return Component.translatable("hearthgate.share.players", s.peers().size(), direct, relayed);
	}

	@Override
	public void onClose() {
		Compat.setScreen(minecraft, parent);
	}
}
