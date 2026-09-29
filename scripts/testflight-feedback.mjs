import { createPrivateKey, sign } from 'node:crypto';

const key = process.env.APP_STORE_CONNECT_PRIVATE_KEY;
const keyId = process.env.APP_STORE_CONNECT_KEY_IDENTIFIER;
const issuer = process.env.APP_STORE_CONNECT_ISSUER_ID;
const bundleId = process.env.IOS_BUNDLE_ID || 'com.lakesidegames.electioneer';
if (!key || !keyId || !issuer) throw new Error('Missing App Store Connect credentials');

const base64url = (value) => Buffer.from(value).toString('base64url');
const now = Math.floor(Date.now() / 1000);
const unsigned = `${base64url(JSON.stringify({ alg: 'ES256', kid: keyId, typ: 'JWT' }))}.${base64url(JSON.stringify({ iss: issuer, iat: now, exp: now + 900, aud: 'appstoreconnect-v1' }))}`;
const signature = sign('sha256', Buffer.from(unsigned), {
  key: createPrivateKey(key.replace(/\\n/g, '\n')),
  dsaEncoding: 'ieee-p1363',
});
const token = `${unsigned}.${signature.toString('base64url')}`;

async function get(url) {
  const response = await fetch(url, { headers: { Authorization: `Bearer ${token}` } });
  if (!response.ok) throw new Error(`App Store Connect request failed: ${response.status} ${new URL(url).pathname}`);
  return response.json();
}

async function list(url) {
  const records = [];
  while (url) {
    const page = await get(url);
    records.push(...(page.data || []));
    url = page.links?.next;
  }
  return records;
}

const root = 'https://api.appstoreconnect.apple.com/v1';
const apps = await list(`${root}/apps?filter%5BbundleId%5D=${encodeURIComponent(bundleId)}&limit=1`);
if (apps.length !== 1) throw new Error(`Expected one app for ${bundleId}, found ${apps.length}`);
const appId = apps[0].id;

for (const [label, resource] of [
  ['crash', 'betaFeedbackCrashSubmissions'],
  ['screenshot', 'betaFeedbackScreenshotSubmissions'],
]) {
  const items = await list(`${root}/apps/${appId}/${resource}?limit=200`);
  console.log(`${label} submissions: ${items.length}`);
  for (const item of items) {
    const a = item.attributes || {};
    const result = {
      id: item.id,
      submitted: a.timestamp || a.createdDate || null,
      device: a.deviceModel || null,
      os: a.osVersion || null,
      buildId: item.relationships?.build?.data?.id || null,
      comment: a.comment || null,
    };
    console.log(JSON.stringify(result));
  }
}
