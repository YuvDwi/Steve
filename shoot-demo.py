"""Capture a screenshot of the dashboard in demo mode for visual inspection."""

import asyncio
import sys
from playwright.async_api import async_playwright

URL = sys.argv[1] if len(sys.argv) > 1 else "http://localhost:5173/?demo=1"
OUT = sys.argv[2] if len(sys.argv) > 2 else r"C:\Users\LuZhong\Documents\Github\Steve\demo-shot.png"

async def main():
    async with async_playwright() as p:
        browser = await p.chromium.launch(
            headless=True,
            executable_path=r"C:\Users\LuZhong\AppData\Local\ms-playwright\chromium-1208\chrome-win64\chrome.exe",
        )
        context = await browser.new_context(viewport={"width": 1280, "height": 800})
        page = await context.new_page()
        await page.goto(URL, wait_until="networkidle")
        await page.wait_for_timeout(2500)
        await page.screenshot(path=OUT, full_page=False)
        print("Saved", OUT)
        await browser.close()

asyncio.run(main())
