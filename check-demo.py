"""Quick check: open the dashboard in demo mode and dump the DOM + any errors."""

import asyncio
import sys
from playwright.async_api import async_playwright

URL = sys.argv[1] if len(sys.argv) > 1 else "http://localhost:5173/?demo=1"

async def main():
    async with async_playwright() as p:
        # Use the full chromium we already have installed, not headless_shell.
        browser = await p.chromium.launch(
            headless=True,
            executable_path=r"C:\Users\LuZhong\AppData\Local\ms-playwright\chromium-1208\chrome-win64\chrome.exe",
        )
        context = await browser.new_context(viewport={"width": 1280, "height": 800})
        page = await context.new_page()
        errors = []
        page.on("pageerror", lambda exc: errors.append(f"pageerror: {exc}"))
        page.on("console", lambda msg: errors.append(f"console.{msg.type}: {msg.text}"))
        await page.goto(URL, wait_until="networkidle")
        await page.wait_for_timeout(2000)

        info = await page.evaluate("""() => {
          const cs = document.querySelectorAll('canvas');
          const demoBanner = !!document.body.innerText.match(/Demo mode/);
          const root = document.getElementById('root');
          return {
            canvasCount: cs.length,
            canvasSizes: Array.from(cs).map(c => ({w: c.width, h: c.height})),
            demoBanner,
            rootTextLen: root ? root.innerText.length : 0,
            rootFirstChars: root ? root.innerText.slice(0, 200) : '',
          };
        }""")
        print("URL:", URL)
        print("Errors:")
        for e in errors:
            print(" ", e)
        print("Info:", info)
        await browser.close()

asyncio.run(main())
