import http from 'k6/http';
import { check } from 'k6';
import { Counter, Rate } from 'k6/metrics';

const pendingCount = new Counter('pending_count');
const replayCount = new Counter('replay_count');
const businessFailureCount = new Counter('business_failure_count');
const transportFailureCount = new Counter('transport_failure_count');
const systemErrorCount = new Counter('system_error_count');
const businessRejectRate = new Rate('business_reject_rate');
const systemErrorRate = new Rate('system_error_rate');

const isSmoke = (__ENV.K6_MODE || '').toLowerCase() === 'smoke';
const userPrefix = (__ENV.USER_PREFIX || 'u_p').trim();

const parsePositiveInt = (value, fallback) => {
    const parsed = Number.parseInt(value, 10);
    return Number.isFinite(parsed) && parsed > 0 ? parsed : fallback;
};

const parsePositiveIntOrDefault = (key, fallback) => parsePositiveInt(__ENV[key], fallback);
const safeStagesTarget = (target, maxVUs) => Math.min(parsePositiveIntOrDefault('K6_TARGET', target), maxVUs);
const safeMaxVUs = parsePositiveIntOrDefault('K6_MAX_VUS', 220);
const trackHttpReqFailed = isSmoke
    ? ((__ENV.K6_HTTP_REQ_FAILED_STRICT || '').toLowerCase() === 'true')
    : false;

const stageTargetWithFallback = safeStagesTarget(220, safeMaxVUs);
const requestedStartRate = parsePositiveIntOrDefault('K6_START_RATE', 120);
const requestedPreAllocVUs = parsePositiveIntOrDefault('K6_PREALLOC_VUS', 120);
const normalizedStartRate = Math.min(requestedStartRate, stageTargetWithFallback);
const normalizedPreallocVUs = Math.min(requestedPreAllocVUs, stageTargetWithFallback, safeMaxVUs);

const scenario = isSmoke ? {
    executor: 'shared-iterations',
    vus: parsePositiveIntOrDefault('K6_SMOKE_VUS', 10),
    iterations: parsePositiveIntOrDefault('K6_SMOKE_ITERATIONS', 80),
} : {
    executor: 'ramping-arrival-rate',
    timeUnit: '1s',
    startRate: normalizedStartRate,
    preAllocatedVUs: normalizedPreallocVUs,
    maxVUs: safeMaxVUs,
    stages: [
        { duration: __ENV.K6_STAGE_UP || '30s', target: stageTargetWithFallback },
        { duration: __ENV.K6_STAGE_STEADY || '90s', target: stageTargetWithFallback },
        { duration: __ENV.K6_STAGE_DOWN || '30s', target: 0 },
    ],
};

export const options = {
    scenarios: {
        hot: scenario,
    },
    thresholds: {
        ...(trackHttpReqFailed ? { http_req_failed: ['rate<0.1'] } : {}),
        transport_failure_count: ['count<10'],
        system_error_rate: [isSmoke ? 'rate<0.05' : 'rate<0.01'],
        http_req_duration: [isSmoke ? 'p(95)<1500' : 'p(95)<2500', isSmoke ? 'p(99)<3000' : 'p(99)<3500'],
    checks: [isSmoke ? 'rate>0.9' : 'rate>0.95'],
    },
};

export default function () {
    const activityId = __ENV.ACTIVITY_ID || '1001';
    const baseUrl = __ENV.BASE_URL || 'http://localhost:8080';
    const userId = 1000000 + (__VU * 100000) + __ITER;
    const reqId = `${userPrefix}-${__VU}-${__ITER}-${Date.now()}`;

    const parseCode = (res) => {
        try {
            return (JSON.parse(res.body || '{}').code || '').toUpperCase();
        } catch {
            return '';
        }
    };

    const isBusinessReject = code => code === 'DUPLICATE_PURCHASE' || code === 'SOLD_OUT';
    const isSystemCode = code =>
        code.endsWith('_ERROR') || code.startsWith('SYSTEM');

    let isSystemError = false;

    const payload = JSON.stringify({
        userId: userId,
        requestId: reqId
    });

    const res = http.post(
        `${baseUrl}/api/v1/activities/${activityId}/participations`,
        payload,
        { headers: { 'Content-Type': 'application/json' } }
    );

    const statusCode = Number.parseInt(res.status, 10);
    if (res.error || statusCode >= 500 || statusCode === 0) {
        isSystemError = true;
    }

    const code = parseCode(res);
    const ok = check(res, {
        'status is 200/201/202/409': (r) => [200, 201, 202, 409].includes(r.status),
        'response has business code': (r) => {
            if (![200, 201, 202, 409].includes(r.status)) {
                return false;
            }
            if (r.status === 409) {
                return true;
            }
            if (['SUCCESS', 'REPLAY', 'PENDING'].includes(code)) {
                if (code === 'PENDING') {
                    pendingCount.add(1);
                }
                if (code === 'REPLAY') {
                    replayCount.add(1);
                }
                return true;
            }
            if (isBusinessReject(code)) {
                return true;
            }
            if (isSystemCode(code)) {
                isSystemError = true;
                return false;
            }
            return false;
        },
    });

    const isTransportError = Boolean(res.error) || res.status === 0;
    const systemFail = isTransportError || isSystemError || (statusCode >= 500);
    const isBusinessFailure = statusCode === 409 || isBusinessReject(code);

    if (isTransportError) {
        transportFailureCount.add(1);
    }

    if (isBusinessFailure) {
        businessRejectRate.add(1);
        businessFailureCount.add(1);
    } else {
        businessRejectRate.add(0);
        businessFailureCount.add(0);
    }

    if (systemFail) {
        systemErrorCount.add(1);
        systemErrorRate.add(1);
    } else {
        systemErrorCount.add(0);
        systemErrorRate.add(0);
    }

    if (!ok) {
        console.error(`HTTP ${res.status}, body=${res.body}`);
    }
}
