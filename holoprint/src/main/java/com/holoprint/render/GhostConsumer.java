package com.holoprint.render;

import com.mojang.blaze3d.vertex.VertexConsumer;

/**
 * Sits between Minecraft's block drawing and the real buffer. It
 * <ul>
 *   <li>makes every vertex see-through (and optionally tinted), and</li>
 *   <li>drops block faces that touch another ghost block, so a wall of ghost
 *       blocks looks like one clean surface instead of a stack of layers.</li>
 * </ul>
 * Block models are always made of 4-vertex quads, so we collect four vertices,
 * decide, then pass them on.
 */
final class GhostConsumer implements VertexConsumer {
	private VertexConsumer parent;
	private int alpha = 150;
	private int tintR = 255, tintG = 255, tintB = 255;

	// the block being drawn, in camera-relative coordinates, and which faces to drop
	private float bx, by, bz, size = 1f;
	private int cullMask;

	// one quad being collected
	private final float[] px = new float[4], py = new float[4], pz = new float[4];
	private final int[] cr = new int[4], cg = new int[4], cb = new int[4], ca = new int[4];
	private final float[] u = new float[4], v = new float[4];
	private final int[] u1 = new int[4], v1 = new int[4], u2 = new int[4], v2 = new int[4];
	private final float[] nx = new float[4], ny = new float[4], nz = new float[4];
	private final boolean[] hasUv1 = new boolean[4];
	private int count = -1; // index of the vertex being filled, -1 = none yet
	private int received;   // vertices Minecraft gave us for the current block

	GhostConsumer wrap(VertexConsumer parent) {
		if (this.parent != parent) {
			discard();
			this.parent = parent;
		}
		return this;
	}

	void setLook(int alpha, int tintRgb) {
		this.alpha = Math.max(0, Math.min(255, alpha));
		this.tintR = (tintRgb >> 16) & 0xFF;
		this.tintG = (tintRgb >> 8) & 0xFF;
		this.tintB = tintRgb & 0xFF;
	}

	/** Called before each block: where it is (min corner, camera-relative) and faces to drop. */
	void beginBlock(float x, float y, float z, float size, int cullMask) {
		discard();
		this.bx = x;
		this.by = y;
		this.bz = z;
		this.size = size;
		this.cullMask = cullMask;
		this.received = 0;
	}

	/** True if the block's normal model drew nothing (chests, signs, water…). */
	boolean blockWasEmpty() {
		return received == 0;
	}

	/** Called after each block; a half-finished quad (shouldn't happen) is dropped. */
	void endBlock() {
		if (count == 3) emitIfVisible();
		discard();
	}

	private void discard() {
		count = -1;
	}

	// ------------------------------------------------------------------ VertexConsumer

	public VertexConsumer addVertex(float x, float y, float z) {
		received++;
		if (count == 3) emitIfVisible();
		if (count == 3) count = -1;
		count++;
		px[count] = x;
		py[count] = y;
		pz[count] = z;
		cr[count] = cg[count] = cb[count] = ca[count] = 255;
		u[count] = v[count] = 0;
		u2[count] = v2[count] = 0;
		nx[count] = ny[count] = nz[count] = 0;
		hasUv1[count] = false;
		return this;
	}

	public VertexConsumer setColor(int r, int g, int b, int a) {
		if (count < 0) return this;
		cr[count] = r * tintR / 255;
		cg[count] = g * tintG / 255;
		cb[count] = b * tintB / 255;
		ca[count] = a * alpha / 255;
		return this;
	}

	public VertexConsumer setColor(int argb) {
		return setColor((argb >> 16) & 0xFF, (argb >> 8) & 0xFF, argb & 0xFF, (argb >>> 24) & 0xFF);
	}

	public VertexConsumer setUv(float uu, float vv) {
		if (count < 0) return this;
		u[count] = uu;
		v[count] = vv;
		return this;
	}

	public VertexConsumer setUv1(int uu, int vv) {
		if (count < 0) return this;
		u1[count] = uu;
		v1[count] = vv;
		hasUv1[count] = true;
		return this;
	}

	public VertexConsumer setUv2(int uu, int vv) {
		if (count < 0) return this;
		u2[count] = uu;
		v2[count] = vv;
		return this;
	}

	public VertexConsumer setNormal(float x, float y, float z) {
		if (count < 0) return this;
		nx[count] = x;
		ny[count] = y;
		nz[count] = z;
		return this;
	}

	public VertexConsumer setLineWidth(float width) {
		return this; // block quads never use this
	}

	// ------------------------------------------------------------------ culling

	private void emitIfVisible() {
		if (cullMask != 0) {
			int face = boundaryFace();
			if (face >= 0 && (cullMask & (1 << face)) != 0) return;
		}
		for (int i = 0; i < 4; i++) {
			VertexConsumer c = parent.addVertex(px[i], py[i], pz[i]).setColor(cr[i], cg[i], cb[i], ca[i]).setUv(u[i], v[i]);
			if (hasUv1[i]) c.setUv1(u1[i], v1[i]);
			c.setUv2(u2[i], v2[i]).setNormal(nx[i], ny[i], nz[i]);
		}
	}

	/**
	 * Which side of the block this quad lies flat on (0 down,1 up,2 north,3 south,4 west,5 east),
	 * or -1 if it's an inner face (like the step of a stair), which we always keep.
	 */
	private int boundaryFace() {
		final float eps = 0.002f * Math.max(1f, size);
		if (same(px, bx, eps)) return 4;
		if (same(px, bx + size, eps)) return 5;
		if (same(py, by, eps)) return 0;
		if (same(py, by + size, eps)) return 1;
		if (same(pz, bz, eps)) return 2;
		if (same(pz, bz + size, eps)) return 3;
		return -1;
	}

	private static boolean same(float[] a, float val, float eps) {
		return Math.abs(a[0] - val) < eps && Math.abs(a[1] - val) < eps && Math.abs(a[2] - val) < eps && Math.abs(a[3] - val) < eps;
	}
}
