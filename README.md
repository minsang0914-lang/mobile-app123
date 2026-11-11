# SVT 안드로이드 앱

[SVT · Smart Video Transfer](https://minsang0914-lang.github.io/STV20220211/)를 전체 화면으로 보여주는 안드로이드 앱입니다. 사이트처럼 동영상을 고르고, 변환한 GIF를 휴대폰에 저장할 수 있습니다.

## 아이콘

밝은 파란색 배경에 SVT 글자와 영상 프레임을 넣은 아이콘입니다.

- 앱 아이콘: `app/src/main/res/mipmap-*`
- 큰 원본: `branding/svt-icon-1024.png`

## GitHub Actions로 APK 받기

이 컴퓨터에 안드로이드 스튜디오가 없어도, 저장소를 GitHub에 올리면 Actions가 서명된 APK를 만듭니다. 프로젝트 폴더 이름에 한글이 있어도 빌드되도록 설정해 두었습니다.

```bash
git init
git add .
git commit -m "SVT 안드로이드 앱 추가"
git branch -M main
git remote add origin https://github.com/사용자이름/저장소이름.git
git push -u origin main
```

1. GitHub 저장소의 **Actions** 탭을 엽니다.
2. **Build APK**가 끝날 때까지 기다립니다.
3. 해당 실행을 열고 Artifacts에서 **SVT-apk**를 받습니다.
4. 압축을 풀면 `SVT-release.apk`가 있습니다.

휴대폰에 설치할 때는 APK를 열고, 처음이라면 이 출처의 앱 설치를 허용하면 됩니다. 같은 키로 다시 빌드한 APK는 기존 앱 위에 설치됩니다.

서명 키는 `keystore/svt-release.p12`입니다. 개인 기기에 바로 설치하려고 저장소에 포함해 두었습니다. 저장소를 공개하면 다른 사람도 이 키로 같은 패키지 이름의 APK를 만들 수 있습니다. 공개 저장소로 둘 계획이면 키 파일을 저장소에서 빼고 비밀번호와 함께 본인만 아는 곳에 두세요.
