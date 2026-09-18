package io.processgpt.app;

import android.Manifest;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.webkit.WebSettings;
import android.webkit.WebView;

import com.getcapacitor.BridgeActivity;

import java.io.BufferedReader;
import java.io.InputStreamReader;

import java.net.URI;

/**
 * 웹 포털을 감싸는 껍데기.
 *
 * 화면과 기능은 전부 포털의 것이다. 여기서 하는 일은 **앱에서만 할 수 있는 것**
 * 세 가지뿐이다.
 *
 *   1. 알림 (wrapper.js 를 페이지에 얹어 준다)
 *   2. 뒤로 가기 (없으면 어느 화면에서든 앱이 곧바로 닫힌다)
 *   3. 마지막으로 보던 곳 기억 (없으면 켤 때마다 홍보 페이지로 돌아간다)
 *
 * 포털은 자기가 앱 안에서 돈다는 것을 모른다. 그래서 이 셋을 포털에 요구하지
 * 않고 바깥에서 얹는다 — 포털을 건드리기 시작하면 이 앱이 없애려던 이중 작업이
 * 다시 생긴다.
 */
public class MainActivity extends BridgeActivity {

    private static final String PREFS = "wrapper";
    private static final String KEY_LAST_URL = "lastUrl";

    /**
     * 알림이 실어 보낸 주소. **super.onCreate() 전에** 꺼내 둔다.
     *
     * 푸시 플러그인이 자기 초기화를 하면서 실행 인텐트의 값을 가져가 버린다.
     * 그 뒤에 읽으면 비어 있어서, 알림을 눌러도 늘 첫 화면만 열렸다.
     * 그래서 플러그인들이 깨어나기 전에 먼저 챙긴다.
     */
    private String pendingUrl;

    /**
     * 이번 실행에서 아직 첫 화면을 열지 않았는가.
     *
     * 앱은 포털의 index.html 을 웹뷰 캐시에 담아 두고 다음에도 그대로 쓴다.
     * 그러면 포털을 새로 배포해도 **앱만 옛 화면을 계속 본다** — 실제로
     * 배포된 번들과 앱이 불러온 번들이 서로 달랐다. 켤 때 한 번은 새로 받는다.
     */
    private boolean firstStart = true;

    /** 이 앱이 머무를 수 있는 곳. 그 밖의 주소는 기억하지도, 되돌아가지도 않는다. */
    private static boolean isOurs(String url) {
        if (url == null || url.isEmpty()) return false;
        try {
            URI uri = new URI(url);
            String host = uri.getHost();
            if (host == null) return false;
            return host.equals("process-gpt.io") || host.endsWith(".process-gpt.io");
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        // 플러그인이 가져가기 전에 먼저 챙긴다. (위 pendingUrl 주석 참고)
        pendingUrl = urlFromIntent();

        // 웹뷰가 뜨기 전에 등록해야 화면에서 부를 수 있다.
        registerPlugin(PushSupportPlugin.class);
        super.onCreate(savedInstanceState);
        askNotificationPermission();
    }

    /**
     * 알림을 보내도 되는지 물어본다.
     *
     * 안드로이드 13 부터는 메니페스트에 적어 둔 것만으로는 부족하고,
     * 사용자에게 직접 허락을 받아야 한다. 받지 않으면 기기는 메시지를
     * 받기는 받는데 안드로이드가 조용히 버린다 — 보낸 쪽은 성공으로 보이고
     * 받는 사람은 아무것도 못 보는, 가장 찾기 어려운 경우가 된다.
     */
    private void askNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return;
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return;
        requestPermissions(new String[] { Manifest.permission.POST_NOTIFICATIONS }, 1001);
    }

    /**
     * 알림을 눌렀을 때 실제로 값이 들어오는 자리.
     *
     * 앱을 완전히 끄지 않는 한 안드로이드는 새 인텐트로 전달한다. onCreate 의
     * 인텐트는 비어 있고 여기에만 값이 온다 — 확인해 보니 이랬다.
     *
     *     onCreate    → (없음)
     *     onNewIntent → [.., url, title, google.message_id, ..]
     *
     * 그래서 여기서도 이동을 걸어 준다. onStart 에서만 처리하면 그때는 아직
     * 값이 없어서 **알림을 눌러도 늘 첫 화면만 열린다.**
     */
    @Override
    protected void onNewIntent(android.content.Intent intent) {
        setIntent(intent);
        super.onNewIntent(intent);

        String url = urlFromIntent();
        if (url == null) return;

        pendingUrl = url;
        WebView web = getBridge() != null ? getBridge().getWebView() : null;
        if (web != null) followNotification(web);
    }

    private String urlFromIntent() {
        try {
            Bundle extras = getIntent() != null ? getIntent().getExtras() : null;
            if (extras == null) return null;
            Object url = extras.get("url");
            String value = url != null ? String.valueOf(url) : null;
            return isOurs(value) ? value : null;
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public void onStart() {
        super.onStart();

        WebView web = getBridge() != null ? getBridge().getWebView() : null;
        if (web == null) return;

        if (BuildConfig.DEBUG) {
            WebSettings settings = web.getSettings();
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        }

        loadFirstScreen(web);
        followNotification(web);
        schedulePushRegistration(web);
        injectShell(web);
    }

    /**
     * 마지막으로 보던 곳으로 되돌린다.
     *
     * 왜 필요한가
     *   앱이 여는 주소(server.url)는 조직이 붙지 않은 루트다. 그런데 로그인
     *   세션은 조직 주소(uengine.process-gpt.io)에 저장된다 — 브라우저는 주소가
     *   다르면 다른 곳으로 보기 때문이다. 그래서 그냥 두면 **켤 때마다 홍보
     *   페이지로 돌아가** 매번 다시 들어가야 한다.
     *
     *   조직 이름은 사람마다 달라서 빌드할 때 정할 수 없다. 대신 마지막으로
     *   머물던 곳을 기억해 두었다가 거기서 다시 시작한다.
     */
    private void loadFirstScreen(final WebView web) {
        if (!firstStart) return;
        firstStart = false;

        // 포털이 index.html 을 한 시간 캐시하라고 내려보낸다(max-age=3600).
        // 그러면 포털을 새로 배포해도 **앱은 최대 한 시간 동안 옛 화면**을 본다 —
        // 실제로 배포된 번들과 앱이 불러온 번들이 서로 달랐다. 이 앱의 값어치는
        // "지금의 포털을 그대로 보여 주는 것" 이므로, 켤 때 한 번 비운다.
        //
        // 서버가 index.html 에만 no-cache 를 붙이면 이 줄은 지워도 된다.
        // 그편이 낫다 — 지금은 해시가 붙은 자바스크립트까지 다시 받는다.
        web.clearCache(true);

        // 비우기만 하면 늦다. Capacitor 는 이미 캐시에 있던 화면을 띄운 뒤이므로,
        // 비운 다음 한 번 다시 불러와야 새 화면이 온다.
        web.postDelayed(new Runnable() {
            @Override
            public void run() {
                web.reload();
            }
        }, 1500);
    }

    /**
     * 이번 실행이 알림을 눌러 시작된 것인가.
     *
     * 인텐트를 비우지 않는다 — 실제로 꺼내 쓰는 것은 wrapper.js(PushSupportPlugin)
     * 이고, 여기서 지워 버리면 그쪽이 갈 곳을 잃는다.
     */
    private boolean launchedFromNotification() {
        try {
            Bundle extras = getIntent() != null ? getIntent().getExtras() : null;
            if (extras == null) return false;
            return extras.containsKey("google.message_id")
                    || extras.containsKey("google.sent_time")
                    || extras.containsKey("url");
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public void onPause() {
        super.onPause();

        WebView web = getBridge() != null ? getBridge().getWebView() : null;
        if (web == null) return;

        String url = web.getUrl();
        if (!isOurs(url)) return;

        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_LAST_URL, url).apply();
    }

    /**
     * 알림을 담당하는 조각을 페이지에 얹는다.
     *
     * 포털이 뜨는 데 시간이 걸리므로 조금 기다렸다 넣는다. wrapper.js 는 두 번
     * 들어와도 한 번만 돌도록 스스로를 막는다.
     */
    /**
     * 알림을 눌러 들어온 건으로 화면을 옮긴다.
     *
     * 알림에는 서버가 넣어 준 주소가 그대로 들어 있다
     * (예: https://uengine.process-gpt.io/todolist/<건>). 그래서 앱에서 경로를
     * 다시 해석할 필요가 없다 — 그 주소를 열기만 하면 된다.
     */
    private void followNotification(final WebView web) {
        if (pendingUrl == null) return;

        final String target = pendingUrl;
        // 한 번만 따라간다. 남겨 두면 앱을 다시 앞으로 부를 때마다 같은 곳으로 튄다.
        pendingUrl = null;

        // 곧바로 부르면 안 된다. 앱은 이제 막 시작 주소를 여는 중이라, 그 뒤에
        // 도착하는 기본 이동이 우리 주소를 덮어쓴다.
        web.postDelayed(new Runnable() {
            @Override
            public void run() {
                android.util.Log.i("wrapper", "알림으로 이동: " + target);
                web.loadUrl(target);
            }
        }, 6000);
    }

    /**
     * 모바일 껍데기를 페이지에 씌운다.
     *
     * 포털은 화면을 갈아 끼우는 방식(SPA)이라 페이지가 다시 뜨지 않는다. 그래서
     * 주입한 것이 살아 있는지 짧게 확인하며 필요할 때 다시 넣는다. 두 파일 모두
     * 이미 들어와 있으면 아무 일도 하지 않는다.
     */
    /** 껍데기를 계속 얹혀 두는 반복 작업. onStop 에서 멈춘다. */
    private Runnable shellPump;

    private void injectShell(final WebView web) {
        final String css = readAsset("public/appshell.css");
        final String js = readAsset("public/appshell.js");
        if (css == null || js == null) return;

        final String script =
            "(function(){"
            + "if(!document.getElementById('pg-shell-css')){"
            + "var s=document.createElement('style');s.id='pg-shell-css';"
            + "s.textContent=" + quote(css) + ";document.head.appendChild(s);}"
            + "if(!window.__pgShell){window.__pgShell=1;" + js + "}"
            + "})()";

        // 앞서는 onStart 직후 60초 동안만 얻었다. 그러니 로그인을 마치기 전에
        // 창이 닫혔다 — 비밀번호를 치고 조직 주소로 넘어가면 그때는 이미 끝난 뒤였고,
        // 그 새 문서에는 껍데기가 얹히지 않아 탭도 경로 제한도 없는 채로 남았다.
        //
        // 시간을 늘리는 것으로는 모자란다 — 얼마를 주든 그보다 늦게 들어오는 사람이 있다.
        // 스스로 다시 예약하게 해서 화면이 살아 있는 동안은 계속 지켜보게 한다.
        // 이미 얹혀 있으면 __pgShell 에서 바로 빠져나오므로 비용은 거의 없고,
        // 페이지가 통째로 바뀌면(조직 주소로의 이동 등) 그 문서에 자연스럽게 다시 얹힌다.
        if (shellPump != null) web.removeCallbacks(shellPump);

        shellPump = new Runnable() {
            @Override
            public void run() {
                web.evaluateJavascript(
                    "try{" + script + ";''}catch(e){'SHELL_ERR '+(e&&e.message)}",
                    new android.webkit.ValueCallback<String>() {
                        @Override
                        public void onReceiveValue(String v) {
                            if (v != null && v.indexOf("SHELL_ERR") >= 0) {
                                android.util.Log.w("shell", v);
                            }
                        }
                    });
                web.postDelayed(this, 2000L);
            }
        };
        web.post(shellPump);
    }

    /**
     * 화면이 내려가면 껍데기 감시도 멈춘다. 안 멈추면 보이지도 않는 화면을
     * 2초마다 지켜보며 배터리를 쓴다. onStart 에서 다시 시작한다.
     */
    @Override
    public void onStop() {
        WebView web = getBridge() != null ? getBridge().getWebView() : null;
        if (web != null && shellPump != null) web.removeCallbacks(shellPump);
        super.onStop();
    }

    /**
     * 자바스크립트 문자열 리터럴로 안전하게 감싼다.
     *
     * 손으로 이스케이프하지 않는다 — 따옴표 하나만 어긋나도 주입한 코드가
     * 통째로 조용히 죽는다. JSON 문자열 규칙이 자바스크립트와 같으므로
     * 이미 검증된 것을 쓴다.
     */
    private static String quote(String raw) {
        return org.json.JSONObject.quote(raw);
    }

    private String readAsset(String path) {
        StringBuilder out = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(getAssets().open(path), "UTF-8"))) {
            String line;
            while ((line = r.readLine()) != null) out.append(line).append('\n');
            return out.toString();
        } catch (Exception e) {
            android.util.Log.w("wrapper", "껍데기 파일을 읽지 못했습니다: " + path, e);
            return null;
        }
    }

    /**
     * 이 기기를 알림 받을 곳으로 등록한다.
     *
     * 포털이 로그인과 조직 이동을 마쳐야 누구인지 읽을 수 있으므로, 한 번 보고
     * 마는 대신 잠시 간격을 두고 몇 번 시도한다. 이미 등록돼 있으면 같은 줄을
     * 고칠 뿐이라 여러 번 해도 문제가 없다.
     */
    private void schedulePushRegistration(final WebView web) {
        for (int i = 1; i <= 6; i++) {
            web.postDelayed(new Runnable() {
                @Override
                public void run() {
                    PushRegistrar.register(MainActivity.this, web);
                }
            }, i * 5000L);
        }
    }

    /**
     * 뒤로 가기를 웹 화면의 뒤로 가기로 쓴다.
     *
     * Capacitor 의 기본 처리는 화면 쪽 자바스크립트가 backButton 을 듣고 있을 때만
     * 동작하는데, 포털은 앱 안에서 돈다는 것을 모르므로 아무도 듣지 않는다.
     * 그러면 어느 화면에서 눌러도 앱이 곧바로 닫힌다 — 업무 하나 열어 보고
     * 뒤로 가려던 사람이 앱 밖으로 나가 버린다.
     */
    @Override
    public void onBackPressed() {
        WebView web = getBridge() != null ? getBridge().getWebView() : null;
        if (web != null && web.canGoBack()) {
            web.goBack();
            return;
        }
        super.onBackPressed();
    }
}
