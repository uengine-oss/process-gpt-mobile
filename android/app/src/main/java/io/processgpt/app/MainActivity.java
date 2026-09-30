package io.processgpt.app;

import android.Manifest;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
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
 *   4. 켤 때 로딩 화면 (없으면 포털이 뜰 때까지 빈 화면만 보인다)
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

    /** 첫 화면으로 열 곳과, 아직 열기 전인지. onNewIntent 가 알림 건으로 바꿔 끼울 수 있다. */
    private String firstScreenUrl;
    private boolean firstScreenPending;

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
        showLoading();
        createNotificationChannel();
        askNotificationPermission();
    }

    /* ─────────────────────────── 로딩 화면 ─────────────────────────── */

    /**
     * 켤 때 포털이 뜰 때까지 덮어 두는 화면 (마스코트 + 진행 표시).
     *
     * 없으면 시작 화면이 사라진 뒤 몇 초 동안 **빈 흰 화면**만 보인다 — 포털을 받아
     * 그리는 데 시간이 걸리고, 켤 때 한 번 캐시를 비우고 다시 불러오므로(loadFirstScreen)
     * 그 사이에 한 번 더 비었다가 다시 그려진다. 사람에게는 앱이 멈춘 것처럼 보인다.
     *
     * 시작 화면(styles.xml)과 같은 흰 바탕 · 같은 마스코트라서 끊김 없이 이어진다.
     */
    private View loadingView;
    private android.animation.ObjectAnimator loadingBob;
    private long startedAt;

    /**
     * 로딩 화면을 걷어도 되는 가장 이른 시각(켠 뒤 ms).
     * 옛 문서는 timeOrigin 으로 걸러지므로 짧게 둔다 — 첫 화면 이동(loadFirstScreen)이 걸리기 전의
     * 빈 문서를 "다 떴다" 로 보지 않을 만큼만.
     */
    private static final long REVEAL_AFTER_MS = 800;

    /**
     * 마지막으로 페이지 이동을 지시한 시각(epoch ms).
     *
     * 문서의 performance.timeOrigin 이 이보다 앞서면 아직 이동하기 전의 옛 문서다.
     * 처음에는 옛 문서에 표식을 심었는데, evaluateJavascript 도 이동도 비동기라 표식이
     * **새 문서에** 심기는 경우가 있었고, 그러면 끝까지 옛 문서로 보여 20초를 다 채웠다.
     */
    private volatile long navIssuedAt;

    private void markNavigation() {
        navIssuedAt = System.currentTimeMillis();
    }

    /** 아무리 늦어도 이만큼 뒤에는 걷는다 — 판단이 틀려도 앱을 가둬 두지 않는다. */
    private static final long LOADING_MAX_MS = 20000;

    private void showLoading() {
        startedAt = android.os.SystemClock.uptimeMillis();

        float dp = getResources().getDisplayMetrics().density;

        android.widget.LinearLayout column = new android.widget.LinearLayout(this);
        column.setOrientation(android.widget.LinearLayout.VERTICAL);
        column.setGravity(android.view.Gravity.CENTER);
        column.setBackgroundColor(android.graphics.Color.WHITE);
        // 덮여 있는 동안 아래 웹 화면이 눌리지 않게 한다.
        column.setClickable(true);

        android.widget.ImageView robot = new android.widget.ImageView(this);
        robot.setImageResource(R.drawable.pg_mascot);
        robot.setAdjustViewBounds(true);
        robot.setContentDescription("Process-GPT");
        column.addView(robot, new android.widget.LinearLayout.LayoutParams((int) (132 * dp), (int) (120 * dp)));

        android.widget.ProgressBar bar = new android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setIndeterminate(true);
        bar.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(0xFF1E88E5));
        android.widget.LinearLayout.LayoutParams barLp = new android.widget.LinearLayout.LayoutParams((int) (120 * dp), (int) (4 * dp));
        barLp.topMargin = (int) (24 * dp);
        column.addView(bar, barLp);

        android.widget.TextView label = new android.widget.TextView(this);
        label.setText("불러오는 중…");
        label.setTextColor(0xFF6B7280);
        label.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14);
        android.widget.LinearLayout.LayoutParams labelLp = new android.widget.LinearLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        labelLp.topMargin = (int) (12 * dp);
        column.addView(label, labelLp);

        addContentView(column, new android.view.ViewGroup.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT));
        loadingView = column;

        // 살짝 떠오르내리게 해서 멈춘 화면이 아니라는 것을 보인다.
        loadingBob = android.animation.ObjectAnimator.ofFloat(robot, "translationY", 0f, -10 * dp);
        loadingBob.setDuration(900);
        loadingBob.setRepeatMode(android.animation.ValueAnimator.REVERSE);
        loadingBob.setRepeatCount(android.animation.ValueAnimator.INFINITE);
        loadingBob.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
        loadingBob.start();

        column.postDelayed(this::checkLoaded, 600);
        // 확인이 어떻게 되든 이만큼 뒤에는 걷는다. 확인 반복과 따로 건다 — 반복이 끊겨도 산다.
        column.postDelayed(this::hideLoading, LOADING_MAX_MS);
    }

    /**
     * 포털이 화면을 그렸는지 본다. 아직이면 잠시 뒤 다시 본다.
     *
     * "그렸다" 의 기준: 이동 전 옛 문서가 아니고, 문서를 다 받았고, 포털의 #app 안에
     * 무언가가 채워졌다. #app 이 없는 문서(연결 실패 화면 등)는 받는 대로 보여 준다.
     */
    private void checkLoaded() {
        if (loadingView == null) return;

        // 다음 확인은 답을 기다리지 않고 먼저 예약한다. 페이지가 옮겨 가는 도중에 물으면
        // evaluateJavascript 가 답을 주지 않고 버리는데, 답에서 예약하면 거기서 반복이 끊겨
        // 로딩 화면이 걷히지 않았다.
        loadingView.postDelayed(this::checkLoaded, 300);

        long elapsed = android.os.SystemClock.uptimeMillis() - startedAt;
        WebView web = getBridge() != null ? getBridge().getWebView() : null;
        if (web == null || elapsed < REVEAL_AFTER_MS) return;

        web.evaluateJavascript(
            "(function(){if(performance.timeOrigin<" + navIssuedAt + ")return 'old';"
            + "if(document.readyState!=='complete')return 'loading';"
            + "var a=document.getElementById('app');if(!a)return 'ready';"
            + "return a.querySelectorAll('*').length>30?'ready':'mounting';})()",
            new android.webkit.ValueCallback<String>() {
                @Override
                public void onReceiveValue(String v) {
                    if (loadingView == null) return;
                    if ("\"ready\"".equals(v)) hideLoading();
                }
            });
    }

    private void hideLoading() {
        final View view = loadingView;
        if (view == null) return;
        loadingView = null;
        android.util.Log.i("wrapper", "로딩 화면 걷음: " + (android.os.SystemClock.uptimeMillis() - startedAt) + "ms");

        view.animate().alpha(0f).setDuration(250).withEndAction(new Runnable() {
            @Override
            public void run() {
                if (loadingBob != null) loadingBob.cancel();
                android.view.ViewParent parent = view.getParent();
                if (parent instanceof android.view.ViewGroup) ((android.view.ViewGroup) parent).removeView(view);
            }
        }).start();
    }

    /**
     * 알림 채널을 만든다 — 중요도 '높음' 이라야 화면 위에 배너로 뜬다.
     *
     * 앱이 꺼져 있을 때 오는 알림은 FCM SDK 가 이 채널(메니페스트의 기본 채널)로 띄운다.
     * 채널이 없으면 중요도 '보통' 인 대체 채널로 가서 알림창에만 조용히 쌓였다 — 사람은
     * 휴대폰을 내려다보기 전까지 업무가 온 줄 모른다. 이미 있으면 아무 일도 하지 않는다.
     * (채널 중요도는 만든 뒤에는 앱이 바꿀 수 없다. 바꾸려면 채널 id 를 새로 정해야 한다.)
     */
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        android.app.NotificationChannel channel = new android.app.NotificationChannel(
            getString(R.string.notification_channel_id),
            getString(R.string.notification_channel_name),
            android.app.NotificationManager.IMPORTANCE_HIGH);
        channel.enableVibration(true);
        android.app.NotificationManager manager = getSystemService(android.app.NotificationManager.class);
        if (manager != null) manager.createNotificationChannel(channel);
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

        // 앱이 꺼져 있어도 최근 목록에 남아 있으면, 안드로이드는 옛 실행 인텐트로 화면을
        // 다시 만들고 알림은 여기로 따로 준다. 그때 첫 화면(loadFirstScreen)을 아직 열기
        // 전이면 목적지만 바꾼다 — 안 그러면 마지막 화면을 열었다가 알림 건으로 한 번 더
        // 옮겨 가 로딩이 두 배로 걸렸다.
        if (firstStart) {
            // onStart 전이다 — loadFirstScreen 이 pendingUrl 을 첫 화면으로 쓴다.
            pendingUrl = url;
            return;
        }
        if (firstScreenPending) {
            firstScreenUrl = url;
            return;
        }

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

        // 어디서 시작할지. 알림을 눌러 켰으면 그 건, 아니면 마지막으로 머물던 **조직 주소의 루트**.
        //
        // 조직 주소로 바로 가는 까닭: 앞서는 lastUrl 을 적어 두기만 하고 읽지 않아서, 켤 때마다
        // 루트(process-gpt.io)에서 포털이 한 번 뜨고 → 조직 주소로 옮겨 → 또 한 번 떴다.
        //
        // 경로는 버리고 루트만 여는 까닭: 첫 화면은 포털이 정한다(모바일은 언제나 정의 체계도 —
        // process-gpt-vue3 의 src/utils/homePath.ts). 앱이 보던 화면을 되살리면 웹에서 휴대폰으로
        // 열었을 때와 첫 화면이 달라진다.
        //
        // 알림 건은 여기서 바로 연다 — followNotification 의 6초 지연을 거치면 첫 화면이
        // 떴다가 알림 건으로 한 번 더 바뀐다.
        firstScreenUrl = pendingUrl != null
                ? pendingUrl
                : orgRoot(getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_LAST_URL, null));
        firstScreenPending = true;
        pendingUrl = null;

        web.post(new Runnable() {
            @Override
            public void run() {
                String first = firstScreenUrl;
                firstScreenUrl = null;
                firstScreenPending = false;
                if (!isOurs(first)) first = isOurs(web.getUrl()) ? web.getUrl() : "https://process-gpt.io/";

                // 포털이 index.html 을 한 시간 캐시하라고 내려보낸다(max-age=3600). 그대로 두면
                // 포털을 새로 배포해도 **앱은 최대 한 시간 동안 옛 화면**을 본다 — 이 앱의 값어치는
                // "지금의 포털을 그대로 보여 주는 것" 이므로 켤 때 문서는 새로 받는다.
                //
                // 앞서는 clearCache(true) 로 캐시를 통째로 비웠다. 그러면 해시가 붙은 큰
                // 자바스크립트까지 매번 새로 받아 첫 화면까지 15초 넘게 걸렸다. 해시 파일은 내용이
                // 바뀌면 이름이 바뀌므로 캐시에 두어도 안전하다 — 문서 요청에만 no-cache 를
                // 붙인다(이 헤더는 이 요청에만 실리고 딸린 파일 요청에는 실리지 않는다).
                java.util.Map<String, String> fresh = new java.util.HashMap<>();
                fresh.put("Cache-Control", "no-cache");
                fresh.put("Pragma", "no-cache");

                // 이동 시각을 적는다 — 로딩 화면이 옛 문서를 보고 너무 일찍 걷히지 않게.
                markNavigation();
                android.util.Log.i("wrapper", "첫 화면: " + first);
                web.loadUrl(first, fresh);
            }
        });
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
        if (web != null) rememberUrl(web.getUrl());
    }

    /** 주소에서 경로를 떼고 조직 주소의 루트만 남긴다. 우리 주소가 아니면 null. */
    private static String orgRoot(String url) {
        if (!isOurs(url)) return null;
        try {
            URI uri = new URI(url);
            return uri.getScheme() + "://" + uri.getHost() + "/";
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 지금 보는 곳을 적어 둔다 — 다음에 켤 때 이 조직 주소로 시작하고(loadFirstScreen, 경로는
     * 버린다), 연결 실패 화면의 "다시 시도" 는 이 주소 그대로 돌아온다.
     *
     * 조직이 붙지 않은 루트(process-gpt.io)는 적지 않는다. 앱은 어차피 거기서 시작하고,
     * 로딩 도중 앱이 내려가면 루트가 적혀서 애써 기억한 조직 주소를 잃는다.
     */
    private void rememberUrl(String url) {
        if (!isOurs(url)) return;
        try {
            if ("process-gpt.io".equals(new URI(url).getHost())) return;
        } catch (Exception e) {
            return;
        }
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

        // 켠 직후에는 곧바로 부르면 안 된다(켠 지 6초가 지났으면 바로 간다 — 이미 떠 있는
        // 앱에서 알림을 눌렀는데 6초씩 기다릴 까닭이 없다). 앱은 이제 막 시작 주소를 여는 중이라, 그 뒤에
        // 도착하는 기본 이동이 우리 주소를 덮어쓴다.
        web.postDelayed(new Runnable() {
            @Override
            public void run() {
                android.util.Log.i("wrapper", "알림으로 이동: " + target);
                markNavigation();
                web.loadUrl(target);
            }
        }, Math.max(300L, 6000L - (android.os.SystemClock.uptimeMillis() - startedAt)));
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
                // 마지막으로 보던 곳도 함께 알려 준다. 연결 실패 화면(shell/index.html)의 "다시 시도"
                // 가 이리로 돌아간다 — 그 화면은 앱 안의 파일(https://localhost)이라 Capacitor 통로가
                // 붙지 않아 네이티브에 물어볼 수 없다.
                String last = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_LAST_URL, "");
                web.evaluateJavascript(
                    "window.__pgLastUrl=" + quote(last) + ";"
                    + "try{" + script + ";''}catch(e){'SHELL_ERR '+(e&&e.message)}",
                    new android.webkit.ValueCallback<String>() {
                        @Override
                        public void onReceiveValue(String v) {
                            if (v != null && v.indexOf("SHELL_ERR") >= 0) {
                                android.util.Log.w("shell", v);
                            }
                        }
                    });
                // 앱이 갑자기 끝나도(작업 목록에서 밀어 끄기 등) 보던 곳이 남게 여기서도 적는다.
                rememberUrl(web.getUrl());
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
        if (web != null && pushPump != null) web.removeCallbacks(pushPump);
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

    /** 기기 등록을 다시 시도하는 반복 작업. 등록을 마치거나 onStop 에서 멈춘다. */
    private Runnable pushPump;

    /**
     * 이 기기를 알림 받을 곳으로 등록한다.
     *
     * 포털이 로그인과 조직 이동을 마쳐야 누구인지 읽을 수 있으므로, 등록될 때까지
     * 5초 간격으로 다시 시도한다. 이미 등록돼 있으면 같은 줄을 고칠 뿐이라 여러 번
     * 해도 문제가 없다.
     *
     * 앞서는 켠 뒤 30초(6번)만 시도했다. 처음 설치해 로그인 화면에서 30초 넘게 머물면
     * 시도가 모두 로그인 전에 끝나(no-session), **앱을 다시 켜기 전까지 알림이 오지 않았다.**
     */
    private void schedulePushRegistration(final WebView web) {
        if (pushPump != null) web.removeCallbacks(pushPump);
        pushPump = new Runnable() {
            @Override
            public void run() {
                if (PushRegistrar.isDone()) return;
                PushRegistrar.register(MainActivity.this, web);
                web.postDelayed(this, 5000L);
            }
        };
        web.postDelayed(pushPump, 5000L);
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
