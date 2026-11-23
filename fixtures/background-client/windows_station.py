"""Supply STARTUPINFOW.lpDesktop for a child launched by a dedicated headless process."""
import ctypes
from ctypes import wintypes as wt
from contextlib import ExitStack, contextmanager
import os
from uuid import uuid4


class _SecurityAttributes(ctypes.Structure):
    _fields_ = [("length", wt.DWORD), ("descriptor", wt.LPVOID), ("inherit", wt.BOOL)]


class _UserObjectFlags(ctypes.Structure):
    _fields_ = [("inherit", wt.BOOL), ("reserved", wt.BOOL), ("flags", wt.DWORD)]


def _function(dll, name, returns, *parameters):
    fn = getattr(dll, name)
    fn.argtypes = parameters
    fn.restype = returns
    return fn


def _checked(value, operation):
    if not value:
        error = ctypes.get_last_error()
        raise OSError(error, f"{operation}: {ctypes.FormatError(error).strip()}")
    return value


def _current_user_descriptor(kernel, security, cleanup):
    # Use the process token so the created desktop can be opened by the same-user child.
    pointer = ctypes.POINTER
    open_token = _function(security, "OpenProcessToken", wt.BOOL, wt.HANDLE, wt.DWORD, pointer(wt.HANDLE))
    token_info = _function(security, "GetTokenInformation", wt.BOOL, wt.HANDLE, ctypes.c_int,
                           wt.LPVOID, wt.DWORD, pointer(wt.DWORD))
    sid_string = _function(security, "ConvertSidToStringSidW", wt.BOOL, wt.LPVOID, pointer(wt.LPWSTR))
    convert_acl = _function(security, "ConvertStringSecurityDescriptorToSecurityDescriptorW",
                            wt.BOOL, wt.LPCWSTR, wt.DWORD, pointer(wt.LPVOID), pointer(wt.DWORD))
    current_process = _function(kernel, "GetCurrentProcess", wt.HANDLE)
    close_handle = _function(kernel, "CloseHandle", wt.BOOL, wt.HANDLE)
    local_free = _function(kernel, "LocalFree", wt.HANDLE, wt.HANDLE)

    with ExitStack() as temp:
        token = wt.HANDLE()
        _checked(open_token(current_process(), 0x0008, ctypes.byref(token)), "OpenProcessToken(TOKEN_QUERY)")
        temp.callback(close_handle, token)
        size = wt.DWORD()
        if token_info(token, 1, None, 0, ctypes.byref(size)) or ctypes.get_last_error() != 122:
            raise RuntimeError("Could not determine the TOKEN_USER size")
        data = ctypes.create_string_buffer(size.value)
        _checked(token_info(token, 1, data, size, ctypes.byref(size)), "GetTokenInformation(TokenUser)")
        # The first field of TOKEN_USER is SID_AND_ATTRIBUTES.Sid.
        sid = ctypes.cast(data, pointer(wt.LPVOID))[0]
        text = wt.LPWSTR()
        _checked(sid_string(sid, ctypes.byref(text)), "ConvertSidToStringSidW")
        temp.callback(local_free, ctypes.cast(text, wt.LPVOID))
        descriptor = wt.LPVOID()
        _checked(convert_acl(f"D:P(A;;GA;;;{text.value})", 1, ctypes.byref(descriptor), None),
                 "Create same-user security descriptor")
        cleanup.callback(local_free, descriptor)
        return _SecurityAttributes(ctypes.sizeof(_SecurityAttributes), descriptor, False)


@contextmanager
def isolated_desktop():
    """Yield a noninteractive station\\desktop. Wait for the child before leaving the context."""
    if os.name != "nt":
        raise OSError("A private Windows window station requires Windows")
    user = ctypes.WinDLL("user32", use_last_error=True)
    kernel = ctypes.WinDLL("kernel32", use_last_error=True)
    security = ctypes.WinDLL("advapi32", use_last_error=True)
    pointer = ctypes.POINTER
    get_station = _function(user, "GetProcessWindowStation", wt.HANDLE)
    set_station = _function(user, "SetProcessWindowStation", wt.BOOL, wt.HANDLE)
    create_station = _function(user, "CreateWindowStationW", wt.HANDLE, wt.LPCWSTR, wt.DWORD,
                               wt.DWORD, pointer(_SecurityAttributes))
    create_desktop = _function(user, "CreateDesktopW", wt.HANDLE, wt.LPCWSTR, wt.LPCWSTR, wt.LPVOID,
                               wt.DWORD, wt.DWORD, pointer(_SecurityAttributes))
    info = _function(user, "GetUserObjectInformationW", wt.BOOL, wt.HANDLE, ctypes.c_int,
                     wt.LPVOID, wt.DWORD, pointer(wt.DWORD))
    close_station = _function(user, "CloseWindowStation", wt.BOOL, wt.HANDLE)
    close_desktop = _function(user, "CloseDesktop", wt.BOOL, wt.HANDLE)

    def object_name(handle):
        length = wt.DWORD()
        if info(handle, 2, None, 0, ctypes.byref(length)) or ctypes.get_last_error() != 122:
            raise RuntimeError("Could not determine the window object's name length")
        name = ctypes.create_unicode_buffer((length.value // ctypes.sizeof(ctypes.c_wchar)) + 1)
        _checked(info(handle, 2, name, ctypes.sizeof(name), ctypes.byref(length)), "Read window object name")
        return name.value

    original = _checked(get_station(), "GetProcessWindowStation")
    original_name = object_name(original)
    station_name = "AnyaBackFit-" + uuid4().hex
    desktop_name = "Client"
    with ExitStack() as cleanup:
        attributes = _current_user_descriptor(kernel, security, cleanup)
        station = _checked(create_station(station_name, 1, 0x10000000, ctypes.byref(attributes)),
                           "CreateWindowStationW(CWF_CREATE_ONLY)")
        cleanup.callback(lambda: _checked(close_station(station), "CloseWindowStation"))
        try:
            flags = _UserObjectFlags()
            size = wt.DWORD()
            _checked(info(station, 1, ctypes.byref(flags), ctypes.sizeof(flags), ctypes.byref(size)),
                     "Read station UOI_FLAGS")
            if size.value != ctypes.sizeof(flags) or flags.flags & 1 or object_name(station) != station_name:
                raise RuntimeError("Windows did not create the requested noninteractive window station")
            _checked(set_station(station), "Select private process window station")
            if object_name(_checked(get_station(), "GetProcessWindowStation")) != station_name:
                raise RuntimeError("Windows selected a different window station")
            desktop = _checked(create_desktop(desktop_name, None, None, 0, 0x10000000, ctypes.byref(attributes)),
                               "CreateDesktopW")
            cleanup.callback(lambda: _checked(close_desktop(desktop), "CloseDesktop"))
            if object_name(desktop) != desktop_name:
                raise RuntimeError("Windows created a different desktop")
        finally:
            _checked(set_station(original), "Restore original process window station")
            if object_name(_checked(get_station(), "GetProcessWindowStation")) != original_name:
                raise RuntimeError("Could not verify the restored process window station")
        yield station_name + "\\" + desktop_name
