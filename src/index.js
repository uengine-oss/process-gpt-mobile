'use strict';

/**
 * 웹 앱이 붙잡는 진입점.
 *
 * Vue 앱은 이 하나만 알면 된다. 폰인지 브라우저인지는 여기서 흡수하므로,
 * 화면 코드에 `if (모바일)` 이 흩어지지 않는다. 그 분기가 흩어지는 순간
 * 화면이 사실상 두 벌이 되고, 규칙이 한쪽에서만 지켜지기 시작한다.
 *
 * 사용:
 *   import { createPortalBridge } from 'process-gpt-mobile';
 *   const bridge = await createPortalBridge();
 *   window.processGpt = bridge;   // 데스크톱 셸과 같은 이름
 *
 * 데스크톱 셸(`process-gpt-desktop`)도 `window.processGpt` 를 노출한다.
 * 이름을 맞춰 두면 화면 코드가 "이 기기가 무엇을 해 줄 수 있는가" 만 묻고,
 * 어떤 껍데기 안인지는 묻지 않아도 된다.
 *
 * 참조: openspec/changes/mobile-portal
 */

const { NativeBridge, isNative, platformName } = require('./native');
const { ProgressStream } = require('./progress-stream');

/**
 * Capacitor 플러그인을 있는 만큼만 불러온다.
 *
 * 브라우저에서는 하나도 없는 것이 정상이므로, 없다고 실패하지 않는다.
 */
async function loadPlugins(globalObj = globalThis) {
  if (!isNative(globalObj)) return {};

  const pick = async (name) => {
    try {
      // eslint-disable-next-line global-require
      return require(name);
    } catch {
      return null;
    }
  };

  const [prefs, push, fs, share] = await Promise.all([
    pick('@capacitor/preferences'),
    pick('@capacitor/push-notifications'),
    pick('@capacitor/filesystem'),
    pick('@capacitor/share'),
  ]);

  return {
    preferences: prefs?.Preferences ?? null,
    push: push?.PushNotifications ?? null,
    filesystem: fs?.Filesystem ?? null,
    share: share?.Share ?? null,
  };
}

async function createPortalBridge({ globalObj = globalThis } = {}) {
  const plugins = await loadPlugins(globalObj);
  const native = new NativeBridge({ ...plugins, globalObj });

  return {
    platform: native.platform,
    isNative: native.isNative,

    /** 이 기기가 무엇을 해 줄 수 있는가. 화면은 이것만 보고 분기한다. */
    capabilities: {
      secureToken: true,
      push: native.isNative,
      saveFile: true,
      // 폰은 파일을 다루지 않는다 — 그 작업은 데스크톱 러너의 몫이다.
      localFiles: false,
    },

    saveToken: (t) => native.saveToken(t),
    loadToken: () => native.loadToken(),
    clearToken: () => native.clearToken(),
    registerForPush: () => native.registerForPush(),
    saveArtifact: (opts) => native.saveArtifact(opts),

    /** 작업 하나의 진행을 따라가는 상태. 끊김 복구와 중복 렌더를 담당한다. */
    followProgress: (todoId, lastSeq = 0) => new ProgressStream({ todoId, lastSeq }),
    restoreProgress: (snapshot) => ProgressStream.restore(snapshot),
  };
}

module.exports = {
  createPortalBridge,
  NativeBridge,
  ProgressStream,
  isNative,
  platformName,
};
