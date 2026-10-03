"""Exact reviewed URL outcomes, independent of aggregate difference counts.

These are bounded test expectations, not production URL parsing or a tolerance
for arbitrary changes within an IDNA/dot category.
"""
import collections
import copy
import hashlib
import json
from pathlib import Path

FIXTURE = Path(__file__).with_name('known-url-outcomes.json')


def canonical_sha(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(',', ':'), ensure_ascii=True).encode('utf-8')).hexdigest()


def load_fixture():
    value = json.loads(FIXTURE.read_text(encoding='utf-8'))
    if value.get('schemaVersion') != 1 or len(value.get('cases', [])) != 54:
        raise AssertionError('Unexpected known-URL fixture schema or case count')
    counts = collections.Counter(row['case']['category'] for row in value['cases'])
    if counts != {'idna': 30, 'dot': 24}:
        raise AssertionError('Known-URL fixture must contain all 30 IDNA and 24 dot cases')
    # Repeated create('.') rows are intentional corpus cases. Exact ordered
    # comparison and the pinned full-corpus hash preserve their multiplicity.
    return value


def select_profile(fixture, node_identity, requested=None):
    versions = node_identity['versions']
    version = versions['node']
    if requested is not None and requested != version:
        raise AssertionError(f'Requested Node profile {requested}, executing {version}')
    if version not in fixture['profiles']:
        raise AssertionError(f'Unreviewed Node reference version {version}; supported exact profiles: {list(fixture["profiles"])}')
    profile = fixture['profiles'][version]
    for name, expected in profile['runtimeVersions'].items():
        if versions.get(name) != expected:
            raise AssertionError(f'Node {version} {name} identity changed: {versions.get(name)} != {expected}')
    return version, profile


def verify_outcomes(records, fixture, version):
    expected = fixture['cases']
    if len(records) != len(expected):
        raise AssertionError(f'Known-URL response count changed: {len(records)} != {len(expected)}')
    for index, (actual, wanted) in enumerate(zip(records, expected)):
        if actual['case'] != wanted['case']:
            raise AssertionError(f'Known-URL case/order changed at {index}')
        for side, value in [('kotlin', wanted['kotlin']), ('reference', wanted['reference'][version])]:
            if actual[side] != value:
                raise AssertionError(f'Exact known-URL {side} outcome changed for {wanted["case"]}: {json.dumps(actual[side], ensure_ascii=True)[:1000]}')
    return {'passed': True, 'cases': 54, 'operations': 150, 'idnaCases': 30, 'dotCases': 24,
            'kotlinOutcomesSha256': canonical_sha([row['kotlin'] for row in records]),
            'referenceOutcomesSha256': canonical_sha([row['reference'] for row in records])}


def difference_signature(records):
    """Used only to prove same-count wrong-value corruption is still rejected."""
    counts = collections.Counter()
    for row in records:
        for operation, expected in row['reference'].items():
            actual = row['kotlin'].get(operation, {})
            if expected != actual:
                kind = ('acceptance' if expected.get('ok') != actual.get('ok') else
                        'value' if expected.get('ok') and operation != 'validate' else 'diagnostic')
                counts[(row['case']['category'], kind)] += 1
    return counts


def negative_controls(records, fixture, version):
    # Mutate copies of real process results only, never a candidate runtime.
    verify_outcomes(records, fixture, version)
    controls = []
    ace = next(i for i, row in enumerate(records) if row['case'].get('input') == 'https://xn--a/01/04912345678904')
    deviation = next(i for i, row in enumerate(records) if row['case'].get('input') == 'https://fa\u00df.de/01/04912345678904')
    mutations = [('candidate-accepts-rejected-ace', ace, 'kotlin', 'parse', fixture['cases'][ace]['reference']['24.21.0']['parse']),
                 ('same-count-wrong-reference-idna-uri', deviation, 'reference', 'normalize', {'ok': True, 'value': 'https://wrong-reference.example/01/04912345678904'}),
                 ('same-count-wrong-candidate-idna-uri', deviation, 'kotlin', 'normalize', {'ok': True, 'value': 'https://wrong-candidate.example/01/04912345678904'})]
    for name, index, side, operation, replacement in mutations:
        corrupted = copy.deepcopy(records)
        corrupted[index][side][operation] = replacement
        unchanged = difference_signature(corrupted) == difference_signature(records)
        if name.startswith('same-count-') and not unchanged:
            raise AssertionError('Negative control must preserve aggregate difference counts: ' + name)
        try:
            verify_outcomes(corrupted, fixture, version)
        except AssertionError as error:
            controls.append({'fault': name, 'detected': True, 'aggregateDifferenceCountsUnchanged': unchanged, 'detail': str(error)[:200]})
        else:
            raise AssertionError('Known-category negative control was silently accepted: ' + name)
    return controls
