"""Hosted Windows check using the same GLFW and OpenAL libraries as Minecraft 1.21.1."""
import ctypes as c
from ctypes import wintypes as w
import hashlib
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import urllib.request
from zipfile import ZipFile

from windows_station import isolated_desktop


def require(value, message):
    if not value:
        raise RuntimeError(message)


def runtime_library(directory, name, filename, expected):
    url = f"https://libraries.minecraft.net/org/lwjgl/lwjgl-{name}/3.3.3/lwjgl-{name}-3.3.3-natives-windows.jar"
    with urllib.request.urlopen(url, timeout=30) as source:
        payload = source.read()
    require(hashlib.sha1(payload).hexdigest() == expected, "Minecraft library hash mismatch")
    with ZipFile(io.BytesIO(payload)) as archive:
        (directory / filename).write_bytes(archive.read(f"windows/x64/org/lwjgl/{name}/{filename}"))


def cursor():
    point, rect = w.POINT(), w.RECT()
    user = c.windll.user32
    pos = [point.x, point.y] if user.GetCursorPos(c.byref(point)) else None
    clip = [rect.left, rect.top, rect.right, rect.bottom] if user.GetClipCursor(c.byref(rect)) else None
    return pos, clip


def child(directory, expected_station):
    user = c.windll.user32
    user.GetProcessWindowStation.restype = w.HANDLE
    station = user.GetProcessWindowStation()
    station_name = c.create_unicode_buffer(256)
    flags = (w.DWORD * 3)()
    used = w.DWORD()
    for key, value in [(2, station_name), (1, flags)]:
        require(user.GetUserObjectInformationW(w.HANDLE(station), key, c.byref(value), c.sizeof(value), c.byref(used)),
                "Could not read child window station")
    require(station_name.value == expected_station and station_name.value.casefold() != "winsta0"
            and flags[2] & 1 == 0, "Child reached an interactive or unexpected window station")
    user.OpenInputDesktop.restype = w.HANDLE
    require(not user.OpenInputDesktop(0, False, 1), "Child could access the input desktop")
    # Exercise GLFW's disabled-cursor and cursor-warp path only on this isolated hosted station.
    glfw = c.CDLL(str(directory / "glfw.dll"))
    window = None
    if glfw.glfwInit():
        glfw.glfwWindowHint(0x00022001, 0)  # GLFW_CLIENT_API = GLFW_NO_API
        glfw.glfwCreateWindow.argtypes = [c.c_int, c.c_int, c.c_char_p, c.c_void_p, c.c_void_p]
        glfw.glfwCreateWindow.restype = c.c_void_p
        window = glfw.glfwCreateWindow(160, 100, b"Isolated input check", None, None)
        if window:
            glfw.glfwSetInputMode(c.c_void_p(window), 0x00033001, 0x00034003)
            glfw.glfwSetCursorPos(c.c_void_p(window), c.c_double(10), c.c_double(10))
            glfw.glfwPollEvents()
    require(os.environ.get("ALSOFT_DRIVERS") == "null", "Expected muted process")
    al = c.CDLL(str(directory / "OpenAL.dll"))
    al.alcOpenDevice.argtypes = [c.c_char_p]
    al.alcOpenDevice.restype = c.c_void_p
    al.alcGetString.argtypes = [c.c_void_p, c.c_int]
    al.alcGetString.restype = c.c_char_p
    device = al.alcOpenDevice(None)
    audio = "no device available"
    if device:
        audio = (al.alcGetString(device, 0x1013) or b"").decode("utf-8")  # ALC_ALL_DEVICES_SPECIFIER
        require("no output" in audio.casefold(), "Unexpected audio output: " + audio)
        al.alcCloseDevice(c.c_void_p(device))
    (directory / "result.json").write_text(json.dumps(dict(
        station=station_name.value, input_desktop=False, glfw_window=bool(window), audio=audio)))
    if window:
        glfw.glfwDestroyWindow(c.c_void_p(window))
    glfw.glfwTerminate()


def main():
    import win32event
    import win32process

    with tempfile.TemporaryDirectory() as temp:
        directory = Path(temp)
        runtime_library(directory, "glfw", "glfw.dll", "e449e28b4891fc423c54c85fbc5bb0b9efece67a")
        runtime_library(directory, "openal", "OpenAL.dll", "cf83862ae95d98496b26915024c7e666d8ab1c8f")
        before = cursor()
        with isolated_desktop() as desktop:
            info = win32process.STARTUPINFO()
            info.lpDesktop = desktop
            command = subprocess.list2cmdline([sys.executable, "-B", str(Path(__file__).resolve()),
                                               "--child", temp, desktop.split("\\")[0]])
            env = dict(os.environ, ALSOFT_DRIVERS="null")
            process, thread, _, _ = win32process.CreateProcess(sys.executable, command, None, None, False,
                                                               0x08000400, env, temp, info)
            try:
                if win32event.WaitForSingleObject(process, 45000) != 0:
                    win32process.TerminateProcess(process, 1)
                    win32event.WaitForSingleObject(process, 5000)
                    raise RuntimeError("Isolated client timed out")
                if (directory / "error.txt").is_file():
                    raise RuntimeError((directory / "error.txt").read_text())
                require(win32process.GetExitCodeProcess(process) == 0, "Isolated child failed")
            finally:
                thread.Close()
                process.Close()
        require(before == cursor(), "Parent pointer position or confinement changed during the isolated test")
        print("Parent cursor state:", before)
        print("Private station and audio check:", (directory / "result.json").read_text())


if __name__ == "__main__":
    require(os.environ.get("GITHUB_ACTIONS") == "true" and os.name == "nt", "Hosted Windows test only")
    if sys.argv[1:2] == ["--child"]:
        try:
            child(Path(sys.argv[2]), sys.argv[3])
        except Exception as exc:
            (Path(sys.argv[2]) / "error.txt").write_text(str(exc))
            raise
    else:
        main()
