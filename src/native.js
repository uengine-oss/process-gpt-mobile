'use strict';

/**
 * 웹이 못 하는 세 가지의 다리.
 *
 * 화면은 기존 Vue 앱을 그대로 쓴다. 폰에서만 필요한 것은 셋뿐이다.
 *
 *   보안 저장소  토큰은 절대 localStorage 에 두지 않는다. 폰에서는 Keychain /
 *               KeyStore 로 가고, 브라우저에서는 메모리에만 둔다. 새로고침하면
 *               사라지는 편이, 남의 스크립트가 읽어 갈 수 있는 것보다 낫다.
 *
 *   푸시        에이전트가 물어볼 게 생겼을 때, 앱을 꺼 둔 사용자에게 닿는 길.
 *
 *   파일 저장   결과물을 폰에 내려받기. 브라우저의 다운로드와 폰의 공유 시트는
 *               다른 물건이라 여기서 갈린다.
 *
 * 같은 코드가 브라우저에서도 앱에서도 돌아야 한다. 그래야 화면을 두 벌
 * 유지하지 않는다 — 두 벌이 되는 순간 "답변이 한 번만 보인다" 같은 규칙이
 * 한쪽에서만 지켜진다.
 *
 * 참조: openspec/changes/mobile-portal
 */

/** 지금 폰 안인가. */
function isNative(globalObj = globalThis) {
  const cap = globalObj?.Capacitor;
  return Boolean(cap && typeof cap.isNativePlatform === 'function' && cap.isNativePlatform());
}

function platformName(globalObj = globalThis) {
  const cap = globalObj?.Capacitor;
  if (cap && typeof cap.getPlatform === 'function') return cap.getPlatform();
  return 'web';
}

/**
 * 브라우저에서 쓰는 토큰 보관.
 *
 * 일부러 메모리다. localStorage 에 두면 페이지 안의 어떤 스크립트든 읽을 수 있고,
 * 300초 수명을 둔 이유가 사라진다.
 */
class MemoryTokenStore {
  constructor() {
    this._values = new Map();
  }

  async set(key, value) {
    this._values.set(key, String(value));
  }

  async get(key) {
    return this._values.has(key) ? this._values.get(key) : null;
  }

  async remove(key) {
    this._values.delete(key);
  }
}

/** 폰에서 쓰는 토큰 보관. Keychain / KeyStore 로 간다. */
class NativeTokenStore {
  constructor(preferences) {
    this._prefs = preferences;
  }

  async set(key, value) {
    await this._prefs.set({ key, value: String(value) });
  }

  async get(key) {
    const result = await this._prefs.get({ key });
    return result?.value ?? null;
  }

  async remove(key) {
    await this._prefs.remove({ key });
  }
}

const TOKEN_KEY = 'runner.token';

class NativeBridge {
  /**
   * @param {object} deps  플러그인을 주입받는다 — 테스트가 폰 없이 돌 수 있게.
   */
  constructor({
    preferences = null,
    push = null,
    filesystem = null,
    share = null,
    globalObj = globalThis,
  } = {}) {
    this._globalObj = globalObj;
    this._push = push;
    this._filesystem = filesystem;
    this._share = share;
    this._store =
      preferences && isNative(globalObj)
        ? new NativeTokenStore(preferences)
        : new MemoryTokenStore();
  }

  get platform() {
    return platformName(this._globalObj);
  }

  get isNative() {
    return isNative(this._globalObj);
  }

  // -- 토큰 -------------------------------------------------------------

  async saveToken(token) {
    await this._store.set(TOKEN_KEY, token);
  }

  async loadToken() {
    return this._store.get(TOKEN_KEY);
  }

  async clearToken() {
    await this._store.remove(TOKEN_KEY);
  }

  // -- 푸시 -------------------------------------------------------------

  /**
   * 알림 등록. 거절당해도 앱은 계속 쓸 수 있어야 한다 — 푸시는 편의이지
   * 로그인의 조건이 아니다.
   *
   * @returns {{granted: boolean, token: string|null, reason?: string}}
   */
  async registerForPush() {
    if (!this.isNative || !this._push) {
      return { granted: false, token: null, reason: 'not running on a device' };
    }

    const permission = await this._push.requestPermissions();
    if (permission?.receive !== 'granted') {
      return { granted: false, token: null, reason: 'the user declined notifications' };
    }

    const token = await new Promise((resolve) => {
      let settled = false;
      const done = (value) => {
        if (!settled) {
          settled = true;
          resolve(value);
        }
      };
      this._push.addListener('registration', (t) => done(t?.value ?? null));
      this._push.addListener('registrationError', () => done(null));
      this._push.register();
      setTimeout(() => done(null), 10000);
    });

    return { granted: true, token };
  }

  // -- 파일 저장 --------------------------------------------------------

  /**
   * 결과물을 기기에 남긴다.
   *
   * 폰에서는 파일로 쓴 뒤 공유 시트를 연다 — 어디에 둘지는 사용자가 고른다.
   * 브라우저에서는 평소의 다운로드다.
   */
  async saveArtifact({ name, data, mimeType = 'application/octet-stream' }) {
    const safeName = sanitiseFilename(name);

    if (this.isNative && this._filesystem) {
      const written = await this._filesystem.writeFile({
        path: safeName,
        data,
        directory: 'CACHE',
        recursive: true,
      });
      if (this._share) {
        await this._share.share({ title: safeName, url: written?.uri });
      }
      return { saved: true, name: safeName, uri: written?.uri ?? null };
    }

    return this._browserDownload(safeName, data, mimeType);
  }

  _browserDownload(name, data, mimeType) {
    const doc = this._globalObj?.document;
    if (!doc || typeof doc.createElement !== 'function') {
      return { saved: false, name, reason: 'no place to save it here' };
    }
    const blob = new this._globalObj.Blob([data], { type: mimeType });
    const url = this._globalObj.URL.createObjectURL(blob);
    const anchor = doc.createElement('a');
    anchor.href = url;
    anchor.download = name;
    anchor.click();
    this._globalObj.URL.revokeObjectURL(url);
    return { saved: true, name };
  }
}

/** 경로가 섞여 들어와 엉뚱한 곳에 쓰이지 않게 이름만 남긴다. */
function sanitiseFilename(name) {
  const fallback = 'artifact';
  if (typeof name !== 'string' || !name.trim()) return fallback;
  const base = name.split(/[\\/]/).pop().trim();
  const cleaned = base.replace(/[\0<>:"|?*]/g, '_').replace(/^\.+/, '');
  return cleaned || fallback;
}

module.exports = {
  NativeBridge,
  MemoryTokenStore,
  NativeTokenStore,
  isNative,
  platformName,
  sanitiseFilename,
  TOKEN_KEY,
};
