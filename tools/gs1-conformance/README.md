# GS1 differential audit

This development-only audit uses Python 3's standard library, a JDK 17+, Kotlin 2.4.20 and Node.
The published Kotlin library has no Python, Node, JavaScript, or encoder/decoder runtime dependency; its only external runtime dependency is
the Kotlin standard library. The Java test probe invokes the actual public Kotlin GS1 implementation.

From the repository root, with `java`, `javac` and `node` on `PATH`:

```sh
export KOTLIN_HOME=/absolute/path/to/kotlinc
python3 tools/gs1-conformance/verify_gs1.py --baseline /path/to/SpecQR
```

The baseline must be the unmodified SpecQR JavaScript checkout at
`15ad15e5c770ea0e39072f8f88b2733018f02ffd`. Override tools with `--java`, `--javac`,
`--node` or `JAVA`, `JAVAC`, `NODE`. `--report` selects the JSON report location
(default `build/gs1-conformance.json`). Kotlin sources are compiled separately into temporary storage with JVM 17
bytecode, language/API 2.2 and warnings as errors. The Java test adapter compiles
against those actual Kotlin classes. `--jar /absolute/path/specqr-kotlin.jar` tests
the exact thin artifact instead. Both routes use only Kotlin standard library
as the external runtime dependency. Every subprocess has a timeout; JVM processing is limited to two active
processors. The tracked baseline source/package files must match the pinned HEAD
before and after execution. Kotlin source, tests, and helper tools are hashed before
and after execution, and a concurrent change fails the audit. A test-only JVM
property corrupts catalog output after actual Kotlin GS1 execution; the same comparator
must detect that negative control as a value mismatch. No reference implementation
or fault-injection branch is copied into the runtime.

The deterministic corpus covers all 50 AI metadata entries; valid and invalid
values for every AI; every ASCII code in hosts, credentials, stems, paths, query
values and creator values; IPv4 numeric forms; IPv6; ports; special-scheme URL
syntax; percent escapes; all UTF-8 byte values and continuation boundaries;
seeded malformed URL/byte-string samples; IDNA deviations; and literal/encoded
dot-only GS1 values. Each URI exercises parsing, validation, and normalization.

The report retains the exact requests and results, corpus hash, Java and Node
versions, baseline commit, and three distinct difference counts:

- Acceptance: one implementation accepts and the other rejects
- Value: both succeed but logical elements, unknown query values/order, AI
  metadata, or normalized/created URI differ
- Diagnostic: acceptance agrees but stable error/warning fields differ. Compared
  fields are `code`, `reason`, `ai`, `value`, `key`, `offset`, `elementIndex`,
  `expected`, and `count`, with missing and null normalized. Human-facing `message`
  prose is intentionally not compared

Ordinary and catalog differences cause a failing exit code. Two separate,
explicitly reported categories are expected:

1. Java's `java.net.IDN` is IDNA2003, whereas browser URLs use UTS #46. For example,
   `faß.de` becomes `fass.de` in Java; final sigma is folded; some joiners are
   removed; newer Unicode characters can be rejected. ASCII ACE (`xn--`) labels
   must round-trip through `IDN.toUnicode(..., ALLOW_UNASSIGNED)`; malformed ACE is
   rejected, but valid IDNA2008 deviation labels such as `xn--fa-hia.de` are also
   rejected. Newer valid ACE scalars such as emoji can pass with this flag even
   when their raw Unicode host form is rejected. Ordinary ASCII non-ACE hosts
   avoid this IDNA boundary. The adapter does not use `java.net.URI`'s stricter raw-character
   grammar and never resolves a host or accesses the network.
2. Dot-only GS1 values are deliberately lossless. The Kotlin parser retains literal
   or percent-encoded `.` / `..` values, and creation/normalization puts them in
   the query. The JS baseline's browser URL normalization can drop them or reject
   the resulting path. The report counts both acceptance and value differences.

This corpus demonstrates tested behavior; it is not a claim of complete WHATWG
URL or GS1 conformance.

## Exact reference-runtime profiles

The pinned JavaScript source uses the host Node runtime's WHATWG URL parser.
Source pinning alone does not pin that parser's semantics. The audited, exact
reference profiles are Node **24.19.0 / Ada 3.4.4** and Node **24.21.0 / Ada 4.0.0**,
both with ICU 78.3 and Unicode 17.0. Other versions or dependency tuples fail
closed until their outcomes are reviewed. The selected profile is always recorded.

Use each installed official Node binary explicitly (the compiler/JDK setup above
still applies):

```sh
python3 tools/gs1-conformance/verify_gs1.py --baseline /path/to/SpecQR --node /path/to/node-24.19.0 --node-profile 24.19.0 --report artifacts/gs1-node24.19.json
python3 tools/gs1-conformance/verify_gs1.py --baseline /path/to/SpecQR --node /path/to/node-24.21.0 --node-profile 24.21.0 --report artifacts/gs1-node24.21.json
```

The full corpus is unchanged: **5,610 cases and 15,690 operations**, with corpus
SHA-256 `61827bb17127b1c931f05d436b72984818636150e8d0a9c1cdf5cec678b99df6`.
`known-url-outcomes.json` fixes all normalized outcomes for the 30 IDNA and 24
dot-only cases: one invariant Kotlin result set and two explicit JavaScript
result sets. `known_url_profiles.py` checks exact requests, order, response counts,
acceptance, normalized URI values and normalized diagnostics. Category membership
or an unchanged aggregate count cannot excuse another outcome. Ordinary and
catalog cases still require zero differential failures.

- Node 24.19.0: **64 documented operation differences**, consisting of 24 IDNA
  (21 acceptance, 3 value) and 40 dot-only (12 acceptance, 28 value).
- Node 24.21.0: **91 documented operation differences**, consisting of 51 IDNA
  (48 acceptance, 3 value) and the same 40 dot-only differences.
- Both profiles require **zero diagnostic differences**.

The extra 27 operations are exactly parse, normalize and validate for these nine
ASCII ACE hosts: `xn--a`, `xn--`, `xn--abc`, `xn--abc-`, `xn--a-ecp.ru`, `xn--0.pt`,
`xn--a.test`, `xn--a_.test`, and `xn--%61.test` (normalized to `xn--a.test`). The
Kotlin adapter rejects these in both profiles under its documented ACE validation
policy. The newer Node/Ada parser accepts them under the WHATWG non-strict ASCII
domain rule. This is an intentional reference-standard change, not a claim of a
Node defect or a reason to change the bounded Kotlin policy.

Primary references: [WHATWG URL IDNA/domain parser](https://url.spec.whatwg.org/#concept-domain-to-ascii)
and [Ada 4's documented ASCII ACE behavior](https://github.com/ada-url/ada/blob/v4.0.0/src/url_pattern_helpers.cpp#L323-L327).
The fixture records official Node binary-archive checksums and published source-
archive checksums. Each run separately fingerprints the actual executing Node
binary and records all its dependency versions, its path/platform/architecture,
the JDK identity, Kotlin class/JAR and standard-library hashes, every reference JS
source file, the fixture, and the audit tools. Sources and executable identities
must remain unchanged during execution. A published source-archive checksum is
provenance metadata, not a claim that the archive was built or executed by this audit.

The catalog corruption control still invokes the actual JVM. Three additional
controls mutate copies of actual process results: making Kotlin accept a rejected
ACE host, changing an IDNA reference URI while preserving aggregate difference
counts, and changing a Kotlin IDNA URI while preserving those same counts. The
exact-outcome guard must reject all three. No profile is selected from candidate
results, and fixtures are never updated automatically on failure.
