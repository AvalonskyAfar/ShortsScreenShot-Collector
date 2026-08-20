#!/usr/bin/env python3
"""Windows receiver for Short Video Collector. Uses only Python's standard library."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import queue
import re
import secrets
import socket
import sys
import tempfile
import threading
import time
import uuid
import webbrowser
from dataclasses import dataclass, field
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Callable
from urllib.parse import parse_qs, urlparse

APP_VERSION = "1.1.0-portable"
MAX_IMAGE_BYTES = 30 * 1024 * 1024
VIDEO_RE = re.compile(r"^video_(\d{6})$")
PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"


def application_dir() -> Path:
    """Return the directory that should travel with the portable receiver."""
    if getattr(sys, "frozen", False):
        return Path(sys.executable).resolve().parent
    return Path(__file__).resolve().parent.parent


def default_config_path(base: Path | None = None) -> Path:
    return (base or application_dir()) / "receiver-config.json"


def local_ipv4_addresses() -> list[str]:
    found: set[str] = set()
    try:
        host = socket.gethostname()
        for item in socket.getaddrinfo(host, None, socket.AF_INET):
            ip = item[4][0]
            if not ip.startswith("127.") and not ip.startswith("169.254."):
                found.add(ip)
    except OSError:
        pass
    try:
        probe = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        probe.connect(("8.8.8.8", 80))
        found.add(probe.getsockname()[0])
        probe.close()
    except OSError:
        pass
    return sorted(found) or ["127.0.0.1"]


@dataclass
class Session:
    id: str
    created: float = field(default_factory=time.time)
    videos: set[int] = field(default_factory=set)


class ReceiverCore:
    def __init__(self, dataset: Path, token: str, logger: Callable[[str], None] | None = None):
        self.dataset = dataset.resolve()
        self.token = token
        self.logger = logger or (lambda message: None)
        self.lock = threading.RLock()
        self.sessions: dict[str, Session] = {}
        self.next_video = 1
        self.frames_received = 0
        self._prepare_dataset()

    def _prepare_dataset(self) -> None:
        self.dataset.mkdir(parents=True, exist_ok=True)
        maximum = 0
        for item in self.dataset.iterdir():
            if item.is_dir() and (match := VIDEO_RE.match(item.name)):
                maximum = max(maximum, int(match.group(1)))
        self.next_video = maximum + 1

    def authenticate(self, supplied: str | None) -> bool:
        return bool(supplied) and secrets.compare_digest(supplied, self.token)

    def new_session(self) -> Session:
        with self.lock:
            session = Session(uuid.uuid4().hex)
            self.sessions[session.id] = session
            self.logger(f"手机已连接，会话 {session.id[:8]}")
            return session

    def require_session(self, session_id: str | None) -> Session | None:
        if not session_id:
            return None
        with self.lock:
            return self.sessions.get(session_id)

    def new_video(self, session: Session) -> int:
        with self.lock:
            number = self.next_video
            while (self.dataset / f"video_{number:06d}").exists():
                number += 1
            folder = self.dataset / f"video_{number:06d}"
            folder.mkdir(parents=False, exist_ok=False)
            self.next_video = number + 1
            session.videos.add(number)
            self.logger(f"新视频：video_{number:06d}")
            return number

    def save_frame(self, session: Session, video: int, frame: int, payload: bytes) -> str:
        if video not in session.videos:
            raise PermissionError("该视频目录不属于当前会话")
        if not (1 <= frame <= 999999):
            raise ValueError("帧编号超出范围")
        if not payload.startswith(PNG_SIGNATURE) or b"IEND" not in payload[-32:]:
            raise ValueError("请求内容不是完整 PNG")

        folder = self.dataset / f"video_{video:06d}"
        final_path = folder / f"frame_{frame:06d}.png"
        with self.lock:
            if final_path.exists():
                current = final_path.read_bytes()
                if hashlib.sha256(current).digest() == hashlib.sha256(payload).digest():
                    return "duplicate"
                raise FileExistsError("该帧编号已存在且内容不同")

            fd, temporary_name = tempfile.mkstemp(prefix=f".{final_path.name}.", suffix=".part", dir=folder)
            try:
                with os.fdopen(fd, "wb") as stream:
                    stream.write(payload)
                    stream.flush()
                    os.fsync(stream.fileno())
                os.replace(temporary_name, final_path)
            except BaseException:
                try:
                    os.unlink(temporary_name)
                except OSError:
                    pass
                raise
            self.frames_received += 1
        self.logger(f"已保存 video_{video:06d}/frame_{frame:06d}.png")
        return "saved"

    def end_session(self, session_id: str) -> None:
        with self.lock:
            if self.sessions.pop(session_id, None):
                self.logger(f"会话结束 {session_id[:8]}")


def make_handler(core: ReceiverCore):
    class Handler(BaseHTTPRequestHandler):
        server_version = "ShortVideoCollector/1.0"

        def log_message(self, _format: str, *args) -> None:
            return

        def _json(self, status: int, payload: dict) -> None:
            body = json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(body)

        def _token(self) -> str | None:
            value = self.headers.get("Authorization", "")
            return value[7:] if value.startswith("Bearer ") else self.headers.get("X-Pair-Token")

        def _authorized(self) -> bool:
            if core.authenticate(self._token()):
                return True
            self._json(HTTPStatus.UNAUTHORIZED, {"error": "配对码错误"})
            return False

        def do_GET(self) -> None:
            path = urlparse(self.path).path
            if path == "/api/v1/health":
                self._json(HTTPStatus.OK, {
                    "service": "ShortVideoCollector",
                    "version": APP_VERSION,
                    "ready": True,
                })
            else:
                self._json(HTTPStatus.NOT_FOUND, {"error": "路径不存在"})

        def do_POST(self) -> None:
            if not self._authorized():
                return
            parsed = urlparse(self.path)
            try:
                if parsed.path == "/api/v1/session":
                    session = core.new_session()
                    self._json(HTTPStatus.CREATED, {"sessionId": session.id})
                    return
                session_id = self.headers.get("X-Session-Id")
                session = core.require_session(session_id)
                if session is None:
                    self._json(HTTPStatus.CONFLICT, {"error": "会话无效，请重新开始"})
                    return
                if parsed.path == "/api/v1/video":
                    video = core.new_video(session)
                    self._json(HTTPStatus.CREATED, {"video": video})
                    return
                if parsed.path == "/api/v1/frame":
                    query = parse_qs(parsed.query)
                    video = int(query.get("video", ["0"])[0])
                    frame = int(query.get("frame", ["0"])[0])
                    length = int(self.headers.get("Content-Length", "0"))
                    if length <= 0 or length > MAX_IMAGE_BYTES:
                        self._json(HTTPStatus.REQUEST_ENTITY_TOO_LARGE, {"error": "图片大小无效"})
                        return
                    payload = self.rfile.read(length)
                    if len(payload) != length:
                        self._json(HTTPStatus.BAD_REQUEST, {"error": "图片传输不完整"})
                        return
                    result = core.save_frame(session, video, frame, payload)
                    self._json(HTTPStatus.OK, {"result": result})
                    return
                if parsed.path == "/api/v1/session/end":
                    core.end_session(session.id)
                    self._json(HTTPStatus.OK, {"result": "ended"})
                    return
                self._json(HTTPStatus.NOT_FOUND, {"error": "路径不存在"})
            except ValueError as error:
                self._json(HTTPStatus.BAD_REQUEST, {"error": str(error)})
            except PermissionError as error:
                self._json(HTTPStatus.FORBIDDEN, {"error": str(error)})
            except FileExistsError as error:
                self._json(HTTPStatus.CONFLICT, {"error": str(error)})
            except OSError as error:
                core.logger(f"保存失败：{error}")
                self._json(HTTPStatus.INTERNAL_SERVER_ERROR, {"error": "文件保存失败"})

    return Handler


class ReusableThreadingHTTPServer(ThreadingHTTPServer):
    allow_reuse_address = True
    daemon_threads = True


def load_config(path: Path, project_dir: Path) -> dict:
    defaults = {
        "host": "0.0.0.0",
        "port": 8765,
        "token": f"{secrets.randbelow(1_000_000):06d}",
        "dataset": str((project_dir / "dataset").resolve()),
    }
    try:
        saved = json.loads(path.read_text(encoding="utf-8"))
        for key in defaults:
            if key in saved:
                defaults[key] = saved[key]
    except (OSError, ValueError, TypeError):
        pass
    dataset = Path(str(defaults["dataset"])).expanduser()
    if not dataset.is_absolute():
        dataset = project_dir / dataset
    defaults["dataset"] = str(dataset.resolve())
    return defaults


def save_config(path: Path, config: dict, project_dir: Path | None = None) -> None:
    portable = dict(config)
    base = (project_dir or path.parent).resolve()
    dataset = Path(str(portable.get("dataset", "dataset"))).expanduser().resolve()
    try:
        portable["dataset"] = str(dataset.relative_to(base))
    except ValueError:
        portable["dataset"] = str(dataset)
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(".tmp")
    temporary.write_text(json.dumps(portable, ensure_ascii=False, indent=2), encoding="utf-8")
    os.replace(temporary, path)


def run_headless(host: str, port: int, dataset: Path, token: str) -> None:
    core = ReceiverCore(dataset, token, print)
    server = ReusableThreadingHTTPServer((host, port), make_handler(core))
    print(f"短视频采集接收端 {APP_VERSION}")
    print(f"监听：{host}:{port}")
    print(f"局域网 IP：{', '.join(local_ipv4_addresses())}")
    print(f"配对码：{token}")
    print(f"目录：{core.dataset}")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


def run_gui(config_path: Path, config: dict, project_dir: Path) -> None:
    import tkinter as tk
    from tkinter import filedialog, messagebox, ttk

    root = tk.Tk()
    root.title("短视频图片接收端")
    root.geometry("720x560")
    root.minsize(620, 480)
    events: queue.Queue[str] = queue.Queue()
    holder: dict[str, object] = {}

    host_var = tk.StringVar(value=str(config["host"]))
    port_var = tk.StringVar(value=str(config["port"]))
    token_var = tk.StringVar(value=str(config["token"]))
    dataset_var = tk.StringVar(value=str(config["dataset"]))
    state_var = tk.StringVar(value="尚未启动")
    address_var = tk.StringVar(value=" / ".join(local_ipv4_addresses()))

    frame = ttk.Frame(root, padding=16)
    frame.pack(fill="both", expand=True)
    ttk.Label(frame, text="短视频图片接收端", font=("Microsoft YaHei UI", 18, "bold")).grid(row=0, column=0, columnspan=3, sticky="w", pady=(0, 16))

    labels = [("电脑局域网 IP", address_var), ("监听地址", host_var), ("端口", port_var), ("配对码", token_var), ("数据集目录", dataset_var)]
    for row, (label, variable) in enumerate(labels, start=1):
        ttk.Label(frame, text=label).grid(row=row, column=0, sticky="w", pady=4)
        entry = ttk.Entry(frame, textvariable=variable)
        entry.grid(row=row, column=1, sticky="ew", padx=(12, 8), pady=4)
        if row == 1:
            entry.configure(state="readonly")
    ttk.Button(frame, text="选择目录", command=lambda: choose_dataset()).grid(row=5, column=2, sticky="ew")

    button_frame = ttk.Frame(frame)
    button_frame.grid(row=6, column=0, columnspan=3, sticky="ew", pady=14)
    start_button = ttk.Button(button_frame, text="启动接收", command=lambda: start_server())
    stop_button = ttk.Button(button_frame, text="停止", state="disabled", command=lambda: stop_server())
    open_button = ttk.Button(button_frame, text="打开数据集", command=lambda: open_dataset())
    start_button.pack(side="left", padx=(0, 8))
    stop_button.pack(side="left", padx=8)
    open_button.pack(side="left", padx=8)
    ttk.Label(button_frame, textvariable=state_var).pack(side="right")

    log = tk.Text(frame, height=16, state="disabled", font=("Consolas", 9), wrap="word")
    log.grid(row=7, column=0, columnspan=3, sticky="nsew")
    frame.columnconfigure(1, weight=1)
    frame.rowconfigure(7, weight=1)

    def append(message: str) -> None:
        timestamp = time.strftime("%H:%M:%S")
        events.put(f"[{timestamp}] {message}\n")

    def drain_events() -> None:
        changed = False
        log.configure(state="normal")
        while True:
            try:
                log.insert("end", events.get_nowait())
                changed = True
            except queue.Empty:
                break
        if changed:
            log.see("end")
        log.configure(state="disabled")
        root.after(150, drain_events)

    def choose_dataset() -> None:
        selected = filedialog.askdirectory(initialdir=dataset_var.get())
        if selected:
            dataset_var.set(selected)

    def open_dataset() -> None:
        path = Path(dataset_var.get()).expanduser().resolve()
        path.mkdir(parents=True, exist_ok=True)
        os.startfile(path)  # type: ignore[attr-defined]

    def set_inputs(enabled: bool) -> None:
        for child in frame.winfo_children():
            if isinstance(child, ttk.Entry) and str(child.cget("state")) != "readonly":
                child.configure(state="normal" if enabled else "disabled")

    def start_server() -> None:
        try:
            port = int(port_var.get())
            if not 1 <= port <= 65535:
                raise ValueError
            token = token_var.get().strip()
            if len(token) < 4:
                raise ValueError("配对码至少 4 位")
            dataset = Path(dataset_var.get()).expanduser().resolve()
            core = ReceiverCore(dataset, token, append)
            server = ReusableThreadingHTTPServer((host_var.get().strip(), port), make_handler(core))
        except (ValueError, OSError) as error:
            messagebox.showerror("无法启动", str(error) or "端口格式不正确")
            return
        holder["server"] = server
        holder["thread"] = threading.Thread(target=server.serve_forever, name="receiver-http", daemon=True)
        holder["thread"].start()  # type: ignore[union-attr]
        actual = {"host": host_var.get(), "port": port, "token": token, "dataset": str(dataset)}
        save_config(config_path, actual, project_dir)
        state_var.set("运行中")
        start_button.configure(state="disabled")
        stop_button.configure(state="normal")
        set_inputs(False)
        append(f"接收端已启动，手机填写 {address_var.get().split(' / ')[0]}:{port}")
        append(f"配对码：{token}")
        append(f"保存目录：{dataset}")

    def stop_server() -> None:
        server = holder.pop("server", None)
        if server:
            threading.Thread(target=lambda: (server.shutdown(), server.server_close()), daemon=True).start()  # type: ignore[union-attr]
        state_var.set("已停止")
        start_button.configure(state="normal")
        stop_button.configure(state="disabled")
        set_inputs(True)
        append("接收端已停止")

    def close() -> None:
        server = holder.get("server")
        if server:
            server.shutdown()  # type: ignore[union-attr]
            server.server_close()  # type: ignore[union-attr]
        root.destroy()

    root.protocol("WM_DELETE_WINDOW", close)
    root.after(100, drain_events)
    root.after(250, start_server)
    root.mainloop()


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="短视频图片数据采集器 Windows 接收端")
    parser.add_argument("--headless", action="store_true", help="无界面运行")
    parser.add_argument("--host", default=None)
    parser.add_argument("--port", type=int, default=None)
    parser.add_argument("--dataset", type=Path, default=None)
    parser.add_argument("--token", default=None)
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv or sys.argv[1:])
    project_dir = application_dir()
    config_path = default_config_path(project_dir)
    config = load_config(config_path, project_dir)
    if args.host is not None:
        config["host"] = args.host
    if args.port is not None:
        config["port"] = args.port
    if args.dataset is not None:
        config["dataset"] = str(args.dataset.resolve())
    if args.token is not None:
        config["token"] = args.token
    if args.headless:
        run_headless(str(config["host"]), int(config["port"]), Path(config["dataset"]), str(config["token"]))
    else:
        run_gui(config_path, config, project_dir)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
