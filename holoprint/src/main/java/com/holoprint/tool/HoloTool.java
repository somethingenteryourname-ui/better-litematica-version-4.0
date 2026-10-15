package com.holoprint.tool;

import org.lwjgl.glfw.GLFW;

import com.holoprint.HoloPrintClient;
import com.holoprint.config.HoloConfig;
import com.holoprint.gui.Toasts;
import com.holoprint.paste.Paster;
import com.holoprint.placement.Placement;
import com.holoprint.placement.PlacementManager;
import com.holoprint.placement.Selection;

import net.fabricmc.fabric.api.event.client.player.ClientPreAttackCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * The tool item (a stick by default), like Litematica's. While you hold it,
 * left and right click do HoloPrint things instead of breaking/using blocks.
 * Ctrl + scroll changes the mode, Alt + scroll does the mode's scroll action.
 */
public final class HoloTool {
	private HoloTool() {}

	public enum Mode {
		MOVE("Move", "RMB: move build to the block you click", "LMB: move build to your feet", "Alt+scroll: nudge where you look"),
		SELECT("Save area", "LMB: corner 1", "RMB: corner 2", "then save it on the Create page"),
		ROTATE("Rotate", "RMB: turn 90°", "LMB: mirror", "Alt+scroll: turn"),
		LAYERS("Layers", "RMB: layer up", "LMB: layer down", "Alt+scroll: change layer"),
		PASTE("Paste", "RMB twice: build it", "LMB: stop pasting", "replaces wrong blocks too"),
		PICK("Pick build", "RMB: select the build you look at", "LMB: next build", "");

		public final String label, hint1, hint2, hint3;

		Mode(String label, String hint1, String hint2, String hint3) {
			this.label = label;
			this.hint1 = hint1;
			this.hint2 = hint2;
			this.hint3 = hint3;
		}
	}

	public static Mode mode = Mode.MOVE;
	private static long lastRightClick;
	private static long pasteArmedUntil;

	public static void register() {
		ClientPreAttackCallback.EVENT.register((client, player, clickCount) -> {
			if (!holding(client)) return false;
			if (clickCount > 0) leftClick(client);
			return true; // never break blocks with the tool
		});
		UseItemCallback.EVENT.register((player, level, hand) -> {
			if (!(player instanceof LocalPlayer) || !holding(Minecraft.getInstance())) return InteractionResult.PASS;
			rightClick(Minecraft.getInstance());
			return InteractionResult.FAIL;
		});
		UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
			if (!(player instanceof LocalPlayer) || !holding(Minecraft.getInstance())) return InteractionResult.PASS;
			rightClick(Minecraft.getInstance());
			return InteractionResult.FAIL;
		});
	}

	private static Item cachedItem;
	private static String cachedId;

	public static Item toolItem() {
		String id = HoloConfig.get().toolItem;
		if (!id.equals(cachedId)) {
			cachedId = id;
			Item it;
			try {
				it = BuiltInRegistries.ITEM.getValue(Identifier.parse(id));
			} catch (RuntimeException e) {
				it = Items.STICK;
			}
			cachedItem = it == null || it == Items.AIR ? Items.STICK : it;
		}
		return cachedItem;
	}

	public static boolean holding(Minecraft mc) {
		return HoloConfig.get().toolEnabled && mc.player != null && mc.screen == null
				&& mc.player.getMainHandItem().getItem() == toolItem();
	}

	private static boolean keyDown(Minecraft mc, int left, int right) {
		long w = mc.getWindow().handle();
		return GLFW.glfwGetKey(w, left) == GLFW.GLFW_PRESS || GLFW.glfwGetKey(w, right) == GLFW.GLFW_PRESS;
	}

	/** From the scroll hook. Returns true if the tool used the scroll. */
	public static boolean onScroll(Minecraft mc, double amount) {
		if (!holding(mc) || amount == 0) return false;
		int dir = amount > 0 ? 1 : -1;
		if (keyDown(mc, GLFW.GLFW_KEY_LEFT_CONTROL, GLFW.GLFW_KEY_RIGHT_CONTROL)) {
			Mode[] all = Mode.values();
			mode = all[Math.floorMod(mode.ordinal() - dir, all.length)];
			return true;
		}
		if (keyDown(mc, GLFW.GLFW_KEY_LEFT_ALT, GLFW.GLFW_KEY_RIGHT_ALT)) {
			scrollAction(mc, dir);
			return true;
		}
		return false;
	}

	private static Placement needBuild() {
		Placement p = PlacementManager.selected();
		if (p == null) Toasts.info("No build selected — place one from the menu (H)");
		return p;
	}

	private static BlockPos aimed(Minecraft mc) {
		if (mc.hitResult instanceof BlockHitResult bhr && mc.hitResult.getType() == HitResult.Type.BLOCK) return bhr.getBlockPos().immutable();
		return null;
	}

	private static void leftClick(Minecraft mc) {
		switch (mode) {
			case MOVE -> {
				Placement p = needBuild();
				if (p == null) return;
				p.moveTo(mc.player.blockPosition());
				PlacementManager.save();
			}
			case SELECT -> {
				BlockPos b = aimed(mc);
				Selection.corner1 = b != null ? b : mc.player.blockPosition();
				Toasts.info("Corner 1: " + HoloPrintClient.fmt(Selection.corner1));
			}
			case ROTATE -> {
				Placement p = needBuild();
				if (p == null) return;
				p.cycleMirror();
				PlacementManager.save();
				Toasts.info("Mirror: " + Placement.mirrorName(p.mirror));
			}
			case LAYERS -> layer(-1);
			case PASTE -> {
				if (Paster.active()) Paster.stop(false);
				else Toasts.info("Nothing is being pasted");
			}
			case PICK -> {
				int n = PlacementManager.all().size();
				if (n == 0) {
					Toasts.info("No builds placed yet");
					return;
				}
				PlacementManager.select((PlacementManager.selectedIndex() + 1) % n);
				Toasts.info("Selected " + PlacementManager.selected().name());
			}
		}
	}

	private static void rightClick(Minecraft mc) {
		long now = System.currentTimeMillis();
		if (now - lastRightClick < 220) return; // holding the button repeats; ignore repeats
		lastRightClick = now;
		switch (mode) {
			case MOVE -> {
				Placement p = needBuild();
				if (p == null) return;
				if (mc.hitResult instanceof BlockHitResult bhr && mc.hitResult.getType() == HitResult.Type.BLOCK) {
					p.moveTo(bhr.getBlockPos().relative(bhr.getDirection()));
					PlacementManager.save();
				} else {
					Toasts.info("Aim at a block to move the build there");
				}
			}
			case SELECT -> {
				BlockPos b = aimed(mc);
				Selection.corner2 = b != null ? b : mc.player.blockPosition();
				Toasts.info("Corner 2: " + HoloPrintClient.fmt(Selection.corner2));
			}
			case ROTATE -> {
				Placement p = needBuild();
				if (p == null) return;
				p.rotateClockwise();
				PlacementManager.save();
			}
			case LAYERS -> layer(1);
			case PASTE -> {
				Placement p = needBuild();
				if (p == null) return;
				if (Paster.active()) {
					Toasts.info("Already pasting — left-click to stop");
				} else if (now < pasteArmedUntil) {
					pasteArmedUntil = 0;
					String err = Paster.start(p, true);
					if (err != null) Toasts.error(err);
				} else {
					pasteArmedUntil = now + 4000;
					int left = p.count - p.statCorrect;
					Toasts.info("Right-click again to paste " + p.name() + " (~" + left + " blocks)");
				}
			}
			case PICK -> {
				int idx = buildInSight(mc);
				if (idx < 0) {
					Toasts.info("Look at a build to select it");
					return;
				}
				PlacementManager.select(idx);
				Toasts.info("Selected " + PlacementManager.selected().name());
			}
		}
	}

	private static void scrollAction(Minecraft mc, int dir) {
		switch (mode) {
			case MOVE -> {
				Placement p = needBuild();
				if (p == null) return;
				Vec3 look = mc.player.getLookAngle();
				double ax = Math.abs(look.x), ay = Math.abs(look.y), az = Math.abs(look.z);
				int dx = 0, dy = 0, dz = 0;
				if (ay >= ax && ay >= az) dy = look.y > 0 ? dir : -dir;
				else if (ax >= az) dx = look.x > 0 ? dir : -dir;
				else dz = look.z > 0 ? dir : -dir;
				p.move(dx, dy, dz);
				PlacementManager.save();
			}
			case ROTATE -> {
				Placement p = needBuild();
				if (p == null) return;
				if (dir > 0) p.rotateClockwise();
				else for (int i = 0; i < 3; i++) p.rotateClockwise();
				PlacementManager.save();
			}
			case LAYERS -> layer(dir);
			default -> mode = Mode.values()[Math.floorMod(mode.ordinal() - dir, Mode.values().length)];
		}
	}

	private static void layer(int dir) {
		Placement p = needBuild();
		if (p == null) return;
		if (p.layerMode == 0) {
			p.layerMode = 1;
			p.layer = dir > 0 ? 0 : p.schematic.sizeY - 1;
		} else {
			p.layer = Math.max(0, Math.min(p.schematic.sizeY - 1, p.layer + dir));
		}
		PlacementManager.save();
		Toasts.info("Layer " + (p.layer + 1) + " / " + p.schematic.sizeY);
	}

	/** Index of the first build whose box your view ray hits (within 128 blocks), or -1. */
	private static int buildInSight(Minecraft mc) {
		Vec3 eye = mc.player.getEyePosition();
		Vec3 d = mc.player.getLookAngle();
		double best = Double.MAX_VALUE;
		int bestIdx = -1;
		var all = PlacementManager.all();
		for (int i = 0; i < all.size(); i++) {
			Placement p = all.get(i);
			double t = rayBox(eye, d, p.minX, p.minY, p.minZ, p.maxX, p.maxY, p.maxZ);
			if (t >= 0 && t < best && t < 128) {
				best = t;
				bestIdx = i;
			}
		}
		return bestIdx;
	}

	private static double rayBox(Vec3 o, Vec3 d, double x0, double y0, double z0, double x1, double y1, double z1) {
		double tmin = -Double.MAX_VALUE, tmax = Double.MAX_VALUE;
		double[] os = {o.x, o.y, o.z}, ds = {d.x, d.y, d.z}, mins = {x0, y0, z0}, maxs = {x1, y1, z1};
		for (int a = 0; a < 3; a++) {
			if (Math.abs(ds[a]) < 1e-9) {
				if (os[a] < mins[a] || os[a] > maxs[a]) return -1;
				continue;
			}
			double t1 = (mins[a] - os[a]) / ds[a], t2 = (maxs[a] - os[a]) / ds[a];
			tmin = Math.max(tmin, Math.min(t1, t2));
			tmax = Math.min(tmax, Math.max(t1, t2));
		}
		if (tmax < 0 || tmin > tmax) return -1;
		return Math.max(0, tmin);
	}
}
