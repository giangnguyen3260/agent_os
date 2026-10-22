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
  await checkMcpConsumer();
  await checkDevServerConsumer();
})().catch(error => {
  console.error(error);
  process.exitCode = 1;
});
