# 증명사진 촬영 (Android)

증명사진·여권사진을 **직접 찍어서** 규격에 맞게 만들어 주는 안드로이드 앱.
촬영 중에는 얼굴 위치를 실시간으로 잡아 주고, 촬영 후에는 배경을 지우고
규격대로 잘라 JPEG 으로 저장한다. 모든 처리는 **휴대폰 안에서만** 이루어진다.

## 무엇을 해 주나

**촬영 화면**
- 규격 비율(35:45) 프레임과 머리 길이 눈금을 화면에 겹쳐 보여 준다
- 얼굴을 매 프레임 추적해 "조금 더 가까이", "턱을 당기고 정면을 보세요" 처럼 한 번에 하나씩 안내
- 머리 크기·위치·정면 여부·눈 뜸·밝기가 모두 맞으면 3초 카운트다운 후 자동 촬영 (끄고 수동 촬영도 가능)
- 앞/뒤 카메라 전환

**결과 화면**
- 인물만 분리해 흰색(또는 연회색·하늘색) 배경으로 합성
- 정수리~턱이 규격 길이가 되도록 자동 크롭, 눈 선 기울기는 10° 이내면 자동 수평 보정
- 검증 리포트: 수평 / 정면 응시 / 눈 뜸 / 밝기 / 초점 / 해상도 / 프레임 / 배경
- 갤러리(`Pictures/증명사진`) 저장, 다른 앱으로 보내기

## 지원 규격

| | 크기 | 출력 | 머리 길이 | 머리 위 여백 |
|---|---|---|---|---|
| 증명사진 | 35 × 45 mm | 413 × 531 px | 30~36 mm | 3~8 mm |
| 여권사진 | 35 × 45 mm | 413 × 531 px | 32~36 mm | 3~5 mm |

둘 다 JPEG 500KB 이하로 저장한다. 413 × 531 px 는 300dpi 로 35 × 45 mm 를 인쇄한 크기이고,
정부24·외교부 온라인 제출에서 요구하는 최소 픽셀 규격이기도 하다.

> 이 앱이 검증하는 것은 **구도와 촬영 상태**다. 표정, 복장(제복·모자), 컬러렌즈,
> 안경 반사 같은 항목은 사람이 직접 확인해야 한다.

## 동작 방식

```
촬영 → 원본 축소(긴 변 1800px)
     → ML Kit 얼굴 검출 (윤곽선·랜드마크·눈 뜸 확률)
     → ML Kit 셀피 세그멘테이션 (인물 마스크)
     → 가이디드 필터로 알파 경계 다듬기 → 경계에서 배경색 걷어내기
     → 정수리 = 마스크에서 머리 위 첫 행,  턱 = 얼굴 윤곽선 최하단
     → 눈 선 기울기만큼 수평 보정 후 규격 비율로 크롭
     → 배경색 위에 인물 합성 → 413×531 로 축소 → 500KB 이하 JPEG
```

### 머리카락 경계 처리

셀피 세그멘테이션 마스크는 256×256 모델 출력을 원본 크기로 늘린 것이라, 경계가 원본에서
5~10px 폭으로 뭉개져 있다. 이걸 그냥 쓰면 경계 픽셀에 섞인 원래 배경색이 실루엣 둘레에
띠처럼 남고, 반대로 마스크를 깎아내면(침식) 잔머리가 통째로 잘려 머리카락이 유령처럼 흐려진다.

그래서 두 단계를 쓴다.

1. **가이디드 필터** — 원본 밝기를 길잡이 삼아 알파가 실제 머리카락 경계를 따라가도록 다시 세운다.
   계수는 1/4 로 줄인 해상도에서 구해 올려 쓴다(fast guided filter).
   세기(`GUIDE_EPS`)를 너무 낮추면 밝은 머리카락 사이가 하얗게 뚫리므로 약하게 잡았다.
2. **배경색 제거** — 경계 픽셀은 `C = a·F + (1-a)·B` 의 혼합이다. 주변에서 배경색 `B` 를
   16px 블록 단위로 추정해 `F` 를 되돌린다. 알파가 0.05~0.90 인 경계 구간에만 적용한다
   (안쪽까지 건드리면 사진 전체가 어두워진다).

머리 모양이 다른 인물 사진 4장으로 방식을 비교한 기록이 `MattingLabTest` 에 있다.
침식 방식보다 머리카락을 더 남기면서 색 번짐이 늘지 않는지 매번 확인한다.

**알려진 한계:** 세그멘테이션 마스크가 머리 위 같은 곳에 배경을 네모나게 물고 오는 경우가
있다. 모델 자체의 오류라 후처리로 깨끗이 지우기 어렵다(배경색 판정으로 지워봤지만 경계만
너덜너덜해져 되돌렸다). 단색 벽 앞에서 찍으면 훨씬 덜하다.

핵심은 **실시간 가이드와 최종 크롭의 역할을 나눈 것**이다.
실시간 단계는 얼굴 박스로 대략만 보고 허용 범위를 넉넉히 주되,
크롭으로 고칠 수 없는 것(고개 각도·눈 감김·노출)만 엄격히 막는다.
정확한 규격은 촬영 후 인물 마스크로 실제 정수리를 찾아 맞춘다.

## 설치와 업데이트

APK는 [Releases](https://github.com/yms9654/id-photo-cam/releases)에 올린다.

| 파일 | 대상 |
|---|---|
| `idphoto-arm64-v8a.apk` | 2017년 이후 나온 대부분의 폰 |
| `idphoto-armeabi-v7a.apk` | 오래된 32비트 폰 |
| `idphoto-universal.apk` | 위 파일이 설치되지 않을 때 |

앱은 실행할 때 `update.json` 을 읽어 설치된 `versionCode` 와 비교하고, 더 높으면 화면 위에
알림 띠를 띄운다. 누르면 기기 ABI에 맞는 APK를 받아 시스템 설치 화면까지 띄운다.
확인은 12시간에 한 번만 하고, "나중에"를 누른 버전은 다시 묻지 않는다.

**무음 자동 업데이트는 안 된다.** 안드로이드는 사이드로드 앱이 사용자 확인 없이 자기 자신을
교체하는 것을 허용하지 않는다. 설치 버튼은 사용자가 눌러야 하고, 안드로이드 8 이상에서는
이 앱에 '알 수 없는 앱 설치' 권한을 한 번 허용해 줘야 한다.

### 새 버전 내보내기

```bash
# 1. app/build.gradle.kts 의 versionCode / versionName 올리기
./gradlew assembleRelease              # universal
./gradlew assembleRelease -PabiSplit   # ABI별

# 2. 릴리스 만들기
gh release create v1.2 <apk들> --title "..." --notes "..."

# 3. update.json 의 versionCode / versionName / notes / 주소를 고쳐 main 에 푸시
```

`update.json` 은 `main` 브랜치 루트에 있고, 앱은 raw.githubusercontent.com 으로 읽는다.

## 빌드

JDK 17 이상, Android SDK 34 필요.

배포용 서명 키는 저장소에 올리지 않는다. `local.properties` 에 아래를 넣어 둔다.
**키가 바뀌면 기존 설치본에 덮어쓸 수 없어 업데이트가 막히므로 키 파일을 잃어버리면 안 된다.**

```properties
releaseStoreFile=/경로/idphoto-release.jks
releaseStorePassword=...
releaseKeyAlias=idphoto
releaseKeyPassword=...
```

```bash
./gradlew assembleDebug            # 디버그 APK
./gradlew testDebugUnitTest        # 단위 테스트 (순수 계산)
./gradlew connectedDebugAndroidTest # 계측 테스트 (ML Kit 파이프라인, 기기·에뮬레이터 필요)
./gradlew bundleRelease            # 배포용 AAB
```

설치:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

ML Kit 모델이 APK 에 함께 들어가서(네트워크 없이 동작) 크기가 크다.
디버그 APK 는 4개 ABI 를 모두 담아 ~130MB, `-PabiSplit` 으로 ABI 별로 쪼개면 arm64 ~35MB,
AAB 로 배포하면 사용자가 받는 크기는 그보다 작다.

## 구조

```
app/src/main/java/com/yms/idphoto/
├── MainActivity.kt          권한 · 화면 전환
├── MainViewModel.kt         촬영 → 처리 → 결과 상태
├── spec/PhotoSpec.kt        규격 정의 (mm 기준, 비율로 환산)
├── camera/
│   ├── FaceAnalyzer.kt      미리보기 프레임 얼굴 검출
│   ├── Guidance.kt          실시간 촬영 가이드 규칙
│   └── ReadyDebouncer.kt    판정 흔들림 억제
├── photo/
│   ├── PhotoProcessor.kt    검출 · 분할 · 합성 (안드로이드 그래픽)
│   ├── CropPlanner.kt       규격 크롭 계산 (순수 Kotlin, 테스트 대상)
│   ├── SpecReport.kt        검증 리포트
│   ├── Imaging.kt           디코딩 · 용량 제한 JPEG 인코딩
│   └── MediaSaver.kt        갤러리 저장 · 공유
├── ui/                      Compose 화면 (카메라 · 결과 · 테마)
```

## 개인정보

**사진과 얼굴 데이터는 기기 밖으로 나가지 않는다.** 얼굴 검출·인물 분리·크롭·저장이 모두
기기 안에서 끝난다. ML Kit 모델이 APK 에 들어 있어 처리에는 네트워크가 아예 필요 없다.

인터넷 권한은 **업데이트 확인에만** 쓴다. 통신하는 곳은 두 군데뿐이다.

- `raw.githubusercontent.com` — 버전 정보(`update.json`) 읽기
- `github.com` / `objects.githubusercontent.com` — 새 APK 내려받기

업데이트 기능이 필요 없다면 `AndroidManifest.xml` 에서 `INTERNET` 과
`REQUEST_INSTALL_PACKAGES` 권한을 지우고 `MainActivity` 의 `checkForUpdate` 호출을
빼면 된다. 그러면 네트워크를 전혀 쓰지 않는 앱이 된다.
