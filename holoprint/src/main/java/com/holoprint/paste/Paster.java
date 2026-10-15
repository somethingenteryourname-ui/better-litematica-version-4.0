package com.holoprint.paste;

import java.util.ArrayList;
import java.util.List;

import com.holoprint.config.HoloConfig;
import com.holoprint.gui.Toasts;
import com.holoprint.placement.Placement;
import com.holoprint.placement.Selection;

import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Builds a placement into the world, a little every tick (like Litematica's paste).
 *
 * In singleplayer the blocks are set directly in your world. On a server it
 * sends /setblock and /fill commands, so you need permission to use those
 * (operator / creative-building servers). Solid blocks go first, then things
 * that hang on them (torches, ladders, redstone…), bottom layer upward, so
 * they don't pop off.
 */
public final class Paster {
	private Paster() {}

	private static Placement target;
	private static int[] queue;
	private static int cursor, total, skipped;
	private static boolean viaCommands;

	public static boolean active() {
		return target != null;
	}

	public static float progress() {
		return total == 0 ? 1f : cursor / (float) total;
	}

	public static String targetName() {
		return target == null ? "" : target.name();
	}

	/** @param replaceWrong also overwrite blocks that are wrong (not just fill empty spots) */
	public static String start(Placement p, boolean replaceWrong) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || mc.player == null) return "Join a world first";
		if (p == null) return "Select a build first";
		if (active()) return "Already pasting " + target.name() + " (use /holoprint paste stop)";

		List<Integer> solid = new ArrayList<>(), attached = new ArrayList<>();
		for (int i = 0; i < p.count; i++) {
			byte st = p.status[i];
			if (st == Placement.CORRECT) continue;
			if (!replaceWrong && (st == Placement.WRONG_BLOCK || st == Placement.WRONG_STATE)) continue;
			boolean occ;
			try {
				occ = p.state[i].canOcclude();
			} catch (RuntimeException e) {
				occ = true;
			}
			(occ ? solid : attached).add(i);
		}
		// placement arrays are already ordered bottom layer first
		int[] q = new int[solid.size() + attached.size()];
		int k = 0;
		for (int i : solid) q[k++] = i;
		for (int i : attached) q[k++] = i;
		if (q.length == 0) return "Nothing to paste — the build is already complete";

		target = p;
		queue = q;
		cursor = 0;
		total = q.length;
		skipped = 0;
		viaCommands = mc.getSingleplayerServer() == null;
		Toasts.info("Pasting " + q.length + " blocks" + (viaCommands ? " with commands (needs permission)" : ""));
		return null;
	}

	public static void stop(boolean quiet) {
		if (target != null && !quiet) Toasts.info("Paste stopped at " + Math.round(progress() * 100) + "%");
		target = null;
		queue = null;
	}

	/** Called every client tick. */
	public static void tick(Minecraft mc) {
		if (target == null) return;
		if (mc.level == null || mc.player == null) {
			stop(true);
			return;
		}
		HoloConfig cfg = HoloConfig.get();
		if (viaCommands) tickCommands(mc, cfg.pasteCommandsPerTick);
		else tickDirect(mc, cfg.pasteBlocksPerTick);
		if (target != null && cursor >= total) {
			Toasts.success("Paste finished" + (skipped > 0 ? " (" + skipped + " blocks were too far away — move closer and paste again)" : ""));
			stop(true);
		}
	}

	/** Singleplayer: set blocks straight in the world on the server thread. */
	private static void tickDirect(Minecraft mc, int perTick) {
		IntegratedServer server = mc.getSingleplayerServer();
		if (server == null) {
			viaCommands = true;
			return;
		}
		List<BlockPos> positions = new ArrayList<>();
		List<BlockState> states = new ArrayList<>();
		Placement p = target;
		while (cursor < total && positions.size() < perTick) {
			int i = queue[cursor++];
			BlockPos pos = new BlockPos(p.wx[i], p.wy[i], p.wz[i]);
			if (!mc.level.hasChunkAt(pos)) {
				skipped++;
				continue;
			}
			if (mc.level.getBlockState(pos) == p.state[i]) continue; // already right
			positions.add(pos);
			states.add(p.state[i]);
		}
		if (positions.isEmpty()) return;
		var dim = mc.level.dimension();
		server.execute(() -> {
			ServerLevel level = server.getLevel(dim);
			if (level == null) return;
			for (int j = 0; j < positions.size(); j++) {
				// 2 = tell clients, 16 = don't make neighbours react (keeps torches etc. from popping)
				level.setBlock(positions.get(j), states.get(j), 2 | 16);
			}
		});
	}

	/** Servers: /setblock, merged into /fill for runs of the same block in a row. */
	private static void tickCommands(Minecraft mc, int perTick) {
		Placement p = target;
		int sent = 0;
		while (cursor < total && sent < perTick) {
			int i = queue[cursor++];
			BlockPos pos = new BlockPos(p.wx[i], p.wy[i], p.wz[i]);
			if (!mc.level.hasChunkAt(pos)) {
				skipped++;
				continue;
			}
			BlockState st = p.state[i];
			if (mc.level.getBlockState(pos) == st) continue; // already right, don't spam the server
			// extend a straight run of identical blocks
			int endX = p.wx[i], endZ = p.wz[i];
			int dx = 0, dz = 0, run = 1;
			while (cursor < total && run < 64) {
				int j = queue[cursor];
				if (p.state[j] != st || p.wy[j] != p.wy[i]) break;
				if (!mc.level.hasChunkAt(new BlockPos(p.wx[j], p.wy[j], p.wz[j]))) break;
				int sx = p.wx[j] - endX, sz = p.wz[j] - endZ;
				boolean step = Math.abs(sx) + Math.abs(sz) == 1;
				if (!step || (run > 1 && (sx != dx || sz != dz))) break;
				dx = sx;
				dz = sz;
				endX = p.wx[j];
				endZ = p.wz[j];
				cursor++;
				run++;
			}
			String block = Selection.stateToString(st);
			String cmd = run == 1
					? "setblock " + pos.getX() + " " + pos.getY() + " " + pos.getZ() + " " + block
					: "fill " + pos.getX() + " " + pos.getY() + " " + pos.getZ() + " " + endX + " " + pos.getY() + " " + endZ + " " + block;
			mc.player.connection.sendCommand(cmd);
			sent++;
		}
	}
}
