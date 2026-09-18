'use strict';

const test = require('node:test');
const assert = require('node:assert');

const { createPortalBridge } = require('../src/index');

const browser = () => ({
  Capacitor: { isNativePlatform: () => false, getPlatform: () => 'web' },
});

test('the bridge works in a plain browser', async () => {
  const bridge = await createPortalBridge({ globalObj: browser() });

  assert.equal(bridge.isNative, false);
  assert.equal(bridge.platform, 'web');
});

test('a browser reports that it cannot do push', async () => {
  const bridge = await createPortalBridge({ globalObj: browser() });

  assert.equal(bridge.capabilities.push, false);
  assert.equal(bridge.capabilities.secureToken, true);
});

test('the phone never claims it can reach local files', async () => {
  // 파일 작업은 데스크톱 러너의 몫이다. 폰이 할 수 있다고 신고하면
  // 큐가 그 작업을 폰으로 보내고, 폰은 전부 거절하게 된다.
  const bridge = await createPortalBridge({ globalObj: browser() });

  assert.equal(bridge.capabilities.localFiles, false);
});

test('the token round-trips through the bridge', async () => {
  const bridge = await createPortalBridge({ globalObj: browser() });

  await bridge.saveToken('tok-1');
  assert.equal(await bridge.loadToken(), 'tok-1');

  await bridge.clearToken();
  assert.equal(await bridge.loadToken(), null);
});

test('following a work item gives a stream that resumes', async () => {
  const bridge = await createPortalBridge({ globalObj: browser() });

  const stream = bridge.followProgress('todo-7');
  stream.accept({ seq: 1, todo_id: 'todo-7' });
  stream.accept({ seq: 2, todo_id: 'todo-7' });

  assert.equal(stream.resumeRequest('ten-a').after_seq, 2);
});

test('a stream survives the app being closed and reopened', async () => {
  const bridge = await createPortalBridge({ globalObj: browser() });
  const before = bridge.followProgress('todo-7');
  before.accept({ seq: 3, todo_id: 'todo-7' });

  const after = bridge.restoreProgress(before.snapshot());

  assert.equal(after.lastSeq, 3);
});
