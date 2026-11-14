package dev.tailcatmc.fabric;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.Optional;

/** Accepts an invitation and connects through a Tailcat tunnel. */
public final class JoinScreen extends Screen {
	private final Screen parent;
	private EditBox input;
	private Button connect;
	private MultiLineTextWidget status;
	private String text = "";
	private Component statusText = Component.empty();
	private boolean connecting;

	public JoinScreen(Screen parent) {
		super(Component.translatable("tailcatmc.join.title"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		int cx = width / 2;
		int top = height / 2 - 60;
		addRenderableWidget(Ui.centered(title, font, width, top));
		addRenderableWidget(Ui.centered(Component.translatable("tailcatmc.join.help").withStyle(ChatFormatting.GRAY),
				font, width, top + 16));

		input = new EditBox(font, cx - 150, top + 38, 300, 20, Component.translatable("tailcatmc.join.invite"));
		input.setMaxLength(4096);
		input.setHint(Component.translatable("tailcatmc.join.hint").withStyle(ChatFormatting.DARK_GRAY));
		input.setValue(text);
		input.setEditable(!connecting);
		input.setResponder(this::onInput);
		addRenderableWidget(input);

		connect = addRenderableWidget(Button.builder(Component.translatable("tailcatmc.join.connect"), b -> begin())
				.bounds(cx - 155, top + 66, 150, 20).build());
		addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> onClose())
				.bounds(cx + 5, top + 66, 150, 20).build());
		status = addRenderableWidget(Ui.centered(statusText, font, width, top + 96));
		onInput(text);
		setInitialFocus(input);
	}

	private void onInput(String value) {
		text = value;
		Optional<Invites.Info> info = Invites.peek(value);
		connect.active = !connecting && info.isPresent();
		if (connecting) {
			return;
		}
		if (value.isBlank()) {
			setStatus(Component.empty());
		} else if (info.isEmpty()) {
			setStatus(Component.translatable("tailcatmc.join.not_an_invite").withStyle(ChatFormatting.RED));
		} else {
			String theirs = info.get().minecraftVersion();
			String ours = Sessions.gameVersion();
			if (theirs != null && !theirs.equals(ours)) {
				setStatus(Component.translatable("tailcatmc.join.version_mismatch", theirs, ours)
						.withStyle(ChatFormatting.YELLOW));
			} else {
				setStatus(Component.empty());
			}
		}
	}

	private void setStatus(Component c) {
		statusText = c;
		if (status != null) {
			Ui.set(status, c, width);
		}
	}

	private void begin() {
		connecting = true;
		connect.active = false;
		input.setEditable(false);
		setStatus(Component.translatable("tailcatmc.starting"));
		TailcatClient.sessions().join(parent, text).whenComplete((ignored, err) -> {
			if (err != null) {
				minecraft.execute(() -> {
					connecting = false;
					input.setEditable(true);
					onInput(text);
					setStatus(Component.translatable("tailcatmc.join.failed", ErrorMessages.rootMessage(err))
							.withStyle(ChatFormatting.RED));
				});
			}
		});
	}

	@Override
	public void onClose() {
		if (connecting) {
			TailcatClient.sessions().stop();
		}
		Compat.setScreen(minecraft, parent);
	}
}
