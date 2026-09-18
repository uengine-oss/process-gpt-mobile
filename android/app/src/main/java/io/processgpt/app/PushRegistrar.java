package io.processgpt.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import android.webkit.ValueCallback;
import android.webkit.WebView;

import com.google.firebase.messaging.FirebaseMessaging;

import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.UUID;

/**
 * 이 기기를 알림 받을 곳으로 등록한다 — **네이티브에서.**
 *
 * 왜 자바스크립트로 하지 않는가
 *   처음에는 페이지에 조각을 얹어 Capacitor 의 푸시 플러그인을 부르게 했다.
 *   루트 주소에서는 됐는데 조직 주소로 넘어가면 조용히 멈췄다. 확인해 보니
 *   원인이 분명했다.
 *
 *       {"names":[], "url":"https://uengine.process-gpt.io/..."}
 *
 *   Capacitor 는 설정에 적힌 주소(process-gpt.io)에만 네이티브 통로를 심는다.
 *   포털이 로그인 뒤 조직 주소로 옮겨 가는 순간 플러그인이 사라진다.
 *   allowNavigation 은 "그리로 가도 된다" 일 뿐, 통로를 함께 옮겨 주지 않는다.
 *
 *   조직 주소는 사람마다 달라 빌드할 때 적을 수 없다. 그래서 통로에 기대지
 *   않기로 했다. 토큰은 네이티브가 직접 받고, 저장도 네이티브가 직접 한다.
 *   페이지에서 가져오는 것은 **누구인지**뿐이고, 그것은 어느 주소에서든
 *   읽을 수 있다.
 */
public class PushRegistrar {

    private static final String TAG = "push";

    /**
     * 이번 실행에서 이미 등록했는가.
     *
     * 포털이 준비될 때까지 몇 번 시도하는데, 성공한 뒤에도 남은 시도가 계속
     * 돌면 같은 줄을 몇 번씩 다시 쓴다. 결과는 같지만 쓸데없는 왕복이다.
     */
    private static volatile boolean done = false;
    private static final String PREFS = "wrapper";
    private static final String KEY_DEVICE_ID = "deviceId";

    /**
     * 이 기기를 가리키는 이름.
     *
     * 웹의 localStorage 가 아니라 앱에 저장한다. localStorage 는 주소마다 따로라
     * 루트와 조직 주소에서 서로 다른 값이 나오고, 그러면 같은 기기가 두 줄이 된다.
     */
    private static String deviceId(Context ctx) {
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String saved = prefs.getString(KEY_DEVICE_ID, null);
        if (saved != null) return saved;

        String made = UUID.randomUUID().toString();
        prefs.edit().putString(KEY_DEVICE_ID, made).apply();
        return made;
    }

    /**
     * 로그인한 사람이 누구인지 페이지에서 읽어 온다.
     *
     * 포털이 만들어 둔 연결($supabase)에서 가져온다. 주소·열쇠·토큰을 앱에
     * 박아 두지 않는 편이 낫다 — 박아 두면 포털이 서버를 옮길 때 앱만 옛 곳을
     * 바라보게 된다.
     */
    public static void register(final Context ctx, final WebView web) {
        if (done) return;

        final String script =
            "(function(){try{"
            + "var sb=window.$supabase; if(!sb) return JSON.stringify({ok:false,why:'no-client'});"
            + "var url=sb.supabaseUrl||sb.rest&&sb.rest.url; var key=sb.supabaseKey;"
            + "var raw=null;"
            + "for(var i=0;i<localStorage.length;i++){var k=localStorage.key(i);"
            + "if(k&&k.indexOf('sb-')===0&&k.indexOf('-auth-token')>0){raw=localStorage.getItem(k);break;}}"
            + "if(!raw) return JSON.stringify({ok:false,why:'no-session'});"
            + "var s=JSON.parse(raw); var t=s.access_token||(s.currentSession&&s.currentSession.access_token);"
            + "var u=(s.user&&s.user.email)||(s.currentSession&&s.currentSession.user&&s.currentSession.user.email);"
            + "if(!t||!u) return JSON.stringify({ok:false,why:'no-token'});"
            + "return JSON.stringify({ok:true,url:url,key:key,token:t,email:u});"
            + "}catch(e){return JSON.stringify({ok:false,why:String(e&&e.message)});}})()";

        web.evaluateJavascript(script, new ValueCallback<String>() {
            @Override
            public void onReceiveValue(String value) {
                try {
                    // evaluateJavascript 는 JSON 문자열을 한 번 더 감싸서 준다.
                    String json = new JSONObject("{\"v\":" + value + "}").getString("v");
                    JSONObject who = new JSONObject(json);

                    if (!who.optBoolean("ok")) {
                        // 로그인 전이면 정상이다. 로그인하면 다시 부른다.
                        Log.i(TAG, "아직 등록할 수 없음: " + who.optString("why"));
                        return;
                    }
                    fetchTokenAndSave(ctx, who);
                } catch (Exception e) {
                    Log.w(TAG, "로그인 정보를 읽지 못했습니다", e);
                }
            }
        });
    }

    /** 기기 토큰을 받아 저장까지 잇는다. */
    private static void fetchTokenAndSave(final Context ctx, final JSONObject who) {
        FirebaseMessaging.getInstance().getToken()
            .addOnCompleteListener(task -> {
                if (!task.isSuccessful() || task.getResult() == null) {
                    Log.w(TAG, "기기 토큰을 받지 못했습니다", task.getException());
                    return;
                }
                final String token = task.getResult();

                // 네트워크는 주 스레드에서 할 수 없다.
                new Thread(() -> save(ctx, who, token)).start();
            });
    }

    /**
     * user_devices 에 이 기기를 적는다.
     *
     * 본인 행만 쓸 수 있게 돼 있어(RLS), 로그인한 사람의 토큰을 그대로 실어
     * 보낸다. 기기를 가리키는 열쇠는 (이메일, 기기) 둘이라 토큰이 갱신돼도
     * 같은 줄을 고친다 — 토큰으로 구분하면 갱신될 때마다 죽은 줄이 쌓인다.
     */
    private static void save(Context ctx, JSONObject who, String token) {
        HttpURLConnection conn = null;
        try {
            String base = who.getString("url").replaceAll("/+$", "");
            URL endpoint = new URL(base + "/rest/v1/user_devices?on_conflict=user_email,device_id");

            String now = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
                    .format(new java.util.Date());

            JSONObject row = new JSONObject();
            row.put("user_email", who.getString("email"));
            row.put("device_id", deviceId(ctx));
            row.put("device_type", "android");
            row.put("device_token", token);
            row.put("last_active_at", now);
            row.put("last_access_at", now);

            conn = (HttpURLConnection) endpoint.openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("apikey", who.getString("key"));
            conn.setRequestProperty("Authorization", "Bearer " + who.getString("token"));
            // 같은 기기가 다시 오면 새로 만들지 말고 고친다.
            conn.setRequestProperty("Prefer", "resolution=merge-duplicates,return=minimal");

            try (OutputStream out = conn.getOutputStream()) {
                out.write(("[" + row.toString() + "]").getBytes("UTF-8"));
            }

            int code = conn.getResponseCode();
            if (code >= 200 && code < 300) {
                done = true;
                Log.i(TAG, "기기 등록 완료");
            }
            else Log.w(TAG, "기기 등록 거절됨: HTTP " + code);
        } catch (Exception e) {
            Log.w(TAG, "기기 등록 중 오류", e);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}
