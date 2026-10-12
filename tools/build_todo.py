"""SudfryTodo 构建脚本：不用 Gradle、不用 AndroidX，直接调命令行工具链出 APK。

    aapt2 compile -> aapt2 link -> javac -> jar -> d8 -> 打包 -> zipalign -> apksigner

路径都可以用环境变量覆盖：
    ANDROID_SDK_ROOT / ANDROID_HOME   Android SDK 根目录（默认 <仓库>/_sdk）
    JAVA_HOME                         JDK 21 根目录
"""
import glob
import os
import shutil
import subprocess
import sys
import zipfile

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PROJ = os.path.join(ROOT, "TodoApp")

SDK_CANDIDATES = [
    os.environ.get("ANDROID_SDK_ROOT"),
    os.environ.get("ANDROID_HOME"),
    os.path.join(ROOT, "_sdk"),
    os.path.join(os.path.expanduser("~"), "AppData", "Local", "Android", "Sdk"),
]
SDK = next((p for p in SDK_CANDIDATES if p and os.path.isdir(p)), os.path.join(ROOT, "_sdk"))

JAVA_HOME = os.environ.get("JAVA_HOME") or r"C:\Program Files\Java\latest\jdk-21"

BUILD_TOOLS = os.environ.get("BUILD_TOOLS_VERSION", "35.0.1")
PLATFORM = os.environ.get("ANDROID_PLATFORM", "android-34")

BT = os.path.join(SDK, "build-tools", BUILD_TOOLS)
AJAR = os.path.join(SDK, "platforms", PLATFORM, "android.jar")
BUILD = os.path.join(PROJ, "build")
OUT = os.path.join(ROOT, "dist")

JAVA = os.path.join(JAVA_HOME, "bin", "java.exe")
JAVAC = os.path.join(JAVA_HOME, "bin", "javac.exe")
KEYTOOL = os.path.join(JAVA_HOME, "bin", "keytool.exe")
KEYSTORE = os.path.join(ROOT, "tools", "todo.jks")
KS_PASS = os.environ.get("TODO_KS_PASS", "android")
KS_ALIAS = "todo"

VERSION_CODE = "9"
VERSION_NAME = "1.5.1"
APK_NAME = "SudfryTodo-v1.5.1.apk"

PACKAGE = "com.sudfry.todo"


def check_env():
    missing = []
    if not os.path.isfile(AJAR):
        missing.append("android.jar: %s" % AJAR)
    if not os.path.isfile(os.path.join(BT, "aapt2.exe")):
        missing.append("aapt2: %s" % os.path.join(BT, "aapt2.exe"))
    if not os.path.isfile(JAVAC):
        missing.append("javac: %s" % JAVAC)
    if missing:
        raise SystemExit(
            "缺少构建依赖：\n  " + "\n  ".join(missing) +
            "\n请设置 JAVA_HOME 与 ANDROID_SDK_ROOT（需要 platform %s 与 build-tools %s）"
            % (PLATFORM, BUILD_TOOLS))


def run(args, **kw):
    printable = " ".join(str(a) for a in args)
    if len(printable) > 400:
        printable = printable[:400] + " ..."
    print(">>", printable, flush=True)
    r = subprocess.run([str(a) for a in args], capture_output=True, text=True,
                       encoding="utf-8", errors="replace", **kw)
    if r.stdout and r.stdout.strip():
        print(r.stdout.strip(), flush=True)
    if r.stderr and r.stderr.strip():
        print(r.stderr.strip(), flush=True)
    if r.returncode != 0:
        raise SystemExit("command failed (%d): %s" % (r.returncode, printable))
    return r


def java_tool(jar, *args, main=None):
    if main is None:
        raise SystemExit("main required")
    return run([JAVA, "-Xmx3g", "-cp", jar, main] + list(args))


def main():
    check_env()
    os.makedirs(OUT, exist_ok=True)
    for sub in ("gen", "classes", "dex"):
        p = os.path.join(BUILD, sub)
        if os.path.isdir(p):
            shutil.rmtree(p)
        os.makedirs(p, exist_ok=True)

    print("== aapt2 compile ==", flush=True)
    app_res_zip = os.path.join(BUILD, "res.zip")
    if os.path.exists(app_res_zip):
        os.remove(app_res_zip)
    run([os.path.join(BT, "aapt2.exe"), "compile", "--dir",
         os.path.join(PROJ, "res"), "-o", app_res_zip])

    print("== aapt2 link ==", flush=True)
    link_cmd = [os.path.join(BT, "aapt2.exe"), "link",
                "-o", os.path.join(BUILD, "base.ap_"),
                "-I", AJAR,
                "--manifest", os.path.join(PROJ, "AndroidManifest.xml"),
                "--java", os.path.join(BUILD, "gen"),
                "--min-sdk-version", "26",
                "--target-sdk-version", "34",
                "--version-code", VERSION_CODE,
                "--version-name", VERSION_NAME,
                "--auto-add-overlay",
                "--no-version-vectors",
                "-R", app_res_zip]
    assets = os.path.join(PROJ, "assets")
    if os.path.isdir(assets):
        link_cmd += ["-A", assets]
    run(link_cmd)

    print("== javac ==", flush=True)
    sources = []
    for base in (os.path.join(PROJ, "src"), os.path.join(BUILD, "gen")):
        for dirpath, _, files in os.walk(base):
            for f in files:
                if f.endswith(".java"):
                    sources.append(os.path.join(dirpath, f))
    print("   %d source files" % len(sources), flush=True)
    argfile = os.path.join(BUILD, "sources.txt")
    with open(argfile, "w", encoding="utf-8") as fh:
        for s in sources:
            fh.write('"%s"\n' % s.replace("\\", "\\\\"))
    run([JAVAC, "-J-Duser.language=en", "-J-Duser.country=US",
         "-nowarn", "-encoding", "UTF-8",
         "-source", "8", "-target", "8",
         "-classpath", AJAR,
         "-d", os.path.join(BUILD, "classes"),
         "@" + argfile])

    print("== jar ==", flush=True)
    classes_jar = os.path.join(BUILD, "app.jar")
    if os.path.exists(classes_jar):
        os.remove(classes_jar)
    classes_root = os.path.join(BUILD, "classes")
    with zipfile.ZipFile(classes_jar, "w", zipfile.ZIP_DEFLATED) as z:
        for dirpath, _, files in os.walk(classes_root):
            for f in files:
                full = os.path.join(dirpath, f)
                z.write(full, os.path.relpath(full, classes_root).replace("\\", "/"))

    print("== d8 ==", flush=True)
    java_tool(os.path.join(BT, "lib", "d8.jar"),
              "--release", "--min-api", "26",
              "--lib", AJAR,
              "--output", os.path.join(BUILD, "dex"),
              classes_jar, main="com.android.tools.r8.D8")

    print("== package ==", flush=True)
    unsigned = os.path.join(BUILD, "unsigned.apk")
    if os.path.exists(unsigned):
        os.remove(unsigned)
    with zipfile.ZipFile(os.path.join(BUILD, "base.ap_")) as src:
        with zipfile.ZipFile(unsigned, "w", zipfile.ZIP_DEFLATED) as dst:
            for info in src.infolist():
                data = src.read(info.filename)
                if info.filename == "resources.arsc" or info.filename.endswith(".so"):
                    dst.writestr(zipfile.ZipInfo(info.filename), data,
                                 compress_type=zipfile.ZIP_STORED)
                else:
                    dst.writestr(info.filename, data, compress_type=zipfile.ZIP_DEFLATED)
    with zipfile.ZipFile(unsigned, "a", zipfile.ZIP_DEFLATED) as dst:
        for dex in sorted(glob.glob(os.path.join(BUILD, "dex", "*.dex"))):
            dst.write(dex, os.path.basename(dex))
            print("   +", os.path.basename(dex), flush=True)

    print("== zipalign ==", flush=True)
    aligned = os.path.join(BUILD, "aligned.apk")
    if os.path.exists(aligned):
        os.remove(aligned)
    run([os.path.join(BT, "zipalign.exe"), "-f", "-p", "4", unsigned, aligned])

    print("== keystore ==", flush=True)
    if not os.path.exists(KEYSTORE):
        run([KEYTOOL, "-genkeypair", "-v", "-keystore", KEYSTORE,
             "-alias", KS_ALIAS, "-keyalg", "RSA", "-keysize", "2048",
             "-validity", "10000", "-storepass", KS_PASS, "-keypass", KS_PASS,
             "-dname", "CN=SudfryTodo, OU=Todo, O=SudfryTodo, L=Beijing, ST=Beijing, C=CN"])

    print("== apksigner ==", flush=True)
    final = os.path.join(OUT, APK_NAME)
    if os.path.exists(final):
        os.remove(final)
    java_tool(os.path.join(BT, "lib", "apksigner.jar"),
              "sign", "--ks", KEYSTORE, "--ks-pass", "pass:" + KS_PASS,
              "--key-pass", "pass:" + KS_PASS, "--ks-key-alias", KS_ALIAS,
              "--min-sdk-version", "26", "--v1-signing-enabled", "true",
              "--v2-signing-enabled", "true", "--out", final, aligned,
              main="com.android.apksigner.ApkSignerTool")
    java_tool(os.path.join(BT, "lib", "apksigner.jar"),
              "verify", "--min-sdk-version", "26", "--print-certs", final,
              main="com.android.apksigner.ApkSignerTool")

    print("\nDONE: %s  (%.2f MB)" % (final, os.path.getsize(final) / 1048576.0), flush=True)


if __name__ == "__main__":
    main()
