"""Writes ci/test-timings.txt from a CI run's `reports` artifact: seconds per test class (per test method for
classes over SPLIT seconds), by module, for the test shards. Usage: python timings.py <reports dir> <out file>."""
import glob, os, re, sys, xml.etree.ElementTree as ET
SPLIT = 240
ADJUST = {}  # name or name#method -> seconds to use instead (tests cut since the run)
root, out = sys.argv[1], sys.argv[2]
rows = []
for f in glob.glob(root + '/**/TEST-*.xml', recursive=True):
    parts = os.path.normpath(f).replace(os.sep, '/').split('/')
    module = 'engine-' + parts[parts.index('build') - 1]
    if module in ('engine-demo', 'engine-bench'):
        continue
    suite = ET.parse(f).getroot()
    name = suite.get('name')
    total = float(suite.get('time'))
    if total > SPLIT:
        for case in suite.iter('testcase'):
            method = re.sub(r'\(.*\)$', '', case.get('name'))
            key = name + '#' + method
            rows.append((ADJUST.get(key, float(case.get('time'))), module, key))
    else:
        rows.append((ADJUST.get(name, total), module, name))
rows.sort(key=lambda r: (r[1], -r[0]))
with open(out, 'w', newline='\n') as o:
    o.write("# Seconds each test class (each test of the classes over 4 minutes) took on CI's software GPU (Mesa lavapipe,\n")
    o.write("# a 4-core runner), by module: what the test shards balance (ci.yml, -Ptruesim.shard=i/n). Tests missing here\n")
    o.write("# go to a shard by their class name's hash. Refresh from a run's reports artifact with ci/timings.py.\n")
    for seconds, module, key in rows:
        o.write(f"{seconds:.1f}\t{module}\t{key}\n")
