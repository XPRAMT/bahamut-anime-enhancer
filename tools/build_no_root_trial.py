#!/usr/bin/env python3
"""Build a signed, offline no-root trial split set from the pulled Anime APKs.

This first direct port deliberately changes only the already-verified double-tap behavior:
every double tap calls the app's own play/pause handler instead of its left/right seek handlers.
It never installs, uninstalls, or otherwise changes the connected device.
"""

from __future__ import annotations

import argparse
import hashlib
import re
import shutil
import subprocess
import sys
import zipfile
from pathlib import Path


PROJECT_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_APKTOOL = Path(r"C:\APP\apktool.bat")
DEFAULT_BUILD_TOOLS = Path(r"C:\Users\XPRAMT\AppData\Local\Android\Sdk\build-tools\36.0.0")
DEFAULT_KEYSTORE = Path(r"C:\Users\XPRAMT\.android\debug.keystore")
DEFAULT_ANDROID_JAR = Path(r"C:\Users\XPRAMT\AppData\Local\Android\Sdk\platforms\android-35\android.jar")
SPLITS = (
    "split_config.arm64_v8a.apk",
    "split_config.xxhdpi.apk",
    "split_config.zh.apk",
)
GESTURE_LISTENERS = (
    (
        "smali_classes7/tw/com/gamer/android/animad/player/AnimeController$GestureListener.smali",
        "Ltw/com/gamer/android/animad/player/AnimeController$GestureListener;",
    ),
    (
        "smali_classes7/tw/com/gamer/android/animad/player/portrait/PortraitAnimeController$GestureListener.smali",
        "Ltw/com/gamer/android/animad/player/portrait/PortraitAnimeController$GestureListener;",
    ),
)
GESTURE_CONTROLLERS = (
    (
        "smali_classes7/tw/com/gamer/android/animad/player/AnimeController.smali",
        "Ltw/com/gamer/android/animad/player/AnimeController;",
        True,
    ),
    (
        "smali_classes7/tw/com/gamer/android/animad/player/portrait/PortraitAnimeController.smali",
        "Ltw/com/gamer/android/animad/player/portrait/PortraitAnimeController;",
        False,
    ),
    (
        "smali_classes7/tw/com/gamer/android/animad/party/ui/PartyController.smali",
        "Ltw/com/gamer/android/animad/party/ui/PartyController;",
        False,
    ),
)


def run(command: list[str]) -> None:
    print("+", subprocess.list2cmdline(command))
    subprocess.run(command, check=True)


def require_file(path: Path, description: str) -> None:
    if not path.is_file():
        raise RuntimeError(f"Missing {description}: {path}")


def patch_double_tap(listener_path: Path, class_descriptor: str) -> None:
    original = listener_path.read_text(encoding="utf-8")
    method_start = original.index(".method public onDoubleTap(Landroid/view/MotionEvent;)Z")
    method_end = original.index(".end method", method_start) + len(".end method")
    method = original[method_start:method_end]
    replacements = (
        (
            f"invoke-direct {{p0}}, {class_descriptor}->onForwardZoneTapped()V",
            f"invoke-direct {{p0}}, {class_descriptor}->onPlayPauseZoneTapped()V",
        ),
        (
            f"invoke-direct {{p0}}, {class_descriptor}->onRewindZoneTapped()V",
            f"invoke-direct {{p0}}, {class_descriptor}->onPlayPauseZoneTapped()V",
        ),
    )
    patched_method = method
    for old, new in replacements:
        old_occurrences = patched_method.count(old)
        if old_occurrences == 1:
            patched_method = patched_method.replace(old, new, 1)
        elif old_occurrences == 0:
            continue
        else:
            raise RuntimeError(
                f"Expected at most one {old} call in {listener_path.name}; "
                f"found {old_occurrences}."
            )
    patched = original[:method_start] + patched_method + original[method_end:]
    if patched != original:
        listener_path.write_text(patched, encoding="utf-8", newline="\n")


def replace_single(source: str, old: str, new: str, description: str) -> str:
    occurrences = source.count(old)
    if occurrences != 1:
        raise RuntimeError(f"Expected one {description}; found {occurrences}.")
    return source.replace(old, new, 1)


def patch_controller_gestures(controller_path: Path, class_descriptor: str,
                              disable_fast_forward: bool) -> None:
    """Delegate three native controllers to the shared in-process LSPosed state machine."""
    source = controller_path.read_text(encoding="utf-8")
    touch_marker = ".locals 9\n"
    touch_hook = (
        ".locals 9\n\n"
        "    invoke-static {p0, p1, p2}, Ltw/com/gamer/android/animad/NoRootGestureSupport;"
        "->trackTouch(Ljava/lang/Object;Landroid/view/View;Landroid/view/MotionEvent;)V\n"
    )
    method_start = source.index(".method public onTouch(Landroid/view/View;Landroid/view/MotionEvent;)Z")
    method_end = source.index(".end method", method_start) + len(".end method")
    prefix = source[:method_start]
    method = source[method_start:method_end]
    method = replace_single(method, touch_marker, touch_hook,
                            f"onTouch entry in {controller_path.name}")
    source = prefix + method + source[method_end:]

    for widget, helper_name, label in (
        ("showBrightnessWidget", "applyBrightness", "no_root_brightness"),
        ("showVolumeWidget", "applyVolume", "no_root_volume"),
    ):
        marker = f".method private {widget}(F)V\n"
        start = source.index(marker)
        end = source.index(".end method", start) + len(".end method")
        method = source[start:end]
        locals_marker = method.split("\n", 2)[0] + "\n" + method.split("\n", 2)[1] + "\n"
        hook = (
            locals_marker + "\n"
            f"    invoke-static {{p0}}, Ltw/com/gamer/android/animad/NoRootGestureSupport;->{helper_name}(Ljava/lang/Object;)Z\n\n"
            "    move-result v0\n\n"
            f"    if-eqz v0, :{label}_original\n\n"
            "    return-void\n\n"
            f"    :{label}_original\n"
        )
        method = replace_single(method, locals_marker, hook,
                                f"{widget} helper gate in {controller_path.name}")
        source = source[:start] + method + source[end:]

    if disable_fast_forward:
        source = replace_single(
            source,
            "    if-nez p1, :cond_1\n\n    .line 20",
            "    goto :cond_1\n\n    .line 20",
            f"long-press scheduler gate in {controller_path.name}",
        )
        fast_forward_pattern = re.compile(
            r"\.method private registerFastForward2xEvent\(\)V\n.*?\.end method",
            re.DOTALL,
        )
        fast_forward_method = ".method private registerFastForward2xEvent()V\n    .locals 0\n\n    return-void\n.end method"
        source, fast_forward_changes = fast_forward_pattern.subn(fast_forward_method, source, count=1)
        if fast_forward_changes != 1:
            raise RuntimeError(f"Could not disable long-press fast forward in {controller_path.name}.")

    controller_path.write_text(source, encoding="utf-8", newline="\n")


def build_gesture_support(work: Path, build_tools: Path, android_jar: Path) -> Path:
    source = PROJECT_ROOT / "tools" / "no_root_support" / "src" / "tw" / "com" / "gamer" / "android" / "animad" / "NoRootGestureSupport.java"
    require_file(source, "no-root gesture support source")
    require_file(android_jar, "Android platform jar")
    javac = Path(shutil.which("javac") or "")
    require_file(javac, "Java compiler")
    d8 = build_tools / "d8.bat"
    require_file(d8, "d8")
    classes = work / "support-classes"
    dex_output = work / "support-dex"
    classes.mkdir(parents=True, exist_ok=True)
    dex_output.mkdir(parents=True, exist_ok=True)
    run([str(javac), "-encoding", "UTF-8", "-source", "17", "-target", "17",
         "-classpath", str(android_jar), "-d", str(classes), str(source)])
    class_files = sorted(classes.rglob("*.class"))
    if not class_files:
        raise RuntimeError("Java compiler did not produce any no-root gesture support classes.")
    run([str(d8), "--lib", str(android_jar), "--min-api", "31", "--output", str(dex_output),
         *(str(class_file) for class_file in class_files)])
    dex = dex_output / "classes.dex"
    require_file(dex, "compiled no-root gesture support dex")
    return dex


def align_and_sign(source: Path, destination: Path, zipalign: Path, java: Path,
                   apksigner_jar: Path, keystore: Path) -> None:
    aligned = destination.with_suffix(".aligned.apk")
    run([str(zipalign), "-f", "-p", "4", str(source), str(aligned)])
    run([
        str(java), "-jar", str(apksigner_jar), "sign",
        "--ks", str(keystore),
        "--ks-key-alias", "androiddebugkey",
        "--ks-pass", "pass:android",
        "--key-pass", "pass:android",
        "--out", str(destination),
        str(aligned),
    ])
    aligned.unlink()
    run([str(java), "-jar", str(apksigner_jar), "verify", "--verbose", str(destination)])


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def replace_patched_dex(original_base: Path, rebuilt_base: Path, destination: Path) -> None:
    """Copy the official base APK, changing patched classes7.dex and adding classes8.dex.

    APKTool resource recompilation is deliberately avoided here: it can change resource
    lookup behavior even when no resource was intentionally edited.  The only source
    edits in this trial are in classes7.dex plus an added helper classes8.dex, so the manifest, resources.arsc, and every
    resource payload are taken directly from the official base APK.
    """
    dex_name = "classes7.dex"
    support_dex_name = "classes8.dex"
    with zipfile.ZipFile(original_base, "r") as original, zipfile.ZipFile(rebuilt_base, "r") as rebuilt:
        original_names = original.namelist()
        if dex_name not in original_names or dex_name not in rebuilt.namelist() or support_dex_name not in rebuilt.namelist():
            raise RuntimeError(f"Expected {dex_name} and {support_dex_name} in the rebuilt base APK.")
        patched_dex = rebuilt.read(dex_name)
        original_dex = original.read(dex_name)
        if patched_dex == original_dex:
            raise RuntimeError(f"Patched {dex_name} is byte-identical to the official dex.")
        support_dex = rebuilt.read(support_dex_name)
        for expected in (b"onPlayPauseZoneTapped",):
            if expected not in patched_dex:
                raise RuntimeError(f"Patched {dex_name} is missing expected symbol: {expected!r}.")
        if b"NoRootGestureSupport" not in support_dex:
            raise RuntimeError("Compiled helper dex is missing NoRootGestureSupport.")

        with zipfile.ZipFile(destination, "w") as rebuilt_archive:
            for entry in original.infolist():
                payload = patched_dex if entry.filename == dex_name else original.read(entry.filename)
                rebuilt_archive.writestr(entry, payload)
            support_entry = rebuilt.getinfo(support_dex_name)
            rebuilt_archive.writestr(support_entry, support_dex)

    with zipfile.ZipFile(original_base, "r") as original, zipfile.ZipFile(destination, "r") as result:
        if result.namelist() != [*original.namelist(), support_dex_name]:
            raise RuntimeError("Dex-only package entry list differs from the official base APK.")
        changed = []
        for entry in original.infolist():
            before = original.read(entry.filename)
            after = result.read(entry.filename)
            if sha256(before) != sha256(after):
                changed.append(entry.filename)
        if changed != [dex_name]:
            raise RuntimeError(f"Dex-only package changed unexpected entries: {changed}")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, default=PROJECT_ROOT / "reverse" / "apk")
    parser.add_argument("--work", type=Path, default=PROJECT_ROOT / "no-root" / "work")
    parser.add_argument("--output", type=Path, default=PROJECT_ROOT / "no-root" / "output")
    parser.add_argument("--apktool", type=Path, default=DEFAULT_APKTOOL)
    parser.add_argument("--build-tools", type=Path, default=DEFAULT_BUILD_TOOLS)
    parser.add_argument("--android-jar", type=Path, default=DEFAULT_ANDROID_JAR)
    parser.add_argument("--keystore", type=Path, default=DEFAULT_KEYSTORE)
    arguments = parser.parse_args()

    source = arguments.source.resolve()
    work = arguments.work.resolve()
    output = arguments.output.resolve()
    decoded = work / "base"
    require_file(source / "base.apk", "base APK")
    for split in SPLITS:
        require_file(source / split, f"split APK {split}")
    require_file(arguments.apktool, "apktool")
    require_file(arguments.keystore, "debug keystore")
    zipalign = arguments.build_tools / "zipalign.exe"
    apksigner_jar = arguments.build_tools / "lib" / "apksigner.jar"
    require_file(zipalign, "zipalign")
    require_file(apksigner_jar, "apksigner.jar")
    java = Path(shutil.which("java") or "")
    require_file(java, "Java runtime")
    if output.exists() and any(output.iterdir()):
        raise RuntimeError("Refusing to overwrite prior trial output; choose a new --output path.")
    if decoded.exists():
        print(f"Reusing existing decoded base: {decoded}")
    else:
        decoded.parent.mkdir(parents=True, exist_ok=True)
        run([str(arguments.apktool), "d", "-o", str(decoded), str(source / "base.apk")])
    output.mkdir(parents=True, exist_ok=True)
    for relative_path, class_descriptor in GESTURE_LISTENERS:
        listener_path = decoded / relative_path
        require_file(listener_path, f"gesture listener {relative_path}")
        patch_double_tap(listener_path, class_descriptor)
    for relative_path, class_descriptor, disable_fast_forward in GESTURE_CONTROLLERS:
        controller_path = decoded / relative_path
        require_file(controller_path, f"gesture controller {relative_path}")
        patch_controller_gestures(controller_path, class_descriptor, disable_fast_forward)

    support_dex = build_gesture_support(work, arguments.build_tools, arguments.android_jar)
    shutil.copyfile(support_dex, decoded / "classes8.dex")

    apktool_rebuilt_base = work / "base-unsigned.apk"
    dex_only_base = work / "base-dex-only-unsigned.apk"
    run([str(arguments.apktool), "b", "-o", str(apktool_rebuilt_base), str(decoded)])
    replace_patched_dex(source / "base.apk", apktool_rebuilt_base, dex_only_base)
    align_and_sign(dex_only_base, output / "base.apk", zipalign, java, apksigner_jar,
                   arguments.keystore)
    for split in SPLITS:
        align_and_sign(source / split, output / split, zipalign, java, apksigner_jar,
                       arguments.keystore)

    print("Created signed no-root trial split set:")
    for apk in ("base.apk", *SPLITS):
        print(output / apk)
    print("This command only creates files. It does not change the connected device.")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (RuntimeError, subprocess.CalledProcessError) as error:
        print(f"Build failed: {error}", file=sys.stderr)
        raise SystemExit(1)
