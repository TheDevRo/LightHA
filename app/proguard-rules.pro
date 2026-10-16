# The wake word engine registers these by name in JNI_OnLoad (cpp/MicroWakeWord_jni.cpp); R8 must not drop or rename any.
-keep class home.kiosk.MicroWakeWord { native <methods>; }
