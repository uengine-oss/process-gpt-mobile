import UIKit
import WebKit
import UserNotifications
import Capacitor

/**
 웹 포털을 감싸는 껍데기 (iOS).

 화면과 기능은 전부 포털의 것이다. 여기서 하는 일은 **앱에서만 할 수 있는 것**이다.

   1. 껍데기 주입 (appshell.js/css — 하단 탭과 경로 제한)
   2. 알림 허락 받기

 안드로이드의 MainActivity 와 짝을 이룬다. 두 쪽이 같은 appshell 파일을 얹으므로
 화면은 저절로 같아진다 — 여기서 UI 를 따로 만들지 않는 이유다.
 */
@UIApplicationMain
class AppDelegate: UIResponder, UIApplicationDelegate {

    var window: UIWindow?

    /** 껍데기를 계속 얹혀 두는 반복 작업. 화면이 내려가면 멈춘다. */
    private var shellPump: Timer?

    func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?) -> Bool {
        askNotificationPermission()
        return true
    }

    /**
     알림을 보내도 되는지 물어본다.

     안드로이드 13 과 마찬가지로 iOS 도 허락 없이는 아무것도 띄우지 못한다.
     받지 않으면 기기는 메시지를 받고도 조용히 버린다 — 보낸 쪽은 성공으로
     보이고 받는 사람은 아무것도 못 보는, 가장 찾기 어려운 경우가 된다.
     */
    private func askNotificationPermission() {
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .badge, .sound]) { granted, _ in
            guard granted else { return }
            // APNs 등록은 메인 스레드에서만 부를 수 있다.
            DispatchQueue.main.async {
                UIApplication.shared.registerForRemoteNotifications()
            }
        }
    }

    // MARK: - 껍데기 주입

    private func webView() -> WKWebView? {
        guard let bridgeVC = window?.rootViewController as? CAPBridgeViewController else { return nil }
        return bridgeVC.webView
    }

    /**
     자바스크립트 문자열 리터럴로 안전하게 감싼다.

     손으로 이스케이프하지 않는다 — 따옴표 하나만 어긋나도 주입한 코드가
     통째로 조용히 죽는다. JSON 문자열 규칙이 자바스크립트와 같으므로
     이미 검증된 것을 쓴다.
     */
    private func jsQuote(_ raw: String) -> String {
        guard let data = try? JSONSerialization.data(withJSONObject: [raw]),
              let wrapped = String(data: data, encoding: .utf8),
              wrapped.count >= 2 else { return "\"\"" }
        // ["…"] 에서 바깥 괄호만 벗긴다.
        return String(wrapped.dropFirst().dropLast())
    }

    /** 번들에 실려 온 껍데기 두 장을 하나의 주입 스크립트로 엮는다. */
    private func shellScript() -> String? {
        guard let cssURL = Bundle.main.url(forResource: "appshell", withExtension: "css", subdirectory: "public"),
              let jsURL = Bundle.main.url(forResource: "appshell", withExtension: "js", subdirectory: "public"),
              let css = try? String(contentsOf: cssURL, encoding: .utf8),
              let js = try? String(contentsOf: jsURL, encoding: .utf8) else {
            // cap sync 가 webDir 을 찾지 못하면 이 파일들이 번들에 없다.
            // 그때는 탭도 경로 제한도 없는 맨 웹뷰가 된다 — 빌드에서 걸러야 한다.
            NSLog("[shell] appshell 파일을 번들에서 찾지 못했습니다")
            return nil
        }

        return """
        (function(){
          if(!document.getElementById('pg-shell-css')){
            var s=document.createElement('style');
            s.id='pg-shell-css';
            s.textContent=\(jsQuote(css));
            document.head.appendChild(s);
          }
          if(!window.__pgShell){ window.__pgShell=1; \(js) }
        })()
        """
    }

    /**
     껍데기를 페이지에 씌운다.

     포털은 화면을 갈아 끼우는 방식(SPA)이라 페이지가 다시 뜨지 않는다. 게다가
     로그인을 마치면 조직 주소로 **문서가 통째로 바뀐다.** 한 번만 넣고 끝내면
     그 새 문서에는 껍데기가 얹히지 않아 탭도 경로 제한도 없는 채로 남는다.
     그래서 화면이 살아 있는 동안은 짧게 되풀이하며 지켜본다. 이미 얹혀 있으면
     __pgShell 에서 바로 빠져나오므로 비용은 거의 없다.
     */
    private func startShellPump() {
        guard shellPump == nil, let script = shellScript() else { return }

        let wrapped = "try{\(script);''}catch(e){'SHELL_ERR '+(e&&e.message)}"
        shellPump = Timer.scheduledTimer(withTimeInterval: 2.0, repeats: true) { [weak self] _ in
            self?.webView()?.evaluateJavaScript(wrapped) { value, _ in
                if let text = value as? String, text.contains("SHELL_ERR") {
                    NSLog("[shell] %@", text)
                }
            }
        }
        shellPump?.fire()
    }

    /**
     화면이 내려가면 감시도 멈춘다. 안 멈추면 보이지도 않는 화면을 2초마다
     들여다보며 배터리를 쓴다. 다시 앞으로 오면 그때 시작한다.
     */
    private func stopShellPump() {
        shellPump?.invalidate()
        shellPump = nil
    }

    // MARK: - 생명주기

    func applicationWillResignActive(_ application: UIApplication) {
        stopShellPump()
    }

    func applicationDidEnterBackground(_ application: UIApplication) {
        stopShellPump()
    }

    func applicationWillEnterForeground(_ application: UIApplication) {
    }

    func applicationDidBecomeActive(_ application: UIApplication) {
        startShellPump()
    }

    func applicationWillTerminate(_ application: UIApplication) {
        stopShellPump()
    }

    func application(_ app: UIApplication, open url: URL, options: [UIApplication.OpenURLOptionsKey: Any] = [:]) -> Bool {
        // Called when the app was launched with a url. Feel free to add additional processing here,
        // but if you want the App API to support tracking app url opens, make sure to keep this call
        return ApplicationDelegateProxy.shared.application(app, open: url, options: options)
    }

    func application(_ application: UIApplication, continue userActivity: NSUserActivity, restorationHandler: @escaping ([UIUserActivityRestoring]?) -> Void) -> Bool {
        // Called when the app was launched with an activity, including Universal Links.
        // Feel free to add additional processing here, but if you want the App API to support
        // tracking app url opens, make sure to keep this call
        return ApplicationDelegateProxy.shared.application(application, continue: userActivity, restorationHandler: restorationHandler)
    }

    // MARK: - APNs

    func application(_ application: UIApplication, didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data) {
        NotificationCenter.default.post(name: .capacitorDidRegisterForRemoteNotifications, object: deviceToken)
    }

    func application(_ application: UIApplication, didFailToRegisterForRemoteNotificationsWithError error: Error) {
        NotificationCenter.default.post(name: .capacitorDidFailToRegisterForRemoteNotifications, object: error)
    }
}
