const assert = require('node:assert/strict');
const {spawn} = require('node:child_process');
const {once} = require('node:events');
const fs = require('node:fs/promises');
const {createRequire} = require('node:module');
const os = require('node:os');
const path = require('node:path');
const {setTimeout: delay} = require('node:timers/promises');

const workspace = path.resolve(__dirname, '..');
const cliPackage = require.resolve('@angular/cli/package.json');
const cliRequire = createRequire(cliPackage);
const cli = path.join(path.dirname(cliPackage), 'bin/ng.js');
const environment = {
  ...process.env,
  CI: 'true',
  NG_CLI_ANALYTICS: 'false',
  FORCE_COLOR: '0',
};

async function checkPatternConsumers() {
  // Check both supported module entry points after the ESLint major upgrade.
  await import('@eslint/config-array');
  const esm = await import('eslint');
  const {ESLint} = require('eslint');
  assert.equal(esm.ESLint, ESLint);
  const googleRequire = createRequire(require.resolve('gts/package.json'));
  assert.equal(googleRequire('eslint').ESLint, ESLint);
  const fixture = await fs.mkdtemp(
    path.join(os.tmpdir(), 'agentos-pattern-qc-'),
  );
  try {
    await fs.mkdir(path.join(fixture, 'src'));
    // CI single-run programs read the project file from disk. A lintText
    // overlay on src/main.ts can otherwise silently lint the real main.ts.
    const googleFile = path.join(fixture, 'src/google.ts');
    await fs.writeFile(
      googleFile,
      'export function toolingFixture(value: number) {\n' +
        '  var result = value == 1;\n' +
        '  Promise.resolve(result);\n' +
        '  return result;\n' +
        '}\n',
    );
    await fs.writeFile(
      path.join(fixture, 'tsconfig.json'),
      JSON.stringify({
        extends: path.join(workspace, 'tsconfig.json'),
        compilerOptions: {types: []},
        files: ['src/google.ts'],
        include: [],
      }),
    );
    const google = new ESLint({
      cwd: fixture,
      overrideConfigFile: true,
      overrideConfig: [
        ...require(path.join(workspace, 'eslint.config.js')),
        {
          files: ['src/**/*.ts'],
          languageOptions: {
            parserOptions: {
              tsconfigRootDir: fixture,
              project: './tsconfig.json',
            },
          },
        },
      ],
    });
    const [googleResult] = await google.lintFiles([googleFile]);
    assert.equal(
      googleResult.fatalErrorCount,
      0,
      JSON.stringify(googleResult.messages),
    );
    for (const rule of [
      'no-var',
      'eqeqeq',
      '@typescript-eslint/no-floating-promises',
    ]) {
      assert.ok(
        googleResult.messages.some(message => message.ruleId === rule),
        `Google config must still enforce ${rule} through ESLint 10: ` +
          JSON.stringify(googleResult.messages),
      );
    }
    await fs.writeFile(path.join(fixture, 'src/keep.js'), 'debugger;\n');
    await fs.writeFile(path.join(fixture, 'src/skip.js'), 'debugger;\n');
    const eslint = new ESLint({
      cwd: fixture,
      overrideConfigFile: true,
      overrideConfig: [
        {ignores: ['**/skip.js']},
        {files: ['**/*.js'], rules: {'no-debugger': 'error'}},
      ],
    });
    const results = await eslint.lintFiles(['src/**/*.js']);
    assert.deepEqual(
      results.map(result => path.basename(result.filePath)),
      ['keep.js'],
    );
    assert.equal(results[0].errorCount, 1);
    assert.equal(results[0].messages[0].ruleId, 'no-debugger');

    // Exercise the CLI's package/cache consumers, rather than just new APIs.
    const pacoteRequire = createRequire(cliRequire.resolve('pacote'));
    const packlistRequire = createRequire(
      pacoteRequire.resolve('npm-packlist'),
    );
    const walk = packlistRequire('ignore-walk');
    await fs.writeFile(path.join(fixture, '.npmignore'), '**/skip.js\n');
    const files = await walk({path: fixture, ignoreFiles: ['.npmignore']});
    assert.ok(files.includes('src/keep.js'));
    assert.ok(!files.includes('src/skip.js'));

    const cacache = pacoteRequire('cacache');
    const cache = path.join(fixture, 'cache');
    await cacache.put(cache, 'kept-entry', 'kept-content');
    const orphan = path.join(cache, 'content-v2/sha512/aa/bb/orphan');
    await fs.mkdir(path.dirname(orphan), {recursive: true});
    await fs.writeFile(orphan, 'unreferenced-content');
    const verified = await cacache.verify(cache);
    assert.equal(verified.verifiedContent, 1);
    assert.equal(verified.reclaimedCount, 1);
    assert.equal(
      (await cacache.get(cache, 'kept-entry')).data.toString(),
      'kept-content',
    );
    await assert.rejects(fs.access(orphan), {code: 'ENOENT'});
    console.log(
      'PASS: ESLint CJS/ESM, glob selection/ignores, package ignore-walk and cache glob cleanup',
    );
  } finally {
    await fs.rm(fixture, {recursive: true, force: true});
  }
}

async function checkMcpConsumer() {
  const {Client} = cliRequire('@modelcontextprotocol/sdk/client/index.js');
  const {StdioClientTransport} = cliRequire(
    '@modelcontextprotocol/sdk/client/stdio.js',
  );
  const client = new Client({name: 'agentos-tooling-qc', version: '1.0.0'});
  const transport = new StdioClientTransport({
    command: process.execPath,
    args: [cli, 'mcp', '--read-only', '--local-only'],
    cwd: workspace,
    env: environment,
    stderr: 'pipe',
  });
  transport.stderr?.resume();
  try {
    await client.connect(transport, {timeout: 20000});
    const {tools} = await client.listTools({}, {timeout: 10000});
    assert.ok(tools.some(tool => tool.name === 'list_projects'));
    const result = await client.callTool(
      {name: 'list_projects', arguments: {}},
      undefined,
      {timeout: 10000},
    );
    assert.notEqual(result.isError, true);
    const projects = result.structuredContent;
    assert.ok(
      projects?.workspaces?.some(w =>
        w.projects.some(p => p.name === 'agentos-frontend'),
      ),
      'Angular CLI MCP must discover the actual workspace',
    );
    console.log(
      'PASS: patched SDK supports Angular CLI MCP initialize/list/call',
    );
  } finally {
    await client.close();
    await transport.close();
  }
}

async function stopServer(child) {
  if (child.exitCode !== null || child.signalCode !== null) return;
  const closed = once(child, 'close');
  const signal = name => {
    try {
      if (process.platform === 'win32') child.kill(name);
      else process.kill(-child.pid, name);
    } catch (error) {
      if (error.code !== 'ESRCH') throw error;
    }
  };
  signal('SIGTERM');
  const force = setTimeout(() => signal('SIGKILL'), 3000);
  try {
    await closed;
  } finally {
    clearTimeout(force);
  }
}

async function checkDevServerConsumer() {
  const outside = await fs.mkdtemp(path.join(os.tmpdir(), 'agentos-vite-qc-'));
  const marker = 'agentos-outside-workspace-fixture';
  const secret = path.join(outside, 'outside.txt');
  let child;
  try {
    await fs.writeFile(secret, marker);
    child = spawn(
      process.execPath,
      [
        cli,
        'serve',
        '--host=127.0.0.1',
        '--port=0',
        '--hmr=false',
        '--live-reload=false',
      ],
      {
        cwd: workspace,
        env: environment,
        detached: process.platform !== 'win32',
        stdio: ['ignore', 'pipe', 'pipe'],
      },
    );
    let output = '';
    let spawnError;
    child.on('error', error => {
      spawnError = error;
    });
    const collect = data => {
      output = (output + data).slice(-32768);
    };
    child.stdout.on('data', collect);
    child.stderr.on('data', collect);
    const deadline = Date.now() + 75000;
    let base;
    while (Date.now() < deadline) {
      if (spawnError) throw spawnError;
      if (child.exitCode !== null || child.signalCode !== null)
        throw new Error(
          `Angular dev server exited before ready: ${output.slice(-1500)}`,
        );
      base = output.match(/http:\/\/127\.0\.0\.1:\d+\//)?.[0];
      if (base) break;
      await delay(100);
    }
    assert.ok(
      base,
      `Angular dev server readiness timed out: ${output.slice(-1500)}`,
    );
    const response = await fetch(base, {signal: AbortSignal.timeout(10000)});
    assert.equal(response.status, 200);
    assert.match(await response.text(), /<app-root/);
    const script = await fetch(new URL('main.js', base), {
      signal: AbortSignal.timeout(10000),
    });
    assert.equal(script.status, 200);
    assert.match(script.headers.get('content-type'), /javascript/);
    assert.ok((await script.text()).length > 0);
    const denied = await fetch(
      new URL(`@fs/${secret.replaceAll('\\', '/')}`, base),
      {
        signal: AbortSignal.timeout(10000),
      },
    );
    assert.ok([403, 404].includes(denied.status));
    assert.ok(!(await denied.text()).includes(marker));
    console.log(
      'PASS: Angular dev server serves app/modules and denies outside-workspace fixture',
    );
  } finally {
    try {
      if (child) await stopServer(child);
    } finally {
      await fs.rm(outside, {recursive: true, force: true});
    }
  }
}

(async () => {
  await checkPatternConsumers();
  await checkMcpConsumer();
  await checkDevServerConsumer();
})().catch(error => {
  console.error(error);
  process.exitCode = 1;
});
