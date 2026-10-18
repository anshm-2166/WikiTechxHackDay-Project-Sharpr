from kivy.app import App
from kivy.metrics import dp
from kivy.uix.screenmanager import Screen


class BaseScreen(Screen):
    key = ""

    @property
    def app(self):
        return App.get_running_app()

    def on_show(self, **kw):
        pass

    # responsive layout -----------------------------------------------------
    def setup_responsive(self, root, top=16, bottom=0, wide_max=0, wide_pad=24):
        """Keep `root.padding` in step with the window mode.

        phone: 16dp gutters. column: content centred in a 480dp column.
        wide: `wide_pad` gutters (or a centred `wide_max` column when given).
        """
        self._resp = (root, top, bottom, wide_max, wide_pad)
        root.bind(width=lambda *_: self._apply_pad())
        self.app.bind(mode=lambda *_: self._mode_changed())
        self._apply_pad()

    def _apply_pad(self):
        root, top, bottom, wide_max, wide_pad = self._resp
        mode, w = self.app.mode, root.width
        if mode == "phone":
            h = dp(16)
        elif mode == "column":
            h = max((w - dp(480)) / 2, dp(16))
        else:
            h = dp(wide_pad)
            if wide_max:
                h = max((w - dp(wide_max)) / 2, h)
        root.padding = [h, dp(top), h, dp(bottom)]

    def _mode_changed(self):
        self._apply_pad()
        self.on_mode(self.app.mode)

    def on_mode(self, mode):
        pass
