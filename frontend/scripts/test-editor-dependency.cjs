const assert = require('node:assert/strict');
const fs = require('node:fs');
const {createRequire} = require('node:module');

// Verify the security override through the actual consuming package.
const editorPath = require.resolve('external-editor', {
  paths: [require.resolve('gts')],
});
const editorRequire = createRequire(editorPath);
assert.equal(editorRequire('tmp/package.json').version, '0.2.7');
const {ExternalEditor} = require(editorPath);
const editor = new ExternalEditor('QC temporary-file compatibility check');
const temporaryFile = editor.tempFile;
try {
  assert.equal(
    fs.readFileSync(temporaryFile, 'utf8'),
    'QC temporary-file compatibility check',
  );
} finally {
  editor.cleanup();
}
assert.equal(fs.existsSync(temporaryFile), false);
console.log(
  'PASS: patched tmp dependency supports external-editor create/read/cleanup',
);

const os = require('node:os');
const path = require('node:path');
const tmp = editorRequire('tmp');
const sandbox = fs.mkdtempSync(path.join(os.tmpdir(), 'agentos-qc-'));
try {
  for (const prefix of ['../escape', ['../escape']]) {
    let unexpected;
    try {
      assert.throws(() => {
        unexpected = tmp.fileSync({tmpdir: sandbox, prefix});
      });
    } finally {
      if (unexpected) unexpected.removeCallback();
    }
  }
} finally {
  fs.rmSync(sandbox, {recursive: true, force: true});
}
console.log('PASS: string and array traversal prefixes are rejected');
