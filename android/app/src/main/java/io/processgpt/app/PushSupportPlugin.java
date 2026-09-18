package io.processgpt.app;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/**
 * 이 빌드에서 푸시 알림을 쓸 수 있는가.
 *
 * 왜 필요한가
 *   PushNotifications.register() 는 Firebase 설정(google-services.json)이 없으면
 *   IllegalStateException 을 던진다. 그것도 자바 쪽 스레드에서 던지기 때문에
 *   자바스크립트의 try/catch 로는 잡히지 않고 **앱이 그대로 죽는다.**
 *   실제로 "알림 켜기" 를 누르자 프로세스가 종료됐다.
 *
 *   그래서 부르기 전에 물어본다. 설정이 없으면 부르지 않고, 왜 못 켜는지
 *   사용자에게 말한다.
 *
 * 왜 FirebaseApp 을 직접 보지 않는가
 *   그 클래스는 푸시 플러그인 모듈의 의존성이라 앱 모듈에서 바로 보이지 않는다.
 *   보이게 하려면 Firebase 라이브러리를 여기에 또 넣어야 한다. 대신 같은 것을
 *   가리키는 값을 본다 — google-services 플러그인은 설정 파일을 읽어
 *   `google_app_id` 문자열 자원을 만들어 넣고, Firebase 는 그 값으로 스스로
 *   초기화한다. 자원이 없으면 초기화도 없다.
 */
@CapacitorPlugin(name = "PushSupport")
public class PushSupportPlugin extends Plugin {

    /**
     * 알림을 눌러 앱이 **처음 켜진** 경우, 그 알림의 내용을 돌려준다.
     *
     * 왜 필요한가
     *   앱이 꺼져 있을 때 알림을 누르면 안드로이드가 앱을 켜면서 그 내용을
     *   실행 인텐트에 담아 준다. 그런데 Capacitor 의 푸시 플러그인은 그것을
     *   `pushNotificationActionPerformed` 이벤트로 한 번 쏘고 끝낸다 —
     *   화면(자바스크립트)이 아직 뜨지 않아 그 이벤트를 들을 사람이 없다.
     *   그래서 알림을 눌러 들어와도 늘 첫 화면만 열렸다.
     *
     *   이 함수는 그 내용을 인텐트에서 직접 꺼내 준다. 한 번 꺼내면 지운다 —
     *   남겨 두면 앱을 다시 앞으로 불러올 때마다 같은 곳으로 튄다.
     */
    @PluginMethod
    public void consumeLaunchNotification(PluginCall call) {
        JSObject data = new JSObject();
        boolean found = false;

        try {
            Activity activity = getActivity();
            Intent intent = activity != null ? activity.getIntent() : null;
            Bundle extras = intent != null ? intent.getExtras() : null;

            if (extras != null) {
                for (String key : extras.keySet()) {
                    Object value = extras.get(key);
                    if (value != null) {
                        data.put(key, String.valueOf(value));
                    }
                }

                // FCM 이 실어 보낸 것인지 확인한다. 그 밖의 실행(런처 아이콘 등)에는
                // 이 값들이 없으므로, 엉뚱한 이동을 하지 않는다.
                found = extras.containsKey("google.message_id")
                        || extras.containsKey("google.sent_time")
                        || extras.containsKey("url");

                if (found) {
                    // 한 번만 쓴다.
                    intent.replaceExtras((Bundle) null);
                }
            }
        } catch (Throwable t) {
            found = false;
        }

        JSObject result = new JSObject();
        result.put("found", found);
        result.put("data", found ? data : new JSObject());
        call.resolve(result);
    }

    @PluginMethod
    public void isAvailable(PluginCall call) {
        boolean ready;
        try {
            String packageName = getContext().getPackageName();
            int id = getContext().getResources().getIdentifier("google_app_id", "string", packageName);
            ready = id != 0 && !getContext().getString(id).trim().isEmpty();
        } catch (Throwable t) {
            ready = false;
        }

        JSObject result = new JSObject();
        result.put("available", ready);
        call.resolve(result);
    }
}
