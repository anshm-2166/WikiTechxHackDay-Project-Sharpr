"""Sharpr desktop entry point."""
import os
import sys

os.environ.setdefault("KIVY_NO_ARGS", "1")
os.environ.setdefault("KIVY_NO_CONSOLELOG", "0")
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from kivy.config import Config  # noqa: E402

Config.set("input", "mouse", "mouse,disable_multitouch")
Config.set("kivy", "exit_on_escape", "0")
Config.set("graphics", "multisamples", "4")
Config.set("graphics", "resizable", "1")

from sharpr.app import SharprApp  # noqa: E402

if __name__ == "__main__":
    SharprApp().run()
