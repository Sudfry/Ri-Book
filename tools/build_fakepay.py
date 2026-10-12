"""按给定包名编译 FakePay（同一份源码，编出两个包名）。

用来验证「通知自动记账」：伪造 com.tencent.mm / com.eg.android.AlipayGphone 两个包名，
走系统通知通道真发通知，看应用能不能正确识别平台、金额与收支方向。

    python tools\\build_fakepay.py     -> dist/FakeWeChat.apk, dist/FakeAlipay.apk
"""
import importlib.util
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(ROOT, "tools"))

PROJ = os.path.join(ROOT, "FakePay")
TPL = os.path.join(PROJ, "AndroidManifest.xml.tpl")

JOBS = [
    ("com.tencent.mm", "微信支付(测试)", "FakeWeChat.apk"),
    ("com.eg.android.AlipayGphone", "支付宝(测试)", "FakeAlipay.apk"),
]

spec = importlib.util.spec_from_file_location(
    "bt", os.path.join(ROOT, "tools", "build_todo.py"))
bt = importlib.util.module_from_spec(spec)
spec.loader.exec_module(bt)

for pkg, label, apk in JOBS:
    tpl = open(TPL, encoding="utf-8").read()
    open(os.path.join(PROJ, "AndroidManifest.xml"), "w", encoding="utf-8").write(
        tpl.replace("__PKG__", pkg).replace("__LABEL__", label))
    bt.PROJ = PROJ
    bt.BUILD = os.path.join(PROJ, "build")
    bt.APK_NAME = apk
    bt.VERSION_NAME = "1.0"
    bt.VERSION_CODE = "1"
    print("== building %s ==" % pkg)
    bt.main()
print("DONE")
