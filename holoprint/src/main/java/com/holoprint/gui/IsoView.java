package com.holoprint.gui;

import java.util.Arrays;

import com.holoprint.schematic.BlockStates;
import com.holoprint.schematic.Schematic;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.state.BlockState;

/**
 * An isometric picture of a schematic made from the game's own block icons,
 * so you see real textures (oak planks look like oak planks). The icons are
 * already drawn at the classic isometric angle, so stacking them back-to-front
 * on an isometric grid gives a proper 3D-looking build.
 *
 * Only blocks you could actually see from the current side are drawn.
 */
public final class IsoView {
	private static final float SIDE = 10f;        // cube edge, in icon pixels (block icons are 0.625 x 16)
	private static final float COS30 = 0.8660254f, SIN30 = 0.5f;
	private static final int MAX_ICONS = 40_000;

	public final Schematic schematic;
	private final ItemStack[] icon;      // per palette entry, null = no item (water, fire…)
	private final int[] flatColor;       // fallback colour for blocks without an item
	private final boolean[] solid;       // hides what's behind it
	private final boolean[] air;

	// cache of the draw order for the current view
	private int cachedRot = -1, cachedMaxY = -1;
	private int[] order = new int[0];
	private int orderCount;

	private IsoView(Schematic s) {
		this.schematic = s;
		int n = s.palette.length;
		icon = new ItemStack[n];
		flatColor = new int[n];
		solid = new boolean[n];
		air = new boolean[n];
		for (int i = 0; i < n; i++) {
			BlockState st = BlockStates.parse(s.palette[i]);
			air[i] = s.paletteIsAir[i] || st.isAir();
			if (air[i]) continue;
			Item item = st.getBlock().asItem();
			icon[i] = item == Items.AIR ? null : new ItemStack(item);
			boolean occ;
			try {
				occ = st.canOcclude();
			} catch (RuntimeException e) {
				occ = true;
			}
			solid[i] = occ;
			int col = 0x7F8C99;
			try {
				int c = st.getMapColor(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).col;
				if (c != 0) col = c;
			} catch (RuntimeException ignored) {
			}
			flatColor[i] = col;
		}
		prepare(0, s.sizeY - 1); // work out the default view now (we're usually off the game thread here)
	}

	/** Safe to call off the game thread. */
	public static IsoView of(Schematic s) {
		return new IsoView(s);
	}

	private static float angle(int rot) {
		return (float) Math.toRadians(225 + 90 * (rot & 3));
	}

	/** Works out which blocks are visible from this side, back to front. */
	private void prepare(int rot, int maxY) {
		if (rot == cachedRot && maxY == cachedMaxY) return;
		cachedRot = rot;
		cachedMaxY = maxY;
		Schematic s = schematic;
		float a = angle(rot);
		float c = (float) Math.cos(a), sn = (float) Math.sin(a);
		// which horizontal neighbours face the viewer
		int fx = -sn > 0 ? 1 : -1;
		int fz = c > 0 ? 1 : -1;
		float hx = s.sizeX / 2f, hy = s.sizeY / 2f, hz = s.sizeZ / 2f;

		long[] keys = new long[Math.min(s.volume(), 1 << 22)];
		int k = 0;
		for (int y = 0; y < s.sizeY && y <= maxY; y++) {
			for (int z = 0; z < s.sizeZ; z++) {
				for (int x = 0; x < s.sizeX; x++) {
					int idx = s.index(x, y, z);
					int p = s.cells[idx];
					if (air[p]) continue;
					boolean visible = y == maxY || y == s.sizeY - 1 || !isSolid(x, y + 1, z, maxY)
							|| !isSolid(x + fx, y, z, maxY) || !isSolid(x, y, z + fz, maxY);
					if (!visible) continue;
					float cx = x + 0.5f - hx, cy = y + 0.5f - hy, cz = z + 0.5f - hz;
					float zp = -cx * sn + cz * c;
					float depth = cy * SIN30 + zp * COS30;
					int q = (int) ((depth + 100_000f) * 64f);
					if (k == keys.length) keys = Arrays.copyOf(keys, Math.min(Integer.MAX_VALUE - 8, keys.length * 2 + 16));
					keys[k++] = ((long) q << 32) | (idx & 0xFFFFFFFFL);
				}
			}
		}
		Arrays.sort(keys, 0, k);
		if (order.length < k) order = new int[k];
		for (int i = 0; i < k; i++) order[i] = (int) (keys[i] & 0xFFFFFFFFL);
		orderCount = k;
	}

	private boolean isSolid(int x, int y, int z, int maxY) {
		Schematic s = schematic;
		if (x < 0 || y < 0 || z < 0 || x >= s.sizeX || y >= s.sizeY || z >= s.sizeZ || y > maxY) return false;
		int p = s.cells[s.index(x, y, z)];
		return !air[p] && solid[p];
	}

	public int visibleCount() {
		return orderCount;
	}

	/**
	 * Draws the build inside the given box.
	 *
	 * @param rot   which side you look from (0-3)
	 * @param maxY  highest layer shown (sizeY-1 = everything)
	 * @param zoom  1 = fit the box, bigger = closer
	 * @param panX  extra offset in screen pixels
	 */
	public void render(GuiGraphics g, int x, int y, int w, int h, int rot, int maxY, float zoom, float panX, float panY) {
		Schematic s = schematic;
		maxY = Math.max(0, Math.min(s.sizeY - 1, maxY));
		prepare(rot, maxY);
		float a = angle(rot);
		float c = (float) Math.cos(a), sn = (float) Math.sin(a);
		float hx = s.sizeX / 2f, hy = s.sizeY / 2f, hz = s.sizeZ / 2f;

		// fit: project the 8 corners of the box
		float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, minY = Float.MAX_VALUE, maxYp = -Float.MAX_VALUE;
		for (int i = 0; i < 8; i++) {
			float cx = ((i & 1) == 0 ? -hx : hx), cy = ((i & 2) == 0 ? -hy : hy), cz = ((i & 4) == 0 ? -hz : hz);
			float xp = cx * c + cz * sn, zp = -cx * sn + cz * c;
			float sx = xp * SIDE, sy = (-cy * COS30 + zp * SIN30) * SIDE;
			minX = Math.min(minX, sx); maxX = Math.max(maxX, sx);
			minY = Math.min(minY, sy); maxYp = Math.max(maxYp, sy);
		}
		float fit = Math.min((w - 10) / Math.max(1f, maxX - minX + 12), (h - 10) / Math.max(1f, maxYp - minY + 12));
		float q = fit * zoom; // icon scale (1 = normal 16px icon)
		float ox = x + w / 2f - (minX + maxX) / 2f * q + panX;
		float oy = y + h / 2f - (minY + maxYp) / 2f * q + panY;

		g.enableScissor(x, y, x + w, y + h);
		int start = Math.max(0, orderCount - MAX_ICONS);
		for (int k = start; k < orderCount; k++) {
			int idx = order[k];
			int bx = idx % s.sizeX;
			int bz = (idx / s.sizeX) % s.sizeZ;
			int by = idx / (s.sizeX * s.sizeZ);
			int p = s.cells[idx];
			float cx = bx + 0.5f - hx, cy = by + 0.5f - hy, cz = bz + 0.5f - hz;
			float xp = cx * c + cz * sn, zp = -cx * sn + cz * c;
			float sx = ox + xp * SIDE * q;
			float sy = oy + (-cy * COS30 + zp * SIN30) * SIDE * q;
			if (sx < x - 16 * q || sx > x + w + 16 * q || sy < y - 16 * q || sy > y + h + 16 * q) continue;
			g.pose().pushMatrix();
			g.pose().translate(sx, sy);
			g.pose().scale(q);
			if (icon[p] != null) {
				g.renderItem(icon[p], -8, -8);
			} else {
				// no item (water, lava, fire…): a flat tinted top
				g.fill(-7, -4, 7, 4, 0xA0000000 | flatColor[p]);
			}
			g.pose().popMatrix();
		}
		g.disableScissor();
	}
}
