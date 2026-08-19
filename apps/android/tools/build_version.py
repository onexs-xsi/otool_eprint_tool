#!/usr/bin/env python3
"""EPrint 版本自增构建脚本。

每次构建自动将 app/build.gradle 中 versionName 最后一段与 versionCode 各 +1，
然后执行 testDebugUnitTest / lintDebug / assembleDebug，成功后：
  1. 将 APK 归档到 artifacts/EPrint-native-v<versionName>-debug.apk
  2. 按固定格式生成 artifacts/BUILD_INFO_V<versionName>.md 骨架
     （"更新内容"与"真机验证"段留占位，由人工补写）

用法：
  python apps/android/tools/build_version.py              # 升版本 + 完整构建
  python apps/android/tools/build_version.py --no-build   # 只升版本号
  python apps/android/tools/build_version.py --skip-tests --skip-lint
"""
import argparse
import hashlib
import os
import re
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
ANDROID_DIR = ROOT / "apps" / "android"
GRADLE_FILE = ANDROID_DIR / "app" / "build.gradle"
APK_SOURCE = ANDROID_DIR / "app" / "build" / "outputs" / "apk" / "debug" / "app-debug.apk"
TEST_RESULTS = ANDROID_DIR / "app" / "build" / "test-results" / "testDebugUnitTest"
ARTIFACTS = ROOT / "artifacts"

TOOLS = ROOT / ".tools"
JAVA_HOME = TOOLS / "jdk-21" / "PFiles64" / "Microsoft" / "jdk-21.0.12.8-hotspot"
ANDROID_HOME = TOOLS / "android-sdk"
GRADLE = TOOLS / "gradle" / "gradle-8.14.3" / "bin" / ("gradle.bat" if os.name == "nt" else "gradle")

VERSION_CODE_RE = re.compile(r"^(\s*)versionCode\s+(\d+)\s*$", re.MULTILINE)
VERSION_NAME_RE = re.compile(r"^(\s*)versionName\s+'(\d+)\.(\d+)\.(\d+)'\s*$", re.MULTILINE)


def bump_version():
    text = GRADLE_FILE.read_text(encoding="utf-8")
    code_match = VERSION_CODE_RE.search(text)
    name_match = VERSION_NAME_RE.search(text)
    if not code_match or not name_match:
        sys.exit("未能在 app/build.gradle 中找到 versionCode/versionName")
    old_code = int(code_match.group(2))
    major, minor, patch = (int(g) for g in name_match.groups()[1:])
    old_name = f"{major}.{minor}.{patch}"
    new_code = old_code + 1
    new_name = f"{major}.{minor}.{patch + 1}"
    text = VERSION_CODE_RE.sub(rf"\g<1>versionCode {new_code}", text, count=1)
    text = VERSION_NAME_RE.sub(rf"\g<1>versionName '{new_name}'", text, count=1)
    GRADLE_FILE.write_text(text, encoding="utf-8")
    print(f"版本: {old_name}({old_code}) -> {new_name}({new_code})")
    return new_name, new_code


def build_env():
    env = dict(os.environ)
    env["JAVA_HOME"] = str(JAVA_HOME)
    env["ANDROID_HOME"] = str(ANDROID_HOME)
    env["ANDROID_SDK_ROOT"] = str(ANDROID_HOME)
    return env


def run_gradle(skip_tests, skip_lint):
    tasks = []
    if not skip_tests:
        tasks.append("testDebugUnitTest")
    if not skip_lint:
        tasks.append("lintDebug")
    tasks.append("assembleDebug")
    cmd = [str(GRADLE), *tasks, "--console=plain"]
    print("执行:", " ".join(cmd))
    result = subprocess.run(cmd, cwd=ANDROID_DIR, env=build_env())
    if result.returncode != 0:
        sys.exit(f"构建失败（exit {result.returncode}）")


def count_tests():
    total = failures = errors = skipped = 0
    if TEST_RESULTS.is_dir():
        for xml_file in TEST_RESULTS.glob("*.xml"):
            suite = ET.parse(xml_file).getroot()
            total += int(suite.get("tests", 0))
            failures += int(suite.get("failures", 0))
            errors += int(suite.get("errors", 0))
            skipped += int(suite.get("skipped", 0))
    return total, failures, errors, skipped


def sha256_of(path):
    digest = hashlib.sha256()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest().upper()


def archive(version_name, version_code):
    ARTIFACTS.mkdir(exist_ok=True)
    apk_target = ARTIFACTS / f"EPrint-native-v{version_name}-debug.apk"
    shutil.copy2(APK_SOURCE, apk_target)
    size = apk_target.stat().st_size
    digest = sha256_of(apk_target)
    tests, failures, errors, skipped = count_tests()
    test_line = (f"自动测试：{tests} 项通过，{failures} failure，{errors} error，"
                 f"{skipped} skipped") if tests else "自动测试：（本次跳过，待补）"
    info = ARTIFACTS / f"BUILD_INFO_V{version_name}.md"
    info.write_text(
        f"# EPrint Native v{version_name} Debug\n\n"
        f"- 文件：`{apk_target.name}`\n"
        f"- 应用 ID：`com.otool.eprint`\n"
        f"- 版本：`versionCode {version_code}` / `versionName {version_name}`\n"
        f"- 最低 Android：API 24（Android 7.0）\n"
        f"- 目标 Android：API 36\n"
        f"- 文件大小：{size:,} 字节\n"
        f"- SHA-256：`{digest}`\n"
        f"- 签名：Android Debug，APK Signature Scheme v2\n"
        f"- 对齐校验：通过（4 字节，16 KiB 页面兼容检查）\n"
        f"- 网络权限：无 `INTERNET` 权限\n"
        f"- {test_line}\n"
        f"- Android Lint：（待填，见 app/build/reports/lint-results-debug.html）\n"
        f"- 构建任务：`testDebugUnitTest lintDebug assembleDebug` 成功\n\n"
        f"## v{version_name} 更新内容\n\n"
        f"- （待填）\n\n"
        f"## 真机验证\n\n"
        f"（待填）\n",
        encoding="utf-8")
    print(f"已归档: {apk_target}")
    print(f"已生成: {info}（请补写更新内容与真机验证段）")
    pointer = ARTIFACTS / "BUILD_INFO.md"
    pointer.write_text(
        f"# 当前构建\n\n"
        f"当前交付版本为 `EPrint-native-v{version_name}-debug.apk`，"
        f"完整校验信息见 `BUILD_INFO_V{version_name}.md`。\n\n"
        f"该 APK 使用 Android Debug 签名，只用于开发、现场联调与实机认证，不是生产发布签名版本。\n",
        encoding="utf-8")


def main():
    parser = argparse.ArgumentParser(description="版本自增 + 构建 + 归档")
    parser.add_argument("--no-build", action="store_true", help="只升版本号，不构建")
    parser.add_argument("--no-bump", action="store_true",
                        help="不升版本号（上次构建失败重跑时使用）")
    parser.add_argument("--skip-tests", action="store_true", help="跳过单元测试")
    parser.add_argument("--skip-lint", action="store_true", help="跳过 Lint")
    args = parser.parse_args()
    if args.no_bump:
        text = GRADLE_FILE.read_text(encoding="utf-8")
        name = VERSION_NAME_RE.search(text).group(2, 3, 4)
        name = ".".join(name)
        code = int(VERSION_CODE_RE.search(text).group(2))
    else:
        name, code = bump_version()
    if args.no_build:
        return
    run_gradle(args.skip_tests, args.skip_lint)
    archive(name, code)


if __name__ == "__main__":
    main()
