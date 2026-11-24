/* Se compila cuando LibreDWG no está precompilado para la ABI: la app funciona sin DWG. */
#include <jni.h>
JNIEXPORT jboolean JNICALL
Java_com_cadfield_viewer_cad_LibreDwg_nativeAvailable(JNIEnv *env, jclass cls) { return JNI_FALSE; }
JNIEXPORT jstring JNICALL
Java_com_cadfield_viewer_cad_LibreDwg_nativeDwgToDxf(JNIEnv *env, jclass cls, jstring a, jstring b)
{ return (*env)->NewStringUTF(env, "Motor DWG no incluido en esta compilación"); }
