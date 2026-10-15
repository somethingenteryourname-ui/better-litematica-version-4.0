package com.holoprint.schematic;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import com.holoprint.config.HoloConfig;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/** Lists schematic files on disk and builds small top-down previews of them. */
public final class LocalLibrary {
	private LocalLibrary() {}

	public record Entry(Path path, String name, String ext, String folder, long size, long modified) {}

	/** A top-down colour map plus some stats, made in the background. */
	public record Preview(Schematic schematic, int w, int h, int[] colors, int blocks, List<Map.Entry<String, Integer>> topBlocks, String error,
						  com.holoprint.gui.IsoView iso) {}


	private static final Map<Path, CompletableFuture<Preview>> previews = new ConcurrentHashMap<>();

	public static CompletableFuture<List<Entry>> scan() {
		return CompletableFuture.supplyAsync(() -> {
			Path root = HoloConfig.schematicsDir();
			List<Entry> out = new ArrayList<>();
			try {
				Files.createDirectories(root);
				try (Stream<Path> s = Files.walk(root, 6)) {
					s.filter(Files::isRegularFile).filter(SchematicLoader::isSupported).forEach(p -> {
						try {
							String fn = p.getFileName().toString();
							int dot = fn.lastIndexOf('.');
							Path parent = root.relativize(p).getParent();
							out.add(new Entry(p, dot > 0 ? fn.substring(0, dot) : fn,
									dot > 0 ? fn.substring(dot + 1).toLowerCase(Locale.ROOT) : "",
									parent == null ? "" : parent.toString().replace('\\', '/'),
									Files.size(p), Files.getLastModifiedTime(p).toMillis()));
						} catch (Exception ignored) {
						}
					});
				}
			} catch (Exception e) {
				System.err.println("[HoloPrint] Couldn't scan schematics folder: " + e.getMessage());
			}
			out.sort(Comparator.comparingLong(Entry::modified).reversed());
			return out;
		});
	}

	public static CompletableFuture<Preview> preview(Path file) {
		return previews.computeIfAbsent(file, f -> CompletableFuture.supplyAsync(() -> buildPreview(f)));
	}

	public static void forget(Path file) {
		previews.remove(file);
	}

	private static Preview buildPreview(Path file) {
		Schematic s;
		try {
			s = SchematicLoader.load(file);
		} catch (Exception e) {
			return new Preview(null, 0, 0, new int[0], 0, List.of(), e.getMessage() == null ? e.toString() : e.getMessage(), null);
		}
		int[] paletteColor = new int[s.palette.length];
		Map<String, Integer> blockCounts = new HashMap<>();
		BlockState[] states = new BlockState[s.palette.length];
		for (int i = 0; i < s.palette.length; i++) {
			states[i] = BlockStates.parse(s.palette[i]);
			int col = 0;
			try {
				col = states[i].getMapColor(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).col;
			} catch (RuntimeException ignored) {
			}
			paletteColor[i] = col == 0 ? 0x7F8C99 : col;
		}
		// downsample big builds so the preview stays small
		int step = Math.max(1, (int) Math.ceil(Math.max(s.sizeX, s.sizeZ) / 128.0));
		int w = (s.sizeX + step - 1) / step, h = (s.sizeZ + step - 1) / step;
		int[] colors = new int[w * h];
		int[] heights = new int[w * h];
		int blocks = 0;
		int[] perPalette = new int[s.palette.length];
		for (int y = 0; y < s.sizeY; y++) {
			for (int z = 0; z < s.sizeZ; z++) {
				for (int x = 0; x < s.sizeX; x++) {
					int p = s.cells[s.index(x, y, z)];
					if (s.paletteIsAir[p] || states[p].isAir()) continue;
					blocks++;
					perPalette[p]++;
					int ci = (z / step) * w + (x / step);
					if (y + 1 >= heights[ci]) {
						heights[ci] = y + 1;
						colors[ci] = paletteColor[p];
					}
				}
			}
		}
		// simple height shading so shapes read better
		for (int i = 0; i < colors.length; i++) {
			if (heights[i] == 0) {
				colors[i] = 0;
				continue;
			}
			float shade = 0.55f + 0.45f * heights[i] / (float) Math.max(1, s.sizeY);
			int c = colors[i];
			int r = Math.min(255, (int) (((c >> 16) & 0xFF) * shade));
			int g = Math.min(255, (int) (((c >> 8) & 0xFF) * shade));
			int b = Math.min(255, (int) ((c & 0xFF) * shade));
			colors[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
		}
		for (int i = 0; i < perPalette.length; i++) {
			if (perPalette[i] == 0) continue;
			Block block = states[i].getBlock();
			String name = block.getName().getString();
			blockCounts.merge(name, perPalette[i], Integer::sum);
		}
		List<Map.Entry<String, Integer>> top = new ArrayList<>(blockCounts.entrySet());
		top.sort(Map.Entry.<String, Integer>comparingByValue().reversed());
		if (top.size() > 8) top = new ArrayList<>(top.subList(0, 8));
		return new Preview(s, w, h, colors, blocks, top, null, com.holoprint.gui.IsoView.of(s));
	}

}
