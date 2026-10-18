"""Inference worker process: `python -m sharpr.worker '<job json>'`.

Runs the pipeline in its own process because ncnn holds the GIL during inference, which would freeze
the Kivy UI if it ran in a thread. Emits one JSON object per line prefixed with '@@' on stdout.
A line on stdin (or EOF, if the parent dies) cancels the job.
"""
from __future__ import annotations

import json
import sys
import threading
import traceback


def emit(kind: str, **kw):
    sys.stdout.write("@@" + json.dumps({"k": kind, **kw}) + "\n")
    sys.stdout.flush()


def main() -> int:
    from sharpr import engine, pipeline
    from sharpr.library import Library
    from sharpr.settings import Settings

    job = json.loads(sys.argv[1])
    cancel = threading.Event()

    def watch_stdin():
        try:
            sys.stdin.readline()
        finally:
            cancel.set()

    threading.Thread(target=watch_stdin, daemon=True).start()
    try:
        settings = Settings()
        opts = pipeline.Options.from_dict(job["options"])

        def progress(frac, tile, tiles, label, stage):
            emit("progress", frac=frac, tile=tile, tiles=tiles, label=label, stage=stage)

        res = pipeline.process(job["path"], opts, settings, override=bool(job.get("override")),
                               progress=progress, cancel=cancel)
        progress(1.0, res.info.tiles, res.info.tiles, res.info.backend_label, "Saving")
        item = Library().save_result(job["path"], res, settings["out_format"], int(settings["out_quality"]),
                                     name=job.get("name", ""), replace_id=job.get("replace_id"))
        emit("done", item=item, note=res.note, fell_back=res.info.fell_back,
             backend=res.info.backend_label, seconds=res.seconds)
    except engine.Cancelled:
        emit("cancel")
    except engine.ModelsMissing as e:
        emit("error", title="AI models not found", message=str(e), trace="")
    except pipeline.GuardError as e:
        emit("guard", title=e.title, message=e.message)
    except Exception as e:  # noqa: BLE001
        emit("error", message=str(e), trace=traceback.format_exc())
    return 0


if __name__ == "__main__":
    sys.exit(main())
