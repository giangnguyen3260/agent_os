"""Independent synthetic known-answer oracle for PROVIDER_COMMAND_V1.

Run from repository root. --check verifies the committed fixture without rewriting.
No production implementation or real credential/key is imported or accessed.
"""
import argparse
import base64
import hashlib
import hmac
import json
from pathlib import Path
import struct

DESTINATION = Path(__file__).resolve().parents[1] / 'backend/src/test/resources/providercommands/v1-vectors.json'
SCOPE = {'organization': '11111111-1111-4111-8111-111111111111',
         'actor': '22222222-2222-4222-8222-222222222222'}
TARGET = '33333333-3333-4333-8333-333333333333'
KEY_VERSION = 'command/test-key:v1'
KEY = bytes(range(32))  # Synthetic vector key only; never a deployment default.


def value(item):
    if item is None:
        return b'\x00'
    if isinstance(item, dict):
        return b'\x02' + struct.pack('>I', len(item)) + b''.join(
            value(name) + value(content) for name, content in item.items())
    encoded = item.encode('utf-8', errors='strict')
    return b'\x01' + struct.pack('>I', len(encoded)) + encoded


def vectors():
    cases = []
    for provider, mode, workspace in [
        ('OPENAI', 'OPENAI_API_KEY', None),
        ('ANTHROPIC', 'ANTHROPIC_API_KEY', None),
        ('ANTHROPIC', 'ANTHROPIC_API_KEY', 'wrkspc_A12'),
        ('GOOGLE_GEMINI', 'GEMINI_API_KEY', None),
    ]:
        cases.append(dict(operation='CREATE', target=None, provider=provider,
                          credentialMode=mode, name='\ufeff\u00a0Café 🌱\u2003',
                          normalizedName='Café 🌱', workspace=workspace,
                          expectedRevision=None, expectedCredentialRevision=None,
                          secret='synthetic-!~'))
    cases += [
        dict(operation='RENAME', name='  A\u0301 🌱  ', normalizedName='A\u0301 🌱', expectedRevision='0'),
        dict(operation='ROTATE', expectedRevision='9223372036854775807', secret='synthetic-rotate'),
        dict(operation='DISABLE', expectedRevision='17'),
        dict(operation='ENABLE', expectedRevision='17'),
        dict(operation='ACCESS_CHECK', expectedRevision='17', expectedCredentialRevision='3'),
        dict(operation='MODEL_DISCOVERY', expectedRevision='17', expectedCredentialRevision='3'),
    ]
    result = []
    for index, fields in enumerate(cases):
        item = dict(operation=None, target=TARGET, provider=None, credentialMode=None,
                    name=None, normalizedName=None, workspace=None, expectedRevision=None,
                    expectedCredentialRevision=None, secret=None)
        item.update(fields)
        op = item['operation']
        fields_to_sign = ['PROVIDER_COMMAND_V1', KEY_VERSION, SCOPE['organization'],
                          SCOPE['actor'], op, item['target']]
        if op == 'CREATE':
            options = {'workspaceId': item['workspace']} if item['provider'] == 'ANTHROPIC' else {}
            fields_to_sign += [item['provider'], item['credentialMode'], item['normalizedName'],
                               options, item['secret']]
        elif op == 'RENAME':
            fields_to_sign += [item['expectedRevision'], item['normalizedName']]
        elif op == 'ROTATE':
            fields_to_sign += [item['expectedRevision'], item['secret']]
        elif op in ('ENABLE', 'DISABLE'):
            fields_to_sign += [item['expectedRevision']]
        else:
            fields_to_sign += [item['expectedRevision'], item['expectedCredentialRevision']]
        canonical = b''.join(value(field) for field in fields_to_sign)
        result.append(dict(id=f'v1-{index + 1:02}', scope=SCOPE, keyVersion=KEY_VERSION,
                           keyHex=KEY.hex(), **item, canonicalHex=canonical.hex(),
                           digest=base64.b64encode(hmac.new(KEY, canonical, hashlib.sha256).digest()).decode('ascii')))
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    content = json.dumps(vectors(), ensure_ascii=False, indent=2) + '\n'
    if args.check:
        if DESTINATION.read_text() != content:
            raise SystemExit('Committed command vectors differ from the independent oracle')
        print('10 independent command HMAC vectors match the committed fixture')
    else:
        DESTINATION.write_text(content)
        print('Wrote 10 synthetic command HMAC vectors')


if __name__ == '__main__':
    main()
