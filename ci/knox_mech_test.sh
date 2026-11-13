#!/bin/bash
# Checks, on a real Android (emulator), the mechanism the Knox patch relies on:
#   T1  the build-10 bug: bionic sh cannot start with a glibc-style LD_LIBRARY_PATH
#   T2  the launch snippet exports NAME=value args and passes fd 9 to the program
#   T3  the kernel loads an ELF whose PT_INTERP goes through /proc/self/fd/9 (shell domain)
#   T4  the same inside the app sandbox (run-as com.winlator), user 0
#   T5  the same for a secondary Android user (/data/user/N, like Knox Secure Folder)
#   T6  variable values are never evaluated by the shell
# Results are printed; nothing here fails the job.
set -u
D=/data/local/tmp/kt
W=$(mktemp -d)

adb pull /system/bin/toybox "$W/toybox.orig" >/dev/null
python3 - "$W/toybox.orig" "$W/toybox" <<'PY'
import sys
d=open(sys.argv[1],'rb').read()
old=b"/system/bin/linker64\0"; new=b"/proc/self/fd/9/lk64\0"
assert len(old)==len(new)
print("PT_INTERP occurrences patched:", d.count(old))
open(sys.argv[2],'wb').write(d.replace(old,new))
PY

# must stay identical to RootFSRelocator.LAUNCH_SNIPPET
cat > "$W/snippet" <<'SN'
r="$1"; shift; exec /system/bin/env "$@" 9<"$r"
SN

cat > "$W/outer.sh" <<'SH'
D=/data/local/tmp/kt
S="$(cat $D/snippet)"
mkdir -p $D/root $D/fakelib
echo "GROUP ( libc.so.6 )" > $D/fakelib/libc.so
ln -sf /system/bin/linker64 $D/root/lk64
chmod 755 $D/toybox
echo "== T1 (build-10 bug) sh with LD_LIBRARY_PATH -> dir whose libc.so is a linker script"
LD_LIBRARY_PATH=$D/fakelib /system/bin/sh -c 'echo T1_SH_STARTED'; echo "T1 exit code: $?"
echo "== T2 snippet: env from args + fd 9"
/system/bin/sh -c "$S" sh $D/root "FOO=a b" "BAR=x=y;z" /system/bin/sh -c 'echo "T2 FOO=[$FOO] BAR=[$BAR]"; ls -l /proc/self/fd/9'
echo "== T3 PT_INTERP through /proc/self/fd/9 (shell domain)"
/system/bin/sh -c "$S" sh $D/root "LD_LIBRARY_PATH=/system/lib64" $D/toybox echo T3_INTERP_VIA_FD_OK; echo "T3 exit code: $?"
echo "== T6 no shell evaluation of values (review finding F5)"
rm -f $D/pwned
/system/bin/sh -c "$S" sh $D/root 'RANDOM=a[$(touch /data/local/tmp/kt/pwned)]' 'PPID=5' /system/bin/sh -c 'echo "T6 started, RANDOM passed as text: $(env | grep -c "^RANDOM=a")"'
[ -e $D/pwned ] && echo "T6 FAIL: value was executed" || echo "T6 OK: value was not executed"
echo "== T3n negative: same binary without fd 9"
$D/toybox echo SHOULD_NOT_PRINT; echo "T3n exit code: $? (non-zero expected)"
SH

cat > "$W/inner.sh" <<'SH'
S="$(cat /data/local/tmp/kt/snippet 2>/dev/null)"
[ -n "$S" ] || S='r="$1"; shift; exec /system/bin/env "$@" 9<"$r"'
echo "app data dir: $PWD  uid: $(id -u)  context: $(cat /proc/self/attr/current 2>/dev/null)"
R=$PWD/files/kt/root
mkdir -p $R && ln -sf /system/bin/linker64 $R/lk64
chmod 700 $PWD/files/kt/toybox
/system/bin/sh -c "$S" sh $R "FOO=1" $PWD/files/kt/toybox echo APP_INTERP_VIA_FD_OK; echo "exit code: $?"
/system/bin/sh -c "$S" sh $R $PWD/files/kt/toybox ls -ld /proc/self/fd/9/
SH

adb shell "rm -rf $D; mkdir -p $D"
adb push "$W/toybox" "$W/snippet" "$W/outer.sh" "$W/inner.sh" $D/ >/dev/null
adb shell "chmod 644 $D/snippet $D/inner.sh; sh $D/outer.sh" 2>&1

run_in_app() { # $1 = extra run-as args
  adb shell "run-as com.winlator $1 sh -c 'mkdir -p files/kt && cat > files/kt/toybox' < $D/toybox" 2>&1
  adb shell "run-as com.winlator $1 sh -s < $D/inner.sh" 2>&1
}
echo "== T4 inside the app sandbox, user 0"
run_in_app ""

echo "== T5 secondary user (like Knox)"
out=$(adb shell pm create-user knoxtest 2>&1); echo "$out"
uid=$(echo "$out" | grep -o '[0-9]*$' | tail -1)
if [ -n "$uid" ]; then
  adb shell am start-user -w "$uid" 2>&1 | tail -1
  adb shell pm install-existing --user "$uid" com.winlator 2>&1 | tail -1
  sleep 5
  run_in_app "--user $uid"
else
  echo "T5 skipped: could not create a user"
fi
echo "== done"
