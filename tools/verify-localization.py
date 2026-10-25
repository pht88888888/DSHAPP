#!/usr/bin/env python3
"""Offline resource checks. Run from any directory; does not invoke Gradle."""
import argparse
from pathlib import Path
import re
import subprocess
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / 'app/src/main/res'
FORMAT = re.compile(r'%(?:\d+\$)?[-#+ 0,(<]*\d*(?:\.\d+)?(?:[tT][A-Za-z]|[a-zA-Z%])')

def resources(directory):
    result = {}
    for path in sorted(directory.glob('*.xml')):
        for item in ET.parse(path).getroot():
            if item.tag != 'string':
                continue
            key = item.attrib['name']
            assert key not in result, f'Duplicate resource: {key}'
            result[key] = ''.join(item.itertext())
    return result


def signature(value):
    # Keep order, positional indexes, conversions, escaped line breaks and markup.
    return (
        FORMAT.findall(value),
        re.findall(r'\\[nrt]', value),
    )


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--baseline-ref', help='Local git ref containing the original Chinese defaults')
    args = parser.parse_args()
    default = resources(RES / 'values')
    chinese = resources(RES / 'values-zh')
    assert default.keys() == chinese.keys(), (
        'Resource key mismatch', default.keys() ^ chinese.keys()
    )
    for key, value in default.items():
        assert signature(value) == signature(chinese[key]), f'Placeholder mismatch: {key}'
        assert not re.search(r"(?<!\\)'", value), f'Unescaped apostrophe: {key}'
        assert not re.search(r'[\u3400-\u9fff]', value), f'Chinese default: {key}'
    references = set()
    for source in (ROOT / 'app/src').rglob('*.kt'):
        references.update(re.findall(r'(?<!android\.)\bR\.string\.(\w+)', source.read_text()))
    assert references <= default.keys(), ('Missing R.string references', references - default.keys())
    for path in (ROOT / 'app/src').rglob('*.xml'):
        for node in ET.parse(path).iter():
            for attribute, value in node.attrib.items():
                if value.startswith('@string/'):
                    assert value[8:] in default, f'Missing resource {value} in {path}'
                if attribute.startswith('{http://schemas.android.com/apk/res/android}') and attribute.rsplit('}', 1)[-1] in {'text', 'hint', 'contentDescription', 'title', 'label', 'description'}:
                    assert not re.search(r'[\u3400-\u9fff]', value), f'Hardcoded Chinese attribute: {path}'
    # The original Chinese files must remain exact copies of the specified baseline.
    for filename in (('strings.xml', 'strings_logs.xml') if args.baseline_ref else ()):
        original = subprocess.check_output(
            ['git', 'show', f'{args.baseline_ref}:app/src/main/res/values/{filename}'], cwd=ROOT
        )
        assert (RES / 'values-zh' / filename).read_bytes() == original, filename
        source = (RES / 'values' / filename).read_text()
        assert re.findall(r'<!--.*?-->', source, re.S) == re.findall(
            r'<!--.*?-->', original.decode(), re.S
        ), f'Comments changed: {filename}'
        # Markup and CDATA delimiters inside existing string resources are unchanged.
        body = re.compile(r'<string\b[^>]*name="([^"]+)"[^>]*>(.*?)</string>', re.S)
        before = dict(body.findall(original.decode()))
        after = dict(body.findall(source))
        for key in before:
            assert re.findall(r'<[^>]*>|<!\[CDATA\[|\]\]>', before[key]) == re.findall(
                r'<[^>]*>|<!\[CDATA\[|\]\]>', after[key]
            ), f'Markup changed: {key}'
    print(f'PASS: {len(default)} default strings = {len(chinese)} Chinese strings; identical keys and placeholder signatures.')
    print(f'PASS: all {len(references)} referenced app R.string keys exist; XML references and labels verified.')
    if args.baseline_ref:
        print('PASS: original Chinese resource files are byte-identical to the baseline; XML comments and markup preserved.')

if __name__ == '__main__':
    main()
