/* Random and broken VIDEOX operands and records straight into the VM core's check, move, decode and compose
 * (build with -fsanitize=address,undefined): any input either is refused by the check with nothing written, or
 * decodes with every write inside the arena (the arena sits in a larger buffer with guard bytes on both sides). */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "../src/vm/vm_gfx.h"

static uint64_t s = 0x9E3779B97F4A7C15ull;
static uint32_t rnd(void) { s ^= s << 13; s ^= s >> 7; s ^= s << 17; return (uint32_t)s; }
static uint32_t rndn(uint32_t n) { return n ? rnd() % n : 0; }
static void span(void *c, uint32_t y, uint32_t x0, uint32_t x1, const uint8_t *row) { (void)c; (void)y; (void)x0; (void)x1; (void)row; }
static void mark(void *c, uint32_t y) { (void)c; (void)y; }

#define GUARD 4096
int main(int argc, char **argv)
{
	long n = argc > 1 ? atol(argv[1]) : 200000, ok = 0, refused = 0;
	uint8_t *buf = malloc(VM_ARENA_SIZE + 2 * GUARD), *a = buf + GUARD, out[VM_PANEL_W / 2 + 8];
	static struct vm_video vs;

	memset(buf, 0x5A, VM_ARENA_SIZE + 2 * GUARD);
	for (long it = 0; it < n; it++) {
		uint32_t v[10], detail = 0;
		struct vm_videox x;
		int planes = 1 + (int)rndn(4);
		/* mostly plausible layouts, sometimes wild values */
		v[2] = rndn(8) ? 1 + rndn(rndn(4) ? 64 : 640) : rnd();
		v[3] = rndn(8) ? 1 + rndn(rndn(4) ? 48 : 480) : rnd();
		v[1] = rndn(8) ? (v[2] + 7) / 8 + rndn(3) : rnd() % 200;
		/* the tables (plane and state addresses) at v[0] and v[9], their entries mostly plausible */
		v[0] = rndn(16) ? 0x3FE00 + rndn(0x40) : rnd();
		v[9] = rndn(16) ? 0x3FE80 + rndn(0x40) : rnd();
		uint32_t base = rndn(0x400), sbase = 0x20000 + rndn(0x400), psize = v[3] * v[1] + rndn(64);
		int valid = rndn(4) != 0;   /* mostly a valid layout (planes and states apart), else anywhere */
		for (int p = 0; p < 4; p++) {
			uint32_t pa = valid ? base + (uint32_t)p * psize : rndn(8) ? rndn(0x20000) : rnd();
			uint32_t sa = valid ? sbase + (uint32_t)p * (VM_VIDEOX_PIX_STATE + rndn(32)) : rndn(8) ? 0x20000 + rndn(0x8000) : rnd();
			if ((uint64_t)v[0] + 4 * p + 4 <= VM_ARENA_SIZE) memcpy(a + v[0] + 4 * p, &pa, 4);
			if ((uint64_t)v[9] + 4 * p + 4 <= VM_ARENA_SIZE) memcpy(a + v[9] + 4 * p, &sa, 4);
		}
		v[7] = rndn(8) ? 0x30000 + rndn(0x4000) : rnd();
		v[6] = rndn(8) ? 0x3FF00 + rndn(0xF0) : rnd();
		v[4] = rnd() % 1400 - 400;
		v[5] = rnd() % 1000 - 300;
		v[8] = rndn(8) ? 2 + rndn(4000) : rnd();
		if (v[7] < VM_ARENA_SIZE) {
			uint32_t len = v[8] < VM_ARENA_SIZE - v[7] ? v[8] : VM_ARENA_SIZE - v[7];
			for (uint32_t i = 0; i < len; i++) a[v[7] + i] = (uint8_t)rnd();
			if (len >= 2 && rndn(4)) {   /* a well-formed header most of the time */
				uint32_t pos = v[7] + 2, left;
				a[v[7]] = (uint8_t)rndn(16);   /* move, redraw, scale 2, pixel coding */
				a[v[7] + 1] = (uint8_t)planes;
				if (a[v[7]] & 1) pos += 6;
				for (int p = 0; p < planes && pos + 3 <= v[7] + len; p++) {
					left = v[7] + len - pos - 3;
					uint32_t l = p == planes - 1 && rndn(2) ? left : 1 + rndn(left ? left : 1);
					a[pos] = (uint8_t)l; a[pos + 1] = (uint8_t)(l >> 8); a[pos + 2] = (uint8_t)(l >> 16);
					if (pos + 3 < v[7] + len) a[pos + 3] = (uint8_t)rndn(4);
					pos += 3 + l;
				}
			}
		}
		if (v[6] < VM_ARENA_SIZE - 16) for (int i = 0; i < 16; i++) a[v[6] + i] = (uint8_t)rndn(17);
		if (vm_videox_check(a, v, &x, &detail)) {
			refused++;
			continue;
		}
		if (x.flags & 1) vm_videox_move(a, v, &x, mark, NULL);
		for (uint32_t p = x.planes; p-- > 0;) {
			uint32_t pv[10];
			if (x.flags & VM_VX_PIXELS) {
				vm_videox_pixels(a, v, &x, p, span, NULL);
			} else {
				vm_videox_plane(v, &x, p, pv);
				vm_video_decode(a, pv, &vs, span, NULL);
			}
		}
		for (uint32_t y = 0; y < v[3]; y++) {
			uint32_t x0 = rndn(v[2]), x1 = x0 + 1 + rndn(v[2] - x0);
			{ uint8_t pair[256]; vm_videox_pairs(a, v, pair); vm_videox_compose(a, v, &x, pair, y, x0, x1, out); }
			if (x1 - x0 <= VM_PANEL_W / 2) vm_videox_compose2(a, v, &x, y, x0, x1, out);
		}
		ok++;
	}
	for (int i = 0; i < GUARD; i++) {
		if (buf[i] != 0x5A || buf[GUARD + VM_ARENA_SIZE + i] != 0x5A) { printf("FAIL: write outside the arena\n"); return 1; }
	}
	printf("videox_fuzz: %ld cases, %ld decoded, %ld refused, no write outside the arena\n", n, ok, refused);
	free(buf);
	return 0;
}
