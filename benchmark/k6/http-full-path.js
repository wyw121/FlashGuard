import http from 'k6/http';
import exec from 'k6/execution';
import { sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

const requests = new Counter('participation_requests');
const accepted = new Counter('accepted_requests');
const confirmed = new Counter('confirmed_results');
const rejects = new Counter('business_rejections');
const throttled = new Counter('throttled_requests');
const replays = new Counter('replayed_requests');
const errors = new Rate('system_error_rate');
const admission = new Trend('admission_ms', true);
const completion = new Trend('confirmed_e2e_ms', true);
const rate = Number(__ENV.RATE || 10);
const mode = __ENV.SCENARIO;
export const options = {
  scenarios: { traffic: { executor: 'constant-arrival-rate', rate, timeUnit: '1s',
    duration: `${__ENV.SECONDS || 180}s`, preAllocatedVUs: Number(__ENV.VUS || 100),
    maxVUs: Number(__ENV.VUS || 100), gracefulStop: '35s' } },
  summaryTrendStats: ['min', 'med', 'max', 'p(50)', 'p(95)', 'p(99)'],
  thresholds: { system_error_rate: ['rate==0'], dropped_iterations: ['count==0'] },
};
function body(response) { try { return response.json(); } catch (_) { return {}; } }
function diagnosticToken(value) {
  return typeof value === 'string' ? value.replace(/[^a-zA-Z0-9_.:-]/g, '_').slice(0, 64) : null;
}
function recordError(failed, requestId, phase, response, value, start) {
  errors.add(failed);
  if (!failed) return;
  console.error(JSON.stringify({
    event: 'flashguard_system_error',
    requestId: diagnosticToken(requestId),
    phase,
    httpStatus: response.status,
    code: diagnosticToken(value && value.code),
    dataStatus: diagnosticToken(value && value.data && value.data.status),
    hasOrderNo: !!(value && value.data && value.data.orderNo),
    elapsedMs: Date.now() - start,
  }));
}
export default function () {
  requests.add(1); accepted.add(0); confirmed.add(0); rejects.add(0); throttled.add(0); replays.add(0);
  const id = exec.scenario.iterationInTest;
  // Replay is a deterministic 50-user/50-request distribution, not accidental duplicate UUIDs.
  const slot = mode === 'replay' ? id % 50 : id;
  const requestId = `${__ENV.RUN_ID}-${slot}`;
  const start = Date.now();
  const response = http.post(`${__ENV.BASE_URL}/api/v1/activities/${__ENV.ACTIVITY_ID}/participations`,
    JSON.stringify({ userId: 1000000 + slot, requestId }),
    { headers: { 'Content-Type': 'application/json' }, timeout: '10s', tags: { name: 'participate' } });
  admission.add(response.timings.duration);
  const value = body(response);
  if (['SOLD_OUT', 'DUPLICATE_PURCHASE', 'THROTTLED'].includes(value.code)) {
    rejects.add(1); if (value.code === 'THROTTLED') throttled.add(1);
    recordError(response.status !== (value.code === 'THROTTLED' ? 429 : 409) || mode === 'success' || mode === 'replay',
      requestId, 'admission_business_rejection', response, value, start); return;
  }
  if (![200, 201, 202].includes(response.status) || !['SUCCESS', 'REPLAY', 'PENDING'].includes(value.code)) {
    recordError(true, requestId, 'admission_response', response, value, start); return;
  }
  accepted.add(1); if (value.code === 'REPLAY') replays.add(1);
  let final = value;
  let finalResponse = response;
  while (final.data && final.data.status !== 'CONFIRMED' && Date.now() - start < 30000) {
    sleep(0.1);
    const result = http.get(`${__ENV.BASE_URL}/api/v1/participations/${requestId}`,
      { timeout: '5s', tags: { name: 'result_poll' } });
    if (result.status !== 200) { recordError(true, requestId, 'result_poll', result, body(result), start); return; }
    finalResponse = result;
    final = body(result);
  }
  const done = final.data && final.data.status === 'CONFIRMED' && final.data.orderNo;
  recordError(!done, requestId, 'confirmation_validation', finalResponse, final, start);
  if (done) { confirmed.add(1); completion.add(Date.now() - start); }
}
