import {createServer, request as upstreamRequest} from 'node:http';
import {createReadStream} from 'node:fs';
import {stat} from 'node:fs/promises';
import {extname, resolve, sep} from 'node:path';

const backend = new URL(process.env.BACKEND_URL);
if (backend.protocol !== 'http:' || backend.hostname !== '127.0.0.1') {
  throw new Error('A disposable loopback backend is required');
}
const root = resolve('dist/agentos-frontend/browser');
const port = Number(process.env.PORT);
if (!Number.isInteger(port) || port < 1024 || port > 65535) {
  throw new Error('An explicit test proxy port is required');
}
let dropCreate = process.env.DROP_CREATE_RESPONSE === 'once';
const types = {
  '.html': 'text/html',
  '.js': 'application/javascript',
  '.css': 'text/css',
  '.woff2': 'font/woff2',
  '.svg': 'image/svg+xml',
  '.ico': 'image/x-icon',
};
const server = createServer(async (incoming, outgoing) => {
  const path = incoming.url || '/';
  if (path.startsWith('/api/')) {
    const drop =
      dropCreate &&
      incoming.method === 'POST' &&
      /^\/api\/v1\/organizations\/[0-9a-f-]+\/provider-connections$/.test(path);
    if (drop) dropCreate = false;
    const proxy = upstreamRequest(
      new URL(path, backend),
      {method: incoming.method, headers: incoming.headers},
      response => {
        if (drop) {
          response.resume();
          response.once('end', () => outgoing.destroy());
          response.once('error', () => outgoing.destroy());
          return;
        }
        outgoing.writeHead(response.statusCode || 502, response.headers);
        response.pipe(outgoing);
      },
    );
    proxy.on('error', () => {
      if (outgoing.headersSent) outgoing.destroy();
      else outgoing.writeHead(502).end();
    });
    incoming.pipe(proxy);
    return;
  }
  try {
    const requested = resolve(
      root,
      '.' + decodeURIComponent(path.split('?')[0]),
    );
    if (requested !== root && !requested.startsWith(root + sep)) {
      outgoing.writeHead(404).end();
      return;
    }
    let file = requested;
    const entry = await stat(file).catch(() => null);
    if (!entry?.isFile()) file = resolve(root, 'index.html');
    outgoing.writeHead(200, {
      'Content-Type': types[extname(file)] || 'application/octet-stream',
      'Cache-Control': 'no-store',
    });
    createReadStream(file).pipe(outgoing);
  } catch {
    outgoing.writeHead(404).end();
  }
});
server.listen(port, '127.0.0.1');
