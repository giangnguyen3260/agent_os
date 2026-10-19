// Test the real service with deterministic transport ordering; no Angular rendering.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const ts = require('typescript');
class HttpErrorResponse extends Error {
  constructor(status) { super(String(status)); this.status = status; }
}
class Params { set() { return this; } toString() { return ''; } }
function fixture() {
  let resolve, reject;
  const promise = new Promise((a, b) => { resolve = a; reject = b; });
  const http = {
    get: url => url.endsWith('/csrf') ? Promise.resolve({data: {headerName: 'X-CSRF', token: 'test'}}) : promise,
    post: url => Promise.resolve(url.endsWith('/login') ? {data: {id: 'B'}} : undefined),
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
        read.set = next => { value = next; };
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
  return {service: new module.exports.AuthService(), resolve, reject};
}
(async () => {
  for (const outcome of ['success', 'unauthorized']) {
    const {service, resolve, reject} = fixture();
    const oldSession = service.session().catch(error => error);
    await service.logout();
    await service.login('b@example.test', 'test-only');
    assert.equal(service.user().id, 'B');
    if (outcome === 'success') resolve({data: {id: 'A'}});
    else reject(new HttpErrorResponse(401));
    await oldSession;
    assert.equal(service.user().id, 'B', `stale ${outcome} changed current identity`);
    console.log(`PASS: delayed session ${outcome} cannot overwrite a newer account`);
  }
})().catch(error => { console.error(error); process.exitCode = 1; });
