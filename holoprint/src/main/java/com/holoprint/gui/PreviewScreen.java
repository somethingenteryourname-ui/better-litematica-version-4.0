package com.holoprint.gui;

import java.util.ArrayList;
import java.util.List;

import org.lwjgl.glfw.GLFW;

import com.holoprint.config.HoloConfig;
import com.holoprint.placement.Placement;
import com.holoprint.placement.PlacementManager;
import com.holoprint.schematic.Schematic;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * Full-screen look at a schematic with real block textures. Drag to move,
 * scroll to zoom, turn it around, and slide the layer to see inside.
 */
public final class PreviewScreen extends Screen {
	private record Hit(int x, int y, int w, int h, Runnable action) {
		boolean contains(double mx, double my) {
			return mx >= x && my >= y && mx < x + w && my < y + h;
		}
	}

	private final Screen parent;
	private final IsoView view;
	private final Schematic schematic;
	private final List<Hit> hits = new ArrayList<>();
	private int rot;
	private int maxY;
	private float zoom = 1f, panX, panY;
	private boolean dragging;
	private int vx, vy, vw, vh;

	public PreviewScreen(Screen parent, IsoView view) {
		super(Component.literal("HoloPrint preview"));
		this.parent = parent;
		this.view = view;
		this.schematic = view.schematic;
		this.maxY = schematic.sizeY - 1;
	}

	@Override
	protected void init() {
		vx = 10;
		vy = 30;
		vw = width - 20;
		vh = height - 62;
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void onClose() {
		Minecraft.getInstance().setScreen(parent);
	}

	@Override
	public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float delta) {
		g.fillGradient(0, 0, width, height, 0xF005070C, Ui.a(Ui.lerp(0x05070C, Ui.accent2(), 0.12f), 0xF0));
		int acc = Ui.accent();
		for (int x = 0; x < width; x += 24) g.fill(x, 0, x + 1, height, Ui.a(acc, 0x0A));
		for (int y = 0; y < height; y += 24) g.fill(0, y, width, y + 1, Ui.a(acc, 0x0A));
	}

	@Override
	public void render(GuiGraphics g, int mx, int my, float delta) {
		hits.clear();
		Ui.brackets(g, vx, vy, vw, vh, Ui.a(Ui.accent(), 0xC0), 10);
		view.render(g, vx, vy, vw, vh, rot, maxY, zoom, panX, panY);

		// title
		g.drawString(font, font.plainSubstrByWidth(schematic.name, width / 2), 12, 10, 0xFFFFFFFF, false);
		String info = schematic.sizeX + " × " + schematic.sizeY + " × " + schematic.sizeZ + "  ·  by " + schematic.author;
		g.drawString(font, info, 12 + Math.min(width / 2, font.width(schematic.name)) + 10, 10, Ui.MUTED, false);
		String help = "drag = move  ·  scroll = zoom  ·  R = turn  ·  ↑↓ = layers  ·  Esc = back";
		g.drawString(font, help, width - 12 - font.width(help), 10, Ui.DIM, false);

		// bottom bar
		int by = height - 26;
		int x = 10;
		x = button(g, mx, my, x, by, 22, "↺", 0, () -> rot = (rot + 3) & 3) + 4;
		x = button(g, mx, my, x, by, 22, "↻", 0, () -> rot = (rot + 1) & 3) + 10;
		g.drawString(font, "LAYER", x, by + 5, Ui.DIM, false);
		x += font.width("LAYER") + 6;
		x = button(g, mx, my, x, by, 18, "-", 0, () -> maxY = Math.max(0, maxY - 1)) + 4;
		String layer = (maxY + 1) + " / " + schematic.sizeY;
		g.drawString(font, layer, x + 2, by + 5, Ui.a(Ui.accent(), 0xFF), false);
		x += font.width(layer) + 8;
		x = button(g, mx, my, x, by, 18, "+", 0, () -> maxY = Math.min(schematic.sizeY - 1, maxY + 1)) + 4;
		x = button(g, mx, my, x, by, 30, "All", 0, () -> maxY = schematic.sizeY - 1) + 10;
		// layer slider
		int sw = Math.max(60, Math.min(220, width - x - 260));
		g.fill(x, by + 8, x + sw, by + 10, Ui.a(0x1C2333, 0xFF));
		int knob = x + (int) (sw * (schematic.sizeY <= 1 ? 1f : maxY / (float) (schematic.sizeY - 1)));
		g.fill(x, by + 8, knob, by + 10, Ui.a(Ui.accent(), 0xFF));
		g.fill(knob - 2, by + 4, knob + 2, by + 14, 0xFFFFFFFF);
		final int sx0 = x, sww = sw;
		hits.add(new Hit(x - 3, by, sw + 6, 18, () -> {
			double rel = (lastMouseX - sx0) / (double) sww;
			maxY = (int) Math.round(Math.max(0, Math.min(1, rel)) * (schematic.sizeY - 1));
		}));
		x += sw + 12;
		x = button(g, mx, my, x, by, 70, "Reset view", 0, () -> {
			zoom = 1f;
			panX = panY = 0;
		}) + 4;

		boolean inWorld = Minecraft.getInstance().level != null;
		int bw = 120;
		if (inWorld) {
			button(g, mx, my, width - 10 - bw, by, bw, "Place hologram", 1, () -> {
				Placement p = PlacementManager.place(schematic);
				Toasts.success("Placed " + p.name());
				Minecraft.getInstance().setScreen(null);
			});
		}
		button(g, mx, my, width - 10 - bw - (inWorld ? 64 : 0), by, 60, "Back", 0, this::onClose);

		if (view.visibleCount() > 40_000) {
			g.drawString(font, "Very big build: only the closest 40k blocks are drawn", vx + 6, vy + vh - 12, Ui.DIM, false);
		}
		Toasts.render(g, width);
	}

	private double lastMouseX;

	private int button(GuiGraphics g, int mx, int my, int x, int y, int w, String label, int style, Runnable action) {
		boolean hover = mx >= x && my >= y && mx < x + w && my < y + 18;
		Ui.button(g, font, x, y, w, 18, label, hover, style, true);
		hits.add(new Hit(x, y, w, 18, action));
		return x + w;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		double mx = event.x(), my = event.y();
		lastMouseX = mx;
		if (event.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
			for (int i = hits.size() - 1; i >= 0; i--) {
				if (hits.get(i).contains(mx, my)) {
					hits.get(i).action().run();
					return true;
				}
			}
			if (mx >= vx && my >= vy && mx < vx + vw && my < vy + vh) {
				dragging = true;
				return true;
			}
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
		if (dragging) {
			panX += (float) dx;
			panY += (float) dy;
			return true;
		}
		return super.mouseDragged(event, dx, dy);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		dragging = false;
		return super.mouseReleased(event);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		if (scrollY == 0) return false;
		float old = zoom;
		zoom = (float) Math.max(0.3f, Math.min(40f, zoom * (scrollY > 0 ? 1.18 : 1 / 1.18)));
		// zoom toward the mouse
		float cx = vx + vw / 2f + panX, cy = vy + vh / 2f + panY;
		float f = zoom / old;
		panX += (float) ((mouseX - cx) * (1 - f));
		panY += (float) ((mouseY - cy) * (1 - f));
		return true;
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		int k = event.key();
		if (k == GLFW.GLFW_KEY_R) {
			rot = (rot + 1) & 3;
			return true;
		}
		if (k == GLFW.GLFW_KEY_UP) {
			maxY = Math.min(schematic.sizeY - 1, maxY + 1);
			return true;
		}
		if (k == GLFW.GLFW_KEY_DOWN) {
			maxY = Math.max(0, maxY - 1);
			return true;
		}
		return super.keyPressed(event);
	}

	static int defaultAccent() {
		return HoloConfig.get().accentColor;
	}
}
