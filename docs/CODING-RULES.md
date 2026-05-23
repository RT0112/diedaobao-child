# 开发强制规范

> 每次改完代码，必须按以下流程执行，缺一不可。

---

## 强制流程（每次修改代码后）

1. **编译**：`./gradlew assembleDebug`
2. **安装到 K70**：`adb -s a0c2910e install -r app/build/outputs/apk/debug/app-arm64-v8a-debug.apk`
3. **Commit**：`git add` 相关文件 + `git commit -m "..."`
4. **Push**：`git push origin main`
5. **清理测试数据**：服务端 DB 的测试数据要及时清掉

---

## K70 设备信息

- 设备 ID：`a0c2910e`
- 设备型号：`23113RKC6C`（Redmi K70）
- APK 路径：`app/build/outputs/apk/debug/app-arm64-v8a-debug.apk`
- Java 路径：`/Users/zhou/Library/Java/zulu17.44.53-ca-jdk17.0.8.1-macosx_x64/zulu-17.jdk/Contents/Home`

---

## 编译环境

每次编译前必须设置：
```bash
export JAVA_HOME=/Users/zhou/Library/Java/zulu17.44.53-ca-jdk17.0.8.1-macosx_x64/zulu-17.jdk/Contents/Home
export PATH=$JAVA_HOME/bin:$PATH
export ANDROID_HOME=/Users/zhou/Library/Android/sdk
```

---

## 禁止事项

- ❌ 改完代码不编译
- ❌ 编译完不装到 K70（设备 a0c2910e）
- ❌ 不 commit / 不 push
- ❌ 测试数据留在服务端 DB
- ❌ 通知 qclaw / 测试人员（除非用户要求）
