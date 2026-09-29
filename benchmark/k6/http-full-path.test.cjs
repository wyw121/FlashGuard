const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');

// Execute the actual workload with deterministic HTTP responses; no k6 runtime required.
function workload(mode, rejectCode) {
  const samples = {};
  const users = new Set();
  const requestIds = new Set();
  const execution = { scenario: { iterationInTest: 0 } };
  class Metric {
    constructor(name) { this.name = name; samples[name] = []; }
    add(value) { samples[this.name].push(Number(value)); }
  }
  const context = vm.createContext({
    __ENV: { SCENARIO: mode, RUN_ID: 'unit-run', BASE_URL: 'http://test', ACTIVITY_ID: '42' },
    exec: execution, Counter: Metric, Rate: Metric, Trend: Metric,
    sleep() {}, console: { error() {} },
    http: { post(url, payload) {
      const { userId, requestId } = JSON.parse(payload);
      const repeated = requestIds.has(requestId);
      const duplicate = users.has(userId) && !repeated;
      users.add(userId); requestIds.add(requestId);
      const code = rejectCode || (duplicate ? 'DUPLICATE_PURCHASE' : repeated ? 'REPLAY' : 'SUCCESS');
      return { status: ['SOLD_OUT', 'DUPLICATE_PURCHASE'].includes(code) ? 409 : 201,
        timings: { duration: 1 }, json: () => ({ code, data: { status: 'CONFIRMED', orderNo: `o-${userId}` } }) };
    } },
  });
  const source = fs.readFileSync(path.join(__dirname, 'http-full-path.js'), 'utf8')
    .replace(/^import .*;\r?\n/gm, '')
    .replace('export const options', 'const options')
    .replace('export default function ()', 'function iteration()');
  vm.runInContext(source, context);
  return {
    run(ids) { for (const id of ids) { execution.scenario.iterationInTest = id; context.iteration(); } },
    total(name) { return samples[name].reduce((a, b) => a + b, 0); },
    users, requestIds,
  };
}

test('mixed traffic yields half unique orders and half duplicate-user rejections even out of order', () => {
  const load = workload('mixed');
  load.run(Array.from({ length: 1000 }, (_, i) => 999 - i));
  assert.equal(load.requestIds.size, 1000);
  assert.equal(load.users.size, 500);
  assert.equal(load.total('confirmed_results'), 500);
  assert.equal(load.total('business_rejections'), 500);
  assert.equal(load.total('system_error_rate'), 0);
});

test('odd mixed window counts the unpaired request as an additional order', () => {
  const load = workload('mixed');
  load.run([0, 1, 2, 3, 4]);
  assert.equal(load.total('confirmed_results'), 3);
  assert.equal(load.total('business_rejections'), 2);
});

test('mixed traffic must not classify sold-out as an expected duplicate rejection', () => {
  const load = workload('mixed', 'SOLD_OUT');
  load.run([0]);
  assert.equal(load.total('system_error_rate'), 1);
});

test('existing replay distribution retains 50 identities and confirms every response', () => {
  const load = workload('replay');
  load.run(Array.from({ length: 100 }, (_, i) => i));
  assert.equal(load.users.size, 50);
  assert.equal(load.requestIds.size, 50);
  assert.equal(load.total('confirmed_results'), 100);
  assert.equal(load.total('business_rejections'), 0);
  assert.equal(load.total('system_error_rate'), 0);
});
