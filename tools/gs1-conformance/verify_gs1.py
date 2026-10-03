import argparse
import collections
import hashlib
import json
import os
from pathlib import Path
import random
import secrets
import shutil
import string
import subprocess
import sys
import tempfile

PIN = "15ad15e5c770ea0e39072f8f88b2733018f02ffd"
HERE = Path(__file__).resolve().parent
PROJECT = HERE.parents[1]
sys.path.insert(0, str(PROJECT / "tools/conformance"))
from protocol import configure, _classpath, generate, verify_identity
from known_url_profiles import FIXTURE, load_fixture, select_profile, verify_outcomes, negative_controls
parser = argparse.ArgumentParser(description="Audit bounded GS1 helpers against pinned SpecQR JS")
parser.add_argument("--baseline", type=Path, required=True, help="Read-only SpecQR JS checkout at " + PIN)
parser.add_argument("--java", default=os.environ.get("JAVA", "java"))
parser.add_argument("--javac", default=os.environ.get("JAVAC"))
parser.add_argument("--jar", type=Path, help="Test this exact thin Kotlin JAR rather than compiling Kotlin sources")
parser.add_argument("--node", default=os.environ.get("NODE", "node"))
parser.add_argument("--node-profile", choices=["24.19.0", "24.21.0"], help="Assert the exact reviewed reference profile in addition to runtime identity")
parser.add_argument("--report", type=Path, default=PROJECT / "build/gs1-conformance.json")
args = parser.parse_args()
baseline = args.baseline.resolve()
configure(args.java, args.jar)
if args.javac is None:
    args.javac = str(Path(shutil.which(args.java) or args.java).resolve().with_name("javac"))
revision = subprocess.check_output(["git", "-C", str(baseline), "rev-parse", "HEAD"], text=True, timeout=30).strip()
if revision != PIN:
    parser.error("Baseline must be exactly " + PIN + "; found " + revision)
subprocess.run(["git", "-C", str(baseline), "diff", "--quiet", "HEAD", "--", "src", "package.json"], check=True, timeout=30)

def source_hash():
    digest = hashlib.sha256()
    files = sorted(p for p in (PROJECT / "src").rglob("*") if p.is_file() and p.suffix in (".java", ".kt")) + sorted(HERE.rglob("*.java")) + sorted(HERE.glob("*.py")) + sorted(HERE.glob("*.mjs")) + sorted(HERE.glob("*.json")) + [PROJECT / "tools/conformance/protocol.py"]
    for source in files:
        digest.update(str(source.relative_to(PROJECT)).encode("utf-8"))
        digest.update(b"\0")
        digest.update(source.read_bytes())
        digest.update(b"\0")
    return digest.hexdigest()

def node_identity():
    details = json.loads(subprocess.check_output([args.node, "-p", "JSON.stringify({executable:process.execPath,versions:process.versions,platform:process.platform,architecture:process.arch})"], text=True, encoding="utf-8", timeout=30))
    details["executableSha256"] = hashlib.sha256(Path(details["executable"]).read_bytes()).hexdigest()
    return details

source_before = source_hash()
node_before = node_identity()
known_fixture = load_fixture()
profile_version, reference_profile = select_profile(known_fixture, node_before, args.node_profile)
baseline_hashes = {str(p.relative_to(baseline)): hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted((baseline / "src").rglob("*.js"))}
baseline_hashes["package.json"] = hashlib.sha256((baseline / "package.json").read_bytes()).hexdigest()

def comparison_kind(operation, expected, actual):
    if expected == actual:
        return None
    if expected.get("ok") != actual.get("ok"):
        return "acceptance"
    return "value" if expected.get("ok") and operation != "validate" else "diagnostic"

root='https://example.com/01/04912345678904'
cases=[dict(command='catalog',category='catalog')]
def url(s,category='ordinary'):cases.append(dict(command='url',input=s,category=category))
def create(value,base='https://example.com',category='ordinary'):cases.append(dict(command='create',elements=[dict(ai='01',value='04912345678904'),dict(ai='10',value=value)],baseUrl=base,category=category))
for options in [dict(unknownQuery='reject'),dict(unknownQuery='oops'),dict(primaryAi='10'),dict(primaryAi='00'),dict(normalize=True)]:
 for suffix in ['', '?x=1','?quote%22key=1','?back%5Ckey=1','?line%0Akey=1','?%00=1','?%F0%9F%98%80=1']:
  cases.append(dict(command='url',input=root+suffix,options=options,category='ordinary'))
# Host, authority, prefix, escape and all ASCII punctuation boundaries.
hosts=['example.com','EXAMPLE.COM.','foo..bar','foo_bar.test','-x.test','x-.test','0','0x','0x7f000001','0177.1','127.1','4294967295','4294967296','1.2.3.256','1.2.3.4.5','09','08','1.2.0x100','1.2.3.','0xffffffff','0X7F.1','1.2.3.0004','1.2.3.','a%2eb','a'*64+'.test','a'*256+'.test','%65xample.com','a%2fb','%ff','%ED%A0%80','[::]','[::1]','[0:0:0:1:0:0:0:1]','[1::]','[::ffff:192.0.2.128]','[::ffff:192.00.2.128]','[::%25eth0]','[:::]','[1:2]','[1:2:3:4:5:6:7:8]','[1:2:3:4:5:6:7:8:9]']
for h in hosts:
 for scheme in ['https://','https:','http:///','HTTPS:\\\\']:
  url(scheme+h+'/01/04912345678904/10/LOT?17=271031&x=a+b')
for port in ['','0','000','80','443','65535','65536','999999999999999999999999999','+80','-1','abc',':80','000443']:
 for scheme in ['http','https']:url(scheme+'://example.com:'+port+'/01/04912345678904')
for c in map(chr,range(128)):
 url('https://a'+c+'b.test/01/04912345678904')
 url('https://u'+c+'ser:p'+c+'ass@example.com/01/04912345678904')
 url('https://example.com/a'+c+'b/01/04912345678904')
 url(root+'/10/A'+c+'B')
 url(root+'?x=A'+c+'B')
 create('A'+c+'B')
for host in ['例え.テスト','faß.de','βόλος.gr','ς.gr','σ.gr','☃.net','😀.test','a\u200cb.test','a\u200db.test','a\u3002b','é.test','ｅｘａｍｐｌｅ.com','\u00ad.test','ẞ.de','𐐀.test','\u0600.test']:
 url('https://'+host+'/01/04912345678904','idna')
for ace in ['xn--a','xn--','xn--abc','xn--abc-','xn--a-ecp.ru','xn--fa-hia.de','xn--e28h.test','xn--0.pt','xn--bcher-kva.de','xn--a.test','xn--a_.test','xn--%61.test','xn--ls8h.test','xn--3xa.gr']:
 url('https://'+ace+'/01/04912345678904','idna')
for s in ['.','..','%2e','%2E.','.%2E','%2e%2e']:
 url(root+'/10/'+s,'dot');url(root+'/10/'+s+'/21/S','dot');url(root+'?10='+s,'dot');create(s if '%' not in s else '.',category='dot')
for token in ['', '%', '%0','%00','%FF','%ED%A0%80','%E2%82','%E0%80%80','%F0%9F%98%80','%C0%80','%F4%90%80%80','%C2%A0','a+b','a%2Bb','%26x%3D1','%252e','%','x=y=z']:
 for loc in ['/10/','?10=','?x=','?%31%30=','?x=one&&y=two&x=']:
  url(root+loc+token)
for stem in ['/','//','///','/a//b','/a/./b','/a/../b','/a/%2e/b','/a/%2e%2e/b','/x/%2F/y','/x/%zz/y']:
 url('https://example.com'+stem+'/01/04912345678904');create('LOT','https://example.com'+stem)
for raw in ['http://','https://?x=1','//example.com/01/04912345678904','/01/04912345678904','ftp://example.com/01/04912345678904','mailto:a@b',' '+root+' ','\0\t'+root+'\r\n','\u00a0'+root,root+'#',root+'#x',root+'?',root+'?&',root+'?17=271031&17=271031',root+'/10/A?10=B',root+'/17/271031']:
 url(raw)
# Deterministic malformed/valid percent-encoding property samples.
r=random.Random(701)
chars=string.ascii_letters+string.digits+' -_~/+%?#&=.:;@[]{}\\\x00\x1f\x7f'
for i in range(2500):
 value=''.join(r.choice(chars) for _ in range(r.randrange(1,28)))
 url(root+r.choice(['/10/','?10=','?x=','/10/A?x='])+value)
 if i%10==0:create(value)
# Exercise every UTF-8 octet, continuation-boundary class, and seeded byte string.
for byte in range(256):
 url(root+'?x=%'+format(byte,'02X'))
for lead in range(0xc0,0xf8):
 for trail in [0,0x7f,0x80,0x8f,0x90,0x9f,0xa0,0xbf,0xc0,0xff]:
  url(root+'?x=%'+format(lead,'02X')+'%'+format(trail,'02X'))
for sample in range(512):
 url(root+'?x='+''.join('%'+format(r.randrange(256),'02X') for _ in range(r.randrange(1,8))))
for token in ['%ＦＦ','%４１','%١١','%E2%28%A1','%C0%AF','%EF%BB%BF','%F4%90%80%80']:
 url(root+'?x='+token)
url(root+'?x=\ud800')
# Concrete catalog valid and six invalid element cases per AI.
fixed={'00':18,'01':14,'02':14,**{x:6 for x in ['11','12','13','15','16','17']},'20':2,**{str(x):13 for x in range(410,416)},**{x:3 for x in ['422','424','425','426']},**{str(x):6 for x in list(range(3100,3106))+list(range(3200,3206))}}
variable={'10':20,'21':20,'22':20,'30':8,'37':8,'240':30,'241':30,'400':30,'420':20,**{str(x):90 for x in range(91,100)}}
for ai,limit in (fixed|variable).items():
 numeric=ai in fixed or ai in ['30','37'];valid='0'*limit if ai in fixed else '1' if numeric else 'A'
 for val in [valid,'','0'*(limit+1),'é','A\x1dB','A(B)','A' if numeric else 'A'*limit]:
  cases.append(dict(command='elements',elements=[dict(ai=ai,value=val)],category='catalog'))
body='\n'.join(json.dumps({k:v for k,v in c.items() if k!='category'},ensure_ascii=True) for c in cases)+'\n'
if hashlib.sha256(body.encode("utf-8")).hexdigest() != known_fixture["corpusSha256"]:
    raise AssertionError("GS1 corpus changed; review exact outcome profiles before updating the corpus")
outputs=[]
nonce = secrets.token_hex(16)
identity = generate(PROJECT, [{"command": "identity", "nonce": nonce}])[0]
verify_identity(PROJECT, identity, nonce)
candidate_classpath = _classpath(PROJECT)
with tempfile.TemporaryDirectory(prefix="specqr-gs1-") as temporary:
    classes = Path(temporary)
    sources = [HERE / "Gs1AuditProbe.java"]
    subprocess.run([args.javac, "-J-XX:ActiveProcessorCount=2", "--release", "17", "-Xlint:all", "-Werror", "-encoding", "UTF-8", "-d", str(classes), "--class-path", candidate_classpath, *map(str, sources)], check=True, timeout=180)
    commands = [[args.node, str(HERE / "oracle.mjs"), str(baseline)], [args.java, "-XX:ActiveProcessorCount=2", "-Dfile.encoding=UTF-8", "-cp", str(classes) + os.pathsep + candidate_classpath, "io.specqr.Gs1AuditProbe"]]
    for command in commands:
        process = subprocess.run(command, input=body, text=True, encoding="utf-8", capture_output=True, check=True, timeout=180)
        outputs.append([json.loads(line) for line in process.stdout.splitlines()])
        if len(outputs[-1]) != len(cases):
            raise RuntimeError(f"Expected {len(cases)} results, got {len(outputs[-1])}: {process.stderr}")
    control = subprocess.run([args.java, "-XX:ActiveProcessorCount=2", "-Dfile.encoding=UTF-8", "-Dspecqr.gs1.audit.corrupt=true", "-cp", str(classes) + os.pathsep + candidate_classpath, "io.specqr.Gs1AuditProbe"], input=json.dumps({"command":"catalog"}) + "\n", text=True, encoding="utf-8", capture_output=True, check=True, timeout=30)
    corrupted = json.loads(control.stdout)
    if comparison_kind("catalog", outputs[0][0]["catalog"], corrupted["catalog"]) != "value":
        raise AssertionError("Negative control did not distinguish corrupted Kotlin output")
subprocess.run(["git", "-C", str(baseline), "diff", "--quiet", "HEAD", "--", "src", "package.json"], check=True, timeout=30)
source_after = source_hash()
if source_after != source_before:
    raise RuntimeError("Kotlin source/test/tool tree changed during the audit; rerun against a stable tree")
end_identity = generate(PROJECT, [{"command": "identity", "nonce": nonce}])[0]
verify_identity(PROJECT, end_identity, nonce)
if identity["packageFilesSha256"] != end_identity["packageFilesSha256"]:
    raise RuntimeError("Kotlin runtime files changed during GS1 audit")
if node_identity() != node_before:
    raise RuntimeError("Node executable/runtime identity changed during GS1 audit")
for name, expected in baseline_hashes.items():
    if hashlib.sha256((baseline / name).read_bytes()).hexdigest() != expected:
        raise RuntimeError("Reference source changed during GS1 audit: " + name)
js,j=outputs
known_records = [{"case": case, "reference": expected, "kotlin": actual} for case, expected, actual in zip(cases, js, j) if case["category"] in ("idna", "dot")]
try:
    known_outcome_check = verify_outcomes(known_records, known_fixture, profile_version)
    known_negative_controls = negative_controls(known_records, known_fixture, profile_version)
except AssertionError as error:
    known_outcome_check = {"passed": False, "error": str(error)}
    known_negative_controls = []
stats=collections.Counter();diff=[]
for c,a,b in zip(cases,js,j):
 category=c['category'];stats['cases']+=1;stats['cases_'+category]+=1
 for op in a:
  stats['operations']+=1;stats['operations_'+category]+=1
  field=comparison_kind(op,a[op],b.get(op,{}))
  if field is None:stats['matched']+=1;stats['matched_'+category]+=1;continue
  stats['difference_'+field]+=1;stats['difference_'+category+'_'+field]+=1
  diff.append(dict(case=c,operation=op,kind=field,js=a[op],kotlin=b.get(op)))
for kind in ("acceptance", "value", "diagnostic"):
    stats.setdefault("difference_" + kind, 0)
    for category in ("ordinary", "catalog", "idna", "dot"):
        stats.setdefault("difference_" + category + "_" + kind, 0)
expected_known = reference_profile["expectedKnownDifferenceCounts"]
known_counts_match = all(stats[key] == expected for key, expected in expected_known.items())
unexpected = [difference for difference in diff if difference["case"]["category"] not in ("idna", "dot")]
metadata = {
    "baselineCommit": revision,
    "candidateLanguage": "Kotlin",
    "candidateIdentity": identity,
    "jarConsumer": str(args.jar) if args.jar else None,
    "javaVersion": subprocess.check_output([args.java, "-version"], stderr=subprocess.STDOUT, text=True, timeout=30).strip(),
    "nodeVersion": "v" + node_before["versions"]["node"],
    "nodeIdentity": node_before,
    "referenceProfile": profile_version,
    "reviewedReferenceProfile": reference_profile,
    "knownUrlOutcomes": known_outcome_check,
    "knownUrlOutcomeNegativeControls": known_negative_controls,
    "knownUrlFixtureSha256": hashlib.sha256(FIXTURE.read_bytes()).hexdigest(),
    "baselineSourceSha256": baseline_hashes,
    "corpusSha256": hashlib.sha256(body.encode("utf-8")).hexdigest(),
    "unexpectedDifferences": len(unexpected),
    "knownDifferenceCountsMatch": known_counts_match,
    "expectedKnownDifferenceCounts": expected_known,
    "sourceTreeBeforeSha256": source_before,
    "sourceTreeAfterSha256": source_after,
    "negativeControl": {"passed": True, "description": "Probe executes GS1, then a test-only JVM property corrupts catalog output; comparison detects the change"},
    "diagnosticComparison": "code, reason, ai, value, key, offset, elementIndex, expected, count; missing/null normalized; human-facing message prose excluded",
    "knownDifferenceCategories": {
        "idna": "JDK IDNA2003 mappings and explicit ACE validation versus reviewed Node/Ada WHATWG domain profiles; exact case/outcome checks, including the current non-strict ASCII-domain rule",
        "dot": "Deliberate lossless dot-only GS1 values: preserve in parsing; emit in query",
    },
}
args.report.parent.mkdir(parents=True, exist_ok=True)
args.report.write_text(json.dumps(dict(metadata=metadata, stats=dict(stats), differences=diff, cases=cases, js=js, kotlin=j), ensure_ascii=True, indent=2) + "\n", encoding="utf-8")
print(json.dumps(dict(metadata=metadata, stats=dict(stats)), indent=2))
print("Full reproducible report:", args.report)
if not known_outcome_check["passed"]:
    raise RuntimeError("Exact known-URL outcome guard failed: " + known_outcome_check["error"])
if not known_counts_match:
    raise RuntimeError("Known IDNA/dot difference counts changed; inspect the full report")
if unexpected:
    for difference in unexpected[:20]:
        print(json.dumps(difference, ensure_ascii=True), file=sys.stderr)
    raise SystemExit(1)
