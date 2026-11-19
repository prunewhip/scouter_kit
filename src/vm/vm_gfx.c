/* Memory and drawing primitives (vm_gfx.h). The pixel rules are those of g1v0's protocol 4 gfx.c;
 * protocol 5 adds byte addresses, i32 coordinates, clipped destinations and LINE. */
#include <string.h>
#include "vm_gfx.h"

#define ARENA ((int64_t)VM_ARENA_SIZE)

struct clip {
	int64_t x0, x1, y0, y1;
};

struct font {
	uint32_t base, size, n, k;
};

static uint32_t get16(const uint8_t *p)
{
	return (uint32_t)p[0] | (uint32_t)p[1] << 8;
}

static uint32_t get32(const uint8_t *p)
{
	return (uint32_t)p[0] | (uint32_t)p[1] << 8 | (uint32_t)p[2] << 16 | (uint32_t)p[3] << 24;
}

static int32_t s32(uint32_t v)
{
	return v < 0x80000000u ? (int32_t)v : -(int32_t)~v - 1;
}

static int32_t s16(const uint8_t *p)
{
	uint32_t v = get16(p);

	return v < 0x8000 ? (int32_t)v : (int32_t)v - 65536;
}

static int s8(uint8_t b)
{
	return b < 128 ? b : b - 256;
}

static int64_t min64(int64_t a, int64_t b)
{
	return a < b ? a : b;
}

static int64_t max64(int64_t a, int64_t b)
{
	return a > b ? a : b;
}

/* §3.1: destination d, source coverage a, level L */
static uint32_t combine(uint32_t d, uint32_t a, uint32_t mode, uint32_t level)
{
	uint32_t s = (a * level + 7) / 15;

	switch (mode) {
	case 0:
		return s;
	case 1:
		return d > s ? d : s;
	case 2:
		return (d * (15 - a) + level * a + 7) / 15;
	default:
		return (d * (15 - a) + 7) / 15;
	}
}

/* pixel x of a row; x is below 2^21 once the row is known to be in the arena */
static uint32_t get4(const uint8_t *row, int32_t x)
{
	return (x & 1) ? (uint32_t)(row[x / 2] & 15) : (uint32_t)(row[x / 2] >> 4);
}

static void set4(uint8_t *row, int32_t x, uint32_t v)
{
	uint8_t *p = row + x / 2;

	*p = (x & 1) ? (uint8_t)((*p & 0xF0) | v) : (uint8_t)((*p & 0x0F) | v << 4);
}

static uint32_t get1(const uint8_t *row, int32_t x)
{
	return (uint32_t)(row[x / 8] >> (7 - (x & 7))) & 1;
}

int vm_packbits_ok(const uint8_t *in, uint32_t n, uint32_t len)
{
	uint32_t i = 0, o = 0;

	while (i < n) {
		int h = s8(in[i++]);

		if (h >= 0) {
			if (n - i < (uint32_t)h + 1) {
				return 0;
			}
			i += (uint32_t)h + 1;
			o += (uint32_t)h + 1;
		} else if (h != -128) {
			if (i >= n) {
				return 0;
			}
			i++;
			o += (uint32_t)(1 - h);
		}
		if (o > len) {
			return 0;
		}
	}
	return o == len;
}

void vm_packbits_decode(const uint8_t *in, uint32_t n, uint8_t *out)
{
	uint32_t i = 0;

	while (i < n) {
		int h = s8(in[i++]);

		if (h >= 0) {
			memcpy(out, in + i, (size_t)h + 1);
			out += h + 1;
			i += (uint32_t)h + 1;
		} else if (h != -128) {
			memset(out, in[i++], (size_t)(1 - h));
			out += 1 - h;
		}
	}
}

/* The visible part of the rectangle (x, y, w, h) of a 4-bpp destination bitmap: 2·stride pixels wide,
 * rows down to the end of the arena. 0 if nothing is visible. */
static int clip(uint32_t base, uint32_t stride, int64_t x, int64_t y, uint32_t w, uint32_t h, struct clip *c)
{
	int64_t rows;

	if (base >= VM_ARENA_SIZE || stride == 0 || w == 0 || h == 0) {
		return 0;
	}
	rows = (VM_ARENA_SIZE - base - 1) / stride + 1;
	c->x0 = max64(x, 0);
	c->x1 = min64(x + w, 2 * (int64_t)stride);
	c->y0 = max64(y, 0);
	c->y1 = min64(y + h, rows);
	return c->x0 < c->x1 && c->y0 < c->y1;
}

/* end of visible row y: the last row may stop at the end of the arena */
static int64_t row_end(uint32_t base, uint32_t stride, int64_t y, int64_t x1)
{
	return min64(x1, 2 * (ARENA - base - y * (int64_t)stride));
}

/* every pixel of the source rectangle (w, h > 0) lies in its bitmap and in the arena */
static int source_ok(uint32_t base, uint32_t stride, int64_t x, int64_t y, uint32_t w, uint32_t h, int bpp)
{
	int64_t last, width = bpp == 1 ? 8 * (int64_t)stride : 2 * (int64_t)stride;

	if (x < 0 || y < 0 || x + w > width) {
		return 0;
	}
	last = y + h - 1;
	if (last >= ARENA) {
		return 0;
	}
	return base + last * (int64_t)stride + (x + w - 1) * bpp / 8 < ARENA;
}

int vm_fill(uint8_t *a, const uint32_t *v, uint32_t *detail)
{
	uint32_t dst = v[0], stride = v[1], level = v[6];
	struct clip c;

	if (level > 15) {
		*detail = level;
		return VM_E_ARG;
	}
	if (!clip(dst, stride, s32(v[2]), s32(v[3]), v[4], v[5], &c)) {
		return 0;
	}
	for (int64_t y = c.y0; y < c.y1; y++) {
		uint8_t *row = a + dst + y * (int64_t)stride;
		int64_t x = c.x0, end = row_end(dst, stride, y, c.x1);

		if ((x & 1) && x < end) {
			row[x / 2] = (uint8_t)((row[x / 2] & 0xF0) | level);
			x++;
		}
		if (end - x >= 2) {
			memset(row + x / 2, (int)(level * 0x11), (size_t)((end - x) / 2));
			x += (end - x) & ~(int64_t)1;
		}
		if (x < end) {
			row[x / 2] = (uint8_t)((row[x / 2] & 0x0F) | level << 4);
		}
	}
	return 0;
}

static int64_t first_bit(uint32_t base, uint32_t stride, int64_t x, int64_t y, int bpp)
{
	return 8 * (base + y * (int64_t)stride) + x * bpp;
}

/* the byte ranges of the clipped source and destination of a 4-bpp copy do not overlap */
static int disjoint(uint32_t src, uint32_t ss, uint32_t dst, uint32_t ds, const struct clip *c, int64_t ox, int64_t oy)
{
	int64_t s0 = src + (c->y0 + oy) * (int64_t)ss + (c->x0 + ox) / 2;
	int64_t s1 = src + (c->y1 - 1 + oy) * (int64_t)ss + (c->x1 + ox + 1) / 2;
	int64_t d0 = dst + c->y0 * (int64_t)ds + c->x0 / 2;
	int64_t d1 = dst + (c->y1 - 1) * (int64_t)ds + (c->x1 + 1) / 2;

	return s1 <= d0 || d1 <= s0;
}

/* If the first drawn destination pixel lies after its source pixel in the arena, rows run last to
 * first and pixels right to left (§3.3), so a scroll inside one bitmap works in any direction. */
int vm_blit(uint8_t *a, const uint32_t *v, uint32_t *detail)
{
	uint32_t src = v[0], ss = v[1], dst = v[4], ds = v[5], w = v[8], h = v[9], mode = v[10], level = v[11];
	int64_t sx = s32(v[2]), sy = s32(v[3]), dx = s32(v[6]), dy = s32(v[7]), ox, oy;
	int bpp = (mode & 4) ? 1 : 4, back;
	struct clip c;

	if (mode > 7) {
		*detail = mode;
		return VM_E_ARG;
	}
	if (level > 15) {
		*detail = level;
		return VM_E_ARG;
	}
	if (w == 0 || h == 0) {
		return 0;
	}
	if (!source_ok(src, ss, sx, sy, w, h, bpp)) {
		*detail = src;
		return VM_E_ARENA;
	}
	if (!clip(dst, ds, dx, dy, w, h, &c)) {
		return 0;
	}
	ox = sx - dx;
	oy = sy - dy;
	back = first_bit(src, ss, c.x0 + ox, c.y0 + oy, bpp) < first_bit(dst, ds, c.x0, c.y0, 4);
	if (bpp == 4 && mode == 0 && level == 15 && ((c.x0 | (c.x0 + ox) | c.x1) & 1) == 0 &&
	    (ss == ds || disjoint(src, ss, dst, ds, &c, ox, oy))) {
		/* whole bytes: one memmove per row, in the same row order, gives the same pixels */
		for (int64_t i = 0; i < c.y1 - c.y0; i++) {
			int64_t y = back ? c.y1 - 1 - i : c.y0 + i;
			int64_t end = row_end(dst, ds, y, c.x1);

			if (end > c.x0) {
				memmove(a + dst + y * (int64_t)ds + c.x0 / 2, a + src + (y + oy) * (int64_t)ss + (c.x0 + ox) / 2,
					(size_t)((end - c.x0) / 2));
			}
		}
		return 0;
	}
	for (int64_t i = 0; i < c.y1 - c.y0; i++) {
		int64_t y = back ? c.y1 - 1 - i : c.y0 + i;
		int64_t end = row_end(dst, ds, y, c.x1);
		uint8_t *drow = a + dst + y * (int64_t)ds;
		const uint8_t *srow = a + src + (y + oy) * (int64_t)ss;
		int32_t x0, n, shift;

		if (end <= c.x0) {
			continue;
		}
		x0 = (int32_t)c.x0;
		n = (int32_t)(end - c.x0);
		shift = (int32_t)(c.x0 + ox) - x0;
		for (int32_t j = 0; j < n; j++) {
			int32_t x = back ? x0 + n - 1 - j : x0 + j;
			uint32_t s = bpp == 1 ? get1(srow, x + shift) * 15 : get4(srow, x + shift);

			set4(drow, x, combine(get4(drow, x), s, mode & 3, level));
		}
	}
	return 0;
}

static int font_open(const uint8_t *a, uint32_t base, struct font *f)
{
	const uint8_t *h;

	if ((uint64_t)base + 16 > VM_ARENA_SIZE) {
		return VM_E_FONT;
	}
	h = a + base;
	if (h[0] != 'S' || h[1] != 'K' || h[2] != 'F' || h[3] != 1) {
		return VM_E_FONT;
	}
	f->base = base;
	f->size = get32(h + 4);
	f->n = get16(h + 8);
	f->k = get16(h + 10);
	if (f->n == 0 || 16 + 16 * (uint64_t)f->n + 5 * (uint64_t)f->k > f->size ||
	    (uint64_t)base + f->size > VM_ARENA_SIZE) {
		return VM_E_FONT;
	}
	return 0;
}

static const uint8_t *glyph(const uint8_t *a, const struct font *f, uint32_t g)
{
	return a + f->base + 16 + 12 * g;
}

static int glyph_ok(const struct font *f, const uint8_t *e)
{
	return get32(e) + (uint64_t)e[4] * e[5] <= 8 * (uint64_t)f->size;
}

static int kern(const uint8_t *a, const struct font *f, uint32_t left, uint32_t right)
{
	const uint8_t *keys = a + f->base + 16 + 16 * f->n;
	uint32_t key = left << 16 | right, lo = 0, hi = f->k;

	while (lo < hi) {
		uint32_t mid = (lo + hi) / 2;

		if (get32(keys + 4 * mid) < key) {
			lo = mid + 1;
		} else {
			hi = mid;
		}
	}
	return lo < f->k && get32(keys + 4 * lo) == key ? s8(keys[4 * f->k + lo]) : 0;
}

/* the next index of the n varint bytes at t, from *i; -1 if cut off or longer than 3 bytes */
static int32_t varint(const uint8_t *t, uint32_t n, uint32_t *i)
{
	uint32_t val = 0;

	for (int k = 0; k < 3; k++) {
		uint8_t b;

		if (*i >= n) {
			return -1;
		}
		b = t[(*i)++];
		val |= (uint32_t)(b & 0x7F) << (7 * k);
		if (!(b & 0x80)) {
			return (int32_t)val;
		}
	}
	return -1;
}

/* one glyph's bitmap into the destination with its top-left pixel at (gx, gy), clipped */
static void draw_glyph(uint8_t *a, const struct font *f, const uint8_t *e, uint32_t dst, uint32_t stride,
		       int64_t gx, int64_t gy, uint32_t mode, uint32_t level)
{
	int32_t w = e[4], h = e[5];
	uint32_t bits = 8 * f->base + get32(e);
	int64_t c0 = max64(0, -gx), c1 = min64(w, 2 * (int64_t)stride - gx);

	if (c0 >= c1) {
		return;
	}
	for (int64_t r = max64(0, -gy); r < h && gy + r < ARENA; r++) {
		int64_t row = dst + (gy + r) * (int64_t)stride, end;
		int32_t px0;

		if (row >= ARENA) {
			break;
		}
		end = min64(c1, 2 * (ARENA - row) - gx);
		if (end <= c0) {
			continue;
		}
		px0 = (int32_t)(gx + c0);
		for (int32_t c = (int32_t)c0; c < (int32_t)end; c++) {
			uint32_t bit = bits + (uint32_t)((int32_t)r * w + c);
			uint32_t ink = (uint32_t)(a[bit >> 3] >> (7 - (bit & 7))) & 1;
			int32_t px = px0 + c - (int32_t)c0;

			set4(a + row, px, combine(get4(a + row, px), ink * 15, mode, level));
		}
	}
}

/* Everything is checked before drawing. The second pass checks again, because drawing into a
 * bitmap that overlaps the text or the font may change them; it then stops or skips a glyph. */
int vm_glyphs(uint8_t *a, const uint32_t *v, uint32_t *detail)
{
	uint32_t dst = v[1], stride = v[2], mode = v[5], level = v[6], text = v[7], n = v[8], i;
	int64_t pen = s32(v[3]), y = s32(v[4]);
	int32_t g, prev = -1;
	struct font f;
	int err;

	if (mode > 3) {
		*detail = mode;
		return VM_E_ARG;
	}
	if (level > 15) {
		*detail = level;
		return VM_E_ARG;
	}
	if ((uint64_t)text + n > VM_ARENA_SIZE) {
		*detail = text;
		return VM_E_ARENA;
	}
	err = font_open(a, v[0], &f);
	if (err) {
		return err;
	}
	for (i = 0; i < n;) {
		g = varint(a + text, n, &i);
		if (g < 0) {
			return VM_E_MALFORMED;
		}
		if ((uint32_t)g >= f.n || !glyph_ok(&f, glyph(a, &f, (uint32_t)g))) {
			return VM_E_FONT;
		}
	}
	for (i = 0; i < n; prev = g) {
		const uint8_t *e;

		g = varint(a + text, n, &i);
		if (g < 0 || (uint32_t)g >= f.n) {
			break;
		}
		e = glyph(a, &f, (uint32_t)g);
		if (prev >= 0 && f.k) {
			pen += kern(a, &f, (uint32_t)prev, (uint32_t)g);
		}
		if (e[4] && e[5] && glyph_ok(&f, e)) {
			draw_glyph(a, &f, e, dst, stride, pen + s16(e + 8), y + s16(e + 10), mode, level);
		}
		pen += e[6];
	}
	return 0;
}

static void plot(uint8_t *a, uint32_t dst, uint32_t stride, int64_t x, int64_t y, uint32_t level)
{
	int64_t row = dst + y * (int64_t)stride;

	if (x < 0 || y < 0 || x >= 2 * (int64_t)stride || row + x / 2 >= ARENA) {
		return;
	}
	set4(a + row, (int32_t)x, level);
}

/* Bresenham, one pixel per step along the longer axis (x when |dx| = |dy|). The ends are ordered so
 * that the longer axis increases, so a line and its reverse set the same pixels. */
int vm_line(uint8_t *a, const uint32_t *v, uint32_t *detail)
{
	int64_t x0 = s32(v[2]), y0 = s32(v[3]), x1 = s32(v[4]), y1 = s32(v[5]), dx, dy, d, t;
	uint32_t level = v[6];

	if (level > 15) {
		*detail = level;
		return VM_E_ARG;
	}
	for (int i = 2; i < 6; i++) {
		if (s32(v[i]) < -32768 || s32(v[i]) > 32767) {
			*detail = v[i];
			return VM_E_ARG;
		}
	}
	dx = x1 > x0 ? x1 - x0 : x0 - x1;
	dy = y1 > y0 ? y1 - y0 : y0 - y1;
	if (dx >= dy) {
		if (x0 > x1) {
			t = x0, x0 = x1, x1 = t;
			t = y0, y0 = y1, y1 = t;
		}
		d = 2 * dy - dx;
		for (int64_t x = x0, y = y0; x <= x1; x++) {
			plot(a, v[0], v[1], x, y, level);
			if (d > 0) {
				y += y1 > y0 ? 1 : -1;
				d -= 2 * dx;
			}
			d += 2 * dy;
		}
	} else {
		if (y0 > y1) {
			t = x0, x0 = x1, x1 = t;
			t = y0, y0 = y1, y1 = t;
		}
		d = 2 * dx - dy;
		for (int64_t y = y0, x = x0; y <= y1; y++) {
			plot(a, v[0], v[1], x, y, level);
			if (d > 0) {
				x += x1 > x0 ? 1 : -1;
				d -= 2 * dy;
			}
			d += 2 * dx;
		}
	}
	return 0;
}

int vm_draw_clip(const uint32_t *v, struct vm_draw *d, uint32_t *detail)
{
	uint32_t src = v[0], ss = v[1], w = v[6], h = v[7];
	int64_t sx = s32(v[2]), sy = s32(v[3]), dx = s32(v[4]), dy = s32(v[5]), x0, x1, y0, y1;

	d->x = d->y = d->w = d->h = 0;
	d->stride = ss;
	d->nibble = 0;
	if (w == 0 || h == 0) {
		return 0;
	}
	if (!source_ok(src, ss, sx, sy, w, h, 4)) {
		*detail = src;
		return VM_E_ARENA;
	}
	x0 = max64(dx, 0);
	x1 = min64(dx + w, VM_PANEL_W);
	y0 = max64(dy, 0);
	y1 = min64(dy + h, VM_PANEL_H);
	if (x0 >= x1 || y0 >= y1) {
		return 0;
	}
	d->x = (int32_t)x0;
	d->y = (int32_t)y0;
	d->w = (int32_t)(x1 - x0);
	d->h = (int32_t)(y1 - y0);
	d->nibble = (uint64_t)(2 * (src + (sy + y0 - dy) * (int64_t)ss) + sx + x0 - dx);
	return 0;
}

void vm_draw_row(const uint8_t *a, const struct vm_draw *d, int32_t r, uint8_t *out)
{
	uint64_t n = d->nibble + 2 * (uint64_t)r * d->stride;
	const uint8_t *p = a + n / 2;
	int32_t half = d->w / 2;

	if (!(n & 1)) {
		memcpy(out, p, (size_t)half);
		if (d->w & 1) {
			out[half] = (uint8_t)(p[half] & 0xF0);
		}
	} else {
		for (int32_t i = 0; i < half; i++) {
			out[i] = (uint8_t)(p[i] << 4 | p[i + 1] >> 4);
		}
		if (d->w & 1) {
			out[half] = (uint8_t)(p[half] << 4);
		}
	}
}

/* VIDEO (§3.7): an adaptive binary range coder over a 2-D transition code (ITU-T T.6 modes against the
 * row above), with contexts from the row above and the previous frame. */
#define V_VMAX 5
#define V_SAME 0
#define V_V0 3
#define V_ISV (V_V0 + 7 * 7 * 6)
#define V_SIGN (V_ISV + 7 * 2 * 6)
#define V_MAG (V_SIGN + 7 * 6)
#define V_PASS (V_MAG + (V_VMAX - 1) * 7 * 6)
#define V_RUNLEN (V_PASS + 7)
#define V_RUNBIT (V_RUNLEN + 2 * 20)
#define V_PROBS (V_RUNBIT + 2 * 20 * 4)

enum { PC_START, PC_V0, PC_VP1, PC_VM1, PC_V, PC_PASS, PC_HORIZ };

#if defined(__GNUC__)
#define V_INLINE static inline __attribute__((always_inline))
#define V_COPY __builtin_memcpy /* memcpy is a byte loop in the firmware's C library */
#define V_CLZ(x) ((uint32_t)__builtin_clz(x))
#else
#define V_INLINE static inline
#define V_COPY memcpy
static uint32_t V_CLZ(uint32_t x)
{
	uint32_t n = 0;

	for (; !(x & 0x80000000u); x <<= 1) {
		n++;
	}
	return n;
}
#endif

typedef char v_state_is_the_probabilities[2 * V_PROBS == VM_VIDEO_STATE ? 1 : -1];

struct rc {
	uint32_t range, code;
	const uint8_t *p, *end;
	uint8_t *prob; /* the state: u16 probabilities of a 0, in 1/4096 */
};

int vm_video_check(const uint8_t *a, const uint32_t *v, uint32_t *detail)
{
	return vm_video_check_w(a, v, detail, VM_PANEL_W);
}

int vm_video_check_w(const uint8_t *a, const uint32_t *v, uint32_t *detail, uint32_t maxw)
{
	uint32_t bmp = v[0], stride = v[1], w = v[2], h = v[3], level = v[6], src = v[7], n = v[8], st = v[9];
	int64_t bend;

	if (level > 15) {
		*detail = level;
		return VM_E_ARG;
	}
	if (w == 0 || w > maxw || h == 0 || h > VM_PANEL_H || (uint64_t)stride * 8 < w) {
		*detail = w == 0 || w > maxw ? w : h == 0 || h > VM_PANEL_H ? h : stride;
		return VM_E_ARG;
	}
	bend = bmp + (int64_t)(h - 1) * stride + (w + 7) / 8;
	if (bend > ARENA || (int64_t)src + n > ARENA || (int64_t)st + VM_VIDEO_STATE > ARENA) {
		*detail = bend > ARENA ? bmp : (int64_t)src + n > ARENA ? src : st;
		return VM_E_ARENA;
	}
	if ((n && src < bend && bmp < src + n) || (st < bend && bmp < st + VM_VIDEO_STATE) ||
	    (n && st < src + n && src < st + VM_VIDEO_STATE)) {
		*detail = n && src < bend && bmp < src + n ? src : st;
		return VM_E_ARENA;
	}
	if (n == 0 || a[src] > 3) {
		return VM_E_FORMAT;
	}
	return 0;
}

V_INLINE uint32_t rc_bit(struct rc *r, uint32_t i)
{
	uint16_t q;
	uint32_t p, bound, b;

#if defined(__BYTE_ORDER__) && __BYTE_ORDER__ == __ORDER_LITTLE_ENDIAN__
	V_COPY(&q, r->prob + 2 * i, 2);
#else
	q = (uint16_t)(r->prob[2 * i] | r->prob[2 * i + 1] << 8);
#endif
	p = q & 0xFFFu;
	bound = (r->range >> 12) * p;
	b = r->code >= bound;
	if (!b) {
		r->range = bound;
		q = (uint16_t)(p + ((4096 - p) >> 5));
	} else {
		r->code -= bound;
		r->range -= bound;
		q = (uint16_t)(p - (p >> 5));
	}
#if defined(__BYTE_ORDER__) && __BYTE_ORDER__ == __ORDER_LITTLE_ENDIAN__
	V_COPY(r->prob + 2 * i, &q, 2);
#else
	r->prob[2 * i] = (uint8_t)q;
	r->prob[2 * i + 1] = (uint8_t)(q >> 8);
#endif
	while (r->range < 1u << 24) {
		r->range <<= 8;
		r->code = r->code << 8 | (r->p < r->end ? *r->p++ : 0);
	}
	return b;
}

/* a run length: the number of bits after the leading 1 of length + 1 in unary, then those bits */
V_INLINE uint32_t rc_run(struct rc *r, uint32_t colour)
{
	uint32_t nb = 0, v = 1, c;

	while (nb < 20 && rc_bit(r, V_RUNLEN + 20 * colour + (nb < 19 ? nb : 19))) {
		nb++;
	}
	c = V_RUNBIT + 80 * colour + 4 * (nb < 19 ? nb : 19);
	for (uint32_t i = 0; i < nb; i++) {
		v = v << 1 | rc_bit(r, c + (i < 3 ? i : 3));
	}
	return v - 1;
}

/* the class of a transition for the next row's context: 0 horizontal (or a same row), V(d) by d + 5: 1 V0,
 * 2 V+1, 3 V-1, 4 V+2..5, 5 V-2..-5; 6 marks the end of a row */
static const uint8_t v_class[11] = {5, 5, 5, 5, 3, 1, 2, 4, 4, 4, 4};

#define V_FAR 0xFFFFu /* past every b1 + 2 */

/* tc of b1: the previous frame's row as transitions (o: an even count, the last one w when the row ends
 * in ink, then entries V_FAR), k a position that follows b1; 3 + t - b1 for the leftmost t of b1's kind
 * in b1 - 2..b1 + 2, 0 for none */
static uint32_t frame_class(const uint16_t *o, uint32_t *k, uint32_t b1, uint32_t colour)
{
	while (*k > 0 && (uint32_t)o[*k - 1] + 2 >= b1) {
		(*k)--;
	}
	while ((uint32_t)o[*k] + 2 < b1) {
		(*k)++;
	}
	for (uint32_t q = *k; o[q] <= b1 + 2; q++) {
		if ((q & 1) == colour) {
			return 3 + o[q] - b1;
		}
	}
	return 0;
}

/* the transitions of a 1-bpp row of w pixels */
static uint32_t transitions(const uint8_t *row, uint32_t w, uint16_t *t)
{
	uint32_t n = 0, carry = 0;

	for (uint32_t x = 0; x < w; x += 32) {
		const uint8_t *p = row + (x >> 3);
		uint32_t v, d;

		if (w - x >= 32) {
			v = (uint32_t)p[0] << 24 | (uint32_t)p[1] << 16 | (uint32_t)p[2] << 8 | p[3];
		} else {
			v = 0;
			for (uint32_t i = 0; i < 32; i += 8) {
				v = v << 8 | (x + i < w ? p[i >> 3] : 0);
			}
			v &= ~(0xFFFFFFFFu >> (w - x));
		}
		d = v ^ (v >> 1 | carry << 31);
		carry = v & 1;
		if (w - x < 32) {
			d &= ~(0xFFFFFFFFu >> (w - x));
		}
		while (d) {
			uint32_t k = V_CLZ(d);

			t[n++] = (uint16_t)(x + k);
			d ^= 0x80000000u >> k;
		}
	}
	return n;
}

static void set_bits(uint8_t *r, uint32_t x0, uint32_t x1)
{
	uint32_t i = x0 >> 3, e = x1 >> 3;
	uint8_t m0 = (uint8_t)(0xFF >> (x0 & 7)), m1 = (uint8_t)~(0xFF >> (x1 & 7));

	if (i == e) {
		r[i] |= m0 & m1;
		return;
	}
	r[i++] |= m0;
	for (; i < e; i++) {
		r[i] = 0xFF;
	}
	if (x1 & 7) {
		r[e] |= m1;
	}
}

/* bytes lo..hi-1 of a row from its transitions (c, an even count n) */
static void render(uint8_t *row, uint32_t lo, uint32_t hi, const uint16_t *c, uint32_t n)
{
	uint32_t x0 = 8 * lo, x1 = 8 * hi, i = 0;

	for (uint32_t b = lo; b < hi; b++) {
		row[b] = 0;
	}
	while (i < n && c[i + 1] <= x0) {
		i += 2;
	}
	for (; i < n && c[i] < x1; i += 2) {
		set_bits(row, c[i] > x0 ? c[i] : x0, c[i + 1] < x1 ? c[i + 1] : x1);
	}
}

/* where two rows given as transitions (even counts) differ: the first pixel and one past the last; 0 if
 * they are the same */
static int changed(const uint16_t *o, uint32_t no, const uint16_t *c, uint32_t nc, uint32_t *p0, uint32_t *p1)
{
	uint32_t i = 0, j = 0, x, y;

	while (i < no && i < nc && o[i] == c[i]) {
		i++;
	}
	if (i == no && i == nc) {
		return 0;
	}
	*p0 = i == no ? c[i] : i == nc ? o[i] : o[i] < c[i] ? o[i] : c[i];
	while (j < no && j < nc && o[no - 1 - j] == c[nc - 1 - j]) {
		j++;
	}
	x = j < no ? o[no - 1 - j] : 0;
	y = j < nc ? c[nc - 1 - j] : 0;
	*p1 = x > y ? x : y;
	return 1;
}

/* one row's transitions against the reference row (ref and its classes, ended by three entries w and 6);
 * returns their count and ends cur the same way */
static uint32_t video_row(struct rc *rp, const uint16_t *ref, const uint8_t *rcls, const uint16_t *o,
			  uint32_t w, uint16_t *cur, uint8_t *ccls)
{
	struct rc rs = *rp, *r = &rs;
	int32_t a0 = -1;
	uint32_t colour = 0, pc = PC_START, j = 0, n = 0, tk = 0;

	for (;;) {
		uint32_t jb, b1, b2, ac, tc, cls;
		int32_t d = 0, a1;

		while ((int32_t)ref[j] <= a0) {
			j++;
		}
		jb = j + ((j & 1) != colour);
		b1 = ref[jb];
		b2 = ref[jb + 1];
		ac = rcls[jb];
		tc = b1 < w ? frame_class(o, &tk, b1, colour) : 0;
		if (rc_bit(r, V_V0 + (pc * 7 + ac) * 6 + tc)) {
			if (rc_bit(r, V_ISV + (pc * 2 + (ac != 0 && ac != 6)) * 6 + tc)) {
				uint32_t neg = rc_bit(r, V_SIGN + ac * 6 + tc), m = 1;

				while (m < V_VMAX && rc_bit(r, V_MAG + ((m - 1) * 7 + ac) * 6 + tc)) {
					m++;
				}
				d = neg ? -(int32_t)m : (int32_t)m;
			} else if (rc_bit(r, V_PASS + pc)) {
				if (b2 >= w) {
					break;
				}
				a0 = (int32_t)b2;
				pc = PC_PASS;
				continue;
			} else {
				uint32_t t1 = (a0 < 0 ? 0 : (uint32_t)a0) + rc_run(r, colour), r2 = rc_run(r, colour ^ 1);

				if ((int32_t)t1 <= a0) {
					t1 = (uint32_t)a0 + 1;
				}
				if (t1 >= w) {
					break;
				}
				cur[n] = (uint16_t)t1;
				ccls[n++] = 0;
				t1 += r2 ? r2 : 1;
				if (t1 >= w) {
					break;
				}
				cur[n] = (uint16_t)t1;
				ccls[n++] = 0;
				a0 = (int32_t)t1;
				pc = PC_HORIZ;
				continue;
			}
		}
		a1 = (int32_t)b1 + d;
		if (a1 <= a0) {
			a1 = a0 + 1;
		}
		if (a1 >= (int32_t)w) {
			break;
		}
		cls = v_class[d + 5];
		cur[n] = (uint16_t)a1;
		ccls[n++] = (uint8_t)cls;
		a0 = a1;
		colour ^= 1;
		pc = cls < 5 ? cls : (uint32_t)PC_V;
	}
	*rp = rs;
	for (uint32_t i = n; i < n + 3; i++) {
		cur[i] = (uint16_t)w;
		ccls[i] = 6;
	}
	return n;
}

void vm_video_decode(uint8_t *a, const uint32_t *v, struct vm_video *s, vm_span_fn *span, void *ctx)
{
	uint32_t bmp = v[0], stride = v[1], w = v[2], h = v[3], n = v[8], st = v[9];
	uint32_t nb = (w + 7) / 8, flags = a[v[7]], prev = 2, pending = 0, k = 0;
	struct rc r;

	if (flags & 1) {
		for (uint32_t i = 0; i < V_PROBS; i++) {
			a[st + 2 * i] = 0;
			a[st + 2 * i + 1] = 8;
		}
	}
	r.range = 0xFFFFFFFFu;
	r.code = 0;
	r.p = a + v[7] + 1;
	r.end = a + v[7] + n;
	r.prob = a + st;
	for (int i = 0; i < 4; i++) {
		r.code = r.code << 8 | (r.p < r.end ? *r.p++ : 0);
	}
	for (uint32_t i = 0; i < 3; i++) {
		s->t[0][i] = (uint16_t)w;
		s->cls[0][i] = 6;
	}
	for (uint32_t y = 0; y < h; y++) {
		uint8_t *row = a + bmp + y * stride;
		uint32_t same, no, nc, p0, p1;

		if (!(y & 15)) {
			span(ctx, y, 0, 0, row);
		}
		same = rc_bit(&r, V_SAME + prev);
		prev = same;
		if (same) {
			if (flags & 2) {
				span(ctx, y, 0, w, row);
			}
			pending = 1;
			continue;
		}
		if (pending) {
			uint32_t m = transitions(row - stride, w, s->t[k]);

			for (uint32_t i = 0; i < m + 3; i++) {
				s->cls[k][i] = i < m ? 0 : 6;
				if (i >= m) {
					s->t[k][i] = (uint16_t)w;
				}
			}
			pending = 0;
		}
		no = transitions(row, w, s->o);
		if (no & 1) {
			s->o[no++] = (uint16_t)w;
		}
		s->o[no] = s->o[no + 1] = s->o[no + 2] = V_FAR;
		nc = video_row(&r, s->t[k], s->cls[k], s->o, w, s->t[k ^ 1], s->cls[k ^ 1]);
		k ^= 1;
		nc += nc & 1;
		if (!changed(s->o, no, s->t[k], nc, &p0, &p1)) {
			if (flags & 2) {
				span(ctx, y, 0, w, row);
			}
			continue;
		}
		if (flags & 2) {
			render(row, 0, nb, s->t[k], nc);
			span(ctx, y, 0, w, row);
			continue;
		}
		{
			uint32_t lo = p0 >> 3, hi = ((p1 - 1) >> 3) + 1, x0 = 8 * lo, x1 = 8 * hi < w ? 8 * hi + 1 : w;

			render(row, lo, hi, s->t[k], nc);
			if (!((x1 - x0) & 1) && x0 > 0) {
				x0--;
			}
			span(ctx, y, x0, x1, row);
		}
	}
}

#if defined(__BYTE_ORDER__) && __BYTE_ORDER__ == __ORDER_LITTLE_ENDIAN__
/* 8 pixels (bit 7 first) as 4 bytes of 4 bpp, as a little-endian word */
#define X2(b, i) (((b) >> (i) & 2 ? 0xF0u : 0u) | ((b) >> (i) & 1 ? 0x0Fu : 0u))
#define X1(b) (X2(b, 6) | X2(b, 4) << 8 | X2(b, 2) << 16 | X2(b, 0) << 24)
#define X4(b) X1(b), X1((b) + 1), X1((b) + 2), X1((b) + 3)
#define X16(b) X4(b), X4((b) + 4), X4((b) + 8), X4((b) + 12)
#define X64(b) X16(b), X16((b) + 16), X16((b) + 32), X16((b) + 48)
static const uint32_t v_expand[256] = {X64(0), X64(64), X64(128), X64(192)};
#endif

void vm_video_expand(const uint8_t *row, uint32_t x0, uint32_t x1, uint32_t level, uint8_t *out)
{
	uint32_t lv = level * 0x11111111u, x = x0;

	for (; x + 8 <= x1; x += 8, out += 4) {
		uint32_t i = x >> 3, u = x & 7 ? ((uint32_t)row[i] << 8 | row[i + 1]) >> (8 - (x & 7)) & 0xFF : row[i];

#if defined(__BYTE_ORDER__) && __BYTE_ORDER__ == __ORDER_LITTLE_ENDIAN__
		u = v_expand[u] & lv;
		V_COPY(out, &u, 4);
#else
		u = (u | u << 12) & 0x000F000Fu;
		u = (u | u << 6) & 0x03030303u;
		u = ((u | u << 3) & 0x11111111u) * 15 & lv;
		out[0] = (uint8_t)(u >> 24);
		out[1] = (uint8_t)(u >> 16);
		out[2] = (uint8_t)(u >> 8);
		out[3] = (uint8_t)u;
#endif
	}
	for (; x < x1; x += 2, out++) {
		uint32_t hi = row[x >> 3] >> (7 - (x & 7)) & 1;
		uint32_t lo = x + 1 < x1 ? row[(x + 1) >> 3] >> (7 - ((x + 1) & 7)) & 1 : 0;

		*out = (uint8_t)((hi ? level << 4 : 0) | (lo ? level : 0));
	}
}

/* VIDEOX (§3.8) */
static int overlap(int64_t a0, int64_t a1, int64_t b0, int64_t b1)
{
	return a0 < b1 && b0 < a1;
}

int vm_videox_check(const uint8_t *a, const uint32_t *v, struct vm_videox *x, uint32_t *detail)
{
	uint32_t pt = v[0], stride = v[1], w = v[2], h = v[3], lv = v[6], src = v[7], n = v[8], stt = v[9];
	uint32_t i, p, pos, nw = 0;
	int64_t rend = (int64_t)src + n, wr[2 * VM_VIDEOX_PLANES][2], rd[4][2];

	uint32_t maxw;

	maxw = VM_VIDEOX_MAXW;   /* a canvas wider than the panel: (x, y) places it, the panel shows its part */
	if (w == 0 || w > maxw || h == 0 || h > VM_PANEL_H || (uint64_t)stride * 8 < w) {
		*detail = w == 0 || w > maxw ? w : h == 0 || h > VM_PANEL_H ? h : stride;
		return VM_E_ARG;
	}
	if (n < 2 || rend > ARENA) {
		*detail = src;
		return n < 2 ? VM_E_FORMAT : VM_E_ARENA;
	}
	x->flags = a[src];
	x->planes = a[src + 1];
	if (x->flags > 63 || x->planes == 0 || x->planes > VM_VIDEOX_PLANES) {
		*detail = x->flags > 63 ? x->flags : x->planes;
		return VM_E_FORMAT;
	}
#ifdef VM_NO_PIXEL_CODING
	if (x->flags & VM_VX_PIXELS) { /* this build has no pixel coding */
		*detail = x->flags;
		return VM_E_FORMAT;
	}
#endif
	if ((int64_t)pt + 4 * x->planes > ARENA || (int64_t)stt + 4 * x->planes > ARENA || (int64_t)lv + 16 > ARENA) {
		*detail = (int64_t)pt + 4 * x->planes > ARENA ? pt : (int64_t)stt + 4 * x->planes > ARENA ? stt : lv;
		return VM_E_ARENA;
	}
	/* written: every plane and state; read only: the record, the level map and the two tables */
	for (p = 0; p < x->planes; p++) {
		x->plane[p] = get32(a + pt + 4 * p);
		x->state[p] = get32(a + stt + 4 * p);
		wr[nw][0] = x->plane[p];
		wr[nw++][1] = x->plane[p] + (int64_t)(h - 1) * stride + (w + 7) / 8;
		wr[nw][0] = x->state[p];
		wr[nw++][1] = (int64_t)x->state[p] + (x->flags & VM_VX_PIXELS ? VM_VIDEOX_PIX_STATE : VM_VIDEO_STATE);
	}
	for (i = 0; i < nw; i++) {
		if (wr[i][1] > ARENA) {
			*detail = (uint32_t)wr[i][0];
			return VM_E_ARENA;
		}
	}
	rd[0][0] = src, rd[0][1] = rend;
	rd[1][0] = lv, rd[1][1] = (int64_t)lv + 16;
	rd[2][0] = pt, rd[2][1] = (int64_t)pt + 4 * x->planes;
	rd[3][0] = stt, rd[3][1] = (int64_t)stt + 4 * x->planes;
	for (i = 0; i < nw; i++) {
		for (uint32_t k = i + 1; k < nw; k++) {
			if (overlap(wr[i][0], wr[i][1], wr[k][0], wr[k][1])) {
				*detail = (uint32_t)wr[k][0];
				return VM_E_ARENA;
			}
		}
		for (uint32_t k = 0; k < 4; k++) {
			if (overlap(wr[i][0], wr[i][1], rd[k][0], rd[k][1])) {
				*detail = (uint32_t)rd[k][0];
				return VM_E_ARENA;
			}
		}
	}
	for (i = 0; i < (1u << x->planes); i++) {
		if (a[lv + i] > 15) {
			*detail = a[lv + i];
			return VM_E_ARG;
		}
	}
	pos = src + 2;
	x->y0 = x->rows = 0;
	x->dy = 0;
	if (x->flags & 1) {
		if ((int64_t)pos + 6 > rend) {
			*detail = pos;
			return VM_E_FORMAT;
		}
		x->y0 = get16(a + pos);
		x->rows = get16(a + pos + 2);
		x->dy = s16(a + pos + 4);
		pos += 6;
		if ((uint64_t)x->y0 + x->rows > h || x->dy == 0 || (uint32_t)(x->dy < 0 ? -x->dy : x->dy) >= x->rows) {
			*detail = x->y0 + x->rows > h ? x->y0 : (uint32_t)x->dy;
			return VM_E_FORMAT;
		}
	}
	x->bw = x->bh = 0;
	if (x->flags & VM_VX_BLOCK) {
		int64_t tx, ty;

		if ((int64_t)pos + 12 > rend) {
			*detail = pos;
			return VM_E_FORMAT;
		}
		x->bx = get16(a + pos);
		x->by = get16(a + pos + 2);
		x->bw = get16(a + pos + 4);
		x->bh = get16(a + pos + 6);
		x->bdx = s16(a + pos + 8);
		x->bdy = s16(a + pos + 10);
		tx = (int64_t)x->bx + x->bdx;
		ty = (int64_t)x->by + x->bdy;
		if (x->bw == 0 || x->bh == 0 || (uint64_t)x->bx + x->bw > w || (uint64_t)x->by + x->bh > h || tx < 0 ||
		    tx + x->bw > w || ty < 0 || ty + x->bh > h) {
			*detail = pos;
			return VM_E_FORMAT;
		}
		pos += 12;
	}
	x->rw = x->rh = 0;
	x->sx = -32768;
	x->sy = 0;
	x->sprite = 0;
	if (x->flags & VM_VX_SPRITE) {
		if ((int64_t)pos + 16 > rend) {
			*detail = pos;
			return VM_E_FORMAT;
		}
		x->rx = get16(a + pos);
		x->ry = get16(a + pos + 2);
		x->rw = get16(a + pos + 4);
		x->rh = get16(a + pos + 6);
		x->sx = s16(a + pos + 8);
		x->sy = s16(a + pos + 10);
		x->sprite = get32(a + pos + 12);
		if (x->sx != -32768 && (int64_t)x->sprite + 64 > ARENA) {
			*detail = x->sprite;
			return VM_E_ARENA;
		}
		pos += 16;
	}
	for (p = 0; p < x->planes; p++) {
		uint32_t pv[10];
		int err;

		if ((int64_t)pos + 3 > rend) {
			*detail = pos;
			return VM_E_FORMAT;
		}
		x->len[p] = (uint32_t)a[pos] | (uint32_t)a[pos + 1] << 8 | (uint32_t)a[pos + 2] << 16;
		x->at[p] = pos + 3;
		pos += 3 + x->len[p];
		if (x->len[p] == 0 || pos > rend) {
			*detail = x->at[p];
			return VM_E_FORMAT;
		}
		vm_videox_plane(v, x, p, pv);
		err = vm_video_check_w(a, pv, detail, VM_VIDEOX_MAXW);   /* the plane's record flags */
		if (err) {
			return err;
		}
	}
	if (pos != rend) {
		*detail = pos;
		return VM_E_FORMAT;
	}
	return 0;
}

void vm_videox_plane(const uint32_t *v, const struct vm_videox *x, uint32_t p, uint32_t *pv)
{
	pv[0] = x->plane[p];
	pv[1] = v[1];
	pv[2] = v[2];
	pv[3] = v[3];
	pv[4] = v[4];
	pv[5] = v[5];
	pv[6] = 15;
	pv[7] = x->at[p];
	pv[8] = x->len[p];
	pv[9] = x->state[p];
}

/* rows y0..y0+rows-1 of every plane move by dy (the rows left behind keep their pixels); a row is marked when
 * any plane's bytes there changed */
void vm_videox_move(uint8_t *a, const uint32_t *v, const struct vm_videox *x, vm_mark_fn *mark, void *ctx)
{
	uint32_t stride = v[1], nb = (v[2] + 7) / 8;
	int32_t step = x->dy > 0 ? -1 : 1, d = x->dy;
	int32_t first = d > 0 ? (int32_t)(x->y0 + x->rows) - 1 : (int32_t)x->y0;
	int32_t last = d > 0 ? (int32_t)x->y0 + d : (int32_t)(x->y0 + x->rows) - 1 + d;

	for (int32_t y = first; step < 0 ? y >= last : y <= last; y += step) {
		int diff = 0;

		for (uint32_t p = 0; p < x->planes; p++) {
			uint8_t *dst = a + x->plane[p] + (uint32_t)y * stride;
			const uint8_t *s = a + x->plane[p] + (uint32_t)(y - d) * stride;

			if (memcmp(dst, s, nb)) {
				memmove(dst, s, nb);
				diff = 1;
			}
		}
		if (diff) {
			mark(ctx, (uint32_t)y);
		}
	}
}

/* bit i of a byte (pixel 7 - i of 8) to bit 4i of a word: the 8 pixels as 8 nibbles, pixel 0 in the top one */
#define SP(b) ((uint32_t)((b) & 1) | (uint32_t)((b) >> 1 & 1) << 4 | (uint32_t)((b) >> 2 & 1) << 8 | \
	       (uint32_t)((b) >> 3 & 1) << 12 | (uint32_t)((b) >> 4 & 1) << 16 | (uint32_t)((b) >> 5 & 1) << 20 | \
	       (uint32_t)((b) >> 6 & 1) << 24 | (uint32_t)((b) >> 7 & 1) << 28)
#define SP4(b) SP(b), SP((b) + 1), SP((b) + 2), SP((b) + 3)
#define SP16(b) SP4(b), SP4((b) + 4), SP4((b) + 8), SP4((b) + 12)
#define SP64(b) SP16(b), SP16((b) + 16), SP16((b) + 32), SP16((b) + 48)
static const uint32_t v_spread[256] = {SP64(0), SP64(64), SP64(128), SP64(192)};

void vm_videox_pairs(const uint8_t *a, const uint32_t *v, uint8_t *pair)
{
	const uint8_t *lv = a + v[6];

	for (uint32_t i = 0; i < 256; i++) {
		pair[i] = (uint8_t)((lv[i >> 4] & 15) << 4 | (lv[i & 15] & 15));
	}
}

/* 8 pixels from x of a 1-bpp row (x + 8 <= the row's pixels) */
static inline uint32_t get8(const uint8_t *row, uint32_t x)
{
	uint32_t i = x >> 3, s = x & 7;

	return s ? (uint32_t)(row[i] << s | row[i + 1] >> (8 - s)) & 0xFF : row[i];
}

void vm_videox_compose(const uint8_t *a, const uint32_t *v, const struct vm_videox *xl, const uint8_t *pair,
		       uint32_t y, uint32_t x0, uint32_t x1, uint8_t *out)
{
	const uint8_t *lv = a + v[6];
	const uint8_t *row[VM_VIDEOX_PLANES];
	uint32_t planes = xl->planes, x = x0;

	for (uint32_t p = 0; p < planes; p++) {
		row[p] = a + xl->plane[p] + y * v[1];
	}
	/* 8 pixels at a time: each plane's 8 bits spread to nibbles, the 4 index pairs through the pair table */
	for (; x + 8 <= x1; x += 8, out += 4) {
		uint32_t idx = 0;

		for (uint32_t p = 0; p < planes; p++) {
			idx |= v_spread[get8(row[p], x)] << p;
		}
		out[0] = pair[idx >> 24];
		out[1] = pair[idx >> 16 & 0xFF];
		out[2] = pair[idx >> 8 & 0xFF];
		out[3] = pair[idx & 0xFF];
	}
	for (; x < x1; x += 2, out++) {
		uint32_t i0 = 0, i1 = 0;

		for (uint32_t p = 0; p < planes; p++) {
			i0 |= get1(row[p], (int32_t)x) << p;
			if (x + 1 < x1) {
				i1 |= get1(row[p], (int32_t)x + 1) << p;
			}
		}
		*out = (uint8_t)((lv[i0] & 15) << 4 | (x + 1 < x1 ? lv[i1] & 15 : 0));
	}
}

/* VIDEOX's pixel coding (flags bit 3, §3.8): plane p's record (u8 flags: bit 0 reset, bit 1 redraw; then range
 * coder bytes as VIDEO's) decoded in place, row by row: a "row same" decision (context: the row above's), then
 * each pixel from 11 bits of context: W, WW, NW, N, NE of this frame, the previous frame's pixel and its right and
 * lower neighbours (still in the plane: in-place decoding writes a pixel after reading it), and the pixel's bits of
 * the next three planes up, decoded first. At most w + 1 decisions a row. */
#define PX_ROW 2048 /* + the row above's "same" */

static inline uint32_t bit1(const uint8_t *row, uint32_t x)
{
	return (uint32_t)(row[x >> 3] >> (7 - (x & 7))) & 1;
}

void vm_videox_pixels(uint8_t *a, const uint32_t *v, const struct vm_videox *x, uint32_t p, vm_span_fn *span,
		      void *ctx)
{
#ifdef VM_NO_PIXEL_CODING
	(void)a, (void)v, (void)x, (void)p, (void)span, (void)ctx; /* never called: the check refuses the flag */
	return;
#endif
	uint32_t stride = v[1], w = v[2], h = v[3], src = x->at[p], n = x->len[p], st = x->state[p];
	uint32_t flags = a[src], prev = 1;
	const uint8_t *up_plane[3] = {0, 0, 0};
	struct rc r;

	if (flags & 1) {
		for (uint32_t i = 0; i < VM_VIDEOX_PIX_STATE / 2; i++) {
			a[st + 2 * i] = 0;
			a[st + 2 * i + 1] = 8;
		}
	}
	for (uint32_t k = 0; k < 3; k++) {
		if (p + 1 + k < x->planes) {
			up_plane[k] = a + x->plane[p + 1 + k];
		}
	}
	r.range = 0xFFFFFFFFu;
	r.code = 0;
	r.p = a + src + 1;
	r.end = a + src + n;
	r.prob = a + st;
	for (int i = 0; i < 4; i++) {
		r.code = r.code << 8 | (r.p < r.end ? *r.p++ : 0);
	}
	for (uint32_t y = 0; y < h; y++) {
		uint8_t *row = a + x->plane[p] + y * stride;
		const uint8_t *above = y ? row - stride : 0, *below = y + 1 < h ? row + stride : 0;
		uint32_t lo = w, hi = 0;

		if (!(y & 15)) {
			span(ctx, y, 0, 0, row);
		}
		prev = rc_bit(&r, PX_ROW + prev);
		if (prev) {
			if (flags & 2) {
				span(ctx, y, 0, w, row);
			}
			continue;
		}
		for (uint32_t xx = 0; xx < w; xx++) {
			uint32_t t = bit1(row, xx), c, b, up = 0;

			c = (xx ? bit1(row, xx - 1) : 0) | (xx > 1 ? bit1(row, xx - 2) : 0) << 1;
			if (above) {
				c |= (xx ? bit1(above, xx - 1) : 0) << 2 | bit1(above, xx) << 3 |
				     (xx + 1 < w ? bit1(above, xx + 1) : 0) << 4;
			}
			c |= t << 5 | (xx + 1 < w ? bit1(row, xx + 1) : 0) << 6 | (below ? bit1(below, xx) : 0) << 7;
			for (uint32_t k = 0; k < 3; k++) {
				if (up_plane[k]) {
					up |= bit1(up_plane[k] + y * stride, xx) << k;
				}
			}
			c |= up << 8;
			b = rc_bit(&r, c);
			if (b != t) {
				row[xx >> 3] ^= (uint8_t)(0x80 >> (xx & 7));
				if (xx < lo) {
					lo = xx;
				}
				hi = xx + 1;
			}
		}
		if (flags & 2) {
			span(ctx, y, 0, w, row);
		} else if (hi > lo) {
			span(ctx, y, lo, hi, row);
		}
	}
}

/* scale 2 (flags bit 2): pixels x0..x1-1 of plane row y, each drawn twice: one byte (two equal nibbles) a pixel */
void vm_videox_compose2(const uint8_t *a, const uint32_t *v, const struct vm_videox *xl, uint32_t y, uint32_t x0,
			uint32_t x1, uint8_t *out)
{
	const uint8_t *lv = a + v[6];
	const uint8_t *row[VM_VIDEOX_PLANES];
	uint8_t twice[16];
	uint32_t planes = xl->planes;

	for (uint32_t i = 0; i < 16; i++) {
		twice[i] = (uint8_t)((lv[i] & 15) << 4 | (lv[i] & 15));
	}
	for (uint32_t p = 0; p < planes; p++) {
		row[p] = a + xl->plane[p] + y * v[1];
	}
	for (uint32_t x = x0; x < x1; x++) {
		uint32_t i = 0;

		for (uint32_t p = 0; p < planes; p++) {
			i |= bit1(row[p], x) << p;
		}
		*out++ = twice[i];
	}
}


/* the block move: plane pixels (bx..bx+bw-1, by..by+bh-1) to (bx+bdx, by+bdy) in every plane; rows and pixels are
 * walked away from the overlap (as memmove), and each destination row's changed part goes to span */
void vm_videox_block(uint8_t *a, const uint32_t *v, const struct vm_videox *x, vm_span_fn *span, void *ctx)
{
	uint32_t stride = v[1];
	int32_t rows = (int32_t)x->bh, cols = (int32_t)x->bw;
	int32_t ystep = x->bdy > 0 ? -1 : 1, xstep = x->bdx > 0 ? -1 : 1;

	for (int32_t k = 0; k < rows; k++) {
		int32_t r = ystep < 0 ? rows - 1 - k : k;
		uint32_t sy = x->by + (uint32_t)r, dy = (uint32_t)((int32_t)sy + x->bdy);
		uint32_t lo = UINT32_MAX, hi = 0;

		for (uint32_t p = 0; p < x->planes; p++) {
			const uint8_t *srow = a + x->plane[p] + sy * stride;
			uint8_t *drow = a + x->plane[p] + dy * stride;

			for (int32_t j = 0; j < cols; j++) {
				int32_t c = xstep < 0 ? cols - 1 - j : j;
				uint32_t sx = x->bx + (uint32_t)c, dx = (uint32_t)((int32_t)sx + x->bdx);
				uint32_t b = bit1(srow, sx);

				if (bit1(drow, dx) != b) {
					drow[dx >> 3] ^= (uint8_t)(0x80 >> (dx & 7));
					if (dx < lo) {
						lo = dx;
					}
					if (dx + 1 > hi) {
						hi = dx + 1;
					}
				}
			}
		}
		if (!(k & 15)) {
			span(ctx, dy, 0, 0, a + x->plane[0] + dy * stride);
		}
		if (hi > lo) {
			span(ctx, dy, lo, hi, a + x->plane[0] + dy * stride);
		}
	}
}

int vm_videox_sprite_at(const uint8_t *a, const struct vm_videox *x, int32_t px, int32_t py)
{
	int32_t c = px - x->sx, r = py - x->sy;
	uint32_t mask, ink;

	if (!(x->flags & VM_VX_SPRITE) || x->sx == -32768 || c < 0 || c >= 16 || r < 0 || r >= 16) {
		return -1;
	}
	mask = (uint32_t)a[x->sprite + 4 * (uint32_t)r] << 8 | a[x->sprite + 4 * (uint32_t)r + 1];
	ink = (uint32_t)a[x->sprite + 4 * (uint32_t)r + 2] << 8 | a[x->sprite + 4 * (uint32_t)r + 3];
	if (!(mask >> (15 - c) & 1)) {
		return -1;
	}
	return (ink >> (15 - c) & 1) ? 15 : 0;
}
