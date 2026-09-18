'use strict';

/**
 * 네이티브 다리.
 *
 * 같은 코드가 브라우저에서도 앱에서도 돌아야 한다. 두 벌이 되는 순간
 * "답변이 한 번만 보인다" 같은 규칙이 한쪽에서만 지켜진다.
 */

const test = require('node:test');
const assert = require('node:assert');

const {
  NativeBridge,
  MemoryTokenStore,
  isNative,
  platformName,
  sanitiseFilename,
  TOKEN_KEY,
} = require('../src/native');

function phone(platform = 'android') {
  return { Capacitor: { isNativePlatform: () => true, getPlatform: () => platform } };
}

function browser() {
  return { Capacitor: { isNativePlatform: () => false, getPlatform: () => 'web' } };
}

class FakePreferences {
  constructor() {
    this.data = new Map();
  }
  async set({ key, value }) {
    this.data.set(key, value);
  }
  async get({ key }) {
    return { value: this.data.has(key) ? this.data.get(key) : null };
  }
  async remove({ key }) {
    this.data.delete(key);
  }
}

// --------------------------------------------------------------------------
// knowing where we are
// --------------------------------------------------------------------------

test('the bridge knows whether it is on a phone', () => {
  assert.equal(isNative(phone()), true);
  assert.equal(isNative(browser()), false);
  assert.equal(isNative({}), false, 'Capacitor 가 없으면 브라우저다');
});

test('the platform name is reported for both', () => {
  assert.equal(platformName(phone('ios')), 'ios');
  assert.equal(platformName({}), 'web');
});

// --------------------------------------------------------------------------
// the token
// --------------------------------------------------------------------------

test('on a phone the token goes to the platform store', async () => {
  const prefs = new FakePreferences();
  const bridge = new NativeBridge({ preferences: prefs, globalObj: phone() });

  await bridge.saveToken('tok-1');

  assert.equal(prefs.data.get(TOKEN_KEY), 'tok-1');
  assert.equal(await bridge.loadToken(), 'tok-1');
});

test('in a browser the token never reaches persistent storage', async () => {
  const prefs = new FakePreferences();
  const bridge = new NativeBridge({ preferences: prefs, globalObj: browser() });

  await bridge.saveToken('tok-1');

  assert.equal(prefs.data.size, 0, '브라우저에서는 어디에도 남기지 않는다');
  assert.equal(await bridge.loadToken(), 'tok-1', '이번 세션 동안은 쓸 수 있어야 한다');
});

test('clearing the token actually removes it', async () => {
  const bridge = new NativeBridge({ preferences: new FakePreferences(), globalObj: phone() });
  await bridge.saveToken('tok-1');

  await bridge.clearToken();

  assert.equal(await bridge.loadToken(), null);
});

test('asking for a token that was never stored gives nothing, not a crash', async () => {
  const bridge = new NativeBridge({ globalObj: browser() });

  assert.equal(await bridge.loadToken(), null);
});

test('the memory store keeps runners separate', async () => {
  const store = new MemoryTokenStore();

  await store.set('a', '1');
  await store.set('b', '2');

  assert.equal(await store.get('a'), '1');
  assert.equal(await store.get('b'), '2');
});

// --------------------------------------------------------------------------
// push
// --------------------------------------------------------------------------

test('push is skipped in a browser without pretending it worked', async () => {
  const bridge = new NativeBridge({ globalObj: browser() });

  const result = await bridge.registerForPush();

  assert.equal(result.granted, false);
  assert.match(result.reason, /device/);
});

test('declining notifications does not break the app', async () => {
  const push = {
    requestPermissions: async () => ({ receive: 'denied' }),
    addListener: () => {},
    register: () => {},
  };
  const bridge = new NativeBridge({ push, globalObj: phone() });

  const result = await bridge.registerForPush();

  assert.equal(result.granted, false);
  assert.equal(result.token, null);
  assert.match(result.reason, /declined/);
});

test('granting notifications returns the device token', async () => {
  const listeners = {};
  const push = {
    requestPermissions: async () => ({ receive: 'granted' }),
    addListener: (name, fn) => {
      listeners[name] = fn;
    },
    register: () => listeners.registration?.({ value: 'device-token-1' }),
  };
  const bridge = new NativeBridge({ push, globalObj: phone() });

  const result = await bridge.registerForPush();

  assert.equal(result.granted, true);
  assert.equal(result.token, 'device-token-1');
});

test('a registration failure resolves instead of hanging the app', async () => {
  const listeners = {};
  const push = {
    requestPermissions: async () => ({ receive: 'granted' }),
    addListener: (name, fn) => {
      listeners[name] = fn;
    },
    register: () => listeners.registrationError?.({ error: 'no network' }),
  };
  const bridge = new NativeBridge({ push, globalObj: phone() });

  const result = await bridge.registerForPush();

  assert.equal(result.granted, true);
  assert.equal(result.token, null);
});

// --------------------------------------------------------------------------
// saving a result
// --------------------------------------------------------------------------

test('on a phone the file is written and the share sheet opens', async () => {
  const written = [];
  const shared = [];
  const bridge = new NativeBridge({
    filesystem: {
      writeFile: async (opts) => {
        written.push(opts);
        return { uri: 'file:///cache/견적서.pdf' };
      },
    },
    share: { share: async (opts) => shared.push(opts) },
    globalObj: phone(),
  });

  const result = await bridge.saveArtifact({ name: '견적서.pdf', data: 'JVBERi0=' });

  assert.equal(result.saved, true);
  assert.equal(written.length, 1);
  assert.equal(shared.length, 1, '어디에 둘지는 사용자가 고른다');
});

test('a path smuggled into the filename is reduced to a name', async () => {
  const written = [];
  const bridge = new NativeBridge({
    filesystem: {
      writeFile: async (opts) => {
        written.push(opts);
        return { uri: 'file:///cache/x' };
      },
    },
    globalObj: phone(),
  });

  await bridge.saveArtifact({ name: '../../etc/passwd' });

  assert.equal(written[0].path, 'passwd');
});

test('filenames are reduced to something safe to write', () => {
  assert.equal(sanitiseFilename('/tmp/report.pdf'), 'report.pdf');
  assert.equal(sanitiseFilename('C:\\Windows\\x.txt'), 'x.txt');
  assert.equal(sanitiseFilename('bad:name?.txt'), 'bad_name_.txt');
  assert.equal(sanitiseFilename('...'), 'artifact');
  assert.equal(sanitiseFilename(''), 'artifact');
  assert.equal(sanitiseFilename(undefined), 'artifact');
});

test('in a browser the result downloads the usual way', async () => {
  const clicks = [];
  const fakeDoc = {
    createElement: () => ({
      set href(v) {
        this._href = v;
      },
      get href() {
        return this._href;
      },
      download: '',
      click() {
        clicks.push(this.download);
      },
    }),
  };
  const globalObj = {
    ...browser(),
    document: fakeDoc,
    Blob: class {
      constructor(parts, opts) {
        this.parts = parts;
        this.opts = opts;
      }
    },
    URL: { createObjectURL: () => 'blob:x', revokeObjectURL: () => {} },
  };
  const bridge = new NativeBridge({ globalObj });

  const result = await bridge.saveArtifact({ name: 'report.pdf', data: 'x' });

  assert.equal(result.saved, true);
  assert.deepEqual(clicks, ['report.pdf']);
});

test('nowhere to save is reported rather than silently failing', async () => {
  const bridge = new NativeBridge({ globalObj: browser() });

  const result = await bridge.saveArtifact({ name: 'report.pdf', data: 'x' });

  assert.equal(result.saved, false);
  assert.ok(result.reason);
});
