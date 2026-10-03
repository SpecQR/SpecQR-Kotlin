"""Test-only process bridge. Candidate operations always execute compiled Kotlin.

Python only orchestrates and verifies results; it never generates QR candidates.
The candidate JVM contains the Kotlin runtime, its standard library, and this
Java test adapter. No reference encoder or decoder enters the candidate JVM.
"""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

JAVA = os.environ.get('SPECQR_JAVA', 'java')
JAR = None
DRIVER = Path(__file__).resolve().parents[2] / 'src/test/java/io/specqr/ConformanceDriver.java'
_COMPILED = {}
_TEMP = []


def configure(java=None, jar=None):
    global JAVA, JAR
    JAVA, JAR = java or os.environ.get('SPECQR_JAVA', 'java'), Path(jar).resolve() if jar else None


def kotlin_home():
    configured = os.environ.get('KOTLIN_HOME')
    compiler = shutil.which('kotlinc')
    home = Path(configured).resolve() if configured else Path(compiler).resolve().parents[1] if compiler else None
    if home is None or not (home / 'lib/kotlin-compiler.jar').is_file() or not (home / 'lib/kotlin-stdlib.jar').is_file():
        raise RuntimeError('Set KOTLIN_HOME to the official Kotlin compiler distribution')
    return home


def _classpath(candidate):
    root = Path(candidate).resolve()
    home = kotlin_home()
    stdlib = home / 'lib/kotlin-stdlib.jar'
    sources = sorted((root / 'src/main/kotlin').rglob('*.kt'))
    if not JAR and not sources:
        raise RuntimeError('No Kotlin candidate sources found: ' + str(root))
    compiler = home / 'lib/kotlin-compiler.jar'
    inputs = [DRIVER, stdlib, compiler] + ([JAR] if JAR else sources)
    fingerprint = tuple((str(p), hashlib.sha256(p.read_bytes()).hexdigest()) for p in inputs)
    key = (JAVA, fingerprint)
    if key not in _COMPILED:
        java = Path(shutil.which(JAVA) or JAVA).resolve()
        javac = java.with_name('javac')
        temporary = tempfile.TemporaryDirectory(prefix='specqr-kotlin-conformance-')
        _TEMP.append(temporary)
        base = Path(temporary.name)
        classes, adapter = base / 'classes', base / 'adapter'
        classes.mkdir(); adapter.mkdir()
        if not JAR:
            env = os.environ.copy()
            env['JAVA_HOME'] = str(java.parents[1])
            command = [str(java), '-XX:ActiveProcessorCount=2', '-Xmx512m', '-cp', str(home / 'lib/*'),
                       'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-kotlin-home', str(home), '-jvm-target', '17',
                       '-language-version', '2.2', '-api-version', '2.2', '-Xjdk-release=17', '-Werror', '-no-reflect',
                       '-no-stdlib', '-classpath', str(stdlib), '-d', str(classes), *map(str, sources)]
            process = subprocess.run(command, env=env, capture_output=True, text=True, encoding='utf-8', timeout=240)
            if process.returncode:
                raise RuntimeError('Kotlin candidate compilation failed:\n' + process.stdout + process.stderr)
        runtime = str(JAR) if JAR else str(classes)
        dependencies = os.pathsep.join([runtime, str(stdlib)])
        command = [str(javac), '-J-XX:ActiveProcessorCount=2', '--release', '17', '-encoding', 'UTF-8',
                   '-d', str(adapter), '--class-path', dependencies, str(DRIVER)]
        process = subprocess.run(command, capture_output=True, text=True, encoding='utf-8', timeout=120)
        if process.returncode:
            raise RuntimeError('Java test adapter compilation against Kotlin failed:\n' + process.stdout + process.stderr)
        _COMPILED[key] = os.pathsep.join([str(adapter), dependencies])
    return _COMPILED[key]


def generate(candidate, requests, fault=None):
    command = [JAVA, '-XX:ActiveProcessorCount=2', '-Xmx512m', '-Dfile.encoding=UTF-8', '--class-path', _classpath(candidate), 'io.specqr.ConformanceDriver']
    env = os.environ.copy()
    for name in ('CLASSPATH', 'JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS', 'SPECQR_TEST_FAULT'):
        env.pop(name, None)
    if fault:
        env['SPECQR_TEST_FAULT'] = fault
    process = subprocess.run(command, input=''.join(json.dumps(r, ensure_ascii=True) + '\n' for r in requests),
                             capture_output=True, text=True, encoding='utf-8', env=env, timeout=900)
    if process.returncode:
        raise RuntimeError(f'Kotlin candidate JVM process failed ({process.returncode}): {process.stderr[-4000:]}')
    try:
        results = [json.loads(line) for line in process.stdout.splitlines()]
    except json.JSONDecodeError as error:
        raise RuntimeError(f'Invalid Kotlin JVM JSON-lines response: {process.stdout[:1000]!r}; stderr={process.stderr[-1000:]!r}') from error
    if len(results) != len(requests):
        raise RuntimeError(f'Kotlin JVM response count mismatch: {len(results)} != {len(requests)}')
    return results


def matrix_hash(matrix):
    return hashlib.sha256(''.join(matrix).encode('ascii')).hexdigest()


def verify_identity(candidate, identity, nonce):
    classpath = _classpath(candidate)
    expected_origin = classpath.split(os.pathsep)[1]
    stdlib = kotlin_home() / 'lib/kotlin-stdlib.jar'
    if (identity.get('nonce') != nonce or identity.get('pid') == os.getpid()
            or identity.get('candidateClass') != 'io.specqr.SpecQr'
            or identity.get('classPath') != classpath
            or identity.get('module') != expected_origin
            or identity.get('kotlinMetadata') is not True
            or identity.get('kotlinStdlibSha256') != hashlib.sha256(stdlib.read_bytes()).hexdigest()
            or not identity.get('java') or not identity.get('packageFilesSha256')):
        raise RuntimeError(f'Candidate Kotlin JVM/classpath identity check failed: {identity}')
