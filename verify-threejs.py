"""Verify Structure3D renders correctly in demo mode by inspecting the
canvas pixel grid. We open the dashboard with ?demo=1, wait for first paint,
then sample the rendered canvas to confirm the structure is centered and
not filling the entire viewport (i.e. 0,0,0 is in the middle and the building
leaves headroom around it)."""

import asyncio
import sys
from playwright.async_api import async_playwright


async def main(url: str):
    async with async_playwright() as p:
        browser = await p.chromium.launch()
        context = await browser.new_context(viewport={"width": 1280, "height": 800})
        page = await context.new_page()

        page.on("pageerror", lambda exc: print(f"[pageerror] {exc}"))
        page.on("console", lambda msg: print(f"[console.{msg.type}] {msg.text}")
                if msg.type in ("error", "warning") else None)

        await page.goto(url, wait_until="networkidle")
        # Give the three.js loop a few frames to settle.
        await page.wait_for_timeout(1500)

        # Find the structure canvas. The component renders a <div> with a
        # <canvas> inside it. In demo mode it's the first Structure3D.
        canvas = await page.query_selector("canvas")
        if canvas is None:
            print("ERROR: no canvas found")
            await browser.close()
            sys.exit(1)

        # Inspect the rendered scene by snapshotting canvas pixel data.
        info = await page.evaluate("""
            () => {
              const c = document.querySelector('canvas');
              if (!c) return null;
              const gl = c.getContext('webgl2') || c.getContext('webgl');
              if (!gl) return null;
              const w = c.width, h = c.height;
              const pixels = new Uint8Array(w * h * 4);
              gl.readPixels(0, 0, w, h, gl.RGBA, gl.UNSIGNED_BYTE, pixels);
              // Count non-background pixels (background is dark ~0x101418).
              let nonBg = 0;
              let sumX = 0, sumY = 0;
              const bgR = 0x10, bgG = 0x14, bgB = 0x18;
              for (let y = 0; y < h; y++) {
                for (let x = 0; x < w; x++) {
                  const i = (y * w + x) * 4;
                  const r = pixels[i], g = pixels[i+1], b = pixels[i+2];
                  // Anything that's not the background color and not pure black
                  // counts as part of the structure.
                  if (Math.abs(r - bgR) > 8 || Math.abs(g - bgG) > 8 || Math.abs(b - bgB) > 8) {
                    nonBg++;
                    sumX += x;
                    sumY += y;
                  }
                }
              }
              return {
                w, h,
                nonBg,
                totalPx: w * h,
                ratio: nonBg / (w * h),
                centroidX: nonBg > 0 ? sumX / nonBg : null,
                centroidY: nonBg > 0 ? sumY / nonBg : null,
                centroidXRatio: nonBg > 0 ? (sumX / nonBg) / w : null,
                centroidYRatio: nonBg > 0 ? (sumX / nonBg) / h : null,
              };
            }
        """)
        print("Canvas info:", info)
        await browser.close()
        if info is None:
            sys.exit(1)
        # The structure should fill ~10-50% of the canvas (it has empty space
        # around it). The centroid should be near the center of the canvas.
        cx_ok = info["centroidXRatio"] and 0.35 < info["centroidXRatio"] < 0.65
        ratio_ok = 0.05 < info["ratio"] < 0.55
        print(f"Centered? centroidX ratio = {info['centroidXRatio']:.3f} -> {'OK' if cx_ok else 'OFF'}")
        print(f"Sized?   fill ratio = {info['ratio']:.3f} -> {'OK' if ratio_ok else 'TOO BIG/SMALL'}")
        sys.exit(0 if (cx_ok and ratio_ok) else 2)


if __name__ == "__main__":
    asyncio.run(main(sys.argv[1] if len(sys.argv) > 1 else "http://localhost:5174/?demo=1"))
