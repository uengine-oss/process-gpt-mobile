# process-gpt-mobile

Process-GPT 웹 포털을 안드로이드 · iOS 앱으로 감싸는 껍데기.

화면은 여기 없다. 앱은 웹뷰로 **운영 포털을 그대로 연다.**

```
https://process-gpt.io          ← 앱이 실제로 여는 것
        ↑ server.url (capacitor.config.json)
process-gpt-mobile/             ← 이 저장소: 껍데기 + 네이티브
        ↑ 주입
process-gpt-vue3/mobile-live/shell/   ← 앱에서만 얹는 탭·경로 제한 (appshell.js/css)
```

## 왜 이렇게 만들었나

전용 모바일 프론트를 따로 두면 포털을 고칠 때마다 같은 것을 두 번 만들게 된다.
그래서 화면은 포털 것을 그대로 쓰고, 앱은 **앱에서만 할 수 있는 것**만 맡는다.

1. 하단 탭과 경로 제한 (작은 화면에서 못 쓰는 BPMN 편집기 · 관리자 콘솔로 빠지지 않게)
2. 푸시 알림
3. 뒤로 가기, 마지막으로 보던 곳 기억
4. 연결이 끊겼을 때의 안내 화면 (`shell/index.html`)

## 무엇이 자동으로 따라오고, 무엇이 아닌가

| 바꾼 곳 | 이미 깔린 앱에 반영되려면 |
|---|---|
| `process-gpt-vue3/src/` (포털 화면·기능) | 포털 배포만 하면 된다 |
| `process-gpt-vue3/mobile-live/shell/` (appshell.js/css) | **APK 를 새로 배포해야 한다** |
| 이 저장소의 네이티브 코드 | **APK 를 새로 배포해야 한다** |

appshell 은 `process-gpt-vue3` 안에 있지만 포털 번들이 아니라 **APK 에 실리는
에셋**이다. 이것만 고치고 포털만 배포하면 아무 일도 일어나지 않는다.

## 만들기

```bash
npm install
npm run build          # cap sync — shell 을 android/ios 에셋으로 복사
npm run open:android   # Android Studio 에서 열기
```

Java 는 21 이 필요하다. 시스템 Java 가 다르면 Android Studio 가 함께 설치한 것을 쓴다.

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
```

### APK

```bash
cd android
./gradlew assembleDebug     # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease   # app/build/outputs/apk/release/app-release.apk
```

웹 빌드 단계가 없다. 포털은 런타임에 받아 오고, `cap sync` 는 shell 파일만 옮긴다.

### 서명

`android/keystore.properties` 와 그것이 가리키는 `.jks` 가 있어야 설치 가능한
APK 가 나온다. **둘 다 저장소에 없다** — 서명 키가 새면 누구나 이 앱의 다음
판으로 인정되는 APK 를 만들 수 있기 때문이다.

```properties
# android/keystore.properties
storeFile=processgpt-release.jks
storePassword=...
keyAlias=...
keyPassword=...
```

없으면 빌드는 성공하지만 서명되지 않은 APK 가 나오고 기기가 설치를 거부한다.
**두 파일을 잃어버리면 같은 앱의 다음 판을 낼 수 없다.** 이미 설치한 사람들은
업데이트를 받지 못하고 앱을 지웠다 다시 깔아야 한다. 저장소가 아닌 안전한 곳에
따로 보관할 것.

## 로컬 서버에 붙여 보기

`capacitor.config.local.json` 이 그 용도다. 에뮬레이터 안에서 `127.0.0.1` 은
에뮬레이터 자신이고, 이 PC 는 `10.0.2.2` 로 보인다.

```bash
cp capacitor.config.local.json capacitor.config.json
npx cap sync android
cd android && ./gradlew assembleDebug
```

포털도 에뮬레이터가 닿는 주소로 띄워야 한다.

```bash
cd ../process-gpt-vue3
VITE_SUPABASE_URL=http://10.0.2.2:54321 \
VITE_PORTAL_BASE_URL=http://localhost.10.0.2.2.nip.io:8088 \
npx vite --host 0.0.0.0 --port 8088
```

**IP 가 아니라 nip.io 이름을 쓰는 이유** — 포털은 요청 Host 의 앞부분으로 소속
조직을 가른다. `10.0.2.2` 로 부르면 그것이 `10` 이 되어 토큰의 조직과 어긋난다.
`nip.io` 는 이름 안의 IP 를 그대로 해석하는 공개 DNS 라, 위 이름은 `10.0.2.2` 를
가리키면서 앞부분이 `localhost` 가 된다.

평문(http) 통신은 `android/app/src/main/res/xml/network_security_config.xml` 에
적힌 개발용 주소에만 열려 있다. 안드로이드 9 부터 http 가 기본으로 막히는데,
전체를 열면 운영 통신도 평문으로 나갈 수 있어 주소를 나열했다.

끝나면 `capacitor.config.json` 을 운영용으로 되돌리고 다시 `cap sync` 할 것.

## 푸시 알림

서버(`process-gpt-completion/fcm_service`)가 `user_devices` 표에서 기기 토큰을
읽어 보낸다. 앱은 켤 때 `PushRegistrar` 가 FCM 토큰을 받아 그 표에 자기 줄을 쓴다.

- 안드로이드는 `android/app/google-services.json` 이 있어야 한다 (들어 있다).
  iOS 는 `GoogleService-Info.plist` 가 필요하고 **아직 없다.**
- `POST_NOTIFICATIONS` 는 매니페스트에 적는 것만으로는 부족하다. 안드로이드 13
  부터는 사용자에게 직접 허락을 받아야 하고, 받지 않으면 기기가 메시지를 받고도
  안드로이드가 조용히 버린다 — 보낸 쪽은 성공으로 보이고 받는 사람은 아무것도
  못 보는, 가장 찾기 어려운 경우가 된다. `MainActivity` 가 켤 때 물어본다.
- 알림을 누르면 `data.url` 로 간다. 그 주소가 `process-gpt.io` 안일 때만 따라간다 —
  아니면 알림 하나로 앱을 아무 데나 보낼 수 있다.

## src/ 의 코드에 대해

`src/index.js` · `src/native.js` · `src/progress-stream.js` 는 **지금 쓰이지 않는다.**
전용 프론트를 감싸던 이전 계획에서 만든 것이다. 파일 공유 · 저장 같은 기능을
나중에 붙일 때 참고할 수 있어 지워두지 않았다.

## 남은 일

- iOS: `GoogleService-Info.plist`, 인증서
- 실제 기기 확인: 알림 왕복, 화면 회전, 저사양 기기 반응 속도
