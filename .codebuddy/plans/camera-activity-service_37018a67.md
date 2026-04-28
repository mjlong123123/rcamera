---
name: camera-activity-service
overview: 创建CameraActivity、RemoteCameraService前台服务，实现Camera预览功能，屏幕息屏时camera不停止
todos:
  - id: add-dependencies
    content: 添加CameraX依赖：修改libs.versions.toml和build.gradle.kts
    status: completed
  - id: update-manifest
    content: 修改AndroidManifest.xml：添加权限声明、注册CameraActivity和RemoteCameraService
    status: completed
  - id: create-service
    content: 创建RemoteCameraService前台服务，管理相机生命周期和WakeLock
    status: completed
    dependencies:
      - add-dependencies
  - id: create-camera-activity
    content: 创建CameraActivity：权限请求、绑定Service、显示预览
    status: completed
    dependencies:
      - create-service
  - id: update-main-activity
    content: 修改MainActivity添加按钮启动CameraActivity，添加资源文件
    status: completed
    dependencies:
      - create-camera-activity
---

## 产品概述

在现有RCamera Android应用中添加相机功能，通过前台服务管理相机生命周期，确保息屏时相机持续运行。

## 核心功能

- MainActivity提供按钮，点击启动CameraActivity
- CameraActivity申请相机权限，获取权限后显示相机预览画面
- 创建RemoteCameraService前台服务，在服务中管理Camera的启动和停止
- CameraActivity启动时绑定并启动RemoteCameraService，退出时关闭服务
- RemoteCameraService启动时开启相机，将预览内容传递给CameraActivity显示
- 屏幕息屏时相机不停止（前台服务+WakeLock保障）

## 技术栈

- 语言：Kotlin
- UI框架：Jetpack Compose + Material3（与现有项目一致）
- 相机API：CameraX（生命周期感知，向后兼容至minSdk 24）
- 前台服务：Android Foreground Service + NotificationChannel
- 保活：PowerManager.WakeLock（PARTIAL_WAKE_LOCK）确保息屏时CPU不休眠

## 实现方案

### 架构设计

采用Service-Binder模式实现Activity与Service之间的通信：

- **RemoteCameraService**：前台服务，持有CameraX实例、ProcessCameraProvider和WakeLock，负责相机的开启/关闭和预览绑定
- **CameraActivity**：通过ServiceConnection绑定Service，提供PreviewView的SurfaceProvider给Service设置预览
- 通信方式：通过Binder暴露`setSurfaceProvider()`和`clearSurfaceProvider()`方法

### 相机预览流程

1. CameraActivity启动 → 绑定RemoteCameraService
2. Service的onCreate()中初始化ProcessCameraProvider并开启相机
3. Activity获得SurfaceProvider后通过Binder传递给Service
4. Service将Preview用例绑定到该SurfaceProvider
5. 息屏时：Surface被销毁，Service检测后保持相机开启（仅解绑Preview），WakeLock防止CPU休眠
6. 亮屏时：Surface重建，Activity重新设置SurfaceProvider，Service重新绑定Preview

### 息屏保活策略

- 前台服务保持进程优先级，防止系统回收
- PARTIAL_WAKE_LOCK保持CPU运行，确保相机硬件不进入低功耗状态
- Surface销毁时仅解绑Preview用例，不解绑相机，相机保持opened状态
- Surface重建时重新绑定Preview，无需重新打开相机

### 数据流

```
CameraActivity --[Binder: setSurfaceProvider]--> RemoteCameraService --[CameraX]--> Camera硬件
Camera硬件 --[Preview帧数据]--> RemoteCameraService --[SurfaceProvider]--> CameraActivity的PreviewView
```

## 实现注意事项

- CameraX的ProcessCameraProvider初始化是异步的（ListenableFuture），需在onCreate中完成初始化后再绑定用例
- 前台服务通知需创建NotificationChannel（Android 8.0+必需），targetSdk 36必须提供通知
- Android 14+（API 34）前台服务类型需指定为"camera"，需在manifest中声明对应权限FOREGROUND_SERVICE_CAMERA
- 权限请求使用ActivityResultContracts.RequestPermission()，用户拒绝后应展示说明
- Service绑定使用BIND_AUTO_CREATE标志，确保服务随绑定自动创建和销毁
- WakeLock在Service onCreate获取、onDestroy释放，务必使用try-finally防止泄漏
- Preview用例在Surface销毁时解绑而非关闭整个相机，避免重新打开相机的延迟

## 目录结构

```
app/src/main/
├── AndroidManifest.xml                                          # [MODIFY] 添加CAMERA权限、FOREGROUND_SERVICE权限、FOREGROUND_SERVICE_CAMERA权限、WAKE_LOCK权限；注册CameraActivity和RemoteCameraService
├── java/com/dragon/rcamera/
│   ├── MainActivity.kt                                          # [MODIFY] 添加按钮，点击启动CameraActivity
│   ├── CameraActivity.kt                                        # [NEW] 相机预览Activity：申请相机权限，绑定RemoteCameraService，显示PreviewView预览画面，退出时解绑并停止Service
│   └── RemoteCameraService.kt                                   # [NEW] 前台服务：管理CameraX相机生命周期，显示前台通知，持有WakeLock息屏保活，通过Binder暴露SurfaceProvider设置接口
├── res/
│   ├── drawable/
│   │   └── ic_camera_notification.xml                           # [NEW] 前台服务通知图标（相机图标矢量图）
│   └── values/
│       └── strings.xml                                          # [MODIFY] 添加前台服务通知渠道名称和通知标题文本
gradle/
└── libs.versions.toml                                           # [MODIFY] 添加cameraCore版本号
app/
└── build.gradle.kts                                             # [MODIFY] 添加CameraX依赖（camera-core, camera-camera2, camera-lifecycle, camera-view）
```

## 各文件详细说明

### AndroidManifest.xml [MODIFY]

- 添加`<uses-permission android:name="android.permission.CAMERA"/>`
- 添加`<uses-permission android:name="android.permission.FOREGROUND_SERVICE"/>`
- 添加`<uses-permission android:name="android.permission.FOREGROUND_SERVICE_CAMERA"/>`（API 34+必需）
- 添加`<uses-permission android:name="android.permission.WAKE_LOCK"/>`
- 在`<application>`内注册`CameraActivity`和`RemoteCameraService`（Service需添加`android:foregroundServiceType="camera"`属性）

### MainActivity.kt [MODIFY]

- 将Greeting替换为按钮组件，点击后通过Intent启动CameraActivity
- 按钮使用Material3的FilledButton，文字"打开相机"

### CameraActivity.kt [NEW]

- 继承ComponentActivity，使用Compose setContent
- 在onCreate中：检查相机权限→无权限则请求→有权限则绑定Service并显示PreviewView
- 使用AndroidView嵌入CameraX的PreviewView到Compose中
- 通过ServiceConnection绑定RemoteCameraService，连接成功后调用binder.setSurfaceProvider()
- 在onDestroy中：清除SurfaceProvider，解绑Service，停止Service
- 重写onPause/onResume处理Surface生命周期

### RemoteCameraService.kt [NEW]

- 继承Service，实现onCreate/onDestroy/onBind
- onCreate中：创建通知渠道、启动前台通知、获取WakeLock、初始化ProcessCameraProvider并打开相机
- onBind返回自定义Binder，暴露setSurfaceProvider/clearSurfaceProvider方法
- setSurfaceProvider：将Preview用例绑定到传入的SurfaceProvider
- clearSurfaceProvider：解绑Preview用例但保持相机开启
- onDestroy中：解绑所有用例、关闭相机、释放WakeLock
- 前台服务类型为camera

### ic_camera_notification.xml [NEW]

- 简单的相机矢量图标，用于前台服务通知

### strings.xml [MODIFY]

- 添加`camera_notification_channel_name`（通知渠道名称）
- 添加`camera_notification_title`（通知标题）

### libs.versions.toml [MODIFY]

- 添加`cameraCore = "1.4.2"`版本

### build.gradle.kts [MODIFY]

- 添加camera-core、camera-camera2、camera-lifecycle、camera-view依赖