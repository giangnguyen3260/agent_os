// Test the real service with deterministic transport ordering; no Angular rendering.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const ts = require('typescript');
class HttpErrorResponse extends Error {
  constructor(status) { super(String(status)); this.status = status; }
}
class Params { set() { return this; } toString() { return ''; } }
const A = {id: '10000000-0000-4000-8000-000000000001', email: 'a@example.test', displayName: 'Account A'};
const B = {id: '10000000-0000-4000-8000-000000000002', email: 'b@example.test', displayName: 'Account B'};
function fixture() {
  const published = [];
  let resolve, reject;
  const promise = new Promise((a, b) => { resolve = a; reject = b; });
  const http = {
    get: url => url.endsWith('/csrf') ? Promise.resolve({data: {headerName: 'X-CSRF', token: 'test'}}) : promise,
    post: url => Promise.resolve(url.endsWith('/login') ? {data: B} : undefined),
  };
  const source = ts.transpileModule(fs.readFileSync('src/app/core/auth/auth.service.ts', 'utf8'), {
    compilerOptions: {target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS, experimentalDecorators: true},
  }).outputText;
  const module = {exports: {}};
  const adapters = {
    '@angular/common/http': {HttpErrorResponse, HttpHeaders: Params, HttpParams: Params},
    '@angular/core': {
      inject: () => http,
      Injectable: () => value => value,
      signal: initial => {
        let value = initial;
        const read = () => value;
        read.set = next => { value = next; published.push(next); };
        read.asReadonly = () => read;
        return read;
      },
    },
    rxjs: {firstValueFrom: value => value},
  };
  vm.runInNewContext(source, {module, exports: module.exports, require: name => {
    assert.ok(Object.hasOwn(adapters, name));
    return adapters[name];
  }});
  return {service: new module.exports.AuthService(), resolve, reject, published};
}
(async () => {
  for (const outcome of ['success', 'unauthorized']) {
    const {service, resolve, reject} = fixture();
    const oldSession = service.session().catch(error => error);
    await service.logout();
    await service.login('b@example.test', 'test-only');
    assert.equal(service.user().id, B.id);
    if (outcome === 'success') resolve({data: A});
    else reject(new HttpErrorResponse(401));
    await oldSession;
    assert.equal(service.user().id, B.id, `stale ${outcome} changed current identity`);
    console.log(`PASS: delayed session ${outcome} cannot overwrite a newer account`);
  }
  for (const malformed of [null, {}, {id: A.id}, {...A, id: 'not-a-uuid'}, {...A, id: 1}, {...A, extra: true}, {...A, email: ''}, {...A, displayName: '\uD800'}, {...A, displayName: 'name\nspoof'}]) {
    const {service, resolve, published} = fixture();
    await service.login(B.email, 'test-only');
    const count = published.length;
    const pending = service.session().catch(error => error);
    resolve({data: malformed});
    assert.equal((await pending).kind, 'server');
    assert.equal(service.user().id, B.id);
    assert.equal(published.length, count, 'malformed session reached shared identity observers');
  }
  console.log('PASS: malformed session data never publishes a transient identity');
  const {service, resolve} = fixture();
  const pending = service.session();
  resolve({data: A});
  assert.equal((await pending).id, A.id);
  assert.equal(service.user().id, A.id);
  console.log('PASS: a valid current session still updates shared identity');
})().catch(error => { console.error(error); process.exitCode = 1; });
