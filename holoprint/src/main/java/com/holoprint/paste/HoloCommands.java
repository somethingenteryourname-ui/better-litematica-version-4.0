package com.holoprint.paste;

import com.holoprint.placement.Placement;
import com.holoprint.placement.PlacementManager;
import com.mojang.brigadier.CommandDispatcher;

import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.network.chat.Component;

/**
 * Client-side commands (they never reach the server):
 * /holoprint paste [missing] · /holoprint paste stop · /holoprint menu · /holoprint status
 */
public final class HoloCommands {
	private HoloCommands() {}

	/** Set by "/holoprint menu"; the menu opens next tick (after the chat closes). */
	public static boolean openMenuNextTick;

	public static void register() {
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> build(dispatcher));
	}

	private static void build(CommandDispatcher<FabricClientCommandSource> d) {
		d.register(ClientCommandManager.literal("holoprint")
				.executes(ctx -> {
					ctx.getSource().sendFeedback(Component.literal("HoloPrint: /holoprint paste [missing|stop], /holoprint menu, /holoprint status"));
					return 1;
				})
				.then(ClientCommandManager.literal("paste")
						.executes(ctx -> paste(ctx.getSource(), true))
						.then(ClientCommandManager.literal("missing").executes(ctx -> paste(ctx.getSource(), false)))
						.then(ClientCommandManager.literal("stop").executes(ctx -> {
							if (!Paster.active()) {
								ctx.getSource().sendError(Component.literal("Nothing is being pasted"));
								return 0;
							}
							Paster.stop(false);
							return 1;
						})))
				.then(ClientCommandManager.literal("menu").executes(ctx -> {
					openMenuNextTick = true;
					return 1;
				}))
				.then(ClientCommandManager.literal("status").executes(ctx -> {
					Placement p = PlacementManager.selected();
					if (p == null) {
						ctx.getSource().sendFeedback(Component.literal("No build selected."));
					} else {
						ctx.getSource().sendFeedback(Component.literal(p.name() + ": " + Math.round(p.progress() * 100) + "% built, "
								+ p.statMissing + " missing, " + (p.statWrong + p.statWrongState) + " wrong"));
					}
					if (Paster.active()) {
						ctx.getSource().sendFeedback(Component.literal("Pasting " + Paster.targetName() + ": " + Math.round(Paster.progress() * 100) + "%"));
					}
					return 1;
				})));
	}

	private static int paste(FabricClientCommandSource src, boolean replaceWrong) {
		String err = Paster.start(PlacementManager.selected(), replaceWrong);
		if (err != null) {
			src.sendError(Component.literal(err));
			return 0;
		}
		src.sendFeedback(Component.literal("Pasting " + PlacementManager.selected().name()
				+ (replaceWrong ? " (replacing wrong blocks too)" : " (only empty spots)") + ". Stop with /holoprint paste stop"));
		return 1;
	}
}
