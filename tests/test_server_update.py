"""Exercise the shipped updater with fake downloads/services in an isolated root."""
import hashlib
import os
from pathlib import Path
import sqlite3
import subprocess
import tempfile
import unittest

SCRIPT = Path(__file__).resolve().parents[1] / 'ios/PulseCore/Sources/PulseCore/Resources/update-server.sh'

class ServerUpdateTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.bin = self.root / 'usr/local/bin'
        self.bin.mkdir(parents=True)
        self.tools = self.root / 'tools'
        self.tools.mkdir()
        self.release = self.root / 'release'
        self.release.mkdir()
        for name in ['agent', 'relay']:
            p = self.bin / ('pulse-' + name)
            p.write_text('old-' + name)
            p.chmod(0o755)
            (self.release / ('pulse-' + name + '-x86_64')).write_text('new-' + name)
        (self.release / 'sha256-x86_64.txt').write_text(''.join(
            hashlib.sha256(p.read_bytes()).hexdigest() + '  ' + p.name + '\n'
            for p in sorted(self.release.glob('pulse-*'))))
        config = self.root / 'etc/pulse-relay'
        config.mkdir(parents=True)
        (config / 'pulse-relay.env').write_text('PULSE_DB=/var/lib/pulse-relay/relay.db\nPULSE_ADMIN_TOKEN=keep-existing-token\n')
        db = self.root / 'var/lib/pulse-relay/relay.db'
        db.parent.mkdir(parents=True)
        with sqlite3.connect(db) as connection:
            connection.execute('create table saved(value text)')
            connection.execute("insert into saved values ('existing data')")
        self.tool('uname', '#!/bin/sh\necho x86_64\n')
        self.tool('flock', '#!/bin/sh\nexit 0\n')
        self.tool('sleep', '#!/bin/sh\nexit 0\n')
        self.tool('curl', '''#!/usr/bin/env python3
import os, pathlib, shutil, sys
args=sys.argv[1:]
url=next(a for a in args if a.startswith('https://'))
shutil.copyfile(pathlib.Path(os.environ['FIXTURE_RELEASE'])/url.rsplit('/',1)[1],args[args.index('-o')+1])
''')
        self.tool('systemctl', '''#!/usr/bin/env python3
import os, pathlib, sys
root=pathlib.Path(os.environ['PULSE_UPDATE_ROOT']);args=sys.argv[1:]
if args[0]=='show':
 print('not-found' if args[1]=='pulse-relay.service' and os.environ.get('NO_RELAY') else 'loaded');sys.exit(0)
with (root/'calls').open('a') as log:log.write(' '.join(args)+'\\n')
name=args[-1]
if args[0]=='restart' and os.environ.get('FAIL_RESTART')==name and (root/'usr/local/bin'/name).read_text().startswith('new-'):sys.exit(1)
''')
        self.env = dict(os.environ, PULSE_UPDATE_ROOT=str(self.root), FIXTURE_RELEASE=str(self.release), PATH=str(self.tools)+os.pathsep+os.environ['PATH'])
    def tool(self, name, content):
        p=self.tools/name;p.write_text(content);p.chmod(0o755)
    def run_update(self, **env):
        return subprocess.run(['sh',str(SCRIPT),'https://example.invalid/releases/latest/download'],env=dict(self.env,**env),capture_output=True,text=True,timeout=15)
    def test_updates_relay_before_agent_and_preserves_data(self):
        result=self.run_update()
        self.assertEqual(0,result.returncode,result.stdout+result.stderr)
        self.assertEqual('new-relay',(self.bin/'pulse-relay').read_text())
        self.assertEqual('new-agent',(self.bin/'pulse-agent').read_text())
        calls=(self.root/'calls').read_text().splitlines()
        self.assertLess(calls.index('restart pulse-relay'),calls.index('restart pulse-agent'))
        backup=next((self.root/'var/lib/watchdeck-updates').glob('backup-*'))
        self.assertEqual('old-relay',(backup/'pulse-relay').read_text())
        with sqlite3.connect(backup/'relay.db') as db:
            self.assertEqual('existing data',db.execute('select value from saved').fetchone()[0])
        self.assertIn('keep-existing-token',(self.root/'etc/pulse-relay/pulse-relay.env').read_text())
    def test_bad_checksum_leaves_binaries_and_services_untouched(self):
        (self.release/'pulse-agent-x86_64').write_text('corrupted')
        result=self.run_update()
        self.assertNotEqual(0,result.returncode)
        self.assertEqual('old-agent',(self.bin/'pulse-agent').read_text())
        self.assertEqual('old-relay',(self.bin/'pulse-relay').read_text())
        self.assertFalse((self.root/'calls').exists())
    def test_failed_agent_restart_rolls_back_both_binaries(self):
        result=self.run_update(FAIL_RESTART='pulse-agent')
        self.assertNotEqual(0,result.returncode)
        self.assertEqual('old-agent',(self.bin/'pulse-agent').read_text())
        self.assertEqual('old-relay',(self.bin/'pulse-relay').read_text())
        calls=(self.root/'calls').read_text().splitlines()
        self.assertEqual(['restart pulse-relay','restart pulse-agent'],[c for c in calls if c.startswith('restart')][-2:])
    def test_agent_only_host_does_not_install_a_relay(self):
        (self.bin/'pulse-relay').unlink()
        result=self.run_update(NO_RELAY='yes')
        self.assertEqual(0,result.returncode,result.stdout+result.stderr)
        self.assertEqual('new-agent',(self.bin/'pulse-agent').read_text())
        self.assertFalse((self.bin/'pulse-relay').exists())
        self.assertNotIn('pulse-relay',(self.root/'calls').read_text())

if __name__=='__main__':unittest.main()
