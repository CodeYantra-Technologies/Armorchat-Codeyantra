"""Capture only allowlisted diagnostic metadata, never raw logcat, from both phones."""
import concurrent.futures
import datetime
import pathlib
import re
import subprocess
import sys
import time

ROOT = pathlib.Path(__file__).resolve().parent
SECONDS = int(sys.argv[1]) if len(sys.argv) > 1 else 90
STAGES = (
    'fresh_kp_published|fresh_route_imported|initiator_selected|group_created|'
    'add_member_ok|welcome_submitted|welcome_received|welcome_sender_verified|'
    'welcome_keypackage_match|welcome_join_started|welcome_joined|setup_route_sent|'
    'setup_route_received|session_id_match|session_confirmed'
)
SAFE = re.compile(
    rf'shared_mls stage=({STAGES}) result=(PASS|FAIL) peer=([0-9a-f]{{8}}|-) '
    r'attempt=[0-9a-f]{8}(?: category=(?:key_package_not_found|fresh_pool_mismatch|'
    r'signature|confirmation_tag|ratchet_tree_missing|cipher_suite|snapshot|'
    r'session_selection|decode|rejected|native_other|diagnostic_unavailable))?$'
)

def capture(label, serial):
    process = subprocess.Popen(
        ['adb', '-s', serial, 'logcat', '-v', 'epoch', '-T', '1', 'ArmorChat:I', '*:S'],
        stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True,
    )
    import threading
    timer = threading.Timer(SECONDS, process.terminate)
    timer.start()
    with (ROOT / f'{label}.safe.log').open('a') as output:
        output.write(f'capture_start_utc={datetime.datetime.now(datetime.timezone.utc).isoformat()} duration={SECONDS}\n')
        output.flush()
        for line in process.stdout:
            match = SAFE.search(line.rstrip())
            if match:
                entry = f'host_ns={time.time_ns()} device_epoch={line.split()[0]} {match.group(0)}'
                output.write(entry + '\n')
                output.flush()
                print(label, entry, flush=True)
    timer.cancel()
    process.wait()

with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
    futures = [pool.submit(capture, label, serial) for label, serial in (
        ('phone-A', 'CNU763H001471307631'), ('phone-B', 'R9EMA08ZTVJ'),
    )]
    for future in futures:
        future.result()
