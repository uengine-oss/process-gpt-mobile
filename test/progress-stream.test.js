'use strict';

/**
 * 끊김 복구와 중복 렌더.
 *
 * 폰은 데스크톱보다 훨씬 자주 끊긴다. 그래서 여기서 통과하지 못하면
 * 사용자는 "답변이 두 번 보인다" 또는 "답변이 사라졌다" 를 겪는다.
 */

const test = require('node:test');
const assert = require('node:assert');

const { ProgressStream } = require('../src/progress-stream');

const ev = (seq, extra = {}) => ({ seq, todo_id: 'todo-7', type: 'token', ...extra });

// --------------------------------------------------------------------------
// resuming after a drop
// --------------------------------------------------------------------------

test('a fresh stream starts from the beginning', () => {
  const s = new ProgressStream({ todoId: 'todo-7' });

  assert.equal(s.lastSeq, 0);
  assert.deepEqual(s.resumeRequest('ten-a'), {
    todo_id: 'todo-7',
    tenant_id: 'ten-a',
    after_seq: 0,
  });
});

test('the stream asks to continue from what it actually rendered', () => {
  const s = new ProgressStream({ todoId: 'todo-7' });
  [1, 2, 3].forEach((n) => s.accept(ev(n)));

  assert.equal(s.resumeRequest('ten-a').after_seq, 3);
});

test('everything missed during a drop is rendered, in order', () => {
  const s = new ProgressStream({ todoId: 'todo-7' });
  s.accept(ev(1));

  const rendered = s.acceptBatch([ev(4), ev(2), ev(3)]);

  assert.deepEqual(rendered.map((e) => e.seq), [2, 3, 4]);
});

test('nothing already seen is rendered twice after reconnecting', () => {
  const s = new ProgressStream({ todoId: 'todo-7' });
  [1, 2, 3].forEach((n) => s.accept(ev(n)));

  const rendered = s.acceptBatch([ev(2), ev(3), ev(4)]);

  assert.deepEqual(rendered.map((e) => e.seq), [4]);
});

test('an old event arriving late is dropped, not rendered out of order', () => {
  const s = new ProgressStream({ todoId: 'todo-7' });
  [1, 2, 3].forEach((n) => s.accept(ev(n)));

  assert.equal(s.accept(ev(2)).render, false);
  assert.equal(s.lastSeq, 3, '늦게 온 옛 이벤트가 진행 위치를 되돌리면 안 된다');
});

// --------------------------------------------------------------------------
// the same turn arriving on three channels
// --------------------------------------------------------------------------

test('a turn delivered three ways is rendered once', () => {
  const s = new ProgressStream({ todoId: 'todo-7' });

  const fromStream = s.accept(ev(5, { via: 'sse' }));
  const fromChatRow = s.accept(ev(5, { via: 'chats' }));
  const fromEventRow = s.accept(ev(5, { via: 'events' }));

  assert.equal(fromStream.render, true);
  assert.equal(fromChatRow.render, false);
  assert.equal(fromEventRow.render, false);
});

test('whichever channel arrives first is the one that renders', () => {
  const s = new ProgressStream({ todoId: 'todo-7' });

  // 스트림이 늦고 대화 행이 먼저 오는 경우.
  assert.equal(s.accept(ev(5, { via: 'chats' })).render, true);
  assert.equal(s.accept(ev(5, { via: 'sse' })).render, false);
});

test('a turn that only ever arrives on one channel is not lost', () => {
  const s = new ProgressStream({ todoId: 'todo-7' });

  // 어느 한 경로를 끄는 방식이었다면 여기서 답변이 통째로 사라진다.
  assert.equal(s.accept(ev(9, { via: 'events' })).render, true);
});

// --------------------------------------------------------------------------
// events the server could not number
// --------------------------------------------------------------------------

test('an unnumbered event is still shown to the user', () => {
  const s = new ProgressStream({ todoId: 'todo-7' });

  const verdict = s.accept({ todo_id: 'todo-7', type: 'token' });

  assert.equal(verdict.render, true);
  assert.equal(verdict.reason, 'unsequenced');
});

test('an unnumbered event does not move the resume point', () => {
  const s = new ProgressStream({ todoId: 'todo-7' });
  s.accept(ev(3));

  s.accept({ todo_id: 'todo-7', type: 'token' });

  assert.equal(s.lastSeq, 3);
});

test('junk is refused rather than crashing the view', () => {
  const s = new ProgressStream({ todoId: 'todo-7' });

  assert.equal(s.accept(null).render, false);
  assert.equal(s.accept('nonsense').render, false);
  assert.equal(s.accept(undefined).render, false);
});

// --------------------------------------------------------------------------
// moving between work items
// --------------------------------------------------------------------------

test('switching work items does not carry the old numbering over', () => {
  const s = new ProgressStream({ todoId: 'todo-7' });
  [1, 2, 3].forEach((n) => s.accept(ev(n)));

  s.switchTo('todo-8');

  // 순번은 작업마다 1부터 다시 센다. 섞이면 새 작업의 첫 이벤트가 걸러진다.
  assert.equal(s.lastSeq, 0);
  assert.equal(s.accept({ seq: 1, todo_id: 'todo-8' }).render, true);
});

// --------------------------------------------------------------------------
// closing and reopening the app
// --------------------------------------------------------------------------

test('reopening the app continues where it left off', () => {
  const before = new ProgressStream({ todoId: 'todo-7' });
  [1, 2, 3, 4].forEach((n) => before.accept(ev(n)));

  const after = ProgressStream.restore(before.snapshot());

  assert.equal(after.resumeRequest('ten-a').after_seq, 4);
  assert.equal(after.accept(ev(5)).render, true);
});

test('a restored stream does not re-render what it had already shown', () => {
  const before = new ProgressStream({ todoId: 'todo-7' });
  [1, 2, 3].forEach((n) => before.accept(ev(n)));

  const after = ProgressStream.restore(before.snapshot());

  // 재접속은 after_seq=3 으로 요청하므로 서버가 1~3 을 보내지 않는다.
  assert.deepEqual(after.acceptBatch([ev(4), ev(5)]).map((e) => e.seq), [4, 5]);
});

test('a corrupt snapshot restarts from the beginning rather than failing', () => {
  assert.equal(ProgressStream.restore(null).lastSeq, 0);
  assert.equal(ProgressStream.restore({ lastSeq: -5 }).lastSeq, 0);
  assert.equal(ProgressStream.restore({ lastSeq: 'x' }).lastSeq, 0);
});
