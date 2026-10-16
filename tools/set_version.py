"""Bump the app version (patch here instead of fighting PowerShell quoting)."""
import io
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
name = sys.argv[1] if len(sys.argv) > 1 else "1.2.5"
code = sys.argv[2] if len(sys.argv) > 2 else "8"
old_name, old_code = sys.argv[3] if len(sys.argv) > 3 else "1.2.4", sys.argv[4] if len(sys.argv) > 4 else "7"

edits = [
    (os.path.join(ROOT, "tools", "build.ps1"),
     [("'%s'," % old_name, "'%s'," % name), ("$VersionCode = %s," % old_code, "$VersionCode = %s," % code)]),
    (os.path.join(ROOT, "app", "src", "main", "AndroidManifest.xml"),
     [('android:versionCode="%s"' % old_code, 'android:versionCode="%s"' % code),
      ('android:versionName="%s"' % old_name, 'android:versionName="%s"' % name)]),
    (os.path.join(ROOT, "README.md"),
     [("DSMonitor-%s.apk" % old_name, "DSMonitor-%s.apk" % name)]),
]
for path, pairs in edits:
    t = io.open(path, encoding="utf-8").read()
    for old, new in pairs:
        t = t.replace(old, new)
    io.open(path, "w", encoding="utf-8", newline="\n").write(t)
print("version %s -> %s (code %s)" % (old_name, name, code))
