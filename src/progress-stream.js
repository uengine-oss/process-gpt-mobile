'use strict';

/**
 * 진행 이벤트를 받아 화면에 무엇을 그릴지 정한다.
 *
 * 폰에서 가장 잘 깨지는 두 가지를 여기서 막는다.
 *
 *   끊김 복구  — 지하철에서 끊겼다 붙을 때, 놓친 것은 전부 받고 이미 본 것은
 *               하나도 다시 받지 않아야 한다. 기준은 시각이 아니라 순번이다.
 *               같은 밀리초에 여러 건이 들어오면 시각으로는 순서가 갈리고,
 *               기기 시계와 서버 시계는 애초에 다르다.
 *
 *   중복 렌더  — 한 답변이 세 경로(스트림 · 대화 행 · 이벤트 행)로 온다.
 *               먼저 온 것만 그리고 나머지는 버린다. 어느 한 경로를 끄는 방식은
 *               쓰지 않는다 — 그 경로만 도착하는 경우에 답변이 통째로 사라진다.
 *
 * 순수 로직이라 브라우저에서도 앱에서도 같은 코드가 돈다.
 *
 * 참조: openspec/changes/mobile-portal
 */

class ProgressStream {
  constructor({ todoId, lastSeq = 0 } = {}) {
    this.todoId = todoId || null;
    this._lastSeq = Number.isFinite(lastSeq) && lastSeq > 0 ? Math.floor(lastSeq) : 0;
    this._rendered = new Set();
  }

  /** 지금까지 그린 마지막 순번. 재접속은 여기서 이어진다. */
  get lastSeq() {
    return this._lastSeq;
  }

  /** 재접속 요청 본문. */
  resumeRequest(tenantId) {
    return { todo_id: this.todoId, tenant_id: tenantId, after_seq: this._lastSeq };
  }

  /**
   * 이 이벤트를 그려야 하는가.
   *
   * @returns {{render: boolean, reason?: string}}
   */
  accept(event) {
    if (!event || typeof event !== 'object') {
      return { render: false, reason: 'not an event' };
    }

    const seq = event.seq;
    if (!Number.isFinite(seq) || seq <= 0) {
      // 순번 없는 이벤트는 작업에 매이지 않은 것이다(서버가 매기지 못한 경우).
      // 순서를 보장할 수 없으므로 진행 위치를 옮기지 않고, 그리기는 한다 —
      // 버리면 사용자가 볼 것을 잃는다.
      return { render: true, reason: 'unsequenced' };
    }

    const key = `${event.todo_id ?? this.todoId}#${seq}`;
    if (this._rendered.has(key)) {
      return { render: false, reason: 'already rendered' };
    }

    this._rendered.add(key);
    if (seq > this._lastSeq) this._lastSeq = seq;
    return { render: true };
  }

  /**
   * 재접속으로 받은 묶음을 처리한다. 순번 순으로 정렬해 넣는다 —
   * 도착 순서가 곧 발생 순서라는 보장은 없다.
   */
  acceptBatch(events) {
    const list = Array.isArray(events) ? [...events] : [];
    list.sort((a, b) => (a?.seq ?? 0) - (b?.seq ?? 0));
    return list.filter((e) => this.accept(e).render);
  }

  /**
   * 다른 작업으로 옮겨간다. 이전 작업의 기억은 버린다 —
   * 순번은 작업마다 1부터 다시 세므로 섞이면 잘못 걸러진다.
   */
  switchTo(todoId, lastSeq = 0) {
    this.todoId = todoId;
    this._lastSeq = Number.isFinite(lastSeq) && lastSeq > 0 ? Math.floor(lastSeq) : 0;
    this._rendered.clear();
  }

  /** 앱을 껐다 켰을 때 이어붙일 수 있도록 최소 상태만 남긴다. */
  snapshot() {
    return { todoId: this.todoId, lastSeq: this._lastSeq };
  }

  static restore(snapshot) {
    return new ProgressStream({
      todoId: snapshot?.todoId,
      lastSeq: snapshot?.lastSeq,
    });
  }
}

module.exports = { ProgressStream };
