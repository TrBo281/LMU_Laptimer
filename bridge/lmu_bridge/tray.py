"""Optional Windows tray icon and autostart helpers.

Both are opt-in: the bridge never writes to the registry unless the user runs
``--install-autostart`` explicitly.
"""

from __future__ import annotations

import logging
import sys
import threading
import webbrowser

from .config import Config

logger = logging.getLogger(__name__)

RUN_KEY = r"Software\Microsoft\Windows\CurrentVersion\Run"
RUN_VALUE = "LMULaptimerBridge"


def install_autostart(command: str) -> bool:
    if sys.platform != "win32":
        logger.warning("Autostart is only implemented for Windows")
        return False
    import winreg

    with winreg.OpenKey(winreg.HKEY_CURRENT_USER, RUN_KEY, 0, winreg.KEY_SET_VALUE) as key:
        winreg.SetValueEx(key, RUN_VALUE, 0, winreg.REG_SZ, command)
    logger.info("Autostart registered: %s", command)
    return True


def remove_autostart() -> bool:
    if sys.platform != "win32":
        return False
    import winreg

    try:
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, RUN_KEY, 0, winreg.KEY_SET_VALUE) as key:
            winreg.DeleteValue(key, RUN_VALUE)
        return True
    except FileNotFoundError:
        return False


def start_tray(config: Config, on_quit) -> None:
    """Run a tray icon in a daemon thread; no-op when pystray is missing."""
    try:
        import pystray
        from PIL import Image, ImageDraw
    except Exception as exc:  # noqa: BLE001 - tray is optional
        logger.info("Tray unavailable (%s) - running headless", exc)
        return

    image = Image.new("RGB", (64, 64), (17, 17, 17))
    draw = ImageDraw.Draw(image)
    draw.rectangle((10, 30, 54, 44), fill=(182, 255, 59))
    draw.rectangle((10, 16, 26, 30), fill=(182, 255, 59))

    def open_pairing(icon, item) -> None:
        webbrowser.open(f"http://127.0.0.1:{config.port}/pair")

    def quit_app(icon, item) -> None:
        on_quit()
        icon.stop()

    menu = pystray.Menu(
        pystray.MenuItem("Pairing-Seite öffnen", open_pairing, default=True),
        pystray.MenuItem("Beenden", quit_app),
    )
    icon = pystray.Icon("LMU Laptimer", image, "LMU Laptimer Bridge", menu)
    threading.Thread(target=icon.run, name="tray", daemon=True).start()
    logger.info("Tray icon started")
