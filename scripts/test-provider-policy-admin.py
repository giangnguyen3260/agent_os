#!/usr/bin/env python3
"""Docker-free operator CLI contract tests. Run: python3 -B scripts/test-provider-policy-admin.py."""
import contextlib
import io
import itertools
import json
from pathlib import Path
import subprocess
import sys
import types
import unittest
from unittest.mock import patch

# Execute current source in memory: no import cache, Docker or database needed.
CLI_PATH = Path(__file__).with_name('provider-policy-admin.py')
cli = types.ModuleType('provider_policy_admin_under_test')
cli.__file__ = str(CLI_PATH)
exec(compile(CLI_PATH.read_text(), str(CLI_PATH), 'exec'), cli.__dict__)
PROVIDERS = ('OPENAI', 'ANTHROPIC', 'GOOGLE_GEMINI')
UNCONFIRMED = {'status': 'UNCONFIRMED',
               'action': 'Run show before deciding; do not retry automatically'}


def confirmed(revision='1', selected=('OPENAI',)):
    return {'status': 'CONFIRMED', 'revision': revision,
            'providers': {p: p in selected for p in PROVIDERS}}


class OperatorCliTest(unittest.TestCase):
    def invoke(self, args, stdout='', returncode=0, error=None):
        out, err = io.StringIO(), io.StringIO()
        result = subprocess.CompletedProcess([], returncode, stdout, 'PRIVATE_DB_DIAGNOSTIC')
        with patch.object(sys, 'argv', [str(CLI_PATH)] + args), \
                patch.object(cli.subprocess, 'run', side_effect=error, return_value=result) as run, \
                contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            try:
                code = cli.main()
            except SystemExit as failure:
                code = failure.code
        return code, out.getvalue(), err.getvalue(), run

    def unconfirmed(self, stdout, args=None, returncode=0, error=None):
        code, out, err, run = self.invoke(args or ['show'], stdout, returncode, error)
        self.assertEqual(code, 2)
        self.assertEqual(json.loads(out), UNCONFIRMED)
        self.assertEqual(err, '')
        self.assertNotIn('PRIVATE', out)
        run.assert_called_once()  # No automatic retry, including timeout/parse failures.

    def test_valid_confirmed_results_and_allowlisted_output(self):
        cases = [(['show'], confirmed('9223372036854775807')),
                 (['provision', '--providers', 'OPENAI'], confirmed()),
                 (['replace', '--providers', 'ANTHROPIC', '--expected-revision', '1'],
                  confirmed('2', ('ANTHROPIC',)))]
        for args, value in cases:
            with self.subTest(args=args):
                code, out, err, run = self.invoke(args, json.dumps(value))
                self.assertEqual(code, 0)
                self.assertEqual(json.loads(out), value)
                self.assertEqual(err, '')
                run.assert_called_once()
                self.assertEqual(run.call_args.kwargs['timeout'], 45)
                self.assertTrue(run.call_args.kwargs['capture_output'])
                self.assertIn('ON_ERROR_STOP=1', run.call_args.args[0][-1])

    def test_rejections_are_operation_specific(self):
        for operation, statuses in [('show', ['UNPROVISIONED', 'UNAVAILABLE']),
                                    ('replace', ['UNAVAILABLE', 'REVISION_CONFLICT',
                                                 'NO_CHANGE', 'REVISION_EXHAUSTED'])]:
            args = [operation]
            if operation == 'replace':
                args += ['--providers', 'OPENAI', '--expected-revision', '1']
            for status in statuses:
                with self.subTest(operation=operation, status=status):
                    code, out, err, run = self.invoke(args, json.dumps({'status': status}))
                    self.assertEqual((code, json.loads(out), err), (1, {'status': status}, ''))
                    run.assert_called_once()
        self.unconfirmed('{"status":"NO_CHANGE"}')
        self.unconfirmed('{"status":"UNAVAILABLE"}', ['provision', '--providers', 'OPENAI'])

    def test_nonzero_process_never_releases_provisional_result(self):
        for code in (1, 2, 3, -9):
            with self.subTest(code=code):
                self.unconfirmed(json.dumps(confirmed()), returncode=code)

    def test_process_errors_and_timeout_are_fixed_without_retry(self):
        for error in [OSError('PRIVATE_OS_ERROR'),
                      subprocess.TimeoutExpired('PRIVATE_COMMAND', 45,
                                                output='PRIVATE_OUTPUT', stderr='PRIVATE_ERROR'),
                      UnicodeDecodeError('utf-8', b'\xff', 0, 1, 'PRIVATE_DECODE')]:
            with self.subTest(error=type(error).__name__):
                self.unconfirmed('', error=error)

    def test_malformed_json_status_shapes_and_extra_output(self):
        payloads = ['', 'not JSON', '{', 'null', 'true', '1', '[]', '"CONFIRMED"', '{}',
                    '{"status":[]}', '{"status":{}}', '{"status":null}',
                    '{"status":1}', '{"status":"OTHER"}', '{"status":"CONFIRMED"}',
                    '{"status":"UNAVAILABLE","diagnostic":"PRIVATE"}',
                    'PRIVATE\n' + json.dumps(confirmed()),
                    json.dumps(confirmed()) + '\nPRIVATE',
                    json.dumps(confirmed()) + '\n' + json.dumps(confirmed()),
                    '{"status":"CONFIRMED","status":"CONFIRMED"}',
                    '{"status":"CONFIRMED","revision":"1","providers":'
                    '{"OPENAI":true,"OPENAI":false,"ANTHROPIC":true,"GOOGLE_GEMINI":false}}']
        for payload in payloads:
            with self.subTest(payload=payload[:60]):
                self.unconfirmed(payload)
        value = confirmed(); value['diagnostic'] = 'PRIVATE'
        self.unconfirmed(json.dumps(value))

    def test_deeply_nested_process_json_is_unconfirmed(self):
        depth = sys.getrecursionlimit() + 100
        self.unconfirmed('{"status":' + '[' * depth + '0' + ']' * depth + '}')

    def test_revision_types_grammar_and_bounds(self):
        for revision in [None, True, 1, 1.0, [], {}, '', '0', '-1', '+1', '01',
                         ' 1', '1 ', '1.0', '1e0', '9223372036854775808']:
            with self.subTest(revision=revision):
                value = confirmed(); value['revision'] = revision
                self.unconfirmed(json.dumps(value))

    def test_provider_exact_types_keys_and_nonempty_selection(self):
        for flags in [None, [], {}, dict.fromkeys(PROVIDERS, False),
                      {'OPENAI': True, 'ANTHROPIC': False},
                      dict(confirmed()['providers'], EXTRA=True),
                      dict(confirmed()['providers'], OPENAI=1),
                      dict(confirmed()['providers'], ANTHROPIC=0),
                      dict(confirmed()['providers'], OPENAI='true'),
                      dict(confirmed()['providers'], OPENAI=None)]:
            with self.subTest(flags=flags):
                value = confirmed(); value['providers'] = flags
                self.unconfirmed(json.dumps(value))

    def test_operation_selection_and_revision_must_match(self):
        for args, value in [(['provision', '--providers', 'OPENAI'], confirmed('2')),
                            (['provision', '--providers', 'OPENAI'], confirmed('1', ('ANTHROPIC',))),
                            (['replace', '--providers', 'OPENAI', '--expected-revision', '1'], confirmed('1')),
                            (['replace', '--providers', 'OPENAI', '--expected-revision',
                              '9223372036854775807'], confirmed('9223372036854775807'))]:
            with self.subTest(args=args):
                self.unconfirmed(json.dumps(value), args)

    def test_all_subsets_emit_sql_without_subprocess(self):
        for size in range(1, 4):
            for subset in itertools.combinations(PROVIDERS, size):
                for op in ('provision', 'replace'):
                    args = [op, '--providers', ','.join(subset), '--sql-only']
                    if op == 'replace':
                        args += ['--expected-revision', '9223372036854775807']
                    with self.subTest(args=args):
                        code, out, err, run = self.invoke(args)
                        self.assertEqual((code, err), (0, ''))
                        self.assertTrue(out.startswith('BEGIN ISOLATION LEVEL READ COMMITTED;'))
                        self.assertTrue(out.endswith('COMMIT;\n'))
                        run.assert_not_called()
        code, out, err, run = self.invoke(['show', '--sql-only'])
        self.assertEqual((code, err), (0, ''))
        self.assertIn('REPEATABLE READ READ ONLY', out)
        run.assert_not_called()

    def test_invalid_arguments_rejected_before_subprocess(self):
        cases = [['provision'], ['provision', '--providers', ''],
                 ['provision', '--providers', 'OPENAI,OPENAI'],
                 ['provision', '--providers', 'openai'],
                 ['provision', '--providers', 'OPENAI, ANTHROPIC'],
                 ['provision', '--providers', "OPENAI');SELECT 1;--"],
                 ['provision', '--providers', 'OPENAI', '--expected-revision', '1'],
                 ['show', '--providers', 'OPENAI'], ['show', '--expected-revision', '1'],
                 ['replace', '--providers', 'OPENAI']]
        for revision in ['0', '-1', '01', '+1', ' 1', '1 ', '1.0', '1e1',
                         '9223372036854775808', '1;SELECT 1', '']:
            cases.append(['replace', '--providers', 'OPENAI', '--expected-revision', revision])
        for args in cases:
            with self.subTest(args=args):
                code, out, _, run = self.invoke(args)
                self.assertEqual((code, out), (2, ''))
                run.assert_not_called()


if __name__ == '__main__':
    unittest.main()
