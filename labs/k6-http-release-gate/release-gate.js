import { check } from 'k6';
import exec from 'k6/execution';
import http from 'k6/http';

const inventoryUrl = (__ENV.INVENTORY_URL || '').replace(/\/+$/, '');
if (!inventoryUrl) {
    throw new Error('Set INVENTORY_URL to the inventory API base URL.');
}

export const options = {
    scenarios: {
        inventory: {
            executor: 'shared-iterations',
            vus: 2,
            iterations: 20,
            maxDuration: '15s',
        },
    },
    thresholds: {
        http_req_failed: ['rate<0.05'],
        checks: ['rate>0.95'],
        iterations: ['count==20'],
        dropped_iterations: ['count==0'],
    },
};

http.setResponseCallback(http.expectedStatuses(200));

export default function () {
    const sku = exec.scenario.iterationInTest;
    const response = http.get(`${inventoryUrl}/inventory/sku-${sku}`, {
        redirects: 0,
        timeout: '3s',
    });

    check(response, {
        'inventory returns 200 and an available item':
            inventoryIsAvailable,
    });
}

function inventoryIsAvailable(reply) {
    if (reply.status !== 200) {
        return false;
    }
    try {
        return reply.json('available') === true;
    } catch {
        return false;
    }
}
